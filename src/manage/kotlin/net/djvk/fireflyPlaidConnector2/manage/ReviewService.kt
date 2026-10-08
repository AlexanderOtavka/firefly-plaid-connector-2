package net.djvk.fireflyPlaidConnector2.manage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillReviewRepository
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillReviewRow
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillRunRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ReviewStatus
import net.djvk.fireflyPlaidConnector2.manage.firefly.FireflyTransactions
import net.djvk.fireflyPlaidConnector2.manage.web.ReviewCandidateView
import net.djvk.fireflyPlaidConnector2.manage.web.ReviewView
import net.djvk.fireflyPlaidConnector2.transactions.ReviewKind
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Resolves what a backfill could not decide safely.
 *
 * - **Unmatched:** a transaction several imports could be. The owner merges it into one of
 *   them (updated in place, exactly as a backfill updates a match), imports it as new, or
 *   dismisses it.
 * - **Leftover:** an import nothing matched, beside one that did. The owner deletes it or
 *   dismisses the review to keep it.
 *
 * Merging and deleting are refused once the Firefly transaction's `external_id` differs from
 * when the review was made: another review, or a later backfill, has already claimed it. A
 * review is claimed before Firefly is touched, so two clicks cannot both act on it, and
 * reopened if the Firefly call fails.
 */
@Service
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class ReviewService(
    private val reviews: BackfillReviewRepository,
    private val runs: BackfillRunRepository,
    private val firefly: FireflyTransactions,
    private val converter: TransactionConverter,
    @Value("\${fireflyPlaidConnector2.manage.publicBaseUrl:}")
    private val publicBaseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    init {
        require(publicBaseUrl.isNotBlank()) { "fireflyPlaidConnector2.manage.publicBaseUrl is required in manage mode" }
    }

    /** Serializes merges and deletes, so two cannot both pass the unchanged check for one target. */
    private val writes = Mutex()

    private fun url(id: String) = "${publicBaseUrl.trimEnd('/')}/transactions/show/$id"

    /** A run's reviews; open ones carry their targets' current details from Firefly. */
    suspend fun list(runId: Long): List<ReviewView> = reviews.forRun(runId).map { row ->
        val candidates = row.targets.map { target ->
            val split = if (row.status == ReviewStatus.open) firefly.find(target.id) else null
            ReviewCandidateView(
                fireflyId = target.id,
                url = url(target.id),
                date = split?.date?.toLocalDate(),
                description = split?.description,
                categoryName = split?.categoryName,
                changed = split != null && split.externalId != target.externalId,
            )
        }
        ReviewView.of(row, candidates, row.relatedIds.associateWith { url(it) })
    }

    suspend fun importNew(id: Long) {
        val open = requireOpen(id, ReviewKind.UNMATCHED)
        val review = claim(open, ReviewStatus.imported)
        undoClaimOnFailure(review) {
            if (!firefly.insert(review.proposed!!)) {
                logger.info("Review {}: Firefly already had this transaction (duplicate hash)", id)
            }
        }
    }

    suspend fun merge(id: Long, fireflyId: String) = writes.withLock {
        val open = requireOpen(id, ReviewKind.UNMATCHED)
        val existing = requireUnchanged(open, fireflyId)
        if (existing.type != open.proposed!!.type) {
            throw ManageException(
                409,
                "Firefly cannot change a ${existing.type.value} into a ${open.proposed.type.value}; dismiss this instead",
            )
        }
        val review = claim(open, ReviewStatus.merged, fireflyId)
        undoClaimOnFailure(review) {
            firefly.update(fireflyId, converter.refreshImported(review.proposed!!, existing, review.generated))
        }
    }

    suspend fun delete(id: Long) = writes.withLock {
        val open = requireOpen(id, ReviewKind.LEFTOVER)
        val fireflyId = open.targets.single().id
        requireUnchanged(open, fireflyId)
        val review = claim(open, ReviewStatus.deleted)
        undoClaimOnFailure(review) { firefly.delete(fireflyId) }
    }

    fun dismiss(id: Long) {
        claim(requireOpen(id, null), ReviewStatus.dismissed)
    }

    private fun requireOpen(id: Long, kind: ReviewKind?): BackfillReviewRow {
        val review = reviews.find(id) ?: throw ManageException(404, "No such review")
        if (runs.find(review.runId)?.dryRun != false) {
            throw ManageException(409, "This review is from a dry run; run the backfill for real to act on it")
        }
        if (review.status != ReviewStatus.open) throw ManageException(409, "This review is already ${review.status}")
        if (kind != null && review.kind != kind) throw ManageException(400, "Not available for this review")
        return review
    }

    /** The target's current split, if it is one of [review]'s and nothing has claimed it since. */
    private suspend fun requireUnchanged(review: BackfillReviewRow, fireflyId: String): TransactionSplit {
        val target = review.targets.firstOrNull { it.id == fireflyId }
            ?: throw ManageException(400, "Firefly transaction $fireflyId is not part of this review")
        val split = firefly.find(fireflyId)
            ?: throw ManageException(409, "Firefly transaction $fireflyId no longer exists, or has several splits")
        if (split.externalId != target.externalId) {
            throw ManageException(
                409,
                "Firefly transaction $fireflyId has been matched to another bank transaction since this backfill",
            )
        }
        return split
    }

    private fun claim(review: BackfillReviewRow, status: ReviewStatus, mergedInto: String? = null): BackfillReviewRow {
        if (!reviews.resolve(review.id, status, mergedInto)) throw ManageException(409, "This review was just resolved")
        return review
    }

    private suspend fun undoClaimOnFailure(review: BackfillReviewRow, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            reviews.reopen(review.id)
            throw e
        }
    }
}
