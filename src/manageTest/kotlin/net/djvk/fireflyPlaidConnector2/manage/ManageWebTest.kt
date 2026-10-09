package net.djvk.fireflyPlaidConnector2.manage

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.lib.TestApplication
import net.djvk.fireflyPlaidConnector2.manage.db.AccountRepository
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillReviewRepository
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillRunRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ReviewStatus
import net.djvk.fireflyPlaidConnector2.manage.firefly.FireflyTransactions
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.ReviewCandidate
import net.djvk.fireflyPlaidConnector2.transactions.ReviewKind
import net.djvk.fireflyPlaidConnector2.transactions.ReviewTarget
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillStatus
import net.djvk.fireflyPlaidConnector2.manage.db.ItemRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ItemStatus
import net.djvk.fireflyPlaidConnector2.manage.db.NewPlaidAccount
import net.djvk.fireflyPlaidConnector2.manage.firefly.FireflyAccount
import net.djvk.fireflyPlaidConnector2.manage.firefly.FireflyDirectory
import net.djvk.fireflyPlaidConnector2.manage.firefly.NewAssetAccount
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRoleProperty
import net.djvk.fireflyPlaidConnector2.manage.k8s.ClusterGateway
import net.djvk.fireflyPlaidConnector2.manage.k8s.JobResult
import net.djvk.fireflyPlaidConnector2.manage.plaid.ExchangedItem
import net.djvk.fireflyPlaidConnector2.manage.plaid.LinkMode
import net.djvk.fireflyPlaidConnector2.manage.plaid.LinkedItem
import net.djvk.fireflyPlaidConnector2.manage.plaid.PlaidLinkClient
import net.djvk.fireflyPlaidConnector2.manage.web.OWNER_AUTHORITY
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private const val SEEDED_TOKEN = "access-production-seeded-secret-0001"
private const val NEW_TOKEN = "access-production-new-secret-0002"

