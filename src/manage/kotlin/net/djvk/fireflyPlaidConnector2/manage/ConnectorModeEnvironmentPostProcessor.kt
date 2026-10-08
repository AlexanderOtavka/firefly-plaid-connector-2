package net.djvk.fireflyPlaidConnector2.manage

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PropertiesLoaderUtils

const val SYNC_MODE_PROPERTY = "fireflyPlaidConnector2.syncMode"
const val MANAGE_MODE = "manage"

/**
 * Keeps one image safe to run in every mode.
 *
 * The dashboard adds a servlet stack, JDBC, and Spring Security to the classpath. Batch and
 * polled modes must still start as a plain process with no web server and no database, so:
 *
 * - The web application type is decided here from `syncMode`, overriding anything else:
 *   `servlet` for manage mode, `none` otherwise.
 * - DataSource, Flyway, and JDBC auto-configuration are always excluded. Database mode builds
 *   its own DataSource explicitly (see `DatabaseConfiguration`), so a missing
 *   `spring.datasource.url` can never break a batch Job.
 * - Manage mode gets its server/session defaults from `plaid-manager-defaults.properties`, at
 *   the lowest precedence so a deployment can still override them.
 *
 * `application.yml` carries the same exclusions, but the cluster sets SPRING_CONFIG_LOCATION,
 * which replaces the classpath `application.yml` entirely; this post-processor applies either way.
 */
class ConnectorModeEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val manage = environment.getProperty(SYNC_MODE_PROPERTY) == MANAGE_MODE

        val existingExcludes = environment.getProperty("spring.autoconfigure.exclude")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val excludes = (existingExcludes + ALWAYS_EXCLUDED).distinct()

        environment.propertySources.addFirst(
            MapPropertySource(
                "fireflyPlaidConnectorMode",
                mapOf(
                    "spring.main.web-application-type" to if (manage) "servlet" else "none",
                    "spring.autoconfigure.exclude" to excludes.joinToString(","),
                ),
            )
        )

        if (manage) {
            val defaults = PropertiesLoaderUtils.loadProperties(ClassPathResource(MANAGE_DEFAULTS))
            @Suppress("UNCHECKED_CAST")
            environment.propertySources.addLast(
                MapPropertySource("plaidManagerDefaults", defaults as Map<String, Any>)
            )
        }
    }

    companion object {
        const val MANAGE_DEFAULTS = "plaid-manager-defaults.properties"

        val ALWAYS_EXCLUDED = listOf(
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
            "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
        )
    }
}
