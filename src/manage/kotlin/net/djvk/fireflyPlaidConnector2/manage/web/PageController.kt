package net.djvk.fireflyPlaidConnector2.manage.web

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.manage.BackfillService
import net.djvk.fireflyPlaidConnector2.manage.DEFAULT_BACKFILL_TIMEOUT_SECONDS
import net.djvk.fireflyPlaidConnector2.manage.ItemService
import net.djvk.fireflyPlaidConnector2.manage.MANAGE_MODE
import net.djvk.fireflyPlaidConnector2.manage.MAX_BACKFILL_TIMEOUT_SECONDS
import net.djvk.fireflyPlaidConnector2.manage.ManageException
import net.djvk.fireflyPlaidConnector2.manage.PlaidMetrics
import net.djvk.fireflyPlaidConnector2.manage.ReviewService
import net.djvk.fireflyPlaidConnector2.manage.SYNC_MODE_PROPERTY
import net.djvk.fireflyPlaidConnector2.manage.db.AccountRepository
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillReviewRepository
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillRunRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ItemRepository
import net.djvk.fireflyPlaidConnector2.manage.firefly.FireflyDirectory
import net.djvk.fireflyPlaidConnector2.manage.plaid.LINK_DAYS_REQUESTED
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.ModelAndView

@Controller
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class PageController(
    private val itemService: ItemService,
    private val backfills: BackfillService,
    private val items: ItemRepository,
    private val accounts: AccountRepository,
    private val runs: BackfillRunRepository,
    private val reviewRows: BackfillReviewRepository,
    private val reviews: ReviewService,
    private val firefly: FireflyDirectory,
    private val metrics: PlaidMetrics,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @GetMapping("/")
    fun dashboard(model: Model): String {
        backfills.reconcile()
        val byItem = accounts.all().groupBy { it.itemId }
        val itemViews = items.list().map { ItemView.of(it, byItem[it.id] ?: emptyList()) }
        val open = reviewRows.openCounts()
        val runViews = runs.recent().map { BackfillRunView.of(it, open[it.id] ?: 0) }
        model.addAttribute("items", itemViews)
        model.addAttribute("itemNames", itemViews.associate { it.id to label(it) })
        model.addAttribute("runs", runViews)
        model.addAttribute("activeRun", runViews.firstOrNull { it.status == "pending" || it.status == "running" })
        model.addAttribute("connectorReplicas", metrics.connectorReadyReplicas())
        model.addAttribute("fireflyAccounts", fireflyAccountNames())
        model.addAttribute("defaultTimeoutHours", DEFAULT_BACKFILL_TIMEOUT_SECONDS / 3600)
        model.addAttribute("maxTimeoutHours", MAX_BACKFILL_TIMEOUT_SECONDS / 3600)
        model.addAttribute("linkDaysRequested", LINK_DAYS_REQUESTED)
        return "dashboard"
    }

    @GetMapping("/items/{id}/mapping")
    fun mapping(@PathVariable id: Long, model: Model): String {
        val item = itemService.requireItem(id)
        val own = accounts.forItem(id)
        val fireflyAccounts = runBlocking { firefly.assetAccounts() }
        val proposed = itemService.proposedMapping(id, fireflyAccounts)
        val predecessor = items.predecessorOf(id)
        model.addAttribute("item", ItemView.of(item, own))
        model.addAttribute("predecessor", predecessor?.let { ItemView.of(it, accounts.forItem(it.id)) })
        model.addAttribute("rows", own.map {
            MappingRowView(AccountView.of(it), proposed[it.id]?.fireflyAccountId, proposed[it.id]?.newAccountName)
        })
        model.addAttribute("fireflyAccounts", fireflyAccounts.filter { it.active })
        // A new link: nothing mapped yet, so every proposal is turned on by default.
        model.addAttribute("fresh", own.none { it.fireflyAccountId != null })
        return "mapping"
    }

    /** What a backfill could not match safely, for the owner to decide on. */
    @GetMapping("/backfills/{id}/review")
    fun review(@PathVariable id: Long, model: Model): String {
        val run = runs.find(id) ?: throw ManageException(404, "No such backfill")
        val item = items.find(run.itemId)
        model.addAttribute("run", BackfillRunView.of(run))
        model.addAttribute("itemName", item?.let { "${it.institutionName ?: "Item"} …${it.itemIdLast4}" } ?: "Item")
        model.addAttribute("reviews", runBlocking { reviews.list(id) })
        return "review"
    }

    /** Where a bank's OAuth page sends the browser back to; re-opens Link to finish. */
    @GetMapping("/oauth-return")
    fun oauthReturn(): String = "oauth-return"

    @GetMapping("/login-failed")
    fun loginFailed(): String = "login-failed"

    @GetMapping("/logged-out")
    fun loggedOut(): String = "logged-out"

    @ExceptionHandler(ManageException::class)
    fun handle(e: ManageException): ModelAndView =
        ModelAndView("error-page", mapOf("message" to e.message)).apply {
            status = org.springframework.http.HttpStatusCode.valueOf(e.status)
        }

    private fun fireflyAccountNames(): Map<Int, String> = try {
        runBlocking { firefly.assetAccounts() }.associate { it.id to it.name }
    } catch (e: Exception) {
        logger.warn("Could not list Firefly asset accounts: {}", e.javaClass.simpleName)
        emptyMap<Int, String>()
    }

    private fun label(item: ItemView) = "${item.institutionName ?: "Item"} …${item.itemIdLast4}"

}