// The mode must be visible when the environment is prepared, which @DynamicPropertySource is not.
@SpringBootTest(
    classes = [TestApplication::class],
    properties = [
        "fireflyPlaidConnector2.syncMode=manage",
        "fireflyPlaidConnector2.itemStore=database",
        "fireflyPlaidConnector2.manage.oauth.clientId=7",
        "fireflyPlaidConnector2.manage.oauth.clientSecret=oauth-client-secret",
        "fireflyPlaidConnector2.manage.publicBaseUrl=https://firefly.example.com",
        "fireflyPlaidConnector2.manage.allowedHosts=localhost",
        "fireflyPlaidConnector2.manage.allowedEmails=owner@example.com",
        "fireflyPlaidConnector2.plaid.url=http://127.0.0.1:9",
        "fireflyPlaidConnector2.plaid.clientId=test-client",
        "fireflyPlaidConnector2.plaid.secret=test-secret",
        "fireflyPlaidConnector2.firefly.url=http://127.0.0.1:9",
        "fireflyPlaidConnector2.firefly.personalAccessToken=test-pat",
        "logging.level.org.springframework.beans=INFO",
        "fireflyPlaidConnector2.manage.navLinks[0].label=Firefly",
        "fireflyPlaidConnector2.manage.navLinks[0].url=/",
        "fireflyPlaidConnector2.manage.navLinks[1].label=Grafana",
        "fireflyPlaidConnector2.manage.navLinks[1].url=https://grafana.example.com/d/plaid",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension::class)
class ManageWebTest {
    companion object {
        private val db = TestPostgres.freshDatabase()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("fireflyPlaidConnector2.database.url") { db.url }
            registry.add("fireflyPlaidConnector2.database.username") { db.username }
            registry.add("fireflyPlaidConnector2.database.password") { db.password }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var items: ItemRepository
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var runs: BackfillRunRepository
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var backfills: BackfillService
    @Autowired lateinit var itemService: ItemService

    @MockBean lateinit var plaid: PlaidLinkClient
    @MockBean lateinit var cluster: ClusterGateway
    @MockBean lateinit var firefly: FireflyDirectory
    @MockBean lateinit var fireflyTransactions: FireflyTransactions
    @Autowired lateinit var reviews: BackfillReviewRepository

    private val owner = oauth2Login().authorities(SimpleGrantedAuthority(OWNER_AUTHORITY))

    private var card: Long = 0

    @BeforeEach
    fun seed() {
        jdbc.sql("TRUNCATE backfill_run, plaid_account, plaid_item RESTART IDENTITY CASCADE").update()
        card = items.insert("item-card-wxyz", SEEDED_TOKEN, "ins_1", "Example Bank", 90, ItemStatus.active)
        accounts.upsert(card, listOf(NewPlaidAccount("acc-card", "persist-1", "Example Card", "1234", "credit", "credit card")))
        accounts.setMapping(accounts.forItem(card).single().id, card, 8, true)
        items.setCursor(card, "cursor-1")
        items.recordSuccess(card, 3)
        items.recordFailure(card, "ITEM_LOGIN_REQUIRED", "login required for $SEEDED_TOKEN")
        items.markRepaired(card)
        items.recordFailure(card, "INTERNAL_SERVER_ERROR", "try again")
        val run = runs.insertPending(card, listOf(accounts.forItem(card).single().id), 90, 3600)
        runs.finish(run, BackfillStatus.failed, null, "Job failed for $SEEDED_TOKEN")

        runBlocking {
            whenever(plaid.createLinkToken(any(), anyOrNull())).thenReturn("link-production-token")
            whenever(plaid.exchange(any())).thenReturn(ExchangedItem("item-card-new1", NEW_TOKEN))
            whenever(plaid.describe(eq(NEW_TOKEN))).thenReturn(
                LinkedItem(
                    "item-card-new1", NEW_TOKEN, "ins_1", "Example Bank",
                    listOf(NewPlaidAccount("acc-card-new", "persist-1", "Example Card", "1234", "credit", "credit card")),
                )
            )
            whenever(firefly.assetAccounts()).thenReturn(listOf(FireflyAccount(8, "Example Credit Card", "ccAsset", true)))
        }

        whenever(cluster.connectorReadyReplicas()).thenReturn(1)
    }

    private fun perform(request: RequestBuilder): MvcResult = mvc.perform(request).andReturn()


    @Test
    fun `API calls without a login get 401, pages redirect to Firefly`() {
        mvc.perform(get("/api/items")).andExpect(status().isUnauthorized)
        mvc.perform(get("/")).andExpect(status().isFound)
            .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/oauth2/authorization/firefly")))
    }

    @Test
    fun `the login redirect goes to Firefly's public authorize endpoint`() {
        mvc.perform(get("/oauth2/authorization/firefly")).andExpect(status().isFound)
            .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://firefly.example.com/oauth/authorize?")))
            .andExpect(
                header().string(
                    "Location",
                    org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.containsString("plaid%2Flogin%2Foauth2%2Fcode%2Ffirefly"),
                        org.hamcrest.Matchers.containsString("plaid/login/oauth2/code/firefly"),
                    ),
                )
            )
    }

    @Test
    fun `a logged-in user without the owner authority is refused`() {
        mvc.perform(get("/api/items").with(oauth2Login())).andExpect(status().isForbidden)
    }

    @Test
    fun `requests for another host are refused`() {
        mvc.perform(get("/api/items").with(owner).with { it.serverName = "evil.example"; it })
            .andExpect(status().`is`(421))
    }

    @Test
    fun `the dashboard has one header and gives the script instants to localize`() {
        val body = perform(get("/").with(owner)).response.contentAsString
        // A fragment named "header" also matches every <header> element in fragments.html.
        assertThat(Regex("<header").findAll(body).count()).isEqualTo(1)
        assertThat(body).containsPattern("<time datetime=\"\\d{4}-\\d{2}-\\d{2}T[^\"]*Z\"")
    }

    @Test
    fun `the header carries the configured links, and the page no second title`() {
        val body = perform(get("/").with(owner)).response.contentAsString
        assertThat(body).contains("<a class=\"nav-link\" href=\"/\">Firefly</a>")
            .contains("<a class=\"nav-link\" href=\"https://grafana.example.com/d/plaid\">Grafana</a>")
            .doesNotContain("Back to Firefly")
            .doesNotContain("<h1")
        // Pages shown without a session get them too.
        assertThat(perform(get("/logged-out")).response.contentAsString).contains("Grafana")
    }

    @Test
    fun `the Item count includes retired Items, and Items show their synced date range`() {
        val old = items.insert("item-old-abcd", "access-production-old", "ins_1", "Example Bank", 90, ItemStatus.active)
        items.retire(old)
        items.recordSuccess(card, 2, LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 7))
        items.widenTxDates(card, LocalDate.of(2024, 9, 27)..LocalDate.of(2026, 10, 2))
        val body = perform(get("/").with(owner)).response.contentAsString
        assertThat(body).contains("2 of 10 used")
        assertThat(body).containsPattern("2024-09-27</span> to\\s*<span[^>]*>2026-10-07<")
        assertThat(body).contains("none synced yet")
    }

    @Test
    fun `state-changing calls need the session CSRF token`() {
        mvc.perform(post("/api/items/$card/retire").with(owner)).andExpect(status().isForbidden)
    }

    @Test
    fun `metrics are public and carry only item last4 and error codes`() {
        val body = mvc.perform(get("/-/metrics")).andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(body).contains("plaid_item_last_success_timestamp_seconds{item=\"wxyz\"}")
        assertThat(body).contains("plaid_item_error{code=\"INTERNAL_SERVER_ERROR\",item=\"wxyz\"} 1")
        assertThat(body).contains("plaid_backfill_last_result{status=\"failed\"} 0")
        assertThat(body).contains("plaid_connector_ready_replicas 1")
        assertThat(body).doesNotContain("Example Bank").doesNotContain("try again").doesNotContain("access-")
    }

    @Test
    fun `a new link is stored as pending_history with 730 days`() {
        val result = perform(
            post("/api/exchange").with(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"publicToken":"public-x","mode":"new"}""")
        )
        assertThat(result.response.status).isEqualTo(200)
        val item = items.findByPlaidItemId("item-card-new1")!!
        assertThat(item.status).isEqualTo(ItemStatus.pending_history)
        assertThat(item.daysRequested).isEqualTo(730)
        assertThat(accounts.forItem(item.id).single().enabled).isFalse()
    }

    @Test
    fun `a new link defaults to creating a Firefly account, which saving creates and maps`() {
        perform(
            post("/api/exchange").with(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"publicToken":"public-x","mode":"new"}""")
        )
        val item = items.findByPlaidItemId("item-card-new1")!!
        val account = accounts.forItem(item.id).single().id

        val page = perform(get("/items/${item.id}/mapping").with(owner)).response.contentAsString
        assertThat(page).contains("<option value=\"new\" selected=\"selected\">")
        assertThat(page).containsPattern("name=\"newFireflyAccountName\"[^>]*value=\"Example Card\"")
        assertThat(page).containsPattern("<input type=\"checkbox\" name=\"enabled\"\\s+checked=\"checked\">")

        // Already a Firefly account of that name: refused before anything is created.
        val taken = perform(
            post("/api/items/${item.id}/mapping").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"accounts":[{"accountId":$account,"newFireflyAccountName":"example credit card","enabled":true}]}""")
        )
        assertThat(taken.response.status).isEqualTo(409)
        runBlocking { verify(firefly, never()).createAssetAccount(any()) }

        runBlocking {
            whenever(firefly.createAssetAccount(any())).thenReturn(FireflyAccount(20, "Example Card", "ccAsset", true))
        }
        val saved = perform(
            post("/api/items/${item.id}/mapping").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"accounts":[{"accountId":$account,"newFireflyAccountName":" Example Card ","enabled":true}]}""")
        )
        assertThat(saved.response.status).isEqualTo(200)
        runBlocking {
            verify(firefly).createAssetAccount(
                eq(NewAssetAccount("Example Card", AccountRoleProperty.ccAsset, "Created by the Plaid manager for Example Bank account Example Card …1234."))
            )
        }
        val mapped = accounts.forItem(item.id).single()
        assertThat(mapped.fireflyAccountId).isEqualTo(20)
        assertThat(mapped.enabled).isTrue()
    }

    @Test
    fun `replace suggests the old mapping and saving it switches accounts over atomically`() {
        perform(
            post("/api/exchange").with(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"publicToken":"public-x","mode":"replace","replacesItemId":$card}""")
        )
        val replacement = items.findByPlaidItemId("item-card-new1")!!
        assertThat(items.find(card)!!.replacedBy).isEqualTo(replacement.id)

        val page = perform(get("/items/${replacement.id}/mapping").with(owner))
        assertThat(page.response.status).isEqualTo(200)
        assertThat(page.response.contentAsString).contains("<option value=\"8\" selected=\"selected\">Example Credit Card</option>")

        val newAccount = accounts.forItem(replacement.id).single().id
        val saved = perform(
            post("/api/items/${replacement.id}/mapping").with(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"accounts":[{"accountId":$newAccount,"fireflyAccountId":8,"enabled":true}]}""")
        )
        assertThat(saved.response.status).isEqualTo(200)
        assertThat(accounts.forItem(card).single().enabled).isFalse()
        assertThat(accounts.forItem(replacement.id).single().enabled).isTrue()

        val retired = perform(post("/api/items/$card/retire").with(owner).with(csrf()))
        assertThat(retired.response.status).isEqualTo(200)
        runBlocking { verify(plaid).removeItem(eq(SEEDED_TOKEN)) }
        assertThat(items.accessTokenFor(card)).isNull()
    }

    @Test
    fun `backfills are refused for Items that are not active, and capped in days`() {
        val pending = items.insert("item-pending", "access-production-p", null, "Bank", 730, ItemStatus.pending_history)
        val refused = perform(
            post("/api/backfills").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemId":$pending,"days":30}""")
        )
        assertThat(refused.response.status).isEqualTo(409)

        val tooMany = perform(
            post("/api/backfills").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemId":$card,"days":731}""")
        )
        assertThat(tooMany.response.status).isEqualTo(400)

        val started = perform(
            post("/api/backfills").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemId":$card,"days":90,"timeoutSeconds":7200}""")
        )
        assertThat(started.response.status).isEqualTo(200)
        assertThat(started.response.contentAsString).contains("firefly-plaid-backfill-run-")

        val second = perform(
            post("/api/backfills").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemId":$card,"days":90}""")
        )
        assertThat(second.response.status).isEqualTo(409)
    }

    private fun startBackfill() = perform(
        post("/api/backfills").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"itemId":$card,"days":30}""")
    )

    @Test
    fun `a failed Job create keeps the lock until the Job is known to be missing, then releases it`() {
        whenever(cluster.launch(any())).thenThrow(RuntimeException("API server timeout"))
        assertThat(startBackfill().response.status).isEqualTo(502)
        val run = runs.active().single()
        assertThat(run.jobName).isEqualTo("firefly-plaid-backfill-run-${run.id}")

        // The create may have been accepted: while the Job is (or may be) running, keep the lock.
        whenever(cluster.jobResult(eq(run.jobName), any())).thenReturn(null)
        backfills.reconcile()
        assertThat(runs.find(run.id)!!.status).isEqualTo(BackfillStatus.pending)
        assertThat(startBackfill().response.status).isEqualTo(409)

        // Missing for long enough: delete it in case a late create lands, then release the lock.
        whenever(cluster.jobResult(eq(run.jobName), any()))
            .thenReturn(JobResult(BackfillStatus.failed, "never created", jobMissing = true))
        backfills.reconcile()
        verify(cluster).deleteJob(eq(run.jobName))
        assertThat(runs.find(run.id)!!.status).isEqualTo(BackfillStatus.failed)

        org.mockito.Mockito.reset(cluster)
        assertThat(startBackfill().response.status).isEqualTo(200)
    }

    @Test
    fun `a pending row whose Job lookup fails stays locked`() {
        whenever(cluster.launch(any())).thenThrow(RuntimeException("boom"))
        startBackfill()
        val run = runs.active().single()
        whenever(cluster.jobResult(eq(run.jobName), any()))
            .thenReturn(JobResult(BackfillStatus.failed, "never created", jobMissing = true))
        whenever(cluster.deleteJob(any())).thenThrow(RuntimeException("forbidden"))
        backfills.reconcile()
        assertThat(runs.find(run.id)!!.status).isEqualTo(BackfillStatus.pending)
    }

    @Test
    fun `the access token is stored before anything after the exchange can fail`() {
        runBlocking { whenever(plaid.describe(eq(NEW_TOKEN))).thenThrow(RuntimeException("Plaid 500")) }
        val result = perform(
            post("/api/exchange").with(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"publicToken":"public-x","mode":"new"}""")
        )
        assertThat(result.response.status).isEqualTo(200)
        val item = items.findByPlaidItemId("item-card-new1")!!
        assertThat(items.accessTokenFor(item.id)).isEqualTo(NEW_TOKEN)
        assertThat(accounts.forItem(item.id)).isEmpty()

        // The next background pass reads the accounts it could not read at link time.
        runBlocking {
            org.mockito.Mockito.reset(plaid)
            whenever(plaid.describe(eq(NEW_TOKEN))).thenReturn(
                LinkedItem("item-card-new1", NEW_TOKEN, "ins_1", "Example Bank", listOf(NewPlaidAccount("acc-n", null, "Card", "1", "credit", null)))
            )
            whenever(plaid.syncPage(eq(NEW_TOKEN), anyOrNull())).thenThrow(RuntimeException("not ready"))
            itemService.advancePendingItems()
        }
        assertThat(accounts.forItem(item.id).map { it.plaidAccountId }).containsExactly("acc-n")
        assertThat(items.find(item.id)!!.institutionName).isEqualTo("Example Bank")
    }

    @Test
    fun `the stale-sync gauge covers exactly the Items the connector polls`() {
        // Replacement flow: the old Item keeps its cursor but loses its enabled accounts.
        val replaced = items.insert("item-old-aaaa", "access-production-a", null, "Old", 90, ItemStatus.active)
        accounts.upsert(replaced, listOf(NewPlaidAccount("acc-old", null, "Card", "1", "credit", null)))
        items.setCursor(replaced, "c")
        items.recordSuccess(replaced, 1)
        // A new Item that became pollable but has never synced.
        val fresh = items.insert("item-new-bbbb", "access-production-b", null, "New", 730, ItemStatus.pending_history)
        accounts.upsert(fresh, listOf(NewPlaidAccount("acc-new", null, "Card", "2", "credit", null)))
        accounts.setMapping(accounts.forItem(fresh).single().id, fresh, 9, true)
        items.activateWithCursor(fresh, "c2")

        val body = mvc.perform(get("/-/metrics")).andReturn().response.contentAsString
        assertThat(body).doesNotContain("plaid_item_last_success_timestamp_seconds{item=\"aaaa\"}")
        assertThat(body).contains("plaid_item_last_success_timestamp_seconds{item=\"bbbb\"}")
        assertThat(body).contains("plaid_item_last_success_timestamp_seconds{item=\"wxyz\"}")
    }

    /**
     * Token hygiene: exercises every page and API response, including ones built right after
     * a token entered the database, and fails if any of them, or the log, contains a token.
     */
    @Test
    fun `no response or log line ever contains an access token`(output: CapturedOutput) {
        val responses = mutableListOf<MvcResult>()
        fun postJson(path: String, body: String) = perform(
            post(path).with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)
        ).also { responses += it }

        responses += perform(get("/").with(owner))
        responses += perform(get("/api/items").with(owner))
        responses += perform(get("/api/backfills").with(owner))
        responses += perform(get("/items/$card/mapping").with(owner))
        responses += perform(get("/oauth-return").with(owner))
        responses += perform(get("/-/metrics"))
        for (mode in LinkMode.entries) {
            postJson("/api/link-token", """{"mode":"$mode","itemId":$card}""")
        }
        val exchanged = postJson("/api/exchange", """{"publicToken":"public-x","mode":"replace","replacesItemId":$card}""")
        val newId = items.findByPlaidItemId("item-card-new1")!!.id
        responses += perform(get("/items/$newId/mapping").with(owner))
        postJson("/api/items/$newId/mapping", """{"accounts":[]}""")
        postJson("/api/items/$card/repaired", "{}")
        postJson("/api/backfills", """{"itemId":$card,"days":30}""")
        postJson("/api/exchange", """{"publicToken":"public-x","mode":"new"}""") // already linked: returns the existing Item
        responses += perform(get("/").with(owner))
        postJson("/api/items/$card/retire", "{}")
        responses += perform(get("/api/items").with(owner))

        assertThat(exchanged.response.status).isEqualTo(200)
        val dashboard = responses.first().response.contentAsString
        assertThat(dashboard).contains("Repair keeps history depth; only Replace deepens it.")
        assertThat(dashboard).contains("are updated in place rather than imported again")
        assertThat(dashboard).contains("Item …wxyz").contains("INTERNAL_SERVER_ERROR")
        assertThat(items.accessTokenFor(newId)).isEqualTo(NEW_TOKEN)
        for (response in responses) {
            if (response.request.method == "GET") {
                assertThat(response.response.status).describedAs(response.request.requestURI).isEqualTo(200)
            }
            assertThat(response.response.contentAsString)
                .describedAs("${response.request.method} ${response.request.requestURI}")
                .doesNotContain("access-")
            assertThat(response.response.headerNames.flatMap { response.response.getHeaders(it) })
                .noneMatch { it.contains("access-") }
        }
        assertThat(output.all).doesNotContain(SEEDED_TOKEN).doesNotContain(NEW_TOKEN)
        assertThat(output.all).doesNotContain("oauth-client-secret")
    }

    private fun split(type: TransactionTypeProperty = TransactionTypeProperty.withdrawal, externalId: String = "plaid-new-1") =
        TransactionSplit(
            type = type,
            date = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-4)),
            amount = "5.0",
            description = "Corner Cafe",
            sourceId = "8",
            destinationId = null,
            destinationName = "Corner Cafe",
            externalId = externalId,
        )

    private fun unmatched(externalId: String) = ReviewCandidate(
        kind = ReviewKind.UNMATCHED,
        reason = "Several",
        date = LocalDate.of(2026, 9, 1),
        amount = "5.0",
        description = "Corner Cafe",
        proposed = FireflyTransactionDto(null, split(externalId = externalId)),
        targets = listOf(ReviewTarget("101", "plaid-old-1"), ReviewTarget("102", "plaid-old-2")),
    )

    /**
     * A finished backfill with two reviews that could each be Firefly transaction 101 or 102,
     * and a leftover review for 103, which may duplicate 104.
     */
    private fun runWithReviews(dryRun: Boolean = false): Triple<Long, List<Long>, Long> {
        val run = runs.insertPending(card, listOf(accounts.forItem(card).single().id), 30, 3600, dryRun)
        val leftover = ReviewCandidate(
            kind = ReviewKind.LEFTOVER,
            reason = "Probably a duplicate",
            date = LocalDate.of(2026, 8, 30),
            amount = "5.0",
            description = "Corner Cafe",
            proposed = null,
            targets = listOf(ReviewTarget("103", "plaid-old-3")),
            relatedIds = listOf("104"),
        )
        reviews.insertAll(run, listOf(unmatched("plaid-new-1"), unmatched("plaid-new-2"), leftover))
        runs.finish(run, BackfillStatus.succeeded, null, null)
        val rows = reviews.forRun(run)
        return Triple(
            run,
            rows.filter { it.kind == ReviewKind.UNMATCHED }.map { it.id },
            rows.single { it.kind == ReviewKind.LEFTOVER }.id,
        )
    }

    private fun runWithReview(dryRun: Boolean = false): Pair<Long, Long> =
        runWithReviews(dryRun).let { (run, unmatched, _) -> Pair(run, unmatched.first()) }

    private fun postJson(path: String, body: String = "{}") = perform(
        post(path).with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)
    )

    @Test
    fun `a dry run is recorded on the run and passed to its Job`() {
        val started = postJson("/api/backfills", """{"itemId":$card,"days":30,"dryRun":true}""")
        assertThat(started.response.status).isEqualTo(200)
        assertThat(started.response.contentAsString).contains("\"dryRun\":true")
        verify(cluster).launch(org.mockito.kotlin.argThat { dryRun })
    }

    @Test
    fun `a review can be merged into one of its candidates, once`() {
        runBlocking {
            whenever(fireflyTransactions.find(eq("101"))).thenReturn(
                split(externalId = "plaid-old-1").copy(categoryName = "Treats", notes = "with a friend")
            )
        }
        val (run, review) = runWithReview()

        val page = perform(get("/backfills/$run/review").with(owner))
        assertThat(page.response.status).isEqualTo(200)
        assertThat(page.response.contentAsString).contains("Same as this").contains("/transactions/show/101")

        assertThat(postJson("/api/reviews/$review/merge", """{"fireflyId":"999"}""").response.status).isEqualTo(400)
        assertThat(postJson("/api/reviews/$review/merge", """{"fireflyId":"101"}""").response.status).isEqualTo(200)
        runBlocking {
            verify(fireflyTransactions).update(eq("101"), org.mockito.kotlin.argThat {
                externalId == "plaid-new-1" && categoryName == "Treats" && notes == "with a friend"
            })
        }
        assertThat(reviews.find(review)!!.status).isEqualTo(ReviewStatus.merged)
        assertThat(postJson("/api/reviews/$review/merge", """{"fireflyId":"101"}""").response.status).isEqualTo(409)
        assertThat(postJson("/api/reviews/$review/import").response.status).isEqualTo(409)
    }

    @Test
    fun `two reviews cannot both be merged into one transaction`() {
        runBlocking {
            whenever(fireflyTransactions.find(eq("101"))).thenReturn(split(externalId = "plaid-old-1"))
        }
        val (_, unmatched, _) = runWithReviews()
        assertThat(postJson("/api/reviews/${unmatched[0]}/merge", """{"fireflyId":"101"}""").response.status).isEqualTo(200)
        // Firefly now has the first review's external_id on 101.
        runBlocking {
            whenever(fireflyTransactions.find(eq("101"))).thenReturn(split(externalId = "plaid-new-1"))
        }

        val second = postJson("/api/reviews/${unmatched[1]}/merge", """{"fireflyId":"101"}""")

        assertThat(second.response.status).isEqualTo(409)
        assertThat(second.response.contentAsString).contains("matched to another bank transaction")
        assertThat(reviews.find(unmatched[1])!!.status).isEqualTo(ReviewStatus.open)
        runBlocking { verify(fireflyTransactions, org.mockito.kotlin.times(1)).update(any(), any()) }
    }

    @Test
    fun `a probable duplicate can be deleted, only while it is unchanged`() {
        runBlocking {
            whenever(fireflyTransactions.find(eq("103"))).thenReturn(split(externalId = "plaid-old-3"))
        }
        val (run, _, leftover) = runWithReviews()
        assertThat(perform(get("/backfills/$run/review").with(owner)).response.contentAsString)
            .contains("Delete it").contains("/transactions/show/104")
        assertThat(postJson("/api/reviews/$leftover/import").response.status).isEqualTo(400)

        assertThat(postJson("/api/reviews/$leftover/delete").response.status).isEqualTo(200)

        runBlocking { verify(fireflyTransactions).delete(eq("103")) }
        assertThat(reviews.find(leftover)!!.status).isEqualTo(ReviewStatus.deleted)
    }

    @Test
    fun `a probable duplicate that a later run matched cannot be deleted`() {
        runBlocking {
            whenever(fireflyTransactions.find(eq("103"))).thenReturn(split(externalId = "plaid-newer"))
        }
        val (_, _, leftover) = runWithReviews()

        assertThat(postJson("/api/reviews/$leftover/delete").response.status).isEqualTo(409)

        runBlocking { verify(fireflyTransactions, org.mockito.kotlin.never()).delete(any()) }
    }

    @Test
    fun `a review can be imported as new, and a failed import leaves it open`() {
        val (_, review) = runWithReview()
        runBlocking { whenever(fireflyTransactions.insert(any())).thenThrow(RuntimeException("Firefly 500")) }
        assertThat(postJson("/api/reviews/$review/import").response.status).isEqualTo(500)
        assertThat(reviews.find(review)!!.status).isEqualTo(ReviewStatus.open)

        runBlocking {
            org.mockito.Mockito.reset(fireflyTransactions)
            whenever(fireflyTransactions.insert(any())).thenReturn(true)
        }
        assertThat(postJson("/api/reviews/$review/import").response.status).isEqualTo(200)
        runBlocking { verify(fireflyTransactions).insert(org.mockito.kotlin.argThat { externalId == "plaid-new-1" }) }
        assertThat(reviews.find(review)!!.status).isEqualTo(ReviewStatus.imported)
    }

    @Test
    fun `a dry run's reviews are shown but cannot be acted on`() {
        val (run, review) = runWithReview(dryRun = true)
        assertThat(perform(get("/backfills/$run/review").with(owner)).response.contentAsString)
            .contains("This was a dry run").doesNotContain("Import as new")
        assertThat(postJson("/api/reviews/$review/dismiss").response.status).isEqualTo(409)
        assertThat(reviews.find(review)!!.status).isEqualTo(ReviewStatus.open)
    }

    @Test
    fun `a review cannot change a transaction's type`() {
        runBlocking {
            whenever(fireflyTransactions.find(eq("102"))).thenReturn(split(TransactionTypeProperty.transfer, "plaid-old-2"))
        }
        val (_, review) = runWithReview()
        assertThat(postJson("/api/reviews/$review/merge", """{"fireflyId":"102"}""").response.status).isEqualTo(409)
        assertThat(postJson("/api/reviews/$review/dismiss").response.status).isEqualTo(200)
        assertThat(reviews.find(review)!!.status).isEqualTo(ReviewStatus.dismissed)
    }
}
