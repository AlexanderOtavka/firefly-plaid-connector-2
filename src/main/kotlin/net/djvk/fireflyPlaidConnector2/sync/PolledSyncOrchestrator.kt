package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.transactions.FireflyAccountId
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

typealias IntervalMinutes = Int
typealias PlaidSyncCursor = String

/**
 * Orchestrates the polled sync process.
 *
 * Handles the "polled" sync mode, which periodically polls for new transactions and processes them.
 * This class coordinates the different components involved in the sync process.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "polled")
@Component
class PolledSyncOrchestrator(
    @Value("\${fireflyPlaidConnector2.polled.syncFrequencyMinutes}")
    private val syncFrequencyMinutes: IntervalMinutes,

    private val syncHelper: SyncHelper,
    private val cursorManager: CursorStore,
    private val plaidSyncService: PlaidSyncService,
    private val fireflyTransactionService: FireflyTransactionService,
    private val converter: TransactionConverter,
    private val syncOutcomeRecorder: SyncOutcomeRecorder = NoopSyncOutcomeRecorder(),
) : Runner, DisposableBean {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val terminated = AtomicBoolean(false)
    private lateinit var mainJob: Job

    /**
     * Initializes cursors for access tokens that don't have one yet.
     */
    suspend fun initializeCursors() {
        // Read cursor map from storage
        val cursorMap = cursorManager.readCursorMap()

        // Get account mappings
        val (_, plaidItems) = syncHelper.getAccountMapAndPlaidItems()

        // Initialize cursors for access tokens that don't have one yet
        plaidSyncService.initializeCursors(plaidItems, cursorMap)
        cursorManager.writeCursorMap(cursorMap)
    }

    /**
     * Processes transactions by fetching from Plaid, converting, and updating Firefly.
     */
    suspend fun processTransactions(
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        plaidItems: Sequence<PlaidItem>,
        cursorMap: MutableMap<PlaidItemKey, PlaidSyncCursor>
    ) {
        // Fetch existing Firefly transactions
        val existingFireflyTxs = fireflyTransactionService.fetchExistingFireflyTransactions()

        // Process Plaid transactions
        val nextCursorMap = cursorMap.toMutableMap()
        val plaidTransactions = plaidSyncService.processPlaidTransactions(plaidItems, nextCursorMap)

        // Convert Plaid transactions to Firefly format
        logger.trace("Converting Plaid transactions to Firefly transactions")
        val convertResult = converter.convertPollSync(
            accountMap,
            plaidTransactions.created,
            plaidTransactions.updated,
            plaidTransactions.deleted,
            existingFireflyTxs,
        )
        logger.debug(
            "Conversion result: ${convertResult.creates.size} creates; " +
                    "${convertResult.updates.size} updates; " +
                    "${convertResult.deletes.size} deletes;"
        )

        // Process transaction updates in Firefly
        fireflyTransactionService.processFireflyTransactionUpdates(
            convertResult.creates,
            convertResult.updates,
            convertResult.deletes
        )

        // Update cursor map after successful processing
        cursorManager.writeCursorMap(nextCursorMap)
        cursorMap.clear()
        cursorMap.putAll(nextCursorMap)

        // Local change: record per-Item outcomes only once the cycle has been committed.
        for (item in plaidItems) {
            if (item.key !in plaidTransactions.failedItems) {
                syncOutcomeRecorder.itemSucceeded(item, plaidTransactions.addedByItem[item.key] ?: 0)
            }
        }
    }

    /**
     * One polling cycle.
     *
     * Local change: upstream read Items and cursors once, before its loop, so an Item added
     * while the connector was running was never polled. Both are now re-read every cycle, and
     * cursors are initialized for any Item that has appeared since the last one. Re-reading
     * the cursor store is equivalent to keeping it in memory, because it is only written once
     * a cycle has fully succeeded.
     */
    suspend fun pollOnce() {
        val (accountMap, plaidItemsSequence) = syncHelper.getAccountMapAndPlaidItems()
        val plaidItems = plaidItemsSequence.toList()
        val cursorMap = cursorManager.readCursorMap()

        val missingCursors = plaidItems.any { it.key !in cursorMap }
        if (missingCursors) {
            plaidSyncService.initializeCursors(plaidItems.asSequence(), cursorMap)
            cursorManager.writeCursorMap(cursorMap)
        }

        processTransactions(accountMap, plaidItems.asSequence(), cursorMap)
    }

    override fun run() {
        runBlocking {
            mainJob = launch {
                do {
                    try {
                        syncHelper.setApiCreds()

                        while (!terminated.get()) {
                            logger.debug("Polling loop start")
                            try {
                                pollOnce()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.error(
                                    "Polling cycle failed; cursor state was not advanced and the cycle will be retried",
                                    e,
                                )
                            }

                            logger.trace("Calling System.gc()")
                            System.gc()

                            logger.info("Sleeping $syncFrequencyMinutes")
                            delay(syncFrequencyMinutes.minutes)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(
                            "Plaid polling initialization failed; retrying in $syncFrequencyMinutes minutes",
                            e,
                        )
                        delay(syncFrequencyMinutes.minutes)
                    }
                } while (!terminated.get())
            }
        }
    }

    override fun destroy() {
        logger.info("Shutting down ${this::class}")
        terminated.set(true)
        mainJob.cancel()
    }
}
