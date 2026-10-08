package net.djvk.fireflyPlaidConnector2.manage.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.djvk.fireflyPlaidConnector2.config.SecretValue
import net.djvk.fireflyPlaidConnector2.sync.ITEM_STORE_PROPERTY
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

const val DATABASE_ITEM_STORE = "database"

/**
 * The plaid_manager database, for `itemStore: database` only.
 *
 * Spring Boot's DataSource and Flyway auto-configuration are excluded for every mode (see
 * ConnectorModeEnvironmentPostProcessor), so that batch and polled modes in config mode never
 * need a database. This builds the pool and runs the migrations explicitly instead.
 */
@Configuration
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = DATABASE_ITEM_STORE)
class DatabaseConfiguration(
    @Value("\${fireflyPlaidConnector2.database.url}")
    private val url: String,
    @Value("\${fireflyPlaidConnector2.database.username}")
    private val username: String,
    @Value("\${fireflyPlaidConnector2.database.password:}")
    private val password: String,
    @Value("\${fireflyPlaidConnector2.database.passwordFile:}")
    private val passwordFile: String,
    @Value("\${fireflyPlaidConnector2.database.maximumPoolSize:4}")
    private val maximumPoolSize: Int,
) {
    @Bean(destroyMethod = "close")
    fun plaidManagerDataSource(): DataSource {
        val config = HikariConfig().apply {
            jdbcUrl = url
            username = this@DatabaseConfiguration.username
            password = SecretValue.resolve(
                this@DatabaseConfiguration.password,
                passwordFile,
                "fireflyPlaidConnector2.database.password",
            )
            maximumPoolSize = this@DatabaseConfiguration.maximumPoolSize
            poolName = "plaid-manager"
        }
        val dataSource = HikariDataSource(config)
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/plaid-manager")
            .load()
            .migrate()
        return dataSource
    }

    @Bean
    fun plaidManagerJdbcClient(dataSource: DataSource): JdbcClient = JdbcClient.create(dataSource)

    @Bean
    fun plaidManagerTransactionManager(dataSource: DataSource) = DataSourceTransactionManager(dataSource)

    @Bean
    fun plaidManagerTransactionTemplate(transactionManager: DataSourceTransactionManager) =
        TransactionTemplate(transactionManager)
}
