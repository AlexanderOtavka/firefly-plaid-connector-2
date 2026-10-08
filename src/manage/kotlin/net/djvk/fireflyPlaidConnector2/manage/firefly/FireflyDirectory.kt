package net.djvk.fireflyPlaidConnector2.manage.firefly

import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRoleProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountStore
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.CreditCardType
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty
import net.djvk.fireflyPlaidConnector2.manage.MANAGE_MODE
import net.djvk.fireflyPlaidConnector2.manage.ManageException
import net.djvk.fireflyPlaidConnector2.manage.SYNC_MODE_PROPERTY
import net.djvk.fireflyPlaidConnector2.sync.SyncHelper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate

data class FireflyAccount(
    val id: Int,
    val name: String,
    val role: String?,
    val active: Boolean,
)

/** A Firefly asset account to create for a newly linked Plaid account. */
data class NewAssetAccount(
    val name: String,
    val role: AccountRoleProperty,
    val notes: String?,
)

/** Firefly asset accounts, for the mapping picker. */
interface FireflyDirectory {
    suspend fun assetAccounts(): List<FireflyAccount>

    suspend fun createAssetAccount(account: NewAssetAccount): FireflyAccount
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

    private suspend fun ensureCreds() {
        credsMutex.withLock {
            if (!credsSet) {
                syncHelper.setApiCreds()
                credsSet = true
            }
        }
    }

    override suspend fun assetAccounts(): List<FireflyAccount> {
        ensureCreds()
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

    /**
     * Creates an asset account in the user's default currency, with no opening balance: a
     * backfill sets the initial balance. Firefly requires a payment date for a credit card
     * account; only its day of month is used, for the bill reminder, so it is set to the 1st.
     */
    override suspend fun createAssetAccount(account: NewAssetAccount): FireflyAccount {
        ensureCreds()
        val creditCard = account.role == AccountRoleProperty.ccAsset
        val store = AccountStore(
            name = account.name,
            type = ShortAccountTypeProperty.asset,
            accountRole = account.role,
            creditCardType = if (creditCard) CreditCardType.monthlyFull else null,
            monthlyPaymentDate = if (creditCard) LocalDate.now().withDayOfMonth(1) else null,
            notes = account.notes,
        )
        val created = try {
            accountsApi.storeAccount(store).body().data
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.UnprocessableEntity) {
                throw ManageException(422, "Firefly refused to create \"${account.name}\"; is the name already in use?")
            }
            throw e
        }
        return FireflyAccount(
            id = created.id.toInt(),
            name = created.attributes.name,
            role = created.attributes.accountRole?.value,
            active = created.attributes.active ?: true,
        )
    }
}
