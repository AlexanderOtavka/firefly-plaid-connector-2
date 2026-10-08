package net.djvk.fireflyPlaidConnector2.transactions

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Transaction
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.properties.FireflyCategoryConfig
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction

/** Local change: backfills match what the connector already imported. */
internal class BackfillReconcilerTest {
    private val accountA = "a".repeat(37)
    private val accountB = "b".repeat(37)
    private val accountMap = PlaidFixtures.getStandardAccountMapping()
    private val day = LocalDate.of(2026, 9, 1)

    private val converter = TransactionConverter(
        useNameForDestination = true,
        timeZoneString = "America/New_York",
        transferMatchWindowDays = 5,
        enablePrimaryCategorization = false,
        primaryCategoryPrefix = "primary-",
        enableDetailedCategorization = false,
        detailedCategoryPrefix = "detailed-",
        txStyle = TransactionStyleConfig(),
        fireflyCategories = FireflyCategoryConfig(enable = true),
    )
    private val reconciler = converter.backfillReconciler(4)

    private fun plaid(
        id: String,
        amount: Double = 5.0,
        date: LocalDate = day,
        merchant: String = "Corner Cafe",
        account: String = accountA,
        category: PersonalFinanceCategoryEnum = PersonalFinanceCategoryEnum.FOOD_AND_DRINK_COFFEE,
        originalDescription: String? = null,
    ): PlaidTransaction = PlaidFixtures.getPaymentTransaction(
        originalDescription = originalDescription,
        pendingTransactionId = null,
        accountId = account,
        name = merchant,
        merchantName = merchant,
        amount = amount,
        date = date,
        transactionId = id,
        personalFinanceCategory = category.toPersonalFinanceCategory(),
    )

    private fun convert(vararg txs: PlaidTransaction) = runBlocking { converter.convertBatchSync(txs.toList(), accountMap) }

    /** What an earlier run imported: [split] as Firefly returns it, under Firefly id [id]. */
    private fun imported(id: String, split: TransactionSplit) =
        TransactionRead("transactions", id, Transaction(listOf(split)), ObjectLink())

    private fun importedFrom(id: String, tx: PlaidTransaction, edit: (TransactionSplit) -> TransactionSplit = { it }) =
        imported(id, edit(convert(tx).single().tx))

    /** Plans as BatchSyncRunner does, with the connector's text for each Plaid transaction. */
    private fun plan(
        converted: List<FireflyTransactionDto>,
        existing: List<TransactionRead>,
        plaidTxs: List<PlaidTransaction> = emptyList(),
    ) = reconciler.plan(
        converted,
        existing,
        setOf(1, 2),
        plaidTxs.associate { FireflyTransactionExternalIdIndexer.getExternalId(it.transactionId) to converter.connectorText(it) },
    )

    @Test
    fun `a relink matches every transaction although every transaction_id changed`() {
        val existing = listOf(
            importedFrom("101", plaid("old-1", amount = 5.0)),
            importedFrom("102", plaid("old-2", amount = 12.5, merchant = "Grocery Mart")),
            importedFrom("103", plaid("old-3", amount = 80.0, date = day.plusDays(9), merchant = "City Power")),
        )
        val converted = convert(
            plaid("new-1", amount = 5.0),
            plaid("new-2", amount = 12.5, merchant = "Grocery Mart"),
            plaid("new-3", amount = 80.0, date = day.plusDays(9), merchant = "City Power"),
        )

        val plan = plan(converted, existing)

        assertThat(plan.inserts).isEmpty()
        assertThat(plan.reviews).isEmpty()
        assertThat(plan.matched).isEqualTo(3)
        assertThat(plan.updates.associate { it.id to it.tx.externalId }).isEqualTo(
            mapOf("101" to "plaid-new-1", "102" to "plaid-new-2", "103" to "plaid-new-3"),
        )
        assertThat(plan.updates).allMatch { !it.applyRules }
    }

    @Test
    fun `a description in the earlier format is refreshed in place, not imported again`() {
        val tx = plaid("same-id", originalDescription = "CORNER CAFE")
        // The earlier format appended the original description even when it repeated the merchant.
        val existing = listOf(importedFrom("101", tx) { it.copy(description = "Corner Cafe: CORNER CAFE") })

        val plan = plan(convert(tx), existing, listOf(tx))

        assertThat(plan.inserts).isEmpty()
        assertThat(plan.updates.single().id).isEqualTo("101")
        assertThat(plan.updates.single().tx.description).isEqualTo("Corner Cafe")
    }

