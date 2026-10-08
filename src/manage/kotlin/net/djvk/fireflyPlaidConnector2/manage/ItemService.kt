package net.djvk.fireflyPlaidConnector2.manage

import io.ktor.client.plugins.*
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRoleProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsUpdateStatus
import net.djvk.fireflyPlaidConnector2.manage.db.AccountRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ItemRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ItemStatus
import net.djvk.fireflyPlaidConnector2.manage.db.PlaidAccountRow
import net.djvk.fireflyPlaidConnector2.manage.db.PlaidItemRow
import net.djvk.fireflyPlaidConnector2.manage.db.Redaction
import net.djvk.fireflyPlaidConnector2.manage.firefly.FireflyAccount
import net.djvk.fireflyPlaidConnector2.manage.firefly.FireflyDirectory
import net.djvk.fireflyPlaidConnector2.manage.firefly.NewAssetAccount
import net.djvk.fireflyPlaidConnector2.manage.plaid.LINK_DAYS_REQUESTED
import net.djvk.fireflyPlaidConnector2.manage.plaid.LinkMode
import net.djvk.fireflyPlaidConnector2.manage.plaid.PlaidLinkClient
import net.djvk.fireflyPlaidConnector2.sync.PlaidErrorInfo
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

class ManageException(val status: Int, message: String) : RuntimeException(message)

/** Maps an account to an existing Firefly account, or to a new one named [newFireflyAccountName]. */
data class MappingChange(
    val accountId: Long,
    val fireflyAccountId: Int?,
    val enabled: Boolean,
    val newFireflyAccountName: String? = null,
)

/** What the mapping page preselects for an account: an existing Firefly account, or a new one. */
data class MappingProposal(
    val fireflyAccountId: Int?,
    val newAccountName: String?,
)

/**
 * Linking, mapping, repairing, and retiring Items.
 *
 * Order of steps for a new Item: it is saved as `pending_history`; [HistoryWatcher] waits for
 * Plaid's HISTORICAL_UPDATE_COMPLETE, takes the sync cursor, and marks it `active`; polling
 * picks it up from that cursor; a backfill fills in everything before it. The cursor is taken
 * before the backfill runs, so the two overlap instead of leaving a gap; the backfill matches
 * what polling imported and updates it in place.
 */
