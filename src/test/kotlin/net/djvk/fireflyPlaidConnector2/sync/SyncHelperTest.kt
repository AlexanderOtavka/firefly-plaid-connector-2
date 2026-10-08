package net.djvk.fireflyPlaidConnector2.sync

import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AboutApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.nio.file.Files
import java.nio.file.Path

class SyncHelperTest {
    @Test
    fun `groups file-backed Item tokens without using them as Item keys`(@TempDir tempDir: Path) {
        val tokenFile = tempDir.resolve("plaid-item")
        val accessToken = "access-production-sensitive-value"
        Files.writeString(tokenFile, "$accessToken\n")
        val helper = SyncHelper(
            plaidItemSource = ConfigPlaidItemSource(
                AccountConfigs(
                    listOf(
                        AccountConfig(
                            fireflyAccountId = 1,
                            plaidAccountId = "plaid-account-1",
                            plaidItemAccessTokenFile = tokenFile.toString(),
                        ),
                        AccountConfig(
                            fireflyAccountId = 2,
                            plaidAccountId = "plaid-account-2",
                            plaidItemAccessTokenFile = tokenFile.toString(),
                        ),
                    ),
                ),
            ),
            fireflyAccessToken = "firefly-token",
            fireflyAboutApi = mock<AboutApi>(),
            fireflyTxApi = mock<TransactionsApi>(),
            fireflyAccountsApi = mock<AccountsApi>(),
        )

        val (accountMap, itemsSequence) = helper.getAccountMapAndPlaidItems()
        val items = itemsSequence.toList()

        assertThat(accountMap).containsExactlyInAnyOrderEntriesOf(
            mapOf("plaid-account-1" to 1, "plaid-account-2" to 2),
        )
        assertThat(items).containsExactly(
            PlaidItem(
                key = PlaidItem.keyForAccessToken(accessToken),
                accessToken = accessToken,
                accountIds = listOf("plaid-account-1", "plaid-account-2"),
            ),
        )
        assertThat(items.single().key).doesNotContain(accessToken)
    }
}
