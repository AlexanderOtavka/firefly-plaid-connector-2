package net.djvk.fireflyPlaidConnector2.config.properties

import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "firefly-plaid-connector2")
data class AccountConfigs(
    // Local change: empty by default, because the database item store does not use it.
    val accounts: List<AccountConfig> = emptyList(),
)
