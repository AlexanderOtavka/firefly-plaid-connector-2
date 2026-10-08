package net.djvk.fireflyPlaidConnector2.manage

import io.ktor.client.plugins.*
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsUpdateStatus
import net.djvk.fireflyPlaidConnector2.manage.db.AccountRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ItemRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ItemStatus
import net.djvk.fireflyPlaidConnector2.manage.db.PlaidAccountRow
import net.djvk.fireflyPlaidConnector2.manage.db.PlaidItemRow
import net.djvk.fireflyPlaidConnector2.manage.db.Redaction
import net.djvk.fireflyPlaidConnector2.manage.plaid.LINK_DAYS_REQUESTED
import net.djvk.fireflyPlaidConnector2.manage.plaid.LinkMode
import net.djvk.fireflyPlaidConnector2.manage.plaid.PlaidLinkClient
import net.djvk.fireflyPlaidConnector2.sync.PlaidErrorInfo
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

class ManageException(val status: Int, message: String) : RuntimeException(message)

data class MappingChange(
    val accountId: Long,
    val fireflyAccountId: Int?,
    val enabled: Boolean,
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
     * Saves an Item's account mapping. For a replacement Item this also disables all of its
     * predecessor's accounts, in the same transaction, so both can never import into the same
     * Firefly account (the partial unique index would refuse it anyway).
     */
    fun saveMapping(itemId: Long, changes: List<MappingChange>) {
        val item = requireLive(itemId)
        for (change in changes) {
            if (change.enabled && change.fireflyAccountId == null) {
                throw ManageException(400, "An enabled account needs a Firefly account")
            }
        }
        val firefly = changes.filter { it.enabled }.mapNotNull { it.fireflyAccountId }
        if (firefly.size != firefly.toSet().size) {
            throw ManageException(400, "Two accounts are mapped to the same Firefly account")
        }
        tx.executeWithoutResult {
            items.predecessorOf(item.id)?.let { accounts.disableAllFor(it.id) }
            // Disable first, so moving a Firefly account between two of this Item's accounts
            // does not trip the unique index halfway through.
            for (change in changes) {
                accounts.setMapping(change.accountId, item.id, change.fireflyAccountId, false)
            }
            for (change in changes.filter { it.enabled }) {
                accounts.setMapping(change.accountId, item.id, change.fireflyAccountId, true)
            }
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
