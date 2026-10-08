package net.djvk.fireflyPlaidConnector2.manage.db

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.ApiClient
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.sync.ITEM_STORE_PROPERTY
import net.djvk.fireflyPlaidConnector2.transactions.ReviewCandidate
import net.djvk.fireflyPlaidConnector2.transactions.ReviewKind
import net.djvk.fireflyPlaidConnector2.transactions.ReviewTarget
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.ResultSet
import java.time.LocalDate

enum class ReviewStatus { open, imported, merged, deleted, dismissed }

/** Something a backfill could not decide safely; see [ReviewCandidate]. */
data class BackfillReviewRow(
    val id: Long,
    val runId: Long,
    val kind: ReviewKind,
    val reason: String,
    val txDate: LocalDate,
    val amount: String,
    val description: String,
    val proposed: TransactionSplit?,
    val targets: List<ReviewTarget>,
    val relatedIds: List<String>,
    val generated: Set<String>,
    val status: ReviewStatus,
    val mergedInto: String?,
)

@Repository
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = DATABASE_ITEM_STORE)
class BackfillReviewRepository(private val jdbc: JdbcClient) {
    fun insertAll(runId: Long, reviews: List<ReviewCandidate>) {
        for (review in reviews) {
            jdbc.sql(
                """
                INSERT INTO backfill_review (run_id, kind, reason, tx_date, amount, description, proposed,
                                             candidate_ids, candidate_external_ids, related_ids, generated)
                VALUES (:runId, :kind, :reason, :txDate, :amount, :description, CAST(:proposed AS JSONB),
                        CAST(:candidateIds AS TEXT[]), CAST(:candidateExternalIds AS TEXT[]),
                        CAST(:relatedIds AS TEXT[]), CAST(:generated AS TEXT[]))
                """.trimIndent()
            )
                .param("runId", runId)
                .param("kind", review.kind.name.lowercase())
                .param("reason", review.reason)
                .param("txDate", Date.valueOf(review.date))
                .param("amount", review.amount)
                .param("description", review.description)
                .param("proposed", review.proposed?.let { json.writeValueAsString(it.tx) }, java.sql.Types.VARCHAR)
                .param("candidateIds", arrayLiteral(review.targets.map { it.id }))
                .param("candidateExternalIds", arrayLiteral(review.targets.map { it.externalId }))
                .param("relatedIds", arrayLiteral(review.relatedIds))
                .param("generated", arrayLiteral(review.generated.sorted()))
                .update()
        }
    }

    fun forRun(runId: Long): List<BackfillReviewRow> =
        jdbc.sql("SELECT * FROM backfill_review WHERE run_id = :runId ORDER BY tx_date, id")
            .param("runId", runId)
            .query(mapper)
            .list()

    fun find(id: Long): BackfillReviewRow? =
        jdbc.sql("SELECT * FROM backfill_review WHERE id = :id")
            .param("id", id)
            .query(mapper)
            .optional()
            .orElse(null)

    /** Open reviews per run, for the dashboard. */
    fun openCounts(): Map<Long, Int> =
        jdbc.sql("SELECT run_id, count(*) AS open FROM backfill_review WHERE status = 'open' GROUP BY run_id")
            .query { rs, _ -> rs.getLong("run_id") to rs.getInt("open") }
            .list()
            .toMap()

    /**
     * Marks an open review resolved. Returns false if it was no longer open, so two clicks
     * cannot both act on it.
     */
    fun resolve(id: Long, status: ReviewStatus, mergedInto: String? = null): Boolean {
        require(status != ReviewStatus.open)
        return jdbc.sql(
            """
            UPDATE backfill_review SET status = :status, merged_into = :mergedInto, resolved_at = now()
             WHERE id = :id AND status = 'open'
            """.trimIndent()
        )
            .param("id", id)
            .param("status", status.name)
            .param("mergedInto", mergedInto)
            .update() == 1
    }

    /** Undoes [resolve] after the Firefly call it guarded failed. */
    fun reopen(id: Long) {
        jdbc.sql("UPDATE backfill_review SET status = 'open', merged_into = NULL, resolved_at = NULL WHERE id = :id")
            .param("id", id)
            .update()
    }

    companion object {
        /** The Firefly API client's own settings, so a stored split reads back exactly. */
        val json: ObjectMapper = jacksonObjectMapper()
            .apply(ApiClient.JSON_DEFAULT)
            .configure(SerializationFeature.INDENT_OUTPUT, false)
            // Keep the offset Firefly was sent, rather than normalizing to UTC.
            .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)

        /** A PostgreSQL array literal; null elements stay NULL. */
        private fun arrayLiteral(values: List<String?>) = values.joinToString(",", "{", "}") { value ->
            value?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } ?: "NULL"
        }

        private fun textArray(rs: ResultSet, column: String): List<String?> =
            (rs.getArray(column).array as Array<*>).map { it as String? }

        val mapper = RowMapper { rs, _ ->
            val ids = textArray(rs, "candidate_ids")
            val externalIds = textArray(rs, "candidate_external_ids")
            BackfillReviewRow(
                id = rs.getLong("id"),
                runId = rs.getLong("run_id"),
                kind = ReviewKind.valueOf(rs.getString("kind").uppercase()),
                reason = rs.getString("reason"),
                txDate = rs.getObject("tx_date", Date::class.java).toLocalDate(),
                amount = rs.getString("amount"),
                description = rs.getString("description"),
                proposed = rs.getString("proposed")?.let { json.readValue(it) },
                targets = ids.zip(externalIds) { id, externalId -> ReviewTarget(id!!, externalId) },
                relatedIds = textArray(rs, "related_ids").filterNotNull(),
                generated = textArray(rs, "generated").filterNotNull().toSet(),
                status = ReviewStatus.valueOf(rs.getString("status")),
                mergedInto = rs.getString("merged_into"),
            )
        }
    }
}
