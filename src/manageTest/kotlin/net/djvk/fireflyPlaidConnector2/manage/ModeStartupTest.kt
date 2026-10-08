package net.djvk.fireflyPlaidConnector2.manage

import net.djvk.fireflyPlaidConnector2.lib.TestApplication
import net.djvk.fireflyPlaidConnector2.manage.db.DatabasePlaidItemSource
import net.djvk.fireflyPlaidConnector2.sync.BatchSyncRunner
import net.djvk.fireflyPlaidConnector2.sync.ConfigPlaidItemSource
import net.djvk.fireflyPlaidConnector2.sync.CursorManager
import net.djvk.fireflyPlaidConnector2.sync.CursorStore
import net.djvk.fireflyPlaidConnector2.sync.PlaidItemSource
import net.djvk.fireflyPlaidConnector2.sync.PolledSyncOrchestrator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Path
import javax.sql.DataSource

/**
 * The image carries a servlet stack and JDBC for the dashboard, but batch and polled modes
 * must still start as before: no web server, and no database unless `itemStore: database`.
 */
class ModeStartupTest {
    private fun start(tempDir: Path, vararg properties: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(TestApplication::class.java)
            .profiles("test")
            // Command-line arguments, like the cluster's environment variables, outrank the
            // classpath application.yml.
            .run(
                *listOf(
                    "fireflyPlaidConnector2.plaid.url=http://127.0.0.1:9",
                    "fireflyPlaidConnector2.plaid.clientId=test-client",
                    "fireflyPlaidConnector2.plaid.secret=test-secret",
                    "fireflyPlaidConnector2.firefly.url=http://127.0.0.1:9",
                    "fireflyPlaidConnector2.firefly.personalAccessToken=test-pat",
                    "fireflyPlaidConnector2.polled.cursorFileDirectoryPath=$tempDir",
                    "logging.level.org.springframework.beans=INFO",
                    *properties,
                ).map { "--$it" }.toTypedArray()
            )

    @Test
    fun `batch mode starts with no datasource properties and no web server`(@TempDir tempDir: Path) {
        start(tempDir, "fireflyPlaidConnector2.syncMode=batch").use { context ->
            assertThat(context).isNotInstanceOf(WebServerApplicationContext::class.java)
            assertThat(context.getBeansOfType(DataSource::class.java)).isEmpty()
            assertThat(context.getBean(BatchSyncRunner::class.java)).isNotNull()
            assertThat(context.getBean(PlaidItemSource::class.java)).isInstanceOf(ConfigPlaidItemSource::class.java)
            assertThat(context.getBean(CursorStore::class.java)).isInstanceOf(CursorManager::class.java)
        }
    }

    @Test
    fun `polled mode starts with no datasource properties and no web server`(@TempDir tempDir: Path) {
        start(tempDir, "fireflyPlaidConnector2.syncMode=polled").use { context ->
            assertThat(context).isNotInstanceOf(WebServerApplicationContext::class.java)
            assertThat(context.getBeansOfType(DataSource::class.java)).isEmpty()
            assertThat(context.getBean(PolledSyncOrchestrator::class.java)).isNotNull()
        }
    }

    @Test
    fun `a backfill Job in database mode reads Items from the database`(@TempDir tempDir: Path) {
        val db = TestPostgres.freshDatabase()
        start(
            tempDir,
            "fireflyPlaidConnector2.syncMode=batch",
            "fireflyPlaidConnector2.itemStore=database",
            "fireflyPlaidConnector2.database.url=${db.url}",
            "fireflyPlaidConnector2.database.username=${db.username}",
            "fireflyPlaidConnector2.database.password=${db.password}",
            "fireflyPlaidConnector2.batch.itemIds=1",
            "fireflyPlaidConnector2.batch.runId=1",
        ).use { context ->
            assertThat(context).isNotInstanceOf(WebServerApplicationContext::class.java)
            assertThat(context.getBean(PlaidItemSource::class.java)).isInstanceOf(DatabasePlaidItemSource::class.java)
            assertThat(context.getBeansOfType(CursorManager::class.java)).isEmpty()
        }
    }
}
