package net.djvk.fireflyPlaidConnector2.sync

import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
import net.djvk.fireflyPlaidConnector2.transactions.FireflyAccountId
import net.djvk.fireflyPlaidConnector2.transactions.PLAID_EXTERNAL_ID_PREFIX
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Local change: lists the transactions the connector has already imported into some
 * Firefly accounts, for [net.djvk.fireflyPlaidConnector2.transactions.BackfillReconciler].
 *
 * Firefly cannot search by `external_id`, so this pages through each account's transactions
 * in the date range and keeps the single-split ones with a `plaid-` `external_id`. A transfer
 * between two of the accounts is listed under both and returned once.
 */
class ImportedTransactionFetcher(
    private val accountsApi: AccountsApi,
    private val pageSize: Int = 500,
    private val maxPagesPerAccount: Int = 1000,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun fetch(
        accounts: Set<FireflyAccountId>,
        start: LocalDate,
        end: LocalDate,
    ): List<TransactionRead> {
        val byId = LinkedHashMap<String, TransactionRead>()
        for (account in accounts.sorted()) {
            var page = 1
            var listed = 0
            do {
                val body = accountsApi.listTransactionByAccount(
                    account.toString(),
                    page,
                    pageSize,
                    start,
                    end,
                    TransactionTypeFilter.all,
                ).body()
                listed += body.data.size
                for (tx in body.data) {
                    val split = tx.attributes.transactions.singleOrNull() ?: continue
                    if (split.externalId?.startsWith(PLAID_EXTERNAL_ID_PREFIX) == true) {
                        byId.putIfAbsent(tx.id, tx)
                    }
                }
                val totalPages = body.meta.pagination?.totalPages ?: 1
                page++
                if (page > maxPagesPerAccount) {
                    throw IllegalStateException(
                        "Firefly account $account has more than $maxPagesPerAccount pages of transactions"
                    )
                }
            } while (page <= totalPages)
            logger.info("Listed {} Firefly transactions in account {} from {} through {}", listed, account, start, end)
        }
        logger.info("Found {} connector imports to match against", byId.size)
        return byId.values.toList()
    }
}
