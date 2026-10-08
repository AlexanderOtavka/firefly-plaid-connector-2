package net.djvk.fireflyPlaidConnector2.manage

import net.djvk.fireflyPlaidConnector2.manage.db.AccountRepository
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillRunRepository
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillRunRow
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillStatus
import net.djvk.fireflyPlaidConnector2.manage.db.ItemRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ItemStatus
import net.djvk.fireflyPlaidConnector2.manage.k8s.ClusterGateway
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service

const val DEFAULT_BACKFILL_TIMEOUT_SECONDS = 4 * 3600
const val MAX_BACKFILL_TIMEOUT_SECONDS = 24 * 3600
const val MIN_BACKFILL_TIMEOUT_SECONDS = 10 * 60

/**
 * Starts backfills as Kubernetes Jobs and keeps their rows in step with the Jobs.
 *
 * Rules: only `active` Items (their history is complete and polling has a cursor); only one
 * backfill in flight across the system (also enforced by a unique index); days from 1 to the
 * Item's `days_requested` plus the days since it was linked, since Plaid has nothing older;
 * timeout from 10 minutes to 24 hours.
 */
@Service
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class BackfillService(
    private val items: ItemRepository,
    private val accounts: AccountRepository,
    private val runs: BackfillRunRepository,
    private val cluster: ClusterGateway,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun start(
        itemId: Long,
        accountIds: List<Long>?,
        days: Int,
        timeoutSeconds: Int?,
        dryRun: Boolean = false,
    ): BackfillRunRow {
        val item = items.find(itemId) ?: throw ManageException(404, "No such Item")
        if (item.status != ItemStatus.active) {
            throw ManageException(409, "Only active Items can be backfilled; this one is ${item.status}")
        }
        val maxDays = item.maxBackfillDays()
        if (days < 1 || days > maxDays) {
            throw ManageException(400, "Days must be between 1 and $maxDays for this Item")
        }
        val timeout = timeoutSeconds ?: DEFAULT_BACKFILL_TIMEOUT_SECONDS
        if (timeout < MIN_BACKFILL_TIMEOUT_SECONDS || timeout > MAX_BACKFILL_TIMEOUT_SECONDS) {
            throw ManageException(400, "Timeout must be between 10 minutes and 24 hours")
        }
        val enabled = accounts.forItem(itemId).filter { it.enabled }.map { it.id }
        val selected = accountIds?.also {
            if (!enabled.containsAll(it) || it.isEmpty()) {
                throw ManageException(400, "Pick enabled accounts of this Item")
            }
        } ?: enabled
        if (selected.isEmpty()) throw ManageException(409, "This Item has no enabled accounts")

        val runId = try {
            runs.insertPending(itemId, selected, days, timeout, dryRun)
        } catch (_: DuplicateKeyException) {
            throw ManageException(409, "Another backfill is already pending or running")
        }
        val run = runs.find(runId)!!
        // The row already carries the Job's deterministic name. If creating the Job fails,
        // or this process dies first, the row stays pending: reconcile() fails it once the
        // Job has been missing for two minutes. It is never released here, because a create
        // that errored may still have been accepted and the Job may be running.
        try {
            cluster.launch(run)
        } catch (e: Exception) {
            logger.error("Creating backfill Job {} failed; reconcile will settle it: {}", run.jobName, e.message)
            throw ManageException(502, "Could not create the backfill Job; it will be marked failed if it does not appear")
        }
        return runs.find(runId)!!
    }

    /**
     * Brings pending/running rows in line with their Jobs. Batch mode records its own start
     * and result; this catches what it cannot record, like a StartError, an OOM kill, the
     * deadline, or a Job that was never created.
     */
    fun reconcile() {
        for (run in runs.active()) {
            val result = try {
                cluster.jobResult(run.jobName, run.requestedAt)
            } catch (e: Exception) {
                logger.warn("Could not read backfill Job {}: {}", run.jobName, e.message)
                continue
            } ?: continue
            if (result.jobMissing) {
                // A create call that was accepted late must not run without the lock.
                try {
                    cluster.deleteJob(run.jobName)
                } catch (e: Exception) {
                    logger.warn("Could not delete backfill Job {}; leaving run {} pending: {}", run.jobName, run.id, e.message)
                    continue
                }
            }
            runs.finish(run.id, result.status, null, result.error)
        }
    }
}
