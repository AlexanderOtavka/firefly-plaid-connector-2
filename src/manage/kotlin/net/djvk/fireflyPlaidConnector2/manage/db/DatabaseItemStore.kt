package net.djvk.fireflyPlaidConnector2.manage.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.djvk.fireflyPlaidConnector2.manage.SYNC_MODE_PROPERTY
import net.djvk.fireflyPlaidConnector2.sync.BatchOutcome
import net.djvk.fireflyPlaidConnector2.sync.CursorStore
import net.djvk.fireflyPlaidConnector2.sync.ITEM_STORE_PROPERTY
import net.djvk.fireflyPlaidConnector2.sync.PlaidAccountId
import net.djvk.fireflyPlaidConnector2.sync.PlaidErrorInfo
import net.djvk.fireflyPlaidConnector2.sync.PlaidItem
import net.djvk.fireflyPlaidConnector2.sync.PlaidItemKey
import net.djvk.fireflyPlaidConnector2.sync.PlaidItemSource
import net.djvk.fireflyPlaidConnector2.sync.PlaidSyncCursor
import net.djvk.fireflyPlaidConnector2.sync.SyncOutcomeRecorder
import net.djvk.fireflyPlaidConnector2.transactions.FireflyAccountId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate

private const val KEY_PREFIX = "db-item-"

/** The [PlaidItem] key for a plaid_item row: its database id, never anything token-derived. */
fun itemKey(id: Long): PlaidItemKey = "$KEY_PREFIX$id"

fun itemIdFromKey(key: PlaidItemKey): Long? = key.removePrefix(KEY_PREFIX).takeIf { key.startsWith(KEY_PREFIX) }?.toLongOrNull()

/** Parses a comma-separated id list property; blank means "no restriction". */
fun parseIdList(value: String?): List<Long>? =
    value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { it.toLong() }?.takeIf { it.isNotEmpty() }

/**
 * Items and account mappings from the plaid_manager database.
 *
 * - Polled mode syncs Items that are active, or failing but not retired, *and already have a
 *   cursor*. The manager sets the cursor once Plaid's historical pull is complete, so polled
 *   mode never initializes a cursor itself for a database Item, which would throw away the
 *   history Plaid is still pulling.
 * - Batch mode (a backfill Job) syncs only active Items, restricted to `batch.itemIds` and
 *   `batch.accountIds` when those are set.
 */
@Component
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = DATABASE_ITEM_STORE)
class DatabasePlaidItemSource(
    private val items: ItemRepository,
    private val accounts: AccountRepository,
    @Value("\${$SYNC_MODE_PROPERTY:batch}")
    private val syncMode: String,
    @Value("\${fireflyPlaidConnector2.batch.itemIds:}")
    private val batchItemIds: String,
    @Value("\${fireflyPlaidConnector2.batch.accountIds:}")
    private val batchAccountIds: String,
) : PlaidItemSource {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun getAccountMapAndPlaidItems(): Pair<Map<PlaidAccountId, FireflyAccountId>, Sequence<PlaidItem>> {
        val batch = syncMode == "batch"
        val rows = if (batch) {
            accounts.enabledFor(
                statuses = listOf(ItemStatus.active),
                requireCursor = false,
                itemIds = parseIdList(batchItemIds),
                accountIds = parseIdList(batchAccountIds),
            )
        } else {
            accounts.enabledFor(
                statuses = listOf(ItemStatus.active, ItemStatus.login_required, ItemStatus.error),
                requireCursor = true,
                itemIds = null,
                accountIds = null,
            )
        }
        val accountMap = rows.associate { it.plaidAccountId to it.fireflyAccountId!! }
        val byItem = rows.groupBy { it.itemId }
        logger.debug("Read {} mapped accounts across {} Items from the database", rows.size, byItem.size)

        val plaidItems = byItem.mapNotNull { (itemId, itemAccounts) ->
            val token = items.accessTokenFor(itemId) ?: return@mapNotNull null
            PlaidItem(itemKey(itemId), token, itemAccounts.map { it.plaidAccountId })
        }
        return Pair(accountMap, plaidItems.asSequence())
    }
}

/** Sync cursors on plaid_item rows, keyed by [itemKey]. */
@Component
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = DATABASE_ITEM_STORE)
class DatabaseCursorStore(private val items: ItemRepository) : CursorStore {
    override suspend fun readCursorMap(): MutableMap<PlaidItemKey, PlaidSyncCursor> = withContext(Dispatchers.IO) {
        items.cursors().mapKeys { (id, _) -> itemKey(id) }.toMutableMap()
    }

    override suspend fun writeCursorMap(map: Map<PlaidItemKey, PlaidSyncCursor>) = withContext(Dispatchers.IO) {
        for ((key, cursor) in map) {
            val id = itemIdFromKey(key) ?: continue
            if (cursor.isNotEmpty()) {
                items.setCursor(id, cursor)
            }
        }
    }
}

/**
 * Records per-Item sync outcomes and, for a backfill Job started by the manager
 * (`batch.runId`), the run's start, counts, and result.
 */
@Component
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = DATABASE_ITEM_STORE)
class DatabaseSyncOutcomeRecorder(
    private val items: ItemRepository,
    private val runs: BackfillRunRepository,
    private val reviews: BackfillReviewRepository,
    @Value("\${fireflyPlaidConnector2.batch.runId:}")
    private val runId: String,
) : SyncOutcomeRecorder {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val run: Long? get() = runId.trim().toLongOrNull()

    override fun itemSucceeded(item: PlaidItem, added: Int, addedDates: ClosedRange<LocalDate>?) {
        itemIdFromKey(item.key)?.let { items.recordSuccess(it, added, addedDates) }
    }

    override fun itemFailed(item: PlaidItem, error: PlaidErrorInfo) {
        itemIdFromKey(item.key)?.let { items.recordFailure(it, error.code, error.message) }
    }

    override fun batchStarted() {
        run?.let { runs.markStarted(it) }
    }

    override fun batchFinished(outcome: BatchOutcome) {
        val id = run ?: return
        reviews.insertAll(id, outcome.reviews)
        runs.finish(id, BackfillStatus.succeeded, outcome, null)
        // What the run fetched is now in Firefly, matched or inserted, unless it wrote nothing.
        val oldest = outcome.oldestDate ?: return
        val newest = outcome.newestDate ?: return
        if (!outcome.dryRun) {
            runs.find(id)?.let { items.widenTxDates(it.itemId, oldest..newest) }
        }
    }

    override fun batchFailed(outcome: BatchOutcome, error: Throwable) {
        val id = run ?: return
        try {
            reviews.insertAll(id, outcome.reviews)
            runs.finish(id, BackfillStatus.failed, outcome, "${error::class.simpleName}: ${error.message}")
        } catch (e: Exception) {
            // The manager's reconciler still marks the run failed from the Job's status.
            logger.error("Could not record the failure of backfill run {}", id, e)
        }
    }
}
