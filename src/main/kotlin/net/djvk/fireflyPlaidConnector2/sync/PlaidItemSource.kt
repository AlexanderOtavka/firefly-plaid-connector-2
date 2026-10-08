package net.djvk.fireflyPlaidConnector2.sync

import net.djvk.fireflyPlaidConnector2.config.SecretValue
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.transactions.FireflyAccountId
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

const val ITEM_STORE_PROPERTY = "fireflyPlaidConnector2.itemStore"

/**
 * Where the connector gets its Plaid Items and account mappings from.
 *
 * Local change: upstream read these straight from `fireflyPlaidConnector2.accounts`. The
 * lookup sits behind this interface so a database-backed store (see `src/manage/`) can
 * replace it without the sync code knowing which one is in use.
 */
interface PlaidItemSource {
    fun getAccountMapAndPlaidItems(): Pair<Map<PlaidAccountId, FireflyAccountId>, Sequence<PlaidItem>>
}

/**
 * The upstream behaviour: Items and mappings come from the `accounts:` block of the config.
 */
@Component
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = "config", matchIfMissing = true)
class ConfigPlaidItemSource(
    private val plaidAccountsConfig: AccountConfigs,
) : PlaidItemSource {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun getAccountMapAndPlaidItems(): Pair<Map<PlaidAccountId, FireflyAccountId>, Sequence<PlaidItem>> {
        val accountMap = plaidAccountsConfig.accounts.associate { Pair(it.plaidAccountId, it.fireflyAccountId) }
        logger.trace("Read config mapping data for ${accountMap.size} Firefly accounts")
        val accountsByAccessToken = plaidAccountsConfig.accounts.groupBy {
            SecretValue.resolve(
                it.plaidItemAccessToken,
                it.plaidItemAccessTokenFile,
                "fireflyPlaidConnector2.accounts[].plaidItemAccessToken",
            )
        }
        logger.trace("Read config mapping data for ${accountsByAccessToken.size} Plaid items")

        return Pair(accountMap, sequence {
            for ((accessToken, accountConfigs) in accountsByAccessToken) {
                val accountIds = accountConfigs.map { it.plaidAccountId }
                yield(PlaidItem.from(accessToken, accountIds))
            }
        })
    }
}
