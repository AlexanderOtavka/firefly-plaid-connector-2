package net.djvk.fireflyPlaidConnector2.manage

import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import net.djvk.fireflyPlaidConnector2.manage.db.AccountRepository
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillRunRepository
import net.djvk.fireflyPlaidConnector2.manage.db.PlaidAccountRow
import net.djvk.fireflyPlaidConnector2.manage.db.PlaidItemRow
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillStatus
import net.djvk.fireflyPlaidConnector2.manage.db.ItemRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ItemStatus
import net.djvk.fireflyPlaidConnector2.manage.k8s.ClusterGateway
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Items the polled connector syncs in database mode: active or failing (not pending or
 * retired), with a cursor, and with at least one enabled, mapped account. Mirrors
 * DatabasePlaidItemSource.
 */
fun pollableItems(items: List<PlaidItemRow>, accounts: List<PlaidAccountRow>): List<PlaidItemRow> {
    val withEnabled = accounts.filter { it.enabled && it.fireflyAccountId != null }.map { it.itemId }.toSet()
    return items.filter {
        it.status in POLLED_STATUSES && it.hasCursor && it.id in withEnabled
    }
}

private val POLLED_STATUSES = setOf(ItemStatus.active, ItemStatus.login_required, ItemStatus.error)

/**
 * Prometheus metrics for host alerting, served unauthenticated at `/plaid/-/metrics`.
 *
 * Content rule: labels are the last 4 characters of the Plaid Item ID and Plaid's error
 * code, nothing else. No institution names, account names, amounts, error messages, or
 * anything else free-form; alerts built from these leave the network via ntfy.sh.
 *
 * - `plaid_item_last_success_timestamp_seconds{item}`: last successful poll of an Item the
 *   connector polls (see [pollableItems]); for one that has never synced, when it became
 *   pollable, so it still ages. An old Item whose accounts a replacement took over during a
 *   relink is not polled, so it drops out instead of going stale.
 * - `plaid_item_error{item,code}`: 1 while an Item's last sync failed with `code`.
 * - `plaid_backfill_last_result{status}`: 1 if the most recent finished backfill succeeded,
 *   0 if it failed or hit its deadline; with `plaid_backfill_last_finished_timestamp_seconds`.
 * - `plaid_connector_ready_replicas`: ready replicas of the polled connector Deployment.
 */
@Component
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class PlaidMetrics(
    private val items: ItemRepository,
    private val accounts: AccountRepository,
    private val runs: BackfillRunRepository,
    private val cluster: ClusterGateway,
) {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    private val lastSuccess = MultiGauge.builder("plaid_item_last_success_timestamp_seconds")
        .description("Last successful poll of a Plaid Item")
        .register(registry)
    private val itemError = MultiGauge.builder("plaid_item_error")
        .description("1 while a Plaid Item's last sync failed with this error code")
        .register(registry)
    private val backfillResult = MultiGauge.builder("plaid_backfill_last_result")
        .description("1 if the most recent finished backfill succeeded, else 0")
        .register(registry)
    private val backfillFinished = MultiGauge.builder("plaid_backfill_last_finished_timestamp_seconds")
        .description("When the most recent finished backfill ended")
        .register(registry)
    private val connectorReplicas = MultiGauge.builder("plaid_connector_ready_replicas")
        .description("Ready replicas of the polled Plaid connector")
        .register(registry)

    @Volatile
    private var replicasCache: Pair<Instant, Int?>? = null

    fun connectorReadyReplicas(): Int? {
        val cached = replicasCache
        if (cached != null && Duration.between(cached.first, Instant.now()) < Duration.ofSeconds(60)) {
            return cached.second
        }
        return cluster.connectorReadyReplicas().also { replicasCache = Instant.now() to it }
    }

    fun scrape(): String {
        refresh()
        return registry.scrape()
    }

    private fun refresh() {
        val live = items.list().filter { it.status != ItemStatus.retired }
        lastSuccess.register(
            pollableItems(live, accounts.all()).mapNotNull { item ->
                (item.lastSyncAt ?: item.pollingSince)?.let {
                    MultiGauge.Row.of(Tags.of("item", item.itemIdLast4), it.epochSecond.toDouble())
                }
            },
            true,
        )
        itemError.register(
            live.filter { it.lastErrorCode != null }.map {
                MultiGauge.Row.of(Tags.of("item", it.itemIdLast4, "code", it.lastErrorCode!!), 1.0)
            },
            true,
        )
        val last = runs.lastFinished()
        backfillResult.register(
            listOfNotNull(last?.let {
                MultiGauge.Row.of(
                    Tags.of("status", it.status.name),
                    if (it.status == BackfillStatus.succeeded) 1.0 else 0.0,
                )
            }),
            true,
        )
        backfillFinished.register(
            listOfNotNull(last?.finishedAt?.let { MultiGauge.Row.of(Tags.empty(), it.epochSecond.toDouble()) }),
            true,
        )
        connectorReplicas.register(
            listOfNotNull(connectorReadyReplicas()?.let { MultiGauge.Row.of(Tags.empty(), it.toDouble()) }),
            true,
        )
    }
}
