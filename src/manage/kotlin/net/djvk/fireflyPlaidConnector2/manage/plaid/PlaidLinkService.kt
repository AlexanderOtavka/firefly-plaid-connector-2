package net.djvk.fireflyPlaidConnector2.manage.plaid

import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountBase
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.CountryCode
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InstitutionsGetByIdRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.ItemGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.ItemPublicTokenExchangeRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.ItemRemoveRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.LinkTokenCreateRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.LinkTokenCreateRequestUser
import net.djvk.fireflyPlaidConnector2.api.plaid.models.LinkTokenTransactions
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Products
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncResponse
import net.djvk.fireflyPlaidConnector2.manage.db.NewPlaidAccount
import net.djvk.fireflyPlaidConnector2.sync.ITEM_STORE_PROPERTY
import net.djvk.fireflyPlaidConnector2.manage.db.DATABASE_ITEM_STORE
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * `transactions.days_requested` for every new Item. Plaid fixes an Item's history depth at
 * link time and defaults it to 90 days; 730 is the maximum. It is a constant, not a form
 * field, because asking for less is never what we want and is not fixable afterwards.
 */
const val LINK_DAYS_REQUESTED = 730

@Suppress("EnumEntryName")
enum class LinkMode {
    /** A fresh Item for an institution not yet linked. */
    new,

    /** A fresh Item to replace an existing one: the only way to deepen its history. */
    replace,

    /** Plaid update mode on an existing Item, e.g. for ITEM_LOGIN_REQUIRED. Keeps history depth. */
    repair,
}

/** The result of `/item/public_token/exchange`. */
data class ExchangedItem(
    val plaidItemId: String,
    val accessToken: String,
) {
    override fun toString() = "ExchangedItem(plaidItemId=…${plaidItemId.takeLast(4)})"
}

/** What the manager needs from an Item right after linking it. */
data class LinkedItem(
    val plaidItemId: String,
    val accessToken: String,
    val institutionId: String?,
    val institutionName: String?,
    val accounts: List<NewPlaidAccount>,
) {
    override fun toString() = "LinkedItem(plaidItemId=…${plaidItemId.takeLast(4)}, accounts=${accounts.size})"
}

/**
 * Builds a `/link/token/create` request. New and replace links always request
 * [LINK_DAYS_REQUESTED] days; repair is update mode on an existing access token, and Plaid
 * rejects `products` in update mode.
 */
fun buildLinkTokenRequest(
    mode: LinkMode,
    accessToken: String?,
    redirectUri: String,
    clientName: String,
): LinkTokenCreateRequest {
    val user = LinkTokenCreateRequestUser(clientUserId = "firefly-owner")
    return when (mode) {
        LinkMode.new, LinkMode.replace -> LinkTokenCreateRequest(
            clientName = clientName,
            language = "en",
            countryCodes = listOf(CountryCode.US),
            user = user,
            products = listOf(Products.transactions),
            transactions = LinkTokenTransactions(daysRequested = LINK_DAYS_REQUESTED),
            redirectUri = redirectUri,
        )

        LinkMode.repair -> LinkTokenCreateRequest(
            clientName = clientName,
            language = "en",
            countryCodes = listOf(CountryCode.US),
            user = user,
            accessToken = requireNotNull(accessToken) { "Repair needs the Item's access token" },
            redirectUri = redirectUri,
        )
    }
}

/** Plaid calls made on the dashboard's behalf. Never logs or returns an access token. */
interface PlaidLinkClient {
    suspend fun createLinkToken(mode: LinkMode, accessToken: String?): String

    /** Only the exchange itself, so the caller can store the token before anything else can fail. */
    suspend fun exchange(publicToken: String): ExchangedItem

    suspend fun describe(accessToken: String): LinkedItem

    suspend fun removeItem(accessToken: String)

    suspend fun syncPage(accessToken: String, cursor: String?): TransactionsSyncResponse
}

@Component
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = DATABASE_ITEM_STORE)
class DefaultPlaidLinkClient(
    private val plaid: PlaidApiWrapper,
    @Value("\${fireflyPlaidConnector2.manage.publicBaseUrl:}")
    private val publicBaseUrl: String,
    @Value("\${fireflyPlaidConnector2.product.name:Firefly Plaid Connector 2}")
    private val clientName: String,
) : PlaidLinkClient {
    /** Must be registered in the Plaid dashboard, or OAuth institutions fail to link. */
    // Batch and polled modes also use this client with the database item store, but never
    // link, so the setting is only required here.
    val redirectUri: String get() {
        require(publicBaseUrl.isNotBlank()) { "fireflyPlaidConnector2.manage.publicBaseUrl is required to link Items" }
        return "${publicBaseUrl.trimEnd('/')}/plaid/oauth-return"
    }

    override suspend fun createLinkToken(mode: LinkMode, accessToken: String?): String {
        val request = buildLinkTokenRequest(mode, accessToken, redirectUri, clientName)
        return plaid.executeRequest({ it.linkTokenCreate(request) }, "link token create").body().linkToken
    }

    override suspend fun exchange(publicToken: String): ExchangedItem {
        val exchanged = plaid.executeRequest(
            { it.itemPublicTokenExchange(ItemPublicTokenExchangeRequest(publicToken)) },
            "public token exchange",
        ).body()
        return ExchangedItem(exchanged.itemId, exchanged.accessToken)
    }

    override suspend fun describe(accessToken: String): LinkedItem {
        val item = plaid.executeRequest({ it.itemGet(ItemGetRequest(accessToken)) }, "item get").body().item
        val institutionName = item.institutionId?.let { institutionId ->
            try {
                plaid.executeRequest(
                    { it.institutionsGetById(InstitutionsGetByIdRequest(institutionId, listOf(CountryCode.US))) },
                    "institution get",
                ).body().institution.name
            } catch (_: Exception) {
                null
            }
        }
        val accounts = plaid.executeRequest({ it.accountsGet(AccountsGetRequest(accessToken)) }, "accounts get")
            .body()
            .accounts
            .map { it.toNewAccount() }
        return LinkedItem(item.itemId, accessToken, item.institutionId, institutionName, accounts)
    }

    override suspend fun removeItem(accessToken: String) {
        plaid.executeRequest({ it.itemRemove(ItemRemoveRequest(accessToken)) }, "item remove")
    }

    override suspend fun syncPage(accessToken: String, cursor: String?): TransactionsSyncResponse {
        val request = TransactionsSyncRequest(accessToken = accessToken, cursor = cursor, count = 500)
        return plaid.executeRequest({ it.transactionsSync(request) }, "history status sync").body()
    }
}

fun AccountBase.toNewAccount() = NewPlaidAccount(
    plaidAccountId = accountId,
    persistentAccountId = persistentAccountId,
    name = name,
    mask = mask,
    type = type.value,
    subtype = subtype?.value,
)