@Service
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class ItemService(
    private val items: ItemRepository,
    private val accounts: AccountRepository,
    private val plaid: PlaidLinkClient,
    private val firefly: FireflyDirectory,
    private val tx: TransactionTemplate,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun requireItem(id: Long): PlaidItemRow = items.find(id) ?: throw ManageException(404, "No such Item")

    suspend fun createLinkToken(mode: LinkMode, itemId: Long?): String {
        val token = when (mode) {
            LinkMode.new -> null
            LinkMode.replace -> {
                requireLive(itemId)
                null
            }

            LinkMode.repair -> {
                requireLive(itemId)
                items.accessTokenFor(itemId!!) ?: throw ManageException(409, "Item has no access token")
            }
        }
        return plaid.createLinkToken(mode, token)
    }

    /** Exchanges a new Item's public token and saves it as `pending_history`. */
    suspend fun exchange(publicToken: String, mode: LinkMode, replacesItemId: Long?): Long {
        if (mode == LinkMode.repair) throw ManageException(400, "Repair does not exchange a new token")
        val replaced = if (mode == LinkMode.replace) requireLive(replacesItemId) else null
        val exchanged = plaid.exchange(publicToken)
        items.findByPlaidItemId(exchanged.plaidItemId)?.let { return it.id }

        // Store the token before anything else that can fail: once exchanged, the Item is
        // live and billed at Plaid, and without its token it could never be removed.
        val id = try {
            tx.execute {
                val id = items.insert(
                    plaidItemId = exchanged.plaidItemId,
                    accessToken = exchanged.accessToken,
                    institutionId = null,
                    institutionName = null,
                    daysRequested = LINK_DAYS_REQUESTED,
                    status = ItemStatus.pending_history,
                )
                replaced?.let { items.setReplacedBy(it.id, id) }
                id
            }!!
        } catch (e: Exception) {
            // Could not store it, so do not leave an orphan Item billing at Plaid.
            try {
                plaid.removeItem(exchanged.accessToken)
            } catch (removeError: Exception) {
                logger.error(
                    "Could not store Item …{} nor remove it at Plaid ({}); remove it in the Plaid dashboard",
                    exchanged.plaidItemId.takeLast(4),
                    removeError.javaClass.simpleName,
                )
            }
            throw e
        }

        // Institution and accounts. If this fails, advancePendingItems() retries it.
        try {
            refreshMetadata(id, exchanged.accessToken)
        } catch (e: Exception) {
            logger.warn("Item …{} is stored, but reading its accounts failed; will retry", exchanged.plaidItemId.takeLast(4))
        }
        logger.info("Linked Item …{}", exchanged.plaidItemId.takeLast(4))
        return id
    }

    private suspend fun refreshMetadata(id: Long, accessToken: String) {
        val linked = plaid.describe(accessToken)
        tx.executeWithoutResult {
            items.updateInstitution(id, linked.institutionId, linked.institutionName)
            accounts.upsert(id, linked.accounts)
        }
    }

    /** After Link update mode succeeds, the Item's login works again; it keeps its cursor. */
    fun markRepaired(itemId: Long) {
        requireLive(itemId)
        items.markRepaired(itemId)
    }

    /**
     * Suggested Firefly account per account of [itemId]: its current mapping, or, for a
     * replacement Item, the mapping of the predecessor's matching account, by
     * `persistent_account_id` first and then mask + subtype.
     */
    fun suggestedMapping(itemId: Long): Map<Long, Int?> {
        val own = accounts.forItem(itemId)
        val predecessor = items.predecessorOf(itemId)
        val old = predecessor?.let { accounts.forItem(it.id) } ?: emptyList()
        return own.associate { account -> account.id to (account.fireflyAccountId ?: suggestFrom(account, old)) }
    }

    /**
     * What the mapping page preselects. On an Item none of whose accounts is mapped yet (a new
     * link), each account [suggestedMapping] has nothing for defaults to a new Firefly account,
     * or to an unused one of the same name; once anything is mapped, only [suggestedMapping].
     */
    fun proposedMapping(itemId: Long, fireflyAccounts: List<FireflyAccount>): Map<Long, MappingProposal> {
        val own = accounts.forItem(itemId)
        val suggested = suggestedMapping(itemId)
        if (own.any { it.fireflyAccountId != null }) {
            return suggested.mapValues { MappingProposal(it.value, null) }
        }
        val inUse = accounts.all().filter { it.enabled }.mapNotNull { it.fireflyAccountId }.toSet()
        return propose(own, suggested, fireflyAccounts, inUse)
    }

    /**
     * Saves an Item's account mapping, first creating the new Firefly accounts it asks for.
     * For a replacement Item this also disables all of its predecessor's accounts, in the same
     * transaction, so both can never import into the same Firefly account (the partial unique
     * index would refuse it anyway).
     */
    suspend fun saveMapping(itemId: Long, changes: List<MappingChange>) {
        val item = requireLive(itemId)
        val own = accounts.forItem(item.id).associateBy { it.id }
        for (change in changes) {
            if (change.accountId !in own) {
                throw ManageException(400, "An account is not part of this Item")
            }
            if (change.fireflyAccountId != null && change.newFireflyAccountName != null) {
                throw ManageException(400, "Choose an existing Firefly account or a new one, not both")
            }
            if (change.newFireflyAccountName?.isBlank() == true) {
                throw ManageException(400, "A new Firefly account needs a name")
            }
            if (change.enabled && change.fireflyAccountId == null && change.newFireflyAccountName == null) {
                throw ManageException(400, "An enabled account needs a Firefly account")
            }
        }
        val mapped = changes.filter { it.enabled }.mapNotNull { it.fireflyAccountId }
        if (mapped.size != mapped.toSet().size) {
            throw ManageException(400, "Two accounts are mapped to the same Firefly account")
        }
        // A disabled account imports nothing, so it gets no new Firefly account either.
        val toCreate = changes.filter { it.enabled && it.newFireflyAccountName != null }
        val newNames = toCreate.map { it.newFireflyAccountName!!.trim() }
        if (newNames.size != newNames.map { it.lowercase() }.toSet().size) {
            throw ManageException(400, "Two new Firefly accounts have the same name")
        }
        if (toCreate.isNotEmpty()) {
            val existing = firefly.assetAccounts().map { it.name.lowercase() }.toSet()
            newNames.firstOrNull { it.lowercase() in existing }?.let {
                throw ManageException(409, "Firefly already has an asset account named \"$it\"; choose it instead")
            }
        }

        val created = mutableMapOf<Long, FireflyAccount>()
        try {
            for ((change, name) in toCreate.zip(newNames)) {
                val account = own.getValue(change.accountId)
                val institution = item.institutionName ?: "Item …${item.itemIdLast4}"
                created[change.accountId] = firefly.createAssetAccount(
                    NewAssetAccount(
                        name = name,
                        role = roleFor(account),
                        notes = "Created by the Plaid manager for $institution account ${account.name}" +
                            (account.mask?.let { " …$it" } ?: "") + ".",
                    )
                )
                logger.info("Created Firefly asset account {} for Item …{}", created.getValue(change.accountId).id, item.itemIdLast4)
            }
            val resolved = changes.map { change ->
                created[change.accountId]?.let { change.copy(fireflyAccountId = it.id) } ?: change
            }
            tx.executeWithoutResult {
                items.predecessorOf(item.id)?.let { accounts.disableAllFor(it.id) }
                // Disable first, so moving a Firefly account between two of this Item's accounts
                // does not trip the unique index halfway through.
                for (change in resolved) {
                    accounts.setMapping(change.accountId, item.id, change.fireflyAccountId, false)
                }
                for (change in resolved.filter { it.enabled }) {
                    accounts.setMapping(change.accountId, item.id, change.fireflyAccountId, true)
                }
            }
        } catch (e: Exception) {
            if (created.isEmpty()) throw e
            // The new accounts exist in Firefly now; reloading the page preselects them by name.
            val names = created.values.joinToString { "\"${it.name}\"" }
            val reason = (e as? ManageException)?.message ?: "the mapping could not be saved"
            logger.warn("Saving the mapping of Item …{} failed after creating Firefly accounts: {}", item.itemIdLast4, e.javaClass.simpleName)
            throw ManageException((e as? ManageException)?.status ?: 500, "Created $names in Firefly, but: $reason. Reload the page to map them.")
        }
    }

    /**
     * Removes the Item at Plaid (so it stops being billed), then forgets its access token and
     * disables its accounts.
     */
    suspend fun retire(itemId: Long) {
        val item = requireItem(itemId)
        if (item.status == ItemStatus.retired) return
        val token = items.accessTokenFor(itemId)
        if (token != null) {
            try {
                plaid.removeItem(token)
            } catch (e: ClientRequestException) {
                val error = PlaidErrorInfo.from(e)
                // Already gone at Plaid is fine; anything else keeps the token so it can be retried.
                if (error.code != "ITEM_NOT_FOUND") {
                    throw ManageException(502, "Plaid refused /item/remove: ${error.code}")
                }
            }
        }
        tx.executeWithoutResult {
            accounts.disableAllFor(itemId)
            items.retire(itemId)
        }
        logger.info("Retired Item …{}", item.itemIdLast4)
    }

    /**
     * For each `pending_history` Item, asks Plaid whether its historical pull is complete. Once
     * it is, pages `/transactions/sync` to the end (discarding the transactions; the backfill
     * imports them) and stores the resulting cursor, which activates the Item for polling.
     */
    suspend fun advancePendingItems() {
        for (item in items.withStatus(ItemStatus.pending_history)) {
            val token = items.accessTokenFor(item.id) ?: continue
            try {
                if (accounts.forItem(item.id).isEmpty()) {
                    refreshMetadata(item.id, token)
                }
                var page = plaid.syncPage(token, null)
                if (page.transactionsUpdateStatus != TransactionsUpdateStatus.HISTORICAL_UPDATE_COMPLETE) {
                    logger.debug("Item …{} history: {}", item.itemIdLast4, page.transactionsUpdateStatus)
                    continue
                }
                while (page.hasMore) {
                    page = plaid.syncPage(token, page.nextCursor)
                }
                items.activateWithCursor(item.id, page.nextCursor)
                logger.info("Item …{} history is complete; polling starts from here", item.itemIdLast4)
            } catch (e: ClientRequestException) {
                val error = PlaidErrorInfo.from(e)
                items.recordFailure(item.id, error.code, error.message)
                logger.warn("Checking history of Item …{} failed: {}", item.itemIdLast4, error.code)
            } catch (e: Exception) {
                logger.warn("Checking history of Item …{} failed: {}: {}", item.itemIdLast4, e.javaClass.simpleName, Redaction.redact(e.message))
            }
        }
    }

    private fun requireLive(itemId: Long?): PlaidItemRow {
        val item = requireItem(itemId ?: throw ManageException(400, "Missing itemId"))
        if (item.status == ItemStatus.retired) throw ManageException(409, "Item is retired")
        return item
    }

    companion object {
        private val SAVINGS_SUBTYPES = setOf("savings", "cd", "money market", "hsa")

        /**
         * Credit cards become credit card asset accounts, savings-like deposit accounts savings
         * accounts, and everything else a default asset account.
         */
        fun roleFor(account: PlaidAccountRow): AccountRoleProperty = when {
            account.type == "credit" -> AccountRoleProperty.ccAsset
            account.type == "depository" && account.subtype in SAVINGS_SUBTYPES -> AccountRoleProperty.savingAsset
            else -> AccountRoleProperty.defaultAsset
        }

        /**
         * For each of [own] with no [suggested] Firefly account: an active Firefly account of
         * the same name that no enabled account maps to ([inUse]), else a new account named
         * after the Plaid account, with its mask appended when the name alone is taken.
         */
        fun propose(
            own: List<PlaidAccountRow>,
            suggested: Map<Long, Int?>,
            fireflyAccounts: List<FireflyAccount>,
            inUse: Set<Int>,
        ): Map<Long, MappingProposal> {
            val taken = (inUse + suggested.values.filterNotNull()).toMutableSet()
            val names = fireflyAccounts.map { it.name.lowercase() }.toMutableSet()
            val repeated = own.groupingBy { it.name.lowercase() }.eachCount().filterValues { it > 1 }.keys
            return own.associate { account ->
                suggested[account.id]?.let { return@associate account.id to MappingProposal(it, null) }
                val key = account.name.lowercase()
                val sameName = fireflyAccounts.firstOrNull { it.active && it.name.lowercase() == key && it.id !in taken }
                if (sameName != null && key !in repeated) {
                    taken += sameName.id
                    return@associate account.id to MappingProposal(sameName.id, null)
                }
                val name = if ((key in names || key in repeated) && account.mask != null) {
                    "${account.name} …${account.mask}"
                } else {
                    account.name
                }
                names += name.lowercase()
                account.id to MappingProposal(null, name)
            }
        }

        fun suggestFrom(account: PlaidAccountRow, old: List<PlaidAccountRow>): Int? {
            val mapped = old.filter { it.fireflyAccountId != null }
            val byPersistentId = account.persistentAccountId?.let { id ->
                mapped.firstOrNull { it.persistentAccountId == id }
            }
            val byMask = mapped.firstOrNull {
                account.mask != null && it.mask == account.mask && it.subtype == account.subtype
            }
            return (byPersistentId ?: byMask)?.fireflyAccountId
        }
    }
}
