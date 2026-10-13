package net.djvk.fireflyPlaidConnector2.manage

import com.fasterxml.jackson.databind.ObjectMapper
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRoleProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapperTest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Products
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillRunRow
import net.djvk.fireflyPlaidConnector2.manage.db.BackfillStatus
import net.djvk.fireflyPlaidConnector2.manage.db.ItemStatus
import net.djvk.fireflyPlaidConnector2.manage.db.PlaidAccountRow
import net.djvk.fireflyPlaidConnector2.manage.db.PlaidItemRow
import net.djvk.fireflyPlaidConnector2.manage.db.Redaction
import net.djvk.fireflyPlaidConnector2.manage.firefly.FireflyAccount
import net.djvk.fireflyPlaidConnector2.manage.k8s.KubernetesClusterGateway
import net.djvk.fireflyPlaidConnector2.manage.k8s.classifyJob
import net.djvk.fireflyPlaidConnector2.manage.k8s.renderBackfillJob
import net.djvk.fireflyPlaidConnector2.manage.plaid.LINK_DAYS_REQUESTED
import net.djvk.fireflyPlaidConnector2.manage.plaid.LinkMode
import net.djvk.fireflyPlaidConnector2.manage.plaid.buildLinkTokenRequest
import net.djvk.fireflyPlaidConnector2.manage.web.FireflyUser
import net.djvk.fireflyPlaidConnector2.manage.web.NAV_LINKS_PROPERTY
import net.djvk.fireflyPlaidConnector2.manage.web.NavLink
import net.djvk.fireflyPlaidConnector2.manage.web.NavLinks
import net.djvk.fireflyPlaidConnector2.manage.web.importerLink
import net.djvk.fireflyPlaidConnector2.manage.web.isAllowed
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.MapPropertySource
import java.time.Duration
import java.time.Instant

class MigrationPackagingTest {
    /** The upstream .gitignore ignores *.sql; a migration missing from git ships no schema. */
    @Test
    fun `the Flyway migrations are on the classpath`() {
        assertThat(org.springframework.core.io.ClassPathResource("db/plaid-manager/V1__item_store.sql").exists()).isTrue()
    }
}

class LinkTokenRequestTest {
    private val redirect = "https://firefly.example.com/plaid/oauth-return"

    @Test
    fun `new Items always request 730 days of history`() {
        assertThat(LINK_DAYS_REQUESTED).isEqualTo(730)
        for (mode in listOf(LinkMode.new, LinkMode.replace)) {
            val request = buildLinkTokenRequest(mode, null, redirect, "Firefly")
            assertThat(request.transactions?.daysRequested).isEqualTo(730)
            assertThat(request.products).containsExactly(Products.transactions)
            assertThat(request.accessToken).isNull()
            assertThat(request.redirectUri).isEqualTo(redirect)
        }
    }

    @Test
    fun `repair is update mode on the existing token, without products`() {
        val request = buildLinkTokenRequest(LinkMode.repair, "access-production-x", redirect, "Firefly")
        assertThat(request.accessToken).isEqualTo("access-production-x")
        assertThat(request.products).isNull()
        assertThat(request.transactions).isNull()
        assertThat(request.redirectUri).isEqualTo(redirect)
    }

