package net.djvk.fireflyPlaidConnector2.manage.k8s

import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import net.djvk.fireflyPlaidConnector2.manage.MANAGE_MODE
import net.djvk.fireflyPlaidConnector2.manage.SYNC_MODE_PROPERTY
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillRunRow
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

const val RUN_ID_LABEL = "plaid-manager/run-id"
const val ITEM_ID_LABEL = "plaid-manager/item-id"
const val BACKFILL_CONTAINER = "backfill"
private const val HTTP_CONFLICT = 409

/** What the manager needs from the cluster. An interface so tests need no cluster. */
interface ClusterGateway {
    /**
     * Creates the backfill Job for [run], named [BackfillRunRow.jobName]. Creating a Job that
     * already exists succeeds, so a retry after an ambiguous failure is safe.
     */
    fun launch(run: BackfillRunRow)

    /** Deletes the Job, and its pods, if it exists. */
    fun deleteJob(jobName: String)

    /** The Job's final state, or null while it is still running. */
    fun jobResult(jobName: String, requestedAt: Instant): JobResult?

    /** Streams the Job's pod log into [out] until the pod finishes or the client goes away. */
    fun streamLog(jobName: String, out: OutputStream)

    /** Ready replicas of the polled connector Deployment, or null if unknown. */
    fun connectorReadyReplicas(): Int?
}

data class JobResult(
    val status: BackfillStatus,
    val error: String?,
    /** No Job by that name exists; a create call may still be in flight. */
    val jobMissing: Boolean = false,
)

/**
 * Fills in the backfill Job template for one run.
 *
 * The template (a ConfigMap supplied by the deployment) owns everything
 * about the pod: resources, security context, volumes, and the credentials mount at
 * `/etc/firefly-plaid/secrets`. The manager only sets what differs per run.
 */
fun renderBackfillJob(template: Job, image: String, run: BackfillRunRow, itemStoreEnv: List<EnvVar> = emptyList()): Job {
    val job = Serialization.clone(template)
    // Deterministic, from the run ID, so the Job can be found (or created again) after a crash.
    job.metadata.generateName = null
    job.metadata.name = run.jobName
    val labels =mapOf(RUN_ID_LABEL to run.id.toString(), ITEM_ID_LABEL to run.itemId.toString())
    job.metadata.labels = (job.metadata.labels ?: emptyMap()) + labels
    job.spec.activeDeadlineSeconds = run.deadlineSeconds.toLong()
    val podMeta = job.spec.template.metadata
    podMeta.labels = (podMeta.labels ?: emptyMap()) + labels

    val container = job.spec.template.spec.containers.firstOrNull { it.name == BACKFILL_CONTAINER }
        ?: error("The backfill Job template has no container named $BACKFILL_CONTAINER")
    container.image = image
    val overrides = listOf(
        EnvVar("FIREFLYPLAIDCONNECTOR2_SYNCMODE", "batch", null),
        EnvVar("FIREFLYPLAIDCONNECTOR2_ITEMSTORE", "database", null),
        EnvVar("FIREFLYPLAIDCONNECTOR2_BATCH_ITEMIDS", run.itemId.toString(), null),
        EnvVar("FIREFLYPLAIDCONNECTOR2_BATCH_ACCOUNTIDS", run.accountIds.joinToString(","), null),
        EnvVar("FIREFLYPLAIDCONNECTOR2_BATCH_MAXSYNCDAYS", run.days.toString(), null),
        EnvVar("FIREFLYPLAIDCONNECTOR2_BATCH_RUNID", run.id.toString(), null),
        EnvVar("FIREFLYPLAIDCONNECTOR2_BATCH_DRYRUN", run.dryRun.toString(), null),
    ) + itemStoreEnv
    val overridden = overrides.map { it.name }.toSet()
    container.env = (container.env ?: emptyList()).filter { it.name !in overridden } + overrides
    return job
}

