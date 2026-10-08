package net.djvk.fireflyPlaidConnector2.manage

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.config.SecretValue
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.manage.db.AccountRepository
import net.djvk.fireflyPlaidConnector2.manage.db.DATABASE_ITEM_STORE
import net.djvk.fireflyPlaidConnector2.manage.db.ItemRepository
import net.djvk.fireflyPlaidConnector2.manage.db.ItemStatus
import net.djvk.fireflyPlaidConnector2.manage.plaid.PlaidLinkClient
import net.djvk.fireflyPlaidConnector2.sync.CursorManager
import net.djvk.fireflyPlaidConnector2.sync.ITEM_STORE_PROPERTY
import net.djvk.fireflyPlaidConnector2.sync.PlaidItem
import net.djvk.fireflyPlaidConnector2.sync.Runner
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * The `import` sync mode: a one-shot copy of the configured `accounts:` block into the
 * database item store, for the cutover from `itemStore: config`.
 *
 * For each configured Item it calls `/item/get` and `/accounts/get`, upserts the Item and all
 * of its accounts (the configured ones enabled and mapped), and copies the Item's cursor from
 * the polled connector's cursor file, so polling continues where it left off. An Item without
 * a cursor is left `pending_history`, and the dashboard takes one once Plaid's history is
 * complete. Safe to re-run: existing rows are updated, and a token is never rewritten.
 *
 * Items linked before the dashboard existed used Plaid's default history depth, so they are
 * imported with `import.daysRequested` (default 90), which caps their backfills correctly.
 */
@Component
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = "import")
class ImportRunner(
    private val accountConfigs: AccountConfigs,
    private val plaid: PlaidLinkClient,
    private val items: ItemRepository,
    private val accounts: AccountRepository,
    private val tx: TransactionTemplate,
    @Value("\${$ITEM_STORE_PROPERTY:config}")
    private val itemStore: String,
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath:}")
    private val cursorFileDirectoryPath: String,
    @Value("\${fireflyPlaidConnector2.import.daysRequested:90}")
    private val importDaysRequested: Int,
) : Runner {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun run() {
        require(itemStore == DATABASE_ITEM_STORE) { "syncMode: import needs itemStore: database" }
        val cursors = if (cursorFileDirectoryPath.isNotBlank()) {
            runBlocking { CursorManager(cursorFileDirectoryPath).readCursorMap() }
        } else {
            emptyMap()
        }
        val byToken = accountConfigs.accounts.groupBy {
            SecretValue.resolve(it.plaidItemAccessToken, it.plaidItemAccessTokenFile, "fireflyPlaidConnector2.accounts[].plaidItemAccessToken")
        }
        for ((token, configured) in byToken) {
            val linked = runBlocking { plaid.describe(token) }
            val cursor = cursors[PlaidItem.keyForAccessToken(token)]
            val itemId = tx.execute {
                val existing = items.findByPlaidItemId(linked.plaidItemId)
                val id = if (existing != null) {
                    items.updateMetadata(existing.id, linked.institutionId, linked.institutionName, importDaysRequested)
                    existing.id
                } else {
                    items.insert(
                        plaidItemId = linked.plaidItemId,
                        accessToken = token,
                        institutionId = linked.institutionId,
                        institutionName = linked.institutionName,
                        daysRequested = importDaysRequested,
                        status = if (cursor != null) ItemStatus.active else ItemStatus.pending_history,
                    )
                }
                accounts.upsert(id, linked.accounts)
                for (account in configured) {
                    accounts.setMappingByPlaidAccountId(account.plaidAccountId, account.fireflyAccountId, true)
                }
                if (cursor != null) items.setCursor(id, cursor)
                id
            }
            logger.info(
                "Imported Item …{} as {} with {} accounts ({} mapped), cursor {}",
                linked.plaidItemId.takeLast(4),
                itemId,
                linked.accounts.size,
                configured.size,
                if (cursor != null) "copied" else "not found",
            )
        }
        logger.info("Import complete")
    }
}
