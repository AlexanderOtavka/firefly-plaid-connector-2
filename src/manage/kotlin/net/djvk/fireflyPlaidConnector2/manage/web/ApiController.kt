package net.djvk.fireflyPlaidConnector2.manage.web

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.manage.BackfillService
import net.djvk.fireflyPlaidConnector2.manage.ItemService
import net.djvk.fireflyPlaidConnector2.manage.MANAGE_MODE
import net.djvk.fireflyPlaidConnector2.manage.ManageException
import net.djvk.fireflyPlaidConnector2.manage.MappingChange
import net.djvk.fireflyPlaidConnector2.manage.PlaidMetrics
import net.djvk.fireflyPlaidConnector2.manage.ReviewService
import net.djvk.fireflyPlaidConnector2.manage.SYNC_MODE_PROPERTY
import net.djvk.fireflyPlaidConnector2.manage.db.AccountRepository
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillReviewRepository
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillRunRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ItemRepository
import net.djvk.fireflyPlaidConnector2.manage.db.Redaction
import net.djvk.fireflyPlaidConnector2.manage.k8s.ClusterGateway
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class ApiController(
    private val itemService: ItemService,
    private val backfills: BackfillService,
    private val items: ItemRepository,
    private val accounts: AccountRepository,
    private val runs: BackfillRunRepository,
    private val reviewRows: BackfillReviewRepository,
    private val reviews: ReviewService,
    private val cluster: ClusterGateway,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @GetMapping("/items")
    fun listItems(): List<ItemView> {
        val byItem = accounts.all().groupBy { it.itemId }
        return items.list().map { ItemView.of(it, byItem[it.id] ?: emptyList()) }
    }

    @GetMapping("/backfills")
    fun listBackfills(): List<BackfillRunView> {
        backfills.reconcile()
        val open = reviewRows.openCounts()
        return runs.recent().map { BackfillRunView.of(it, open[it.id] ?: 0) }
    }

    @PostMapping("/link-token")
    fun linkToken(@RequestBody request: LinkTokenRequest): LinkTokenResponse = runBlocking {
        LinkTokenResponse(itemService.createLinkToken(request.mode, request.itemId))
    }

    @PostMapping("/exchange")
    fun exchange(@RequestBody request: ExchangeRequest): ExchangeResponse = runBlocking {
        ExchangeResponse(itemService.exchange(request.publicToken, request.mode, request.replacesItemId))
    }

    @PostMapping("/items/{id}/repaired")
    fun repaired(@PathVariable id: Long): OkResponse {
        itemService.markRepaired(id)
        return OkResponse()
    }

    @PostMapping("/items/{id}/mapping")
    fun saveMapping(@PathVariable id: Long, @RequestBody request: MappingRequest): OkResponse {
        itemService.saveMapping(id, request.accounts.map { MappingChange(it.accountId, it.fireflyAccountId, it.enabled) })
        return OkResponse()
    }

    @PostMapping("/items/{id}/retire")
    fun retire(@PathVariable id: Long): OkResponse = runBlocking {
        itemService.retire(id)
        OkResponse()
    }

    @PostMapping("/backfills")
    fun startBackfill(@RequestBody request: BackfillRequest): BackfillRunView =
        BackfillRunView.of(
            backfills.start(request.itemId, request.accountIds, request.days, request.timeoutSeconds, request.dryRun)
        )

    @GetMapping("/backfills/{id}/reviews")
    fun listReviews(@PathVariable id: Long): List<ReviewView> = runBlocking { reviews.list(id) }

    @PostMapping("/reviews/{id}/import")
    fun importReview(@PathVariable id: Long): OkResponse = runBlocking {
        reviews.importNew(id)
        OkResponse()
    }

    @PostMapping("/reviews/{id}/merge")
    fun mergeReview(@PathVariable id: Long, @RequestBody request: MergeRequest): OkResponse = runBlocking {
        reviews.merge(id, request.fireflyId)
        OkResponse()
    }

    @PostMapping("/reviews/{id}/delete")
    fun deleteReviewed(@PathVariable id: Long): OkResponse = runBlocking {
        reviews.delete(id)
        OkResponse()
    }

    @PostMapping("/reviews/{id}/dismiss")
    fun dismissReview(@PathVariable id: Long): OkResponse {
        reviews.dismiss(id)
        return OkResponse()
    }

    /** Streams the backfill pod's log. Nothing is stored; the stream ends with the pod. */
    @GetMapping("/backfills/{id}/log", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun log(@PathVariable id: Long): ResponseEntity<StreamingResponseBody> {
        val run = runs.find(id) ?: throw ManageException(404, "No such backfill")
        val jobName = run.jobName
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "no-store")
            .body(StreamingResponseBody { out -> cluster.streamLog(jobName, out) })
    }

    @ExceptionHandler(ManageException::class)
    fun handle(e: ManageException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.status).body(ErrorResponse(e.message ?: "Error"))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        // Deliberately generic: exception text from Plaid or Firefly clients is not shown.
        // Messages are redacted: a client exception can quote a Plaid response body.
        logger.error("Dashboard API request failed: {}: {}", e.javaClass.simpleName, Redaction.redact(e.message))
        return ResponseEntity.status(500).body(ErrorResponse("Request failed; see the manager log"))
    }
}

@RestController
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class OpsController(private val metrics: PlaidMetrics) {
    @GetMapping("/-/metrics", produces = ["text/plain; version=0.0.4; charset=utf-8"])
    fun metrics(): String = metrics.scrape()

    @GetMapping("/-/healthz", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun healthz(): String = "ok\n"
}
