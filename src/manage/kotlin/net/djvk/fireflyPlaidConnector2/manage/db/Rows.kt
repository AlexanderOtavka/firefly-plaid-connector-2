package net.djvk.fireflyPlaidConnector2.manage.db

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@Suppress("EnumEntryName")
enum class ItemStatus {
    pending_history,
    active,
    login_required,
    error,
    retired,
}

@Suppress("EnumEntryName")
enum class BackfillStatus {
    pending,
    running,
    succeeded,
    failed,
    deadline_exceeded;

    val isActive get() = this == pending || this == running
}

/**
 * A Plaid Item row. Deliberately has no access token field: the only way to read a token is
 * [ItemRepository.accessTokenFor], so a row can be passed around, logged, or rendered safely.
 */
data class PlaidItemRow(
    val id: Long,
    val plaidItemId: String,
    val institutionId: String?,
    val institutionName: String?,
    val daysRequested: Int,
    val linkedAt: Instant,
    val status: ItemStatus,
    val replacedBy: Long?,
    val retiredAt: Instant?,
    val hasCursor: Boolean,
    /** When the Item first got a cursor and so became pollable. */
    val pollingSince: Instant?,
    val lastSyncAt: Instant?,
    val lastSyncAdded: Int?,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
    val lastErrorAt: Instant?,
    val consecutiveFailures: Int,
) {
    /** Only the last 4 characters of the Plaid Item ID are ever shown or exported. */
    val itemIdLast4: String get() = plaidItemId.takeLast(4)

    /**
     * The most history a backfill can usefully ask Plaid for: the Item's own
     * `days_requested`, plus however long it has been linked.
     */
    fun maxBackfillDays(now: Instant = Instant.now()): Int {
        val linkedDays = Duration.between(linkedAt, now).toDays().coerceAtLeast(0)
        return (daysRequested + linkedDays).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

data class PlaidAccountRow(
    val id: Long,
    val itemId: Long,
    val plaidAccountId: String,
    val persistentAccountId: String?,
    val name: String,
    val mask: String?,
    val type: String,
    val subtype: String?,
    val fireflyAccountId: Int?,
    val enabled: Boolean,
)

data class NewPlaidAccount(
    val plaidAccountId: String,
    val persistentAccountId: String?,
    val name: String,
    val mask: String?,
    val type: String,
    val subtype: String?,
)

data class BackfillRunRow(
    val id: Long,
    val itemId: Long,
    val accountIds: List<Long>,
    val days: Int,
    val deadlineSeconds: Int,
    val jobName: String,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val status: BackfillStatus,
    val fetched: Int?,
    val inserted: Int?,
    val duplicates: Int?,
    val failed: Int?,
    val dryRun: Boolean,
    val matched: Int?,
    val updated: Int?,
    val needsReview: Int?,
    val oldestDate: LocalDate?,
    val error: String?,
)

/** Strips anything shaped like a Plaid access token before text is stored or shown. */
object Redaction {
    private val token = Regex("""access-[A-Za-z0-9_-]+""")

    fun redact(text: String?, maxLength: Int = 500): String? =
        text?.replace(token, "[redacted token]")?.take(maxLength)
}
