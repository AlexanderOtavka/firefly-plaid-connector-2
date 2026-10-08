package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsGetResponse
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.*
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.firefly.models.MetaPagination
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PageLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Transaction
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.transactions.PersonalFinanceCategoryEnum
import org.junit.jupiter.api.Test
import java.time.LocalDate
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.*

internal class BatchSyncRunnerTest {
    companion object {
        fun createRunner(
            plaid: PlaidMock,
            firefly: FireflyMock,
            syncDays: Int = 200,
            setInitialBalance: Boolean = false,
            plaidBatchSize: Int = 100,
            syncHelper: SyncHelper? = null,
            converter: TransactionConverter? = null,
            recorder: SyncOutcomeRecorder = NoopSyncOutcomeRecorder(),
            dryRun: Boolean = false,
        ): BatchSyncRunner {
            val defaultSyncHelper = SyncHelper(
                plaidItemSource = ConfigPlaidItemSource(
                    AccountConfigs(listOf(AccountConfig(1, "account1Token", "plaidAccount1"))),
                ),
                fireflyAccessToken = "testToken",
                fireflyAboutApi = firefly.aboutApi,
                fireflyTxApi = firefly.transactionsApi,
                fireflyAccountsApi = firefly.accountsApi,
            )
            val defaultTransactionConverter = TransactionConverter(
                useNameForDestination = false,
                timeZoneString = "America/New_York",
                transferMatchWindowDays = 5,
                enablePrimaryCategorization = true,
                primaryCategoryPrefix = "primary-",
                enableDetailedCategorization = true,
                detailedCategoryPrefix = "detailed-",
                txStyle = TransactionStyleConfig(),
            )

            return BatchSyncRunner(
                syncDays,
                setInitialBalance,
                null,
                plaidBatchSize,
                plaid.wrapper,
                syncHelper ?: defaultSyncHelper,
                firefly.accountsApi,
                converter ?: defaultTransactionConverter,
                recorder,
                reconcile = true,
                reconcileWindowDays = 4,
                dryRun = dryRun,
            )
        }

    }

    /** Local change: a backfill updates what it already imported instead of duplicating it. */
    private class CapturingRecorder : SyncOutcomeRecorder {
        var finished: BatchOutcome? = null
        override fun batchFinished(outcome: BatchOutcome) {
            finished = outcome
        }
    }

    private val day = LocalDate.now().minusDays(10)

    private fun plaidTx(id: String, amount: Double) = PlaidFixtures.getPaymentTransaction(
        pendingTransactionId = null,
        accountId = "plaidAccount1",
        name = "Corner Cafe",
        merchantName = "Corner Cafe",
        amount = amount,
        date = day,
        transactionId = id,
        personalFinanceCategory = PersonalFinanceCategoryEnum.FOOD_AND_DRINK_COFFEE.toPersonalFinanceCategory(),
    )

    /** A relinked Item: new-1 was imported before as old-1; new-2 is new. */
    private fun relinkedFirefly(): Pair<PlaidMock, FireflyMock> {
        val plaid = PlaidMock()
        val firefly = FireflyMock()
        val txs = listOf(plaidTx("new-1", 5.0), plaidTx("new-2", 7.0))
        // Built outside stub {}: creating a mock while stubbing another one confuses Mockito.
        val plaidResponse = createPlaidResponse(
            TransactionsGetResponse(listOf(), txs, txs.size, PlaidFixtures.getItem(), "request")
        )
        plaid.api.stub {
            onBlocking { transactionsGet(any()) } doReturn plaidResponse
        }
        val earlierRun = TransactionConverter(
            useNameForDestination = false,
            timeZoneString = "America/New_York",
            transferMatchWindowDays = 5,
            enablePrimaryCategorization = true,
            primaryCategoryPrefix = "primary-",
            enableDetailedCategorization = true,
            detailedCategoryPrefix = "detailed-",
            txStyle = TransactionStyleConfig(),
        )
        val imported = runBlocking {
            earlierRun.convertBatchSync(listOf(plaidTx("old-1", 5.0)), mapOf("plaidAccount1" to 1)).single().tx
        }
        val fireflyResponse = createFireflyResponse(
            TransactionArray(
                listOf(TransactionRead("transactions", "101", Transaction(listOf(imported)), ObjectLink())),
                Meta(MetaPagination(currentPage = 1, totalPages = 1)),
                PageLink(),
            )
        )
        firefly.accountsApi.stub {
            onBlocking { listTransactionByAccount(eq("1"), any(), any(), any(), any(), any()) } doReturn fireflyResponse
        }
        return Pair(plaid, firefly)
    }

    @Test
    fun `a backfill after a relink updates the earlier import and inserts only what is new`() {
        val (plaid, firefly) = relinkedFirefly()
        val recorder = CapturingRecorder()

        createRunner(plaid, firefly, recorder = recorder).run()

        verifyBlocking(firefly.transactionsApi) { updateTransaction(eq("101"), any()) }
        verifyBlocking(firefly.transactionsApi, times(1)) { storeTransaction(any()) }
        val counts = recorder.finished!!.counts
        assertThat(counts.matched).isEqualTo(1)
        assertThat(counts.updated).isEqualTo(1)
        assertThat(counts.inserted).isEqualTo(1)
        assertThat(counts.needsReview).isZero()
    }

    @Test
    fun `a dry run reports what it would do and writes nothing`() {
        val (plaid, firefly) = relinkedFirefly()
        val recorder = CapturingRecorder()

        createRunner(plaid, firefly, recorder = recorder, dryRun = true).run()

        verifyBlocking(firefly.transactionsApi, never()) { updateTransaction(any(), any()) }
        verifyBlocking(firefly.transactionsApi, never()) { storeTransaction(any()) }
        val outcome = recorder.finished!!
        assertThat(outcome.dryRun).isTrue()
        assertThat(outcome.counts.updated).isEqualTo(1)
        assertThat(outcome.counts.inserted).isEqualTo(1)
    }
}