    @Test
    fun `a description or payee the owner or a rule changed is kept`() {
        val tx = plaid("same-id", merchant = "SQ *COFFEE SHOP #12")
        val existing = listOf(
            importedFrom("101", tx) {
                it.copy(description = "Morning coffee", destinationName = "Corner Cafe", destinationId = "55", categoryName = "Treats")
            },
        )

        val update = plan(convert(plaid("new-id", merchant = "SQ *COFFEE SHOP #12")), existing, listOf(tx)).updates.single()

        assertThat(update.tx.externalId).isEqualTo("plaid-new-id")
        assertThat(update.tx.description).isEqualTo("Morning coffee")
        assertThat(update.tx.destinationName).isEqualTo("Corner Cafe")
        assertThat(update.tx.destinationId).isEqualTo("55")
        assertThat(update.tx.categoryName).isEqualTo("Treats")
    }

    @Test
    fun `a reconciled transaction keeps its date`() {
        val existing = listOf(importedFrom("101", plaid("pending-1", date = day)) { it.copy(reconciled = true) })

        val update = plan(convert(plaid("posted-1", date = day.plusDays(2))), existing).updates.single()

        assertThat(update.tx.date.toLocalDate()).isEqualTo(day)
        assertThat(update.tx.externalId).isEqualTo("plaid-posted-1")
    }

    @Test
    fun `the same payee on a nearby date wins over another payee on the same date`() {
        val existing = listOf(
            importedFrom("101", plaid("old-gas", amount = 40.0, date = day, merchant = "Gas Station")),
            importedFrom("102", plaid("old-chevron", amount = 40.0, date = day.plusDays(2), merchant = "Chevron")),
        )
        val converted = convert(plaid("new-gas", amount = 40.0, date = day.plusDays(2), merchant = "Gas Station"))

        val plan = plan(converted, existing)

        assertThat(plan.updates.single().id).isEqualTo("101")
    }

    @Test
    fun `a charge left pending on the old Item is listed as a probable duplicate`() {
        // The old Item imported P while pending. After the relink, polling imported the new
        // Item's posted copy T, which a backfill matches exactly; P matches nothing.
        val posted = plaid("new-posted", amount = 9.0, date = day.plusDays(2))
        val existing = listOf(
            importedFrom("101", plaid("old-pending", amount = 9.0, date = day)),
            importedFrom("102", posted),
            importedFrom("103", plaid("old-other", amount = 9.0, date = day.minusDays(20))),
        )

        val plan = plan(convert(plaid("earlier", amount = 3.0, date = day.minusDays(20)), posted), existing)

        assertThat(plan.updates).isEmpty()
        val leftover = plan.reviews.single()
        assertThat(leftover.kind).isEqualTo(ReviewKind.LEFTOVER)
        assertThat(leftover.targets).containsExactly(ReviewTarget("101", "plaid-old-pending"))
        assertThat(leftover.relatedIds).containsExactly("102")
        assertThat(leftover.proposed).isNull()
    }

    @Test
    fun `identical same-day charges match one to one`() {
        val existing = listOf(
            importedFrom("101", plaid("old-1")),
            importedFrom("102", plaid("old-2")),
        )
        val converted = convert(plaid("new-1"), plaid("new-2"), plaid("new-3"))

        val plan = plan(converted, existing)

        assertThat(plan.matched).isEqualTo(2)
        assertThat(plan.updates.map { it.id }).containsExactlyInAnyOrder("101", "102")
        assertThat(plan.inserts.map { it.tx.externalId }).containsExactly("plaid-new-3")
        assertThat(plan.reviews).isEmpty()
    }

    @Test
    fun `a pending charge that posted on a later date still matches`() {
        val existing = listOf(importedFrom("101", plaid("pending-1", date = day)))

        val plan = plan(convert(plaid("posted-1", date = day.plusDays(2))), existing)

        assertThat(plan.inserts).isEmpty()
        assertThat(plan.updates.single().id).isEqualTo("101")
        assertThat(plan.updates.single().tx.date.toLocalDate()).isEqualTo(day.plusDays(2))
    }

    @Test
    fun `nothing outside the date window matches`() {
        val existing = listOf(importedFrom("101", plaid("old-1", date = day)))

        val plan = plan(convert(plaid("new-1", date = day.plusDays(5))), existing)

        assertThat(plan.matched).isZero()
        assertThat(plan.inserts).hasSize(1)
    }

