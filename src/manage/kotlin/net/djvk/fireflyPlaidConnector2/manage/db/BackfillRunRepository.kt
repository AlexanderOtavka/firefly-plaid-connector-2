package net.djvk.fireflyPlaidConnector2.manage.db

import net.djvk.fireflyPlaidConnector2.sync.BatchOutcome
import net.djvk.fireflyPlaidConnector2.sync.ITEM_STORE_PROPERTY
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.Timestamp

/** Backfill Job names are firefly-plaid-backfill-run-<backfill_run.id>. */
const val BACKFILL_JOB_NAME_PREFIX = "firefly-plaid-backfill-run-"

fun backfillJobName(runId: Long) = "$BACKFILL_JOB_NAME_PREFIX$runId"

@Repository
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = DATABASE_ITEM_STORE)
class BackfillRunRepository(private val jdbc: JdbcClient) {
    fun recent(limit: Int = 50): List<BackfillRunRow> =
        jdbc.sql("SELECT * FROM backfill_run ORDER BY requested_at DESC, id DESC LIMIT :limit")
            .param("limit", limit)
            .query(mapper)
            .list()

    fun find(id: Long): BackfillRunRow? =
        jdbc.sql("SELECT * FROM backfill_run WHERE id = :id")
            .param("id", id)
            .query(mapper)
            .optional()
            .orElse(null)

    fun active(): List<BackfillRunRow> =
        jdbc.sql("SELECT * FROM backfill_run WHERE status IN ('pending', 'running') ORDER BY id")
            .query(mapper)
            .list()

    fun lastFinished(): BackfillRunRow? =
        jdbc.sql("SELECT * FROM backfill_run WHERE finished_at IS NOT NULL ORDER BY finished_at DESC, id DESC LIMIT 1")
            .query(mapper)
            .optional()
            .orElse(null)

    /**
     * Inserts a pending run. The `backfill_run_one_active` index makes this fail with a
     * DuplicateKeyException while another run is pending or running.
     */
    fun insertPending(itemId: Long, accountIds: List<Long>, days: Int, deadlineSeconds: Int, dryRun: Boolean = false): Long =
        // One statement: the row and its Job name exist together or not at all.
        jdbc.sql(
            """
            WITH next AS (SELECT nextval(pg_get_serial_sequence('backfill_run', 'id')) AS id)
            INSERT INTO backfill_run (id, item_id, account_ids, days, deadline_seconds, dry_run, status, job_name)
            SELECT id, :itemId, CAST(:accountIds AS BIGINT[]), :days, :deadlineSeconds, :dryRun, 'pending',
                   :jobNamePrefix || id
              FROM next
            RETURNING id
            """.trimIndent()
        )
            .param("itemId", itemId)
            .param("accountIds", accountIds.joinToString(",", "{", "}"))
            .param("days", days)
            .param("deadlineSeconds", deadlineSeconds)
            .param("dryRun", dryRun)
            .param("jobNamePrefix", BACKFILL_JOB_NAME_PREFIX)
            .query(Long::class.java)
            .single()

    fun markStarted(id: Long) {
        jdbc.sql(
            "UPDATE backfill_run SET status = 'running', started_at = now() WHERE id = :id AND status IN ('pending', 'running')"
        )
            .param("id", id)
            .update()
    }

    /** Records a run's final result. A run already in a final state is left alone. */
    fun finish(id: Long, status: BackfillStatus, outcome: BatchOutcome?, error: String?) {
        require(!status.isActive) { "finish() needs a final status" }
        jdbc.sql(
            """
            UPDATE backfill_run
               SET status = :status, finished_at = now(), started_at = COALESCE(started_at, now()),
                   fetched = COALESCE(:fetched, fetched), inserted = COALESCE(:inserted, inserted),
                   duplicates = COALESCE(:duplicates, duplicates), failed = COALESCE(:failed, failed),
                   matched = COALESCE(:matched, matched), updated = COALESCE(:updated, updated),
                   needs_review = COALESCE(:needsReview, needs_review),
                   oldest_date = COALESCE(:oldestDate, oldest_date), error = :error
             WHERE id = :id AND status IN ('pending', 'running')
            """.trimIndent()
        )
            .param("id", id)
            .param("status", status.name)
            .param("fetched", outcome?.fetched, java.sql.Types.INTEGER)
            .param("inserted", outcome?.counts?.inserted, java.sql.Types.INTEGER)
            .param("duplicates", outcome?.counts?.duplicates, java.sql.Types.INTEGER)
            .param("failed", outcome?.counts?.failed, java.sql.Types.INTEGER)
            .param("matched", outcome?.counts?.matched, java.sql.Types.INTEGER)
            .param("updated", outcome?.counts?.updated, java.sql.Types.INTEGER)
            .param("needsReview", outcome?.counts?.needsReview, java.sql.Types.INTEGER)
            .param("oldestDate", outcome?.oldestDate?.let { Date.valueOf(it) }, java.sql.Types.DATE)
            .param("error", Redaction.redact(error))
            .update()
    }

    companion object {
        val mapper = RowMapper { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            val accountIds = (rs.getArray("account_ids").array as Array<Any>).map { (it as Number).toLong() }
            BackfillRunRow(
                id = rs.getLong("id"),
                itemId = rs.getLong("item_id"),
                accountIds = accountIds,
                days = rs.getInt("days"),
                deadlineSeconds = rs.getInt("deadline_seconds"),
                jobName = rs.getString("job_name"),
                requestedAt = rs.getObject("requested_at", Timestamp::class.java).toInstant(),
                startedAt = rs.getObject("started_at", Timestamp::class.java)?.toInstant(),
                finishedAt = rs.getObject("finished_at", Timestamp::class.java)?.toInstant(),
                status = BackfillStatus.valueOf(rs.getString("status")),
                fetched = rs.getInt("fetched").takeUnless { rs.wasNull() },
                inserted = rs.getInt("inserted").takeUnless { rs.wasNull() },
                duplicates = rs.getInt("duplicates").takeUnless { rs.wasNull() },
                failed = rs.getInt("failed").takeUnless { rs.wasNull() },
                dryRun = rs.getBoolean("dry_run"),
                matched = rs.getInt("matched").takeUnless { rs.wasNull() },
                updated = rs.getInt("updated").takeUnless { rs.wasNull() },
                needsReview = rs.getInt("needs_review").takeUnless { rs.wasNull() },
                oldestDate = rs.getObject("oldest_date", Date::class.java)?.toLocalDate(),
                error = rs.getString("error"),
            )
        }
    }
}
