package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import net.djvk.fireflyPlaidConnector2.transactions.BackfillReconciler
import net.djvk.fireflyPlaidConnector2.transactions.ReviewCandidate
import java.time.LocalDate

/**
 * Counts from optimistic inserts into Firefly.
 *
 * `duplicates` are transactions Firefly rejected by its duplicate hash. A backfill now
 * matches transactions it already imported first, so these are rare; it is still the backstop.
 *
 * Local change: a batch run also counts the converted transactions that `matched` one
 * already imported, the existing transactions it `updated` in place, and those it left for
 * review. In a dry run, `inserted` and `updated` are what the run would have written.
 */
data class InsertCounts(
    var inserted: Int = 0,
    var duplicates: Int = 0,
    var failed: Int = 0,
    var matched: Int = 0,
    var updated: Int = 0,
    var needsReview: Int = 0,
)

/** Summary of one batch-mode run, for [SyncOutcomeRecorder.batchFinished]. */
data class BatchOutcome(
    val fetched: Int,
    val counts: InsertCounts,
    val oldestDate: LocalDate?,
    /** Nothing was written to Firefly; the counts are what the run would have done. */
    val dryRun: Boolean = false,
    /** Converted transactions the run could not match safely; see [BackfillReconciler]. */
    val reviews: List<ReviewCandidate> = emptyList(),
)

/** A Plaid API error, reduced to the fields that are safe to store and display. */
data class PlaidErrorInfo(
    val code: String?,
    val message: String?,
) {
    companion object {
        private val mapper = ObjectMapper()
        private val textMarker = Regex("""Text: "(.*)"\s*$""", RegexOption.DOT_MATCHES_ALL)

        /**
         * Parses Plaid's `error_code`/`error_message` from an error response body. Anything that
         * is not a Plaid error body yields nulls rather than free-form text.
         */
        fun parse(body: String?): PlaidErrorInfo {
            if (body.isNullOrBlank()) return PlaidErrorInfo(null, null)
            return try {
                val node = mapper.readTree(body)
                PlaidErrorInfo(
                    node.path("error_code").textValue(),
                    node.path("error_message").textValue(),
                )
            } catch (_: Exception) {
                PlaidErrorInfo(null, null)
            }
        }

        suspend fun from(e: ClientRequestException): PlaidErrorInfo {
            val body = try {
                e.response.bodyAsText()
            } catch (_: Exception) {
                null
            }
            val parsed = parse(body)
            if (parsed.code != null) return parsed
            // Ktor also copies the body into the exception message.
            val fromMessage = e.message?.let { textMarker.find(it)?.groupValues?.get(1) }
            val status = try {
                e.response.status.value.toString()
            } catch (_: Exception) {
                "UNKNOWN"
            }
            return parse(fromMessage).takeIf { it.code != null } ?: PlaidErrorInfo("HTTP_$status", null)
        }
    }
}

/**
 * Hook for recording per-Item sync outcomes and batch run results.
 *
 * Local change: upstream only logs these. The database item store records them so the
 * management dashboard and its alerts can see them. Implementations must never record an
 * access token.
 */
interface SyncOutcomeRecorder {
    fun itemSucceeded(item: PlaidItem, added: Int) {}

    fun itemFailed(item: PlaidItem, error: PlaidErrorInfo) {}

    fun batchStarted() {}

    fun batchFinished(outcome: BatchOutcome) {}

    fun batchFailed(outcome: BatchOutcome, error: Throwable) {}
}

@Component
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = "config", matchIfMissing = true)
class NoopSyncOutcomeRecorder : SyncOutcomeRecorder
