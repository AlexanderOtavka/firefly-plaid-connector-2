package net.djvk.fireflyPlaidConnector2.manage.firefly

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeFilter
import net.djvk.fireflyPlaidConnector2.manage.MANAGE_MODE
import net.djvk.fireflyPlaidConnector2.manage.SYNC_MODE_PROPERTY
import net.djvk.fireflyPlaidConnector2.sync.SyncHelper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

data class FireflyAccount(
    val id: Int,
    val name: String,
    val role: String?,
    val active: Boolean,
)

/** Firefly asset accounts, for the mapping picker. */
interface FireflyDirectory {
    suspend fun assetAccounts(): List<FireflyAccount>
}

/** Uses the connector's personal access token, the same one polling and backfills use. */
@Component
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class PatFireflyDirectory(
    private val syncHelper: SyncHelper,
    private val accountsApi: AccountsApi,
) : FireflyDirectory {
    private val credsMutex = Mutex()
    private var credsSet = false

    override suspend fun assetAccounts(): List<FireflyAccount> {
        credsMutex.withLock {
            if (!credsSet) {
                syncHelper.setApiCreds()
                credsSet = true
            }
        }
        val result = mutableListOf<FireflyAccount>()
        var page = 1
        do {
            val body = accountsApi.listAccount(page, null, AccountTypeFilter.asset).body()
            result += body.data.map {
                FireflyAccount(
                    id = it.id.toInt(),
                    name = it.attributes.name,
                    role = it.attributes.accountRole?.value,
                    active = it.attributes.active ?: true,
                )
            }
            val totalPages = body.meta.pagination?.totalPages ?: 1
            page++
        } while (page <= totalPages)
        return result.sortedBy { it.name.lowercase() }
    }
}