    @Test
    fun `an ambiguous pairing is left for review, not guessed`() {
        val existing = listOf(
            importedFrom("101", plaid("old-1", date = day.plusDays(1), merchant = "Corner Cafe")),
            importedFrom("102", plaid("old-2", date = day.plusDays(3), merchant = "Daily Grind")),
        )
        val converted = convert(
            plaid("new-1", date = day, merchant = "Bean Bar"),
            plaid("new-2", date = day.plusDays(2), merchant = "Roastery"),
        )

        val plan = plan(converted, existing)

        assertThat(plan.updates).isEmpty()
        assertThat(plan.inserts).isEmpty()
        assertThat(plan.reviews).hasSize(2)
        assertThat(plan.reviews).allMatch {
            it.kind == ReviewKind.UNMATCHED && it.targets.map { t -> t.id }.toSet() == setOf("101", "102") && it.proposed!!.id == null
        }
        assertThat(plan.reviews.first().targets).contains(ReviewTarget("101", "plaid-old-1"))
    }

    @Test
    fun `the owner's edits survive the update`() {
        val tx = plaid("old-1")
        val existing = listOf(
            importedFrom("101", tx) {
                it.copy(
                    categoryName = "Treats",
                    budgetName = "Fun",
                    notes = "with a friend",
                    reconciled = true,
                    tags = listOf("trip"),
                    billName = "Coffee club",
                )
            },
        )

        val update = plan(convert(plaid("new-1")), existing).updates.single()

        assertThat(update.tx.externalId).isEqualTo("plaid-new-1")
        assertThat(update.tx.categoryName).isEqualTo("Treats")
        assertThat(update.tx.budgetName).isEqualTo("Fun")
        assertThat(update.tx.notes).isEqualTo("with a friend")
        assertThat(update.tx.reconciled).isTrue()
        assertThat(update.tx.tags).contains("trip")
        assertThat(update.tx.billName).isEqualTo("Coffee club")
    }

    @Test
    fun `a category the connector set is refreshed`() {
        val tx = plaid("same-id", category = PersonalFinanceCategoryEnum.FOOD_AND_DRINK_COFFEE)
        val existing = listOf(importedFrom("101", tx) { it.copy(categoryName = "Shopping", categoryId = "7") })

        val update = plan(convert(tx), existing).updates.single()

        assertThat(update.tx.categoryName).isEqualTo("Food and Drink")
        assertThat(update.tx.categoryId).isNull()
    }

    @Test
    fun `an import that is already up to date is matched but not rewritten`() {
        val tx = plaid("same-id")

        val plan = plan(convert(tx), listOf(importedFrom("101", tx)))

        assertThat(plan.matched).isEqualTo(1)
        assertThat(plan.updates).isEmpty()
        assertThat(plan.inserts).isEmpty()
    }

    @Test
    fun `transactions that are not connector imports are never matched`() {
        val handEntered = importedFrom("101", plaid("x")) { it.copy(externalId = null) }
        val csvImport = importedFrom("102", plaid("y")) { it.copy(externalId = "csv-42") }

        val plan = plan(convert(plaid("new-1")), listOf(handEntered, csvImport))

        assertThat(plan.matched).isZero()
        assertThat(plan.inserts).hasSize(1)
    }

    @Test
    fun `a charge already imported as part of a transfer is left alone`() {
        // An earlier run paired this withdrawal with a deposit in account B into one transfer.
        val transfer = imported(
            "101",
            TransactionSplit(
                type = TransactionTypeProperty.transfer,
                date = convert(plaid("t")).single().tx.date,
                amount = "250.00",
                description = "Transfer",
                sourceId = "1",
                destinationId = "2",
                externalId = "plaid-old-deposit",
            ),
        )

        val plan = reconciler.plan(convert(plaid("new-1", amount = 250.0)), listOf(transfer), setOf(1))

        assertThat(plan.matched).isEqualTo(1)
        assertThat(plan.updates).isEmpty()
        assertThat(plan.inserts).isEmpty()
    }

    @Test
    fun `a transfer is matched on both of its accounts`() {
        val transferOut = plaid("new-out", amount = 250.0, account = accountA, category = PersonalFinanceCategoryEnum.TRANSFER_OUT_ACCOUNT_TRANSFER)
        val transferIn = plaid("new-in", amount = -250.0, account = accountB, category = PersonalFinanceCategoryEnum.TRANSFER_IN_ACCOUNT_TRANSFER)
        val converted = convert(transferOut, transferIn)
        assertThat(converted.single().tx.type).isEqualTo(TransactionTypeProperty.transfer)
        val existing = listOf(imported("101", converted.single().tx.copy(externalId = "plaid-old-in")))

        val plan = plan(converted, existing)

        assertThat(plan.inserts).isEmpty()
        assertThat(plan.updates.single().id).isEqualTo("101")
        assertThat(plan.updates.single().tx.externalId).isEqualTo("plaid-new-in")
    }
}
