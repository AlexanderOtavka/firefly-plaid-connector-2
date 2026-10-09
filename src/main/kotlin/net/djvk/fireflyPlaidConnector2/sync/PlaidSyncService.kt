package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.plugins.*
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidTransactionId
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncRequestOptions
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction

/**
 * Service for handling Plaid transaction synchronization.
 */
@Component
class PlaidSyncService(
    private val plaidApiWrapper: PlaidApiWrapper,

    @Value("\${fireflyPlaidConnector2.plaid.batchSize}")
    private val plaidBatchSize: Int,

    @Value("\${fireflyPlaidConnector2.polled.allowItemToFail:false}")
    private val allowItemToFail: Boolean,

    private val syncOutcomeRecorder: SyncOutcomeRecorder = NoopSyncOutcomeRecorder(),
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Creates a transaction sync request for Plaid API.
     */
    fun getTransactionSyncRequest(
        accessToken: PlaidAccessToken,
        cursor: PlaidSyncCursor?,
        batchSize: Int = plaidBatchSize
    ): TransactionsSyncRequest {
        return TransactionsSyncRequest(
            accessToken,
            null,
            null,
            cursor,
            batchSize,
            TransactionsSyncRequestOptions(
                includeOriginalDescription = true,
                includePersonalFinanceCategory = true,
            )
        )
    }

    /**
     * Executes a transaction sync request to Plaid API.
     * Returns null if the request fails and allowItemToFail is true.
     */
    suspend fun executeTransactionSyncRequest(
        accessToken: PlaidAccessToken,
        cursor: PlaidSyncCursor?,
        batchSize: Int = plaidBatchSize,
        item: PlaidItem? = null,
    ): TransactionsSyncResponse? {
        val request = getTransactionSyncRequest(accessToken, cursor, batchSize)
        try {
            return plaidApiWrapper.executeRequest(
                { plaidApi -> plaidApi.transactionsSync(request) },
                "transaction sync request"
            ).body()
        } catch (cre: ClientRequestException) {
            val error = PlaidErrorInfo.from(cre)
            logger.error("Error requesting Plaid transactions: ${error.code ?: "unknown error code"}")
            // Local change: record the failure on both paths, so an ITEM_LOGIN_REQUIRED is
            // visible whether or not the cycle is allowed to continue past it.
            if (item != null) {
                syncOutcomeRecorder.itemFailed(item, error)
            }
            if (allowItemToFail) {
                logger.warn("Plaid Item failed, allowing failure and continuing to the next Item")
                return null
            } else throw cre
        }
    }

    /**
     * Processes Plaid transactions for a set of access tokens and account IDs.
     * Returns lists of created, updated, and deleted transactions.
     */
    suspend fun processPlaidTransactions(
        plaidItems: Sequence<PlaidItem>,
        cursorMap: MutableMap<PlaidItemKey, PlaidSyncCursor>
    ): PlaidTransactionResult {
        val plaidCreatedTxs = mutableListOf<PlaidTransaction>()
        val plaidUpdatedTxs = mutableListOf<PlaidTransaction>()
        val plaidDeletedTxs = mutableListOf<PlaidTransactionId>()
        val addedByItem = mutableMapOf<PlaidItemKey, Int>()
        val addedDatesByItem = mutableMapOf<PlaidItemKey, ClosedRange<LocalDate>>()
        val failedItems = mutableSetOf<PlaidItemKey>()

        itemLoop@ for (item in plaidItems) {
            logger.debug(
                "Querying Plaid transaction sync endpoint for Item ${item.key.take(17)} " +
                        "and ${item.accountIds.size} configured accounts"
            )
            val accountIdSet = item.accountIds.toSet()

            // Plaid transaction batch loop
            do {
                // Iterate through batches of Plaid transactions
                // In sync mode we fetch and retain all Plaid transactions that have changed since the last poll.
                val maybeResponse = executeTransactionSyncRequest(
                    item.accessToken,
                    cursorMap[item.key],
                    plaidBatchSize,
                    item,
                )
                if (maybeResponse == null) {
                    failedItems.add(item.key)
                    continue@itemLoop
                }
                val response: TransactionsSyncResponse = maybeResponse

                cursorMap[item.key] = response.nextCursor
                logger.debug(
                    "Received batch of sync updates for Item ${item.key.take(17)}: " +
                            "${response.added.size} created; ${response.modified.size} updated; " +
                            "${response.removed.size} deleted"
                )

                // The transaction sync endpoint doesn't take accountId as a parameter, so do that filtering here
                val added = response.added.filter { accountIdSet.contains(it.accountId) }
                addedByItem[item.key] = (addedByItem[item.key] ?: 0) + added.size
                if (added.isNotEmpty()) {
                    val dates = added.map { it.date }
                    val seen = addedDatesByItem[item.key]
                    addedDatesByItem[item.key] = minOf(dates.min(), seen?.start ?: LocalDate.MAX)..
                            maxOf(dates.max(), seen?.endInclusive ?: LocalDate.MIN)
                }
                plaidCreatedTxs.addAll(added)
                plaidUpdatedTxs.addAll(response.modified.filter { accountIdSet.contains(it.accountId) })
                plaidDeletedTxs.addAll(response.removed.mapNotNull { it.transactionId })

                // Keep going until we get all the transactions
            } while (response.hasMore)
        }

        return PlaidTransactionResult(
            plaidCreatedTxs,
            plaidUpdatedTxs,
            plaidDeletedTxs,
            addedByItem,
            failedItems,
            addedDatesByItem,
        )
    }

    /**
     * Initializes cursors for access tokens that don't have one yet.
     */
    suspend fun initializeCursors(
        plaidItems: Sequence<PlaidItem>,
        cursorMap: MutableMap<PlaidItemKey, PlaidSyncCursor>
    ) {
        logger.debug("Beginning Plaid sync endpoint cursor initialization")
        cursorCatchupLoop@ for (item in plaidItems) {
            // If we already have a cursor for this Item, then move on
            if (cursorMap.contains(item.key)) {
                logger.debug("Cursor map contains Item ${item.key.take(17)}, skipping initialization")
                continue
            }

            // For access tokens that we don't have cursors for, iterate through historical data and ignore it
            // to get current cursors
            do {
                val response =
                    executeTransactionSyncRequest(item.accessToken, cursorMap[item.key], plaidBatchSize, item)
                        ?: continue@cursorCatchupLoop
                logger.debug(
                    "Received initial sync batch for Item ${item.key.take(17)}"
                )
                if (response.nextCursor.isNotBlank()) {
                    cursorMap[item.key] = response.nextCursor
                }
            } while (response.hasMore)
        }
    }
}

/**
 * Data class to hold the result of processing Plaid transactions.
 */
data class PlaidTransactionResult(
    val created: List<PlaidTransaction>,
    val updated: List<PlaidTransaction>,
    val deleted: List<PlaidTransactionId>,
    /** Local change: per-Item counts and failures, for [SyncOutcomeRecorder]. */
    val addedByItem: Map<PlaidItemKey, Int> = emptyMap(),
    val failedItems: Set<PlaidItemKey> = emptySet(),
    /** Local change: the range of dates of each Item's added transactions. */
    val addedDatesByItem: Map<PlaidItemKey, ClosedRange<LocalDate>> = emptyMap(),
)