package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapperTest
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Local changes: sync outcome recording, and re-reading Items every polling cycle. */
class SyncOutcomeTest {
    private val loginRequired = """
        {"error_type":"ITEM_ERROR","error_code":"ITEM_LOGIN_REQUIRED",
         "error_message":"the login details of this item have changed","display_message":null,
         "request_id":"abc"}
    """.trimIndent()

    private class RecordingRecorder : SyncOutcomeRecorder {
        val failures = mutableListOf<Pair<PlaidItemKey, PlaidErrorInfo>>()
        val successes = mutableListOf<Pair<PlaidItemKey, Int>>()

        override fun itemFailed(item: PlaidItem, error: PlaidErrorInfo) {
            failures += item.key to error
        }

        override fun itemSucceeded(item: PlaidItem, added: Int) {
            successes += item.key to added
        }
    }

    @Test
    fun `parses Plaid error bodies and nothing else`() {
        assertThat(PlaidErrorInfo.parse(loginRequired))
            .isEqualTo(PlaidErrorInfo("ITEM_LOGIN_REQUIRED", "the login details of this item have changed"))
        assertThat(PlaidErrorInfo.parse("<html>bad gateway</html>")).isEqualTo(PlaidErrorInfo(null, null))
        assertThat(PlaidErrorInfo.parse(null)).isEqualTo(PlaidErrorInfo(null, null))
    }

    private fun failingPlaid() = PlaidApiWrapperTest.mockPlaid(MockEngine {
        respond(
            content = ByteReadChannel(loginRequired),
            status = HttpStatusCode.BadRequest,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })

    @Test
    fun `an Item failure is recorded with its Plaid error code when allowed to fail`(): Unit = runBlocking {
        val recorder = RecordingRecorder()
        val service = PlaidSyncService(failingPlaid(), 100, true, recorder)
        val item = PlaidItem.from("access-sandbox-1", listOf("a"))

        val result = service.processPlaidTransactions(sequenceOf(item), mutableMapOf(item.key to "cursor"))

        assertThat(result.failedItems).containsExactly(item.key)
        assertThat(recorder.failures.map { it.first to it.second.code }).containsExactly(item.key to "ITEM_LOGIN_REQUIRED")
    }

    @Test
    fun `an Item failure is recorded even when it stops the cycle`() {
        val recorder = RecordingRecorder()
        val service = PlaidSyncService(failingPlaid(), 100, false, recorder)
        val item = PlaidItem.from("access-sandbox-1", listOf("a"))

        runCatching { runBlocking { service.processPlaidTransactions(sequenceOf(item), mutableMapOf(item.key to "c")) } }

        assertThat(recorder.failures.map { it.second.code }).containsExactly("ITEM_LOGIN_REQUIRED")
    }

    @Test
    fun `the polling cycle re-reads Items and initializes cursors only for new ones`(): Unit = runBlocking {
        val syncHelper: SyncHelper = mock()
        val cursorStore: CursorStore = mock()
        val plaidSyncService: PlaidSyncService = mock()
        val fireflyTransactionService: FireflyTransactionService = mock()
        val converter: TransactionConverter = mock()
        val recorder = RecordingRecorder()
        val orchestrator = PolledSyncOrchestrator(
            30, syncHelper, cursorStore, plaidSyncService, fireflyTransactionService, converter, recorder,
        )
        val first = PlaidItem.from("access-sandbox-1", listOf("a"))
        val second = PlaidItem.from("access-sandbox-2", listOf("b"))

        whenever(fireflyTransactionService.fetchExistingFireflyTransactions()).thenReturn(emptyList())
        whenever(converter.convertPollSync(any(), any(), any(), any(), any()))
            .thenReturn(TransactionConverter.ConvertPollSyncResult(emptyList(), emptyList(), emptyList()))
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(PlaidTransactionResult(emptyList(), emptyList(), emptyList(), mapOf(first.key to 2)))

        // Cycle 1: one Item, which already has a cursor.
        whenever(syncHelper.getAccountMapAndPlaidItems()).thenReturn(Pair(mapOf("a" to 1), sequenceOf(first)))
        whenever(cursorStore.readCursorMap()).thenReturn(mutableMapOf(first.key to "c1"))
        orchestrator.pollOnce()
        verify(plaidSyncService, never()).initializeCursors(any(), any())

        // Cycle 2: an Item appeared since, without a cursor.
        whenever(syncHelper.getAccountMapAndPlaidItems())
            .thenReturn(Pair(mapOf("a" to 1, "b" to 2), sequenceOf(first, second)))
        whenever(cursorStore.readCursorMap()).thenReturn(mutableMapOf(first.key to "c1"))
        orchestrator.pollOnce()
        verify(plaidSyncService).initializeCursors(any(), eq(mutableMapOf(first.key to "c1")))

        assertThat(recorder.successes).contains(first.key to 2, second.key to 0)
    }
}
