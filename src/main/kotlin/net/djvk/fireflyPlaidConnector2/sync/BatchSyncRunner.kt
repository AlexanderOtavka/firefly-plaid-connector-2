package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.plugins.*
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.*
import net.djvk.fireflyPlaidConnector2.constants.IntervalSeconds
import net.djvk.fireflyPlaidConnector2.constants.TimestampSeconds
import net.djvk.fireflyPlaidConnector2.transactions.FireflyAccountId
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionExternalIdIndexer
import net.djvk.fireflyPlaidConnector2.transactions.ReconcilePlan
import net.djvk.fireflyPlaidConnector2.transactions.ReviewCandidate
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

/**
 * Batch sync runner.
 *
 * Handles the "batch" sync mode, which syncs a large batch of transactions at once, then exits.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "batch")
@Component
class BatchSyncRunner(
    @Value("\${fireflyPlaidConnector2.batch.maxSyncDays}")
    private val syncDays: Int,
    @Value("\${fireflyPlaidConnector2.batch.setInitialBalance:false}")
    private val setInitialBalance: Boolean,
    @Value("\${fireflyPlaidConnector2.batch.balanceMinLastUpdatedDatetimeSeconds:}")
    private val balanceMinLastUpdatedDatetimeSeconds: IntervalSeconds? = null,
    @Value("\${fireflyPlaidConnector2.plaid.batchSize}")
    private val plaidBatchSize: Int,

    private val plaidApiWrapper: PlaidApiWrapper,
    private val syncHelper: SyncHelper,
    private val fireflyAccountsApi: AccountsApi,

    private val converter: TransactionConverter,

    private val syncOutcomeRecorder: SyncOutcomeRecorder = NoopSyncOutcomeRecorder(),

    // Local change: see [BackfillReconciler].
    @Value("\${fireflyPlaidConnector2.batch.reconcile.enable:true}")
    private val reconcile: Boolean = true,
    @Value("\${fireflyPlaidConnector2.batch.reconcile.dateWindowDays:4}")
    private val reconcileWindowDays: Long = 4,
    @Value("\${fireflyPlaidConnector2.batch.dryRun:false}")
    private val dryRun: Boolean = false,
    ) : Runner {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Local change: reports the run's start, counts, and result to [SyncOutcomeRecorder], so a
     * backfill started from the management UI can show how it went.
     */
    override fun run() {
        val allPlaidTxs = mutableMapOf<PlaidItemKey, MutableList<Transaction>>()
        val counts = InsertCounts()
        val reviews = mutableListOf<ReviewCandidate>()
        fun outcome() = BatchOutcome(
            fetched = allPlaidTxs.values.sumOf { it.size },
            counts = counts,
            oldestDate = allPlaidTxs.values.flatten().minOfOrNull { it.date },
            dryRun = dryRun,
            reviews = reviews,
        )

        syncOutcomeRecorder.batchStarted()
        try {
            runBatch(allPlaidTxs, counts, reviews)
        } catch (e: Throwable) {
            syncOutcomeRecorder.batchFailed(outcome(), e)
            throw e
        }
        syncOutcomeRecorder.batchFinished(outcome())
    }

    private fun runBatch(
        allPlaidTxs: MutableMap<PlaidItemKey, MutableList<Transaction>>,
        counts: InsertCounts,
        reviews: MutableList<ReviewCandidate>,
    ) {
        val startDate = LocalDate.now().minusDays(syncDays.toLong())
        val endDate = LocalDate.now()

        /**
         * Batch mode is a long-running one-shot job, so its progress is logged at info. Logging it
         * at debug meant a run that was still working through Plaid pages was indistinguishable
         * from one that had hung, which is the only signal a batch job gives before it exits.
         */
        logger.info("Batch sync starting for transactions dated $startDate through $endDate")

        runBlocking {
            syncHelper.setApiCreds()
            val (accountMap, plaidItems) = syncHelper.getAccountMapAndPlaidItems()
            for (item in plaidItems) {
                logger.info("Fetching Plaid data for Item ${item.key.take(17)} and ${item.accountIds.size} accounts")
                var offset = 0
                do {
                    /**
                     * Iterate through batches of Plaid transactions
                     *
                     * We're storing all this data in memory so we can try to match up offsetting transfers before inserting
                     *  into Firefly.
                     * Note that the heap size may need to be increased if you're handling a ton of transactions.
                     */
                    /**
                     * Iterate through batches of Plaid transactions
                     *
                     * We're storing all this data in memory so we can try to match up offsetting transfers before inserting
                     *  into Firefly.
                     * We don't use fireflyPlaidConnector2.transferMatchWindowDays here because if we did we'd have to
                     *  do some complex rolling window shenanigans that I have no interest in implementing, and it's
                     *  easy to run batch mode once on a high-spec machine.
                     * Note that the heap size may need to be increased if you're handling a ton of transactions.
                     */
                    val request = TransactionsGetRequest(
                        item.accessToken,
                        startDate,
                        endDate,
                        null,
                        TransactionsGetRequestOptions(
                            item.accountIds,
                            plaidBatchSize,
                            offset,
                            includeOriginalDescription = true,
                            includePersonalFinanceCategoryBeta = false,
                            includePersonalFinanceCategory = true,
                        )
                    )
                    val plaidTxs: List<Transaction>
                    try {
                        plaidTxs = plaidApiWrapper.executeRequest(
                            { plaidApi -> plaidApi.transactionsGet(request) },
                            "transaction get request"
                        ).body().transactions
                    } catch (cre: ClientRequestException) {
                        logger.error("Error requesting Plaid transactions for Item ${item.key.take(17)}")
                        throw cre
                    }
                    val itemTxs = allPlaidTxs.getOrPut(item.key) { mutableListOf() }
                    itemTxs.addAll(plaidTxs)
                    logger.info(
                        "Received a batch of {} Plaid transactions for Item {}; {} fetched so far, back to {}",
                        plaidTxs.size,
                        item.key.take(17),
                        itemTxs.size,
                        itemTxs.minOfOrNull { it.date } ?: startDate,
                    )

                    /**
                     * Local change: existing transactions are matched once every Item has been
                     *  fetched, below, rather than relying on Firefly's duplicate hash alone.
                     */

                    offset += plaidTxs.size

                    // Keep going until we get all the transactions
                } while (plaidTxs.size == plaidBatchSize)
                logger.info(
                    "Done fetching Plaid data for Item {}: {} transactions",
                    item.key.take(17),
                    allPlaidTxs[item.key]?.size ?: 0,
                )
            }

            // Map Plaid transactions to Firefly transactions
            logger.info("Converting ${allPlaidTxs.values.sumOf { it.size }} Plaid transactions to Firefly transactions")
            val fireflyTxs = converter.convertBatchSync(allPlaidTxs.values.flatten(), accountMap)

            val plan = if (reconcile) {
                val generated = allPlaidTxs.values.flatten().associate {
                    FireflyTransactionExternalIdIndexer.getExternalId(it.transactionId) to converter.connectorText(it)
                }
                reconcileWithImported(fireflyTxs, accountMap.values.toSet(), startDate, endDate, generated)
            } else {
                ReconcilePlan(fireflyTxs, emptyList(), 0, emptyList())
            }
            counts.matched = plan.matched
            counts.needsReview = plan.reviews.size
            reviews.addAll(plan.reviews)
            // Listed here too, for a run without a dashboard to record them on.
            for (review in plan.reviews) {
                logger.warn(
                    "For review, not written: {} {} {} {} ({}); Firefly transactions {}",
                    review.kind,
                    review.date,
                    review.amount,
                    review.proposed?.tx?.externalId ?: review.description,
                    review.reason,
                    review.targets.map { it.id },
                )
            }

            if (dryRun) {
                counts.updated = plan.updates.size
                counts.inserted = plan.inserts.size
                logger.info(
                    "Dry run: would update {} and insert {} transactions; {} matched, {} for review. Nothing was written.",
                    plan.updates.size,
                    plan.inserts.size,
                    plan.matched,
                    plan.reviews.size,
                )
                return@runBlocking
            }

            // Update matches first, so their new external ids are in place before any insert.
            logger.info("Updating ${plan.updates.size} existing transactions in Firefly")
            for (update in plan.updates) {
                syncHelper.updateBatchInFirefly(listOf(update))
                counts.updated++
            }

            // Insert into Firefly
            logger.info("Inserting ${plan.inserts.size} transactions into Firefly")
            syncHelper.optimisticInsertBatchIntoFirefly(plan.inserts, counts)
            logger.info(
                "Done writing to Firefly: {} updated, {} inserted, {} duplicates, {} left for review",
                counts.updated,
                counts.inserted,
                counts.duplicates,
                counts.needsReview,
            )

            // Set initial balance transaction if configured
            if (setInitialBalance) {
                setInitialBalances(allPlaidTxs, syncHelper, startDate)
            }
        }
        logger.info("Batch sync complete")
    }

    /**
     * Local change: plans updates, inserts, and reviews against the transactions the
     * connector already imported into [accounts]. The window extends the range on both sides,
     * so a transaction whose date moved since it was imported is still found.
     */
    private suspend fun reconcileWithImported(
        fireflyTxs: List<FireflyTransactionDto>,
        accounts: Set<FireflyAccountId>,
        startDate: LocalDate,
        endDate: LocalDate,
        generated: Map<String, Set<String>>,
    ): ReconcilePlan {
        val existing = ImportedTransactionFetcher(fireflyAccountsApi).fetch(
            accounts,
            startDate.minusDays(reconcileWindowDays),
            endDate.plusDays(reconcileWindowDays),
        )
        return converter.backfillReconciler(reconcileWindowDays).plan(fireflyTxs, existing, accounts, generated)
    }

    suspend fun setInitialBalances(
        allPlaidTxs: Map<PlaidItemKey, List<Transaction>>,
        syncHelper: SyncHelper,
        startDate: LocalDate,
    ) {
        logger.info("Attempting to set initial balances")
        val (accountMap, plaidItems) = syncHelper.getAccountMapAndPlaidItems()
        // Iterate over all Plaid items/access tokens we have configured
        for (item in plaidItems) {
            val plaidTxs = allPlaidTxs[item.key] ?: continue
            // Request balance data for this item/access token
            logger.debug("Requesting balances for Item ${item.key.take(17)}")
            // Calculate min last updated, if required
            val minLastUpdated = balanceMinLastUpdatedDatetimeSeconds?.let {
                logger.debug("Setting min_last_updated_datetime to $balanceMinLastUpdatedDatetimeSeconds seconds ago")
                OffsetDateTime.now().minusSeconds(it)
            }
            val balances: AccountsGetResponse
            try {
                balances = plaidApiWrapper.executeRequest(
                    { plaidApi ->
                        plaidApi.accountsBalanceGet(
                            AccountsBalanceGetRequest(
                                item.accessToken,
                                null,
                                null,
                                AccountsBalanceGetRequestOptions(item.accountIds, minLastUpdated),
                            )
                        )
                    },
                    "transaction get request"
                ).body()
            } catch (e: Exception) {
                logger.error(
                    "Failed to fetch balances for Item ${item.key.take(17)}",
                    e
                )
                continue
            }

            // Group transactions and balance data by Plaid account id
            val plaidTxsByAccountId = plaidTxs.groupBy { it.accountId }
            val balancesByAccountId = balances.accounts.associate { Pair(it.accountId, it.balances.current) }

            // Iterate over account ids
            for ((accountId, currentBalance) in balancesByAccountId) {
                val fireflyAccountId = accountMap[accountId]
                if (fireflyAccountId == null) {
                    logger.warn("Failed to find Firefly account id for Plaid account $accountId")
                    continue
                }
                if (currentBalance == null) {
                    logger.warn("No current balance data received for Plaid account $accountId")
                    continue
                }
                val fireflyAccount: AccountRead
                try {
                    fireflyAccount = fireflyAccountsApi.getAccount(fireflyAccountId.toString(), null).body().data
                } catch (e: Exception) {
                    logger.error("Error fetching Firefly account $fireflyAccountId", e)
                    continue
                }
                val isCreditCard = fireflyAccount.attributes.accountRole?.value == "ccAsset"

                val txs = plaidTxsByAccountId[accountId] ?: listOf()
                val total = txs.fold(0.0) { acc, tx -> acc + tx.amount }

                /**
                 * Plaid returns positive balances regardless, even though they're functionally negative for
                 *  credit card accounts.
                 */
                val initialBalance = if (isCreditCard) {
                    total - currentBalance
                } else {
                    total + currentBalance
                }

                val earliestTimestamp = txs.fold(OffsetDateTime.now()) { acc, tx ->
                    val ts = converter.getTxPostedTimestamp(tx)
                    if (ts < acc) {
                        ts
                    } else {
                        acc
                    }
                }
                logger.debug("Inserting initial balance $initialBalance for Firefly account id $fireflyAccountId")
                syncHelper.optimisticInsertBatchIntoFirefly(
                    listOf(
                        FireflyTransactionDto(
                            null, TransactionSplit(
                                /**
                                 * Would like this to be [TransactionTypeProperty.openingBalance], but the Firefly API doesn't
                                 *  let us insert with that value.
                                 *
                                 */
                                type = if (initialBalance < 0) TransactionTypeProperty.withdrawal else TransactionTypeProperty.deposit,
                                date = earliestTimestamp.minusHours(1),
                                amount = (initialBalance.absoluteValue).toString(),
                                description = "Plaid Connector Initial Balance",
                                sourceName = "Initial Balance",
                                sourceId = if (initialBalance < 0) fireflyAccountId.toString() else null,
                                destinationId = if (initialBalance < 0) null else fireflyAccountId.toString(),
                                order = 0,
                                reconciled = false,
                            )
                        )
                    )
                )
            }
        }
    }
}