    @Test
    fun `repair without a token is refused`() {
        assertThatThrownBy { buildLinkTokenRequest(LinkMode.repair, null, redirect, "Firefly") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    /** Plaid validates any field that is present, so `"access_tokens": null` fails with INVALID_FIELD. */
    @Test
    fun `link token requests send no null fields`() = runBlocking {
        for ((mode, token) in listOf(LinkMode.new to null, LinkMode.replace to null, LinkMode.repair to "access-production-x")) {
            var body = ""
            val plaid = PlaidApiWrapperTest.mockPlaid(MockEngine { request ->
                body = request.body.toByteArray().toString(Charsets.UTF_8)
                respond(
                    """{"link_token":"link-production-x","expiration":"2026-10-05T00:00:00Z","request_id":"r"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            })
            val request = buildLinkTokenRequest(mode, token, redirect, "Firefly")
            plaid.executeRequest({ it.linkTokenCreate(request) }, "link token create")

            val json = ObjectMapper().readTree(body)
            assertThat(json.has("access_tokens")).isFalse()
            assertThat(body).doesNotContain("null")
        }
    }
}

class BackfillJobTest {
    private val template = """
        apiVersion: batch/v1
        kind: Job
        metadata:
          generateName: firefly-plaid-backfill-
          namespace: firefly
          labels:
            app.kubernetes.io/name: firefly-plaid-backfill
        spec:
          backoffLimit: 0
          activeDeadlineSeconds: 14400
          template:
            metadata:
              labels:
                app.kubernetes.io/name: firefly-plaid-backfill
            spec:
              restartPolicy: Never
              automountServiceAccountToken: false
              containers:
                - name: backfill
                  image: replaced-by-the-manager
                  env:
                    - name: SPRING_CONFIG_LOCATION
                      value: /config/application.yml
                    - name: FIREFLYPLAIDCONNECTOR2_BATCH_MAXSYNCDAYS
                      value: "5"
    """.trimIndent()

    private val run = BackfillRunRow(
        id = 7, itemId = 3, accountIds = listOf(11, 12), days = 400, deadlineSeconds = 7200,
        jobName = "firefly-plaid-backfill-run-7", requestedAt = Instant.now(), startedAt = null, finishedAt = null,
        status = BackfillStatus.pending, fetched = null, inserted = null, duplicates = null,
        failed = null, oldestDate = null, error = null,
        dryRun = true, matched = null, updated = null, needsReview = null,
    )

    @Test
    fun `fills in image, deadline, labels, and run parameters`() {
        val job = renderBackfillJob(
            KubernetesClusterGateway.loadJob(template.byteInputStream()),
            "ghcr.io/example/connector@sha256:abc",
            run,
        )
        assertThat(job.metadata.generateName).isNull()
        assertThat(job.metadata.name).isEqualTo("firefly-plaid-backfill-run-7")
        assertThat(job.metadata.labels).containsEntry("plaid-manager/run-id", "7").containsEntry("plaid-manager/item-id", "3")
        assertThat(job.spec.template.metadata.labels).containsEntry("plaid-manager/run-id", "7")
        assertThat(job.spec.activeDeadlineSeconds).isEqualTo(7200)
        assertThat(job.spec.template.spec.automountServiceAccountToken).isFalse()
        val container = job.spec.template.spec.containers.single()
        assertThat(container.image).isEqualTo("ghcr.io/example/connector@sha256:abc")
        val env = container.env.associate { it.name to it.value }
        assertThat(env).containsEntry("SPRING_CONFIG_LOCATION", "/config/application.yml")
            .containsEntry("FIREFLYPLAIDCONNECTOR2_SYNCMODE", "batch")
            .containsEntry("FIREFLYPLAIDCONNECTOR2_ITEMSTORE", "database")
            .containsEntry("FIREFLYPLAIDCONNECTOR2_BATCH_ITEMIDS", "3")
            .containsEntry("FIREFLYPLAIDCONNECTOR2_BATCH_ACCOUNTIDS", "11,12")
            .containsEntry("FIREFLYPLAIDCONNECTOR2_BATCH_MAXSYNCDAYS", "400")
            .containsEntry("FIREFLYPLAIDCONNECTOR2_BATCH_RUNID", "7")
            .containsEntry("FIREFLYPLAIDCONNECTOR2_BATCH_DRYRUN", "true")
        assertThat(container.env.count { it.name == "FIREFLYPLAIDCONNECTOR2_BATCH_MAXSYNCDAYS" }).isEqualTo(1)
    }

    @Test
    fun `classifies finished Jobs`() {
        fun job(type: String, reason: String?) = JobBuilder().withNewStatus()
            .withConditions(JobConditionBuilder().withType(type).withStatus("True").withReason(reason).build())
            .endStatus().build()

        assertThat(classifyJob(JobBuilder().build(), emptyList(), Instant.now())).isNull()
        assertThat(classifyJob(job("Failed", "DeadlineExceeded"), emptyList(), Instant.now())?.status)
            .isEqualTo(BackfillStatus.deadline_exceeded)
        val failed = classifyJob(job("Failed", "BackoffLimitExceeded"), listOf("OOMKilled"), Instant.now())
        assertThat(failed?.status).isEqualTo(BackfillStatus.failed)
        assertThat(failed?.error).contains("BackoffLimitExceeded").contains("OOMKilled")
        assertThat(classifyJob(job("Complete", null), emptyList(), Instant.now())?.status).isEqualTo(BackfillStatus.failed)
        assertThat(classifyJob(null, emptyList(), Instant.now())).isNull()
        assertThat(classifyJob(null, emptyList(), Instant.now().minus(Duration.ofMinutes(5)))?.status)
            .isEqualTo(BackfillStatus.failed)
    }
}

class ManageRulesTest {
    private fun item(daysRequested: Int, linkedDaysAgo: Long) = PlaidItemRow(
        id = 1, plaidItemId = "item-abcd", institutionId = null, institutionName = null,
        daysRequested = daysRequested, linkedAt = Instant.now().minus(Duration.ofDays(linkedDaysAgo)),
        status = ItemStatus.active, replacedBy = null, retiredAt = null, hasCursor = true, pollingSince = null,
        lastSyncAt = null, lastSyncAdded = null, lastErrorCode = null, lastErrorMessage = null,
        lastErrorAt = null, consecutiveFailures = 0,
    )

    @Test
    fun `backfill days are capped at days_requested plus days since linking`() {
        assertThat(item(90, 0).maxBackfillDays()).isEqualTo(90)
        assertThat(item(90, 30).maxBackfillDays()).isEqualTo(120)
        assertThat(item(730, 10).maxBackfillDays()).isEqualTo(740)
    }

    @Test
    fun `replacement suggestions match persistent id first, then mask and subtype`() {
        fun account(id: Long, persistent: String?, mask: String?, subtype: String?, firefly: Int?) = PlaidAccountRow(
            id = id, itemId = 1, plaidAccountId = "acc-$id", persistentAccountId = persistent, name = "n",
            mask = mask, type = "credit", subtype = subtype, fireflyAccountId = firefly, enabled = firefly != null,
        )
        val old = listOf(account(1, "p1", "1111", "credit card", 8), account(2, null, "2222", "checking", 9))

        assertThat(ItemService.suggestFrom(account(10, "p1", "9999", "credit card", null), old)).isEqualTo(8)
        assertThat(ItemService.suggestFrom(account(11, null, "2222", "checking", null), old)).isEqualTo(9)
        assertThat(ItemService.suggestFrom(account(12, null, "2222", "savings", null), old)).isNull()
        assertThat(ItemService.suggestFrom(account(13, "p2", null, "credit card", null), old)).isNull()
    }

    private fun linked(id: Long, name: String, mask: String?, type: String = "credit", subtype: String? = "credit card") =
        PlaidAccountRow(
            id = id, itemId = 1, plaidAccountId = "acc-$id", persistentAccountId = null, name = name,
            mask = mask, type = type, subtype = subtype, fireflyAccountId = null, enabled = false,
        )

    @Test
    fun `new Firefly accounts get a role from the Plaid type`() {
        assertThat(ItemService.roleFor(linked(1, "Card", null))).isEqualTo(AccountRoleProperty.ccAsset)
        assertThat(ItemService.roleFor(linked(2, "Savings", null, "depository", "savings"))).isEqualTo(AccountRoleProperty.savingAsset)
        assertThat(ItemService.roleFor(linked(3, "Checking", null, "depository", "checking"))).isEqualTo(AccountRoleProperty.defaultAsset)
        assertThat(ItemService.roleFor(linked(4, "Brokerage", null, "investment", "brokerage"))).isEqualTo(AccountRoleProperty.defaultAsset)
    }

    @Test
    fun `new links default to new accounts, or to an unused Firefly account of the same name`() {
        val firefly = listOf(
            FireflyAccount(8, "Example Card", "ccAsset", true),
            FireflyAccount(9, "Checking", "defaultAsset", true),
            FireflyAccount(10, "Old Savings", "savingAsset", false),
        )
        val own = listOf(
            linked(1, "Example Card", "1234"),                         // same name, unused: reuse it
            linked(2, "Checking", "5555", "depository", "checking"), // same name, in use: new, with mask
            linked(3, "Old Savings", "7777", "depository", "savings"), // inactive namesake: new, with mask
            linked(4, "Joint", "1111", "depository", "checking"),   // repeated in the Item: both get masks
            linked(5, "Joint", "2222", "depository", "checking"),
            linked(6, "Mortgage", null, "loan", "mortgage"),        // nothing like it: plain name
            linked(7, "Replaced", "3333"),                          // the predecessor's suggestion wins
        )
        val proposed = ItemService.propose(own, mapOf(7L to 42), firefly, inUse = setOf(9))
        assertThat(proposed[1]).isEqualTo(MappingProposal(8, null))
        assertThat(proposed[2]).isEqualTo(MappingProposal(null, "Checking …5555"))
        assertThat(proposed[3]).isEqualTo(MappingProposal(null, "Old Savings …7777"))
        assertThat(proposed[4]).isEqualTo(MappingProposal(null, "Joint …1111"))
        assertThat(proposed[5]).isEqualTo(MappingProposal(null, "Joint …2222"))
        assertThat(proposed[6]).isEqualTo(MappingProposal(null, "Mortgage"))
        assertThat(proposed[7]).isEqualTo(MappingProposal(42, null))
    }

    @Test
    fun `redaction removes access tokens`() {
        assertThat(Redaction.redact("failed for access-production-1234-abcd today"))
            .isEqualTo("failed for [redacted token] today")
    }
}

class ImporterLinkTest {
    @Test
    fun `the importer link is a path on this origin or an http(s) URL, and unset means none`() {
        assertThat(importerLink("")).isNull()
        assertThat(importerLink("   ")).isNull()
        assertThat(importerLink(" /importer/ ")).isEqualTo("/importer/")
        assertThat(importerLink("https://importer.example.com/")).isEqualTo("https://importer.example.com/")
        assertThat(importerLink("HTTP://importer.example.com")).isEqualTo("HTTP://importer.example.com")
        assertThat(importerLink("javascript:alert(1)")).isNull()
        assertThat(importerLink("//elsewhere.example.com/")).isNull()
        assertThat(importerLink("https:///nohost")).isNull()
        assertThat(importerLink("importer/")).isNull()
    }
}

class FireflyLoginRulesTest {
    private val allowed = setOf("owner@example.com")
    private val mapper = ObjectMapper()

    private fun user(json: String) = FireflyUser.fromJson(mapper.readTree(json))

    @Test
    fun `admits only an unblocked owner on the allow-list`() {
        val owner = user("""{"data":{"id":"1","attributes":{"email":"owner@example.com","blocked":false,"role":"owner"}}}""")
        assertThat(isAllowed(owner, allowed)).isTrue()
        assertThat(isAllowed(owner.copy(role = null), allowed)).isFalse()
        assertThat(isAllowed(owner.copy(blocked = true), allowed)).isFalse()
        assertThat(isAllowed(owner.copy(email = "someone@example.com"), allowed)).isFalse()
        assertThat(isAllowed(owner.copy(email = "OWNER@example.com"), allowed)).isTrue()
    }

    @Test
    fun `a missing blocked flag fails closed`() {
        val noFlag = user("""{"data":{"id":"1","attributes":{"email":"owner@example.com","role":"owner"}}}""")
        assertThat(isAllowed(noFlag, allowed)).isFalse()
        assertThat(isAllowed(user("{}"), allowed)).isFalse()
    }
}

class ConnectorModeEnvironmentPostProcessorTest {
    private fun process(vararg properties: Pair<String, Any>): StandardEnvironment {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test", mapOf(*properties)))
        ConnectorModeEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())
        return environment
    }

    @Test
    fun `batch and polled get no web server and no JDBC auto-configuration`() {
        for (mode in listOf("batch", "polled", "import")) {
            val environment = process(SYNC_MODE_PROPERTY to mode, "spring.main.web-application-type" to "servlet")
            assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none")
            assertThat(environment.getProperty("spring.autoconfigure.exclude"))
                .contains("DataSourceAutoConfiguration")
                .contains("FlywayAutoConfiguration")
            assertThat(environment.getProperty("server.servlet.context-path")).isNull()
        }
    }

    @Test
    fun `manage mode gets the servlet stack and its session defaults`() {
        val environment = process(SYNC_MODE_PROPERTY to "manage")
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("servlet")
        assertThat(environment.getProperty("spring.autoconfigure.exclude")).contains("DataSourceAutoConfiguration")
        assertThat(environment.getProperty("server.servlet.context-path")).isEqualTo("/plaid")
        assertThat(environment.getProperty("server.servlet.session.cookie.name")).isEqualTo("PLAIDSESSION")
        assertThat(environment.getProperty("server.servlet.session.cookie.path")).isEqualTo("/plaid")
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax")
        assertThat(environment.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true")
        assertThat(environment.getProperty("server.servlet.session.timeout")).isEqualTo("30m")
    }
}

class NavLinksTest {
    private fun links(vararg properties: Pair<String, Any>) =
        NavLinks(StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", mapOf(*properties)))
        }).links

    @Test
    fun `defaults to one link back to Firefly`() {
        assertThat(links()).containsExactly(NavLink("Back to Firefly", "/"))
    }

    @Test
    fun `takes any number of links, in order`() {
        assertThat(
            links(
                "$NAV_LINKS_PROPERTY[0].label" to "Firefly", "$NAV_LINKS_PROPERTY[0].url" to "/",
                "$NAV_LINKS_PROPERTY[1].label" to "Grafana", "$NAV_LINKS_PROPERTY[1].url" to "https://grafana.example.com/",
            )
        ).containsExactly(NavLink("Firefly", "/"), NavLink("Grafana", "https://grafana.example.com/"))
    }

    @Test
    fun `an empty list means no links`() {
        assertThat(links(NAV_LINKS_PROPERTY to "")).isEmpty()
    }

    @Test
    fun `refuses links that are not web links`() {
        assertThatThrownBy {
            links("$NAV_LINKS_PROPERTY[0].label" to "Evil", "$NAV_LINKS_PROPERTY[0].url" to "javascript:alert(1)")
        }.hasMessageContaining("http(s)")
    }
}
