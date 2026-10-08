package net.djvk.fireflyPlaidConnector2.manage.web

import net.djvk.fireflyPlaidConnector2.manage.db.BackfillReviewRow
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillRunRow
import net.djvk.fireflyPlaidConnector2.manage.db.ReviewStatus
import net.djvk.fireflyPlaidConnector2.manage.db.PlaidAccountRow
import net.djvk.fireflyPlaidConnector2.manage.db.PlaidItemRow
import net.djvk.fireflyPlaidConnector2.manage.plaid.LinkMode
import net.djvk.fireflyPlaidConnector2.transactions.ReviewKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/*
 * Everything the dashboard sends to a browser, as JSON or through a template. None of these
 * types has an access token field, and TokenHygieneTest fails if a response ever contains one.
 */

data class ItemView(
    val id: Long,
    val itemIdLast4: String,
    val institutionName: String?,
    val status: String,
    val daysRequested: Int,
    val maxBackfillDays: Int,
    val linkedAt: Instant,
    val replacedBy: Long?,
    val retiredAt: Instant?,
    val lastSyncAt: Instant?,
    val lastSyncAdded: Int?,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
    val lastErrorAt: Instant?,
    val consecutiveFailures: Int,
    val accounts: List<AccountView>,
) {
    val isLive get() = status != "retired"
    val canBackfill get() = status == "active" && accounts.any { it.enabled }

    companion object {
        fun of(item: PlaidItemRow, accounts: List<PlaidAccountRow>) = ItemView(
            id = item.id,
            itemIdLast4 = item.itemIdLast4,
            institutionName = item.institutionName,
            status = item.status.name,
            daysRequested = item.daysRequested,
            maxBackfillDays = item.maxBackfillDays(),
            linkedAt = item.linkedAt,
            replacedBy = item.replacedBy,
            retiredAt = item.retiredAt,
            lastSyncAt = item.lastSyncAt,
            lastSyncAdded = item.lastSyncAdded,
            lastErrorCode = item.lastErrorCode,
            lastErrorMessage = item.lastErrorMessage,
            lastErrorAt = item.lastErrorAt,
            consecutiveFailures = item.consecutiveFailures,
            accounts = accounts.map { AccountView.of(it) },
        )
    }
}

data class AccountView(
    val id: Long,
    val name: String,
    val mask: String?,
    val type: String,
    val subtype: String?,
    val fireflyAccountId: Int?,
    val enabled: Boolean,
) {
    companion object {
        fun of(account: PlaidAccountRow) = AccountView(
            id = account.id,
            name = account.name,
            mask = account.mask,
            type = account.type,
            subtype = account.subtype,
            fireflyAccountId = account.fireflyAccountId,
            enabled = account.enabled,
        )
    }
}

data class BackfillRunView(
    val id: Long,
    val itemId: Long,
    val accountIds: List<Long>,
    val days: Int,
    val deadlineSeconds: Int,
    val jobName: String,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val durationSeconds: Long?,
    val status: String,
    val fetched: Int?,
    val inserted: Int?,
    val duplicates: Int?,
    val failed: Int?,
    val oldestDate: LocalDate?,
    val error: String?,
    val dryRun: Boolean,
    val matched: Int?,
    val updated: Int?,
    val needsReview: Int?,
    /** Reviews still waiting for a decision. */
    val openReviews: Int,
) {
    companion object {
        fun of(run: BackfillRunRow, openReviews: Int = 0) = BackfillRunView(
            id = run.id,
            itemId = run.itemId,
            accountIds = run.accountIds,
            days = run.days,
            deadlineSeconds = run.deadlineSeconds,
            jobName = run.jobName,
            requestedAt = run.requestedAt,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt,
            durationSeconds = run.startedAt?.let { start -> Duration.between(start, run.finishedAt ?: Instant.now()).seconds },
            status = run.status.name,
            fetched = run.fetched,
            inserted = run.inserted,
            duplicates = run.duplicates,
            failed = run.failed,
            oldestDate = run.oldestDate,
            error = run.error,
            dryRun = run.dryRun,
            matched = run.matched,
            updated = run.updated,
            needsReview = run.needsReview,
            openReviews = openReviews,
        )
    }
}

data class MappingRowView(
    val account: AccountView,
    val suggestedFireflyAccountId: Int?,
)

data class LinkTokenRequest(val mode: LinkMode, val itemId: Long? = null)

data class LinkTokenResponse(val linkToken: String)

data class ExchangeRequest(val publicToken: String, val mode: LinkMode, val replacesItemId: Long? = null)

data class ExchangeResponse(val itemId: Long)

data class MappingRequest(val accounts: List<MappingEntry>)

data class MappingEntry(val accountId: Long, val fireflyAccountId: Int? = null, val enabled: Boolean = false)

data class BackfillRequest(
    val itemId: Long,
    val accountIds: List<Long>? = null,
    val days: Int,
    val timeoutSeconds: Int? = null,
    val dryRun: Boolean = false,
)

/** A backfill review, as listed on its run's review page. */
data class ReviewView(
    val id: Long,
    /** `unmatched` or `leftover`; see [ReviewKind]. */
    val kind: String,
    val reason: String,
    val date: LocalDate,
    val amount: String,
    val description: String,
    val type: String?,
    val counterparty: String?,
    val status: String,
    val mergedInto: String?,
    val candidates: List<ReviewCandidateView>,
    /** Leftover only: Firefly id to link, for the imports it may duplicate. */
    val related: Map<String, String>,
) {
    val isOpen get() = status == ReviewStatus.open.name
    val isLeftover get() = kind == ReviewKind.LEFTOVER.name.lowercase()

    companion object {
        fun of(row: BackfillReviewRow, candidates: List<ReviewCandidateView>, related: Map<String, String>) = ReviewView(
            id = row.id,
            kind = row.kind.name.lowercase(),
            reason = row.reason,
            date = row.txDate,
            amount = row.amount,
            description = row.description,
            type = row.proposed?.type?.value,
            counterparty = row.proposed?.let { it.destinationName ?: it.sourceName },
            status = row.status.name,
            mergedInto = row.mergedInto,
            candidates = candidates,
            related = related,
        )
    }
}

/** A Firefly transaction in a review; [description] is null if it is gone. */
data class ReviewCandidateView(
    val fireflyId: String,
    val url: String,
    val date: LocalDate?,
    val description: String?,
    val categoryName: String?,
    /** Matched to another bank transaction since the review was made, so it cannot be acted on. */
    val changed: Boolean,
)

data class MergeRequest(val fireflyId: String)

data class ErrorResponse(val error: String)

data class OkResponse(val ok: Boolean = true)
