package net.djvk.fireflyPlaidConnector2.manage

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.manage.db.AccountRepository
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillReviewRepository
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillRunRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ReviewStatus
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.ReviewCandidate
import net.djvk.fireflyPlaidConnector2.transactions.ReviewKind
import net.djvk.fireflyPlaidConnector2.transactions.ReviewTarget
import java.time.OffsetDateTime
import java.time.ZoneOffset
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillStatus
import net.djvk.fireflyPlaidConnector2.manage.db.DatabaseConfiguration
import net.djvk.fireflyPlaidConnector2.manage.db.DatabaseCursorStore
import net.djvk.fireflyPlaidConnector2.manage.db.DatabasePlaidItemSource
import net.djvk.fireflyPlaidConnector2.manage.db.DatabaseSyncOutcomeRecorder
import net.djvk.fireflyPlaidConnector2.manage.db.ItemRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ItemStatus
import net.djvk.fireflyPlaidConnector2.manage.db.NewPlaidAccount
import net.djvk.fireflyPlaidConnector2.manage.db.itemKey
import net.djvk.fireflyPlaidConnector2.sync.BatchOutcome
import net.djvk.fireflyPlaidConnector2.sync.InsertCounts
import net.djvk.fireflyPlaidConnector2.sync.PlaidErrorInfo
import net.djvk.fireflyPlaidConnector2.sync.PlaidItem
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.LocalDate

class ItemStoreTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcClient
    private lateinit var items: ItemRepository
    private lateinit var accounts: AccountRepository
    private lateinit var runs: BackfillRunRepository
    private lateinit var reviews: BackfillReviewRepository

    @BeforeEach
    fun setUp() {
        val db = TestPostgres.freshDatabase()
        dataSource = DatabaseConfiguration(db.url, db.username, db.password, "", 2).plaidManagerDataSource() as HikariDataSource
        jdbc = JdbcClient.create(dataSource)
        items = ItemRepository(jdbc)
        accounts = AccountRepository(jdbc)
        runs = BackfillRunRepository(jdbc)
        reviews = BackfillReviewRepository(jdbc)
    }

    @AfterEach
    fun tearDown() {
        dataSource.close()
    }

    private fun newItem(plaidItemId: String, status: ItemStatus = ItemStatus.active, token: String = "access-production-$plaidItemId"): Long =
        items.insert(plaidItemId, token, "ins_1", "Bank", 730, status)

    private fun newAccount(itemId: Long, plaidAccountId: String, persistent: String? = null) =
        accounts.upsert(itemId, listOf(NewPlaidAccount(plaidAccountId, persistent, "Card", "1234", "credit", "credit card")))

    private fun accountId(plaidAccountId: String) = accounts.all().single { it.plaidAccountId == plaidAccountId }.id

    @Test
    fun `rows never carry the access token, and only accessTokenFor reads it`() {
        val id = newItem("item-1", token = "access-production-secret")
        val row = items.find(id)!!
        assertThat(row.toString()).doesNotContain("access-production-secret")
        assertThat(items.accessTokenFor(id)).isEqualTo("access-production-secret")
        items.retire(id)
        assertThat(items.accessTokenFor(id)).isNull()
        assertThat(items.find(id)!!.status).isEqualTo(ItemStatus.retired)
    }

    @Test
    fun `one enabled Plaid account per Firefly account`() {
        val old = newItem("old")
        val new = newItem("new")
        newAccount(old, "a-old")
        newAccount(new, "a-new")
        accounts.setMapping(accountId("a-old"), old, 8, true)
        assertThatThrownBy { accounts.setMapping(accountId("a-new"), new, 8, true) }
            .isInstanceOf(DuplicateKeyException::class.java)
        // Disabled mappings to the same Firefly account are fine.
        accounts.setMapping(accountId("a-new"), new, 8, false)
    }

    @Test
    fun `an enabled account must be mapped`() {
        val id = newItem("item-1")
        newAccount(id, "a")
        assertThatThrownBy { accounts.setMapping(accountId("a"), id, null, true) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `only one backfill may be pending or running`() {
        val id = newItem("item-1")
        newAccount(id, "a")
        val first = runs.insertPending(id, listOf(accountId("a")), 90, 3600)
        assertThatThrownBy { runs.insertPending(id, listOf(accountId("a")), 90, 3600) }
            .isInstanceOf(DuplicateKeyException::class.java)
        runs.finish(first, BackfillStatus.failed, null, "boom")
        runs.insertPending(id, listOf(accountId("a")), 90, 3600)
        assertThat(runs.find(first)!!.accountIds).containsExactly(accountId("a"))
    }

    @Test
    fun `polled mode only sees Items that already have a cursor`() {
        val active = newItem("active")
        val pending = newItem("pending", ItemStatus.pending_history)
        val retired = newItem("retired")
        for ((item, account) in listOf(active to "a1", pending to "a2", retired to "a3")) {
            newAccount(item, account)
            accounts.setMapping(accountId(account), item, account.last().digitToInt(), true)
        }
        items.setCursor(active, "cursor-1")
        items.retire(retired)

        val source = DatabasePlaidItemSource(items, accounts, "polled", "", "")
        val (accountMap, plaidItems) = source.getAccountMapAndPlaidItems()
        assertThat(accountMap).containsExactlyEntriesOf(mapOf("a1" to 1))
        assertThat(plaidItems.toList()).containsExactly(PlaidItem(itemKey(active), "access-production-active", listOf("a1")))

        // The manager takes the cursor once history is complete; then polling picks the Item up.
        items.activateWithCursor(pending, "cursor-2")
        assertThat(source.getAccountMapAndPlaidItems().second.map { it.key }.toList())
            .containsExactly(itemKey(active), itemKey(pending))
    }

    @Test
    fun `a backfill is restricted to its Item and accounts`() {
        val one = newItem("one")
        val two = newItem("two")
        newAccount(one, "a1")
        newAccount(one, "a2")
        newAccount(two, "b1")
        accounts.setMapping(accountId("a1"), one, 1, true)
        accounts.setMapping(accountId("a2"), one, 2, true)
        accounts.setMapping(accountId("b1"), two, 3, true)

        val source = DatabasePlaidItemSource(items, accounts, "batch", "$one", "${accountId("a2")}")
        val (accountMap, plaidItems) = source.getAccountMapAndPlaidItems()
        assertThat(accountMap).containsExactlyEntriesOf(mapOf("a2" to 2))
        assertThat(plaidItems.single().accountIds).containsExactly("a2")
    }

    @Test
    fun `cursors round-trip through the database`(): Unit = runBlocking {
        val one = newItem("one")
        val store = DatabaseCursorStore(items)
        store.writeCursorMap(mapOf(itemKey(one) to "c1", "item-deadbeef" to "ignored"))
        assertThat(store.readCursorMap()).containsExactlyEntriesOf(mapOf(itemKey(one) to "c1"))
    }

    @Test
    fun `sync outcomes are recorded, and ITEM_LOGIN_REQUIRED marks the Item`() {
        val one = newItem("one")
        val recorder = DatabaseSyncOutcomeRecorder(items, runs, reviews, "")
        val item = PlaidItem(itemKey(one), "access-production-one", emptyList())

        recorder.itemFailed(item, PlaidErrorInfo("ITEM_LOGIN_REQUIRED", "the login details of this item have changed"))
        items.find(one)!!.let {
            assertThat(it.status).isEqualTo(ItemStatus.login_required)
            assertThat(it.lastErrorCode).isEqualTo("ITEM_LOGIN_REQUIRED")
            assertThat(it.consecutiveFailures).isEqualTo(1)
        }

        recorder.itemSucceeded(item, 4, LocalDate.of(2026, 9, 3)..LocalDate.of(2026, 9, 5))
        items.find(one)!!.let {
            assertThat(it.status).isEqualTo(ItemStatus.active)
            assertThat(it.lastErrorCode).isNull()
            assertThat(it.lastSyncAdded).isEqualTo(4)
            assertThat(it.lastSyncAt).isNotNull()
            assertThat(it.earliestTxDate).isEqualTo(LocalDate.of(2026, 9, 3))
            assertThat(it.latestTxDate).isEqualTo(LocalDate.of(2026, 9, 5))
        }
        // A sync that adds nothing leaves the range alone; one that adds widens it.
        recorder.itemSucceeded(item, 0)
        recorder.itemSucceeded(item, 1, LocalDate.of(2026, 9, 8)..LocalDate.of(2026, 9, 8))
        items.find(one)!!.let {
            assertThat(it.earliestTxDate).isEqualTo(LocalDate.of(2026, 9, 3))
            assertThat(it.latestTxDate).isEqualTo(LocalDate.of(2026, 9, 8))
        }

        repeat(3) { recorder.itemFailed(item, PlaidErrorInfo("INTERNAL_SERVER_ERROR", null)) }
        assertThat(items.find(one)!!.status).isEqualTo(ItemStatus.error)
    }

    @Test
    fun `a backfill Job records its run`() {
        val one = newItem("one")
        newAccount(one, "a")
        val run = runs.insertPending(one, listOf(accountId("a")), 90, 3600)
        val recorder = DatabaseSyncOutcomeRecorder(items, runs, reviews, "$run")

        recorder.batchStarted()
        assertThat(runs.find(run)!!.status).isEqualTo(BackfillStatus.running)
        recorder.batchFinished(BatchOutcome(10, InsertCounts(4, 6, 0), LocalDate.of(2024, 9, 30), LocalDate.of(2026, 9, 1)))
        items.find(one)!!.let {
            assertThat(it.earliestTxDate).isEqualTo(LocalDate.of(2024, 9, 30))
            assertThat(it.latestTxDate).isEqualTo(LocalDate.of(2026, 9, 1))
        }
        runs.find(run)!!.let {
            assertThat(it.newestDate).isEqualTo(LocalDate.of(2026, 9, 1))
            assertThat(it.status).isEqualTo(BackfillStatus.succeeded)
            assertThat(it.fetched).isEqualTo(10)
            assertThat(it.inserted).isEqualTo(4)
            assertThat(it.duplicates).isEqualTo(6)
            assertThat(it.oldestDate).isEqualTo(LocalDate.of(2024, 9, 30))
            assertThat(it.finishedAt).isNotNull()
        }
        // A later reconcile cannot overwrite a recorded result.
        runs.finish(run, BackfillStatus.failed, null, "late")
        assertThat(runs.find(run)!!.status).isEqualTo(BackfillStatus.succeeded)
    }

    @Test
    fun `stored errors are redacted`() {
        val one = newItem("one")
        newAccount(one, "a")
        val run = runs.insertPending(one, listOf(accountId("a")), 90, 3600)
        DatabaseSyncOutcomeRecorder(items, runs, reviews, "$run")
            .batchFailed(BatchOutcome(0, InsertCounts(), null), RuntimeException("bad token access-production-one"))
        assertThat(runs.find(run)!!.error).doesNotContain("access-").contains("[redacted token]")
    }
    @Test
    fun `a backfill records its matches, updates, and reviews`() {
        val one = newItem("one")
        newAccount(one, "a")
        val run = runs.insertPending(one, listOf(accountId("a")), 90, 3600, dryRun = true)
        val proposed = TransactionSplit(
            type = TransactionTypeProperty.withdrawal,
            date = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-4)),
            amount = "5.0",
            description = "Corner \"Cafe\"",
            sourceId = "8",
            destinationId = null,
            destinationName = "Corner Cafe",
            externalId = "plaid-new-1",
            tags = listOf("trip"),
        )
        val outcome = BatchOutcome(
            12,
            InsertCounts(inserted = 1, matched = 10, updated = 3, needsReview = 1),
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 9, 2),
            dryRun = true,
            reviews = listOf(
                ReviewCandidate(
                    kind = ReviewKind.UNMATCHED,
                    reason = "Several",
                    date = LocalDate.of(2026, 9, 1),
                    amount = "5.0",
                    description = "Corner \"Cafe\"",
                    proposed = FireflyTransactionDto(null, proposed),
                    targets = listOf(ReviewTarget("101", "plaid-old-1"), ReviewTarget("1\"02", null)),
                    generated = setOf("cornercafe"),
                ),
                ReviewCandidate(
                    kind = ReviewKind.LEFTOVER,
                    reason = "Probably a duplicate",
                    date = LocalDate.of(2026, 8, 30),
                    amount = "5.000000000000",
                    description = "Corner Cafe",
                    proposed = null,
                    targets = listOf(ReviewTarget("103", "plaid-old-3")),
                    relatedIds = listOf("104"),
                ),
            ),
        )

        DatabaseSyncOutcomeRecorder(items, runs, reviews, "$run").batchFinished(outcome)

        runs.find(run)!!.let {
            assertThat(it.dryRun).isTrue()
            assertThat(it.matched).isEqualTo(10)
            assertThat(it.updated).isEqualTo(3)
            assertThat(it.needsReview).isEqualTo(1)
            assertThat(it.newestDate).isEqualTo(LocalDate.of(2026, 9, 2))
        }
        // A dry run wrote nothing, so the Item's synced range is unchanged.
        assertThat(items.find(one)!!.earliestTxDate).isNull()
        val (leftover, review) = reviews.forRun(run)
        assertThat(review.proposed).isEqualTo(proposed)
        assertThat(review.targets).containsExactly(ReviewTarget("101", "plaid-old-1"), ReviewTarget("1\"02", null))
        assertThat(review.generated).containsExactly("cornercafe")
        assertThat(review.txDate).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(leftover.kind).isEqualTo(ReviewKind.LEFTOVER)
        assertThat(leftover.proposed).isNull()
        assertThat(leftover.relatedIds).containsExactly("104")
        assertThat(reviews.openCounts()).containsEntry(run, 2)

        assertThat(reviews.resolve(review.id, ReviewStatus.merged, "101")).isTrue()
        assertThat(reviews.resolve(review.id, ReviewStatus.dismissed)).isFalse()
        assertThat(reviews.find(review.id)!!.mergedInto).isEqualTo("101")
        reviews.reopen(review.id)
        assertThat(reviews.find(review.id)!!.status).isEqualTo(ReviewStatus.open)
    }
}
