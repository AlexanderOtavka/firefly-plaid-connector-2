package net.djvk.fireflyPlaidConnector2.manage.db

import net.djvk.fireflyPlaidConnector2.sync.ITEM_STORE_PROPERTY
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = DATABASE_ITEM_STORE)
class AccountRepository(private val jdbc: JdbcClient) {
    fun forItem(itemId: Long): List<PlaidAccountRow> =
        jdbc.sql("SELECT * FROM plaid_account WHERE item_id = :itemId ORDER BY name, id")
            .param("itemId", itemId)
            .query(mapper)
            .list()

    fun all(): List<PlaidAccountRow> =
        jdbc.sql("SELECT * FROM plaid_account ORDER BY item_id, name, id")
            .query(mapper)
            .list()

    /** Enabled, mapped accounts of Items in [statuses], optionally restricted to some ids. */
    fun enabledFor(
        statuses: Collection<ItemStatus>,
        requireCursor: Boolean,
        itemIds: Collection<Long>?,
        accountIds: Collection<Long>?,
    ): List<PlaidAccountRow> {
        val sql = StringBuilder(
            """
            SELECT a.* FROM plaid_account a JOIN plaid_item i ON i.id = a.item_id
             WHERE a.enabled AND a.firefly_account_id IS NOT NULL AND i.status IN (:statuses)
            """.trimIndent()
        )
        if (requireCursor) sql.append(" AND i.sync_cursor IS NOT NULL")
        if (itemIds != null) sql.append(" AND i.id IN (:itemIds)")
        if (accountIds != null) sql.append(" AND a.id IN (:accountIds)")
        sql.append(" ORDER BY a.item_id, a.id")

        var spec = jdbc.sql(sql.toString()).param("statuses", statuses.map { it.name })
        if (itemIds != null) spec = spec.param("itemIds", itemIds.ifEmpty { listOf(-1L) })
        if (accountIds != null) spec = spec.param("accountIds", accountIds.ifEmpty { listOf(-1L) })
        return spec.query(mapper).list()
    }

    /** Inserts accounts returned by `/accounts/get`, or refreshes their metadata if already known. */
    fun upsert(itemId: Long, accounts: List<NewPlaidAccount>) {
        for (account in accounts) {
            jdbc.sql(
                """
                INSERT INTO plaid_account (item_id, plaid_account_id, persistent_account_id, name, mask, type, subtype)
                VALUES (:itemId, :plaidAccountId, :persistentAccountId, :name, :mask, :type, :subtype)
                ON CONFLICT (plaid_account_id) DO UPDATE
                   SET persistent_account_id = EXCLUDED.persistent_account_id, name = EXCLUDED.name,
                       mask = EXCLUDED.mask, type = EXCLUDED.type, subtype = EXCLUDED.subtype
                """.trimIndent()
            )
                .param("itemId", itemId)
                .param("plaidAccountId", account.plaidAccountId)
                .param("persistentAccountId", account.persistentAccountId)
                .param("name", account.name)
                .param("mask", account.mask)
                .param("type", account.type)
                .param("subtype", account.subtype)
                .update()
        }
    }

    fun setMapping(accountId: Long, itemId: Long, fireflyAccountId: Int?, enabled: Boolean) {
        val updated = jdbc.sql(
            """
            UPDATE plaid_account SET firefly_account_id = :fireflyAccountId, enabled = :enabled
             WHERE id = :id AND item_id = :itemId
            """.trimIndent()
        )
            .param("id", accountId)
            .param("itemId", itemId)
            .param("fireflyAccountId", fireflyAccountId)
            .param("enabled", enabled)
            .update()
        require(updated == 1) { "Account $accountId does not belong to Item $itemId" }
    }

    fun setMappingByPlaidAccountId(plaidAccountId: String, fireflyAccountId: Int, enabled: Boolean) {
        jdbc.sql(
            "UPDATE plaid_account SET firefly_account_id = :fireflyAccountId, enabled = :enabled WHERE plaid_account_id = :plaidAccountId"
        )
            .param("plaidAccountId", plaidAccountId)
            .param("fireflyAccountId", fireflyAccountId)
            .param("enabled", enabled)
            .update()
    }

    fun disableAllFor(itemId: Long) {
        jdbc.sql("UPDATE plaid_account SET enabled = false WHERE item_id = :itemId")
            .param("itemId", itemId)
            .update()
    }

    companion object {
        val mapper = RowMapper { rs, _ ->
            PlaidAccountRow(
                id = rs.getLong("id"),
                itemId = rs.getLong("item_id"),
                plaidAccountId = rs.getString("plaid_account_id"),
                persistentAccountId = rs.getString("persistent_account_id"),
                name = rs.getString("name"),
                mask = rs.getString("mask"),
                type = rs.getString("type"),
                subtype = rs.getString("subtype"),
                fireflyAccountId = rs.getInt("firefly_account_id").takeUnless { rs.wasNull() },
                enabled = rs.getBoolean("enabled"),
            )
        }
    }
}