/** Reads a Job's final state from its conditions and, for a clearer error, its pods. */
fun classifyJob(job: Job?, podReasons: List<String>, requestedAt: Instant, now: Instant = Instant.now()): JobResult? {
    if (job == null) {
        // Give a just-created Job a moment to become visible before calling it lost.
        return if (Duration.between(requestedAt, now) > Duration.ofMinutes(2)) {
            JobResult(BackfillStatus.failed, "The backfill Job was never created or no longer exists", jobMissing = true)
        } else {
            null
        }
    }
    val conditions = job.status?.conditions ?: emptyList()
    val failed = conditions.firstOrNull { it.type == "Failed" && it.status == "True" }
    val complete = conditions.firstOrNull { it.type == "Complete" && it.status == "True" }
    val detail = podReasons.distinct().joinToString(", ").ifEmpty { null }
    return when {
        failed?.reason == "DeadlineExceeded" -> JobResult(BackfillStatus.deadline_exceeded, "Job exceeded its deadline")
        failed != null -> JobResult(
            BackfillStatus.failed,
            listOfNotNull("Job failed: ${failed.reason ?: "unknown"}", detail).joinToString("; "),
        )
        // Batch mode records its own success; if it did not, it did not finish cleanly.
        complete != null -> JobResult(BackfillStatus.failed, "Job completed without recording a result")
        else -> null
    }
}

@Configuration
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class KubernetesConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy
    fun kubernetesClient(): KubernetesClient = KubernetesClientBuilder().build()
}

/**
 * The manager's ServiceAccount is bound to a namespace-scoped Role: create/get/list/watch/delete
 * `batch/jobs`, get/list/watch `pods`, get `pods/log`, and get on the connector Deployment only.
 */
@Component
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class KubernetesClusterGateway(
    @Lazy private val client: KubernetesClient,
    @Value("\${fireflyPlaidConnector2.manage.backfillJobTemplate:/etc/firefly-plaid/backfill/job.yaml}")
    private val templatePath: String,
    @Value("\${fireflyPlaidConnector2.manage.connectorDeployment:firefly-plaid-connector}")
    private val connectorDeployment: String,
    @Value("\${HOSTNAME:}")
    private val podName: String,
) : ClusterGateway {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /** The manager's own image, so backfills always run the same build as the dashboard. */
    private val selfImage: String by lazy {
        val pod = client.pods().withName(podName).get() ?: error("Cannot read this pod ($podName)")
        pod.spec.containers.first { it.name == "manager" }.image
    }

    override fun launch(run: BackfillRunRow) {
        val template = Files.newInputStream(Path.of(templatePath)).use { loadJob(it) }
        val job = renderBackfillJob(template, selfImage, run)
        try {
            client.batch().v1().jobs().resource(job).create()
            logger.info("Created backfill Job {} for run {}", run.jobName, run.id)
        } catch (e: KubernetesClientException) {
            if (e.code != HTTP_CONFLICT) throw e
            logger.info("Backfill Job {} for run {} already exists", run.jobName, run.id)
        }
    }

    override fun deleteJob(jobName: String) {
        client.batch().v1().jobs().withName(jobName)
            .withPropagationPolicy(DeletionPropagation.BACKGROUND)
            .delete()
    }

    override fun jobResult(jobName: String, requestedAt: Instant): JobResult? {
        val job = client.batch().v1().jobs().withName(jobName).get()
        val reasons = client.pods().withLabel("job-name", jobName).list().items.flatMap { pod ->
            pod.status?.containerStatuses.orEmpty().mapNotNull { status ->
                status.state?.terminated?.reason?.takeIf { it != "Completed" }
                    ?: status.state?.waiting?.reason?.takeIf { it != "ContainerCreating" && it != "PodInitializing" }
            }
        }
        return classifyJob(job, reasons, requestedAt)
    }

    override fun streamLog(jobName: String, out: OutputStream) {
        val pod = client.pods().withLabel("job-name", jobName).list().items
            .maxByOrNull { it.metadata.creationTimestamp ?: "" }
            ?: run {
                out.write("No pod for this Job yet.\n".toByteArray())
                return
            }
        // Flush every chunk: a running pod's log can sit below the servlet's response buffer
        // for its whole run, and then nothing reaches the browser until the pod exits.
        client.pods().withName(pod.metadata.name).inContainer(BACKFILL_CONTAINER).tailingLines(2000).watchLog().use { watch ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = watch.output.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                out.flush()
            }
        }
    }

    override fun connectorReadyReplicas(): Int? = try {
        client.apps().deployments().withName(connectorDeployment).get()?.status?.readyReplicas ?: 0
    } catch (e: Exception) {
        logger.warn("Cannot read the connector Deployment: {}", e.message)
        null
    }

    companion object {
        fun loadJob(stream: InputStream): Job = Serialization.unmarshal(stream, Job::class.java)
    }
}
