package net.djvk.fireflyPlaidConnector2.manage.firefly

import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.FireflyTransactionId
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.manage.MANAGE_MODE
import net.djvk.fireflyPlaidConnector2.manage.SYNC_MODE_PROPERTY
import net.djvk.fireflyPlaidConnector2.sync.InsertCounts
import net.djvk.fireflyPlaidConnector2.sync.SyncHelper
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** The Firefly transaction calls behind resolving a backfill review. */
interface FireflyTransactions {
    /** The transaction's only split, or null if it no longer exists or has several. */
    suspend fun find(id: FireflyTransactionId): TransactionSplit?

    /** Inserts like a backfill does; returns false if Firefly rejected it as a duplicate. */
    suspend fun insert(split: TransactionSplit): Boolean

    /** Updates without re-running Firefly's rules, like a backfill's in-place update. */
    suspend fun update(id: FireflyTransactionId, split: TransactionSplit)

    suspend fun delete(id: FireflyTransactionId)
}

/** Uses the connector's personal access token, the same one polling and backfills use. */
@Component
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class PatFireflyTransactions(
    private val syncHelper: SyncHelper,
    private val transactionsApi: TransactionsApi,
) : FireflyTransactions {
    private val credsMutex = Mutex()
    private var credsSet = false

    private suspend fun ensureCreds() {
        credsMutex.withLock {
            if (!credsSet) {
                syncHelper.setApiCreds()
                credsSet = true
            }
        }
    }

    override suspend fun find(id: FireflyTransactionId): TransactionSplit? {
        ensureCreds()
        return try {
            transactionsApi.getTransaction(id).body().data.attributes.transactions.singleOrNull()
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) null else throw e
        }
    }

    override suspend fun insert(split: TransactionSplit): Boolean {
        ensureCreds()
        val counts = syncHelper.optimisticInsertBatchIntoFirefly(listOf(FireflyTransactionDto(null, split)), InsertCounts())
        return counts.duplicates == 0
    }

    override suspend fun update(id: FireflyTransactionId, split: TransactionSplit) {
        ensureCreds()
        syncHelper.updateBatchInFirefly(listOf(FireflyTransactionDto(id, split, applyRules = false)))
    }

    override suspend fun delete(id: FireflyTransactionId) {
        ensureCreds()
        syncHelper.deleteBatchInFirefly(listOf(id))
    }
}
