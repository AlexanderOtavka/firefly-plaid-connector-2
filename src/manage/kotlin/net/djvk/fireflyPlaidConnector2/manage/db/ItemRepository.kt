package net.djvk.fireflyPlaidConnector2.manage.db

import net.djvk.fireflyPlaidConnector2.sync.ITEM_STORE_PROPERTY
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate

/**
 * Plaid Items.
 *
 * Token hygiene: [PlaidItemRow] has no access token field, and [accessTokenFor] is the only
 * method that reads the column. Only code that calls Plaid on an Item's behalf may call it;
 * nothing it returns may reach a response, a log line, or a template.
 */
@Repository
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = DATABASE_ITEM_STORE)
class ItemRepository(private val jdbc: JdbcClient) {
    fun list(): List<PlaidItemRow> =
        jdbc.sql("SELECT $COLUMNS FROM plaid_item ORDER BY status = 'retired', linked_at DESC, id DESC")
            .query(mapper)
            .list()

    fun find(id: Long): PlaidItemRow? =
        jdbc.sql("SELECT $COLUMNS FROM plaid_item WHERE id = :id")
            .param("id", id)
            .query(mapper)
            .optional()
            .orElse(null)

    fun findByPlaidItemId(plaidItemId: String): PlaidItemRow? =
        jdbc.sql("SELECT $COLUMNS FROM plaid_item WHERE plaid_item_id = :plaidItemId")
            .param("plaidItemId", plaidItemId)
            .query(mapper)
            .optional()
            .orElse(null)

    /** Items with the given statuses. */
    fun withStatus(vararg statuses: ItemStatus): List<PlaidItemRow> =
        jdbc.sql("SELECT $COLUMNS FROM plaid_item WHERE status IN (:statuses) ORDER BY id")
            .param("statuses", statuses.map { it.name })
            .query(mapper)
            .list()

    /** The Item that [replacementId] was linked to replace, if any. */
    fun predecessorOf(replacementId: Long): PlaidItemRow? =
        jdbc.sql("SELECT $COLUMNS FROM plaid_item WHERE replaced_by = :id AND status <> 'retired' ORDER BY id LIMIT 1")
            .param("id", replacementId)
            .query(mapper)
            .optional()
            .orElse(null)

    /**
     * The only reader of `access_token`. Returns null for a retired Item.
     */
    fun accessTokenFor(id: Long): String? =
        jdbc.sql("SELECT access_token FROM plaid_item WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun insert(
        plaidItemId: String,
        accessToken: String,
        institutionId: String?,
        institutionName: String?,
        daysRequested: Int,
        status: ItemStatus,
    ): Long {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """
            INSERT INTO plaid_item (plaid_item_id, access_token, institution_id, institution_name, days_requested, status)
            VALUES (:plaidItemId, :accessToken, :institutionId, :institutionName, :daysRequested, :status)
            """.trimIndent()
        )
            .param("plaidItemId", plaidItemId)
            .param("accessToken", accessToken)
            .param("institutionId", institutionId)
            .param("institutionName", institutionName)
            .param("daysRequested", daysRequested)
            .param("status", status.name)
            .update(keys, "id")
        return keys.key!!.toLong()
    }

    /**
     * Refreshes an imported Item's metadata. The access token is not rewritten: a token only
     * enters the database on first insert, from a Plaid exchange or from the import.
     */
    fun updateMetadata(id: Long, institutionId: String?, institutionName: String?, daysRequested: Int) {
        jdbc.sql(
            """
            UPDATE plaid_item
               SET institution_id = :institutionId, institution_name = :institutionName,
                   days_requested = :daysRequested
             WHERE id = :id
            """.trimIndent()
        )
            .param("id", id)
            .param("institutionId", institutionId)
            .param("institutionName", institutionName)
            .param("daysRequested", daysRequested)
            .update()
    }

    fun updateInstitution(id: Long, institutionId: String?, institutionName: String?) {
        jdbc.sql("UPDATE plaid_item SET institution_id = :institutionId, institution_name = :institutionName WHERE id = :id")
            .param("id", id)
            .param("institutionId", institutionId)
            .param("institutionName", institutionName)
            .update()
    }

    fun setReplacedBy(id: Long, replacementId: Long) {
        jdbc.sql("UPDATE plaid_item SET replaced_by = :replacementId WHERE id = :id")
            .param("id", id)
            .param("replacementId", replacementId)
            .update()
    }

    /** Stores the first cursor for an Item whose history is complete, and starts polling it. */
    fun activateWithCursor(id: Long, cursor: String) {
        jdbc.sql(
            """
            UPDATE plaid_item SET sync_cursor = :cursor, status = 'active', polling_since = now()
             WHERE id = :id AND status = 'pending_history'
            """.trimIndent()
        )
            .param("id", id)
            .param("cursor", cursor)
            .update()
    }

    /** Sets the cursor of an imported Item, without changing its status. */
    fun setCursor(id: Long, cursor: String) {
        jdbc.sql(
            "UPDATE plaid_item SET sync_cursor = :cursor, polling_since = COALESCE(polling_since, now()) " +
                "WHERE id = :id AND status <> 'retired'"
        )
            .param("id", id)
            .param("cursor", cursor)
            .update()
    }

    fun setStatus(id: Long, status: ItemStatus) {
        jdbc.sql("UPDATE plaid_item SET status = :status WHERE id = :id AND status <> 'retired'")
            .param("id", id)
            .param("status", status.name)
            .update()
    }

    /** After a successful Link update-mode session the Item can be polled again. */
    fun markRepaired(id: Long) {
        jdbc.sql(
            """
            UPDATE plaid_item
               SET status = CASE WHEN sync_cursor IS NULL THEN 'pending_history' ELSE 'active' END,
                   consecutive_failures = 0, last_error_code = NULL, last_error_message = NULL,
                   last_error_at = NULL
             WHERE id = :id AND status IN ('login_required', 'error', 'active')
            """.trimIndent()
        )
            .param("id", id)
            .update()
    }

    fun retire(id: Long) {
        jdbc.sql(
            """
            UPDATE plaid_item
               SET status = 'retired', access_token = NULL, sync_cursor = NULL, retired_at = now()
             WHERE id = :id
            """.trimIndent()
        )
            .param("id", id)
            .update()
    }

    fun cursors(): Map<Long, String> =
        jdbc.sql("SELECT id, sync_cursor FROM plaid_item WHERE sync_cursor IS NOT NULL AND status <> 'retired'")
            .query { rs, _ -> rs.getLong("id") to rs.getString("sync_cursor") }
            .list()
            .toMap()

    fun recordSuccess(id: Long, added: Int, addedDates: ClosedRange<LocalDate>? = null) {
        jdbc.sql(
            """
            UPDATE plaid_item
               SET last_sync_at = now(), last_sync_added = :added, consecutive_failures = 0,
                   last_error_code = NULL, last_error_message = NULL, last_error_at = NULL,
                   status = CASE WHEN status IN ('login_required', 'error') THEN 'active' ELSE status END,
                   $WIDEN_TX_DATES
             WHERE id = :id AND status <> 'retired'
            """.trimIndent()
        )
            .param("id", id)
            .param("added", added)
            .param("earliest", addedDates?.start?.let { Date.valueOf(it) }, Types.DATE)
            .param("latest", addedDates?.endInclusive?.let { Date.valueOf(it) }, Types.DATE)
            .update()
    }

    /** Widens the Item's synced transaction date range to include [dates]. */
    fun widenTxDates(id: Long, dates: ClosedRange<LocalDate>) {
        jdbc.sql("UPDATE plaid_item SET $WIDEN_TX_DATES WHERE id = :id")
            .param("id", id)
            .param("earliest", Date.valueOf(dates.start), Types.DATE)
            .param("latest", Date.valueOf(dates.endInclusive), Types.DATE)
            .update()
    }

    /**
     * For each Firefly account, the earliest transaction date synced from Plaid by any Item with
     * an account mapped to it, replaced and retired Items included (they keep their mapping).
     * Everything from that date on came from Plaid, so a file import must stop before it. An
     * Item records one range for all its accounts, so for an Item with several accounts the date
     * can be earlier than that account's own oldest import, never later.
     */
    fun earliestTxDateByFireflyAccount(): Map<Int, LocalDate> =
        jdbc.sql(
            """
            SELECT a.firefly_account_id, MIN(i.earliest_tx_date) AS earliest_tx_date
              FROM plaid_account a JOIN plaid_item i ON i.id = a.item_id
             WHERE a.firefly_account_id IS NOT NULL AND i.earliest_tx_date IS NOT NULL
             GROUP BY a.firefly_account_id
            """.trimIndent()
        )
            .query { rs, _ ->
                rs.getInt("firefly_account_id") to rs.getObject("earliest_tx_date", Date::class.java).toLocalDate()
            }
            .list()
            .toMap()

    fun recordFailure(id: Long, code: String?, message: String?) {
        jdbc.sql(
            """
            UPDATE plaid_item
               SET last_error_code = :code, last_error_message = :message, last_error_at = now(),
                   consecutive_failures = consecutive_failures + 1,
                   status = CASE
                       WHEN status = 'pending_history' THEN status
                       WHEN :code = 'ITEM_LOGIN_REQUIRED' THEN 'login_required'
                       WHEN consecutive_failures + 1 >= :errorThreshold THEN 'error'
                       ELSE status
                   END
             WHERE id = :id AND status <> 'retired'
            """.trimIndent()
        )
            .param("id", id)
            .param("code", code ?: "UNKNOWN")
            .param("message", Redaction.redact(message))
            .param("errorThreshold", ERROR_THRESHOLD)
            .update()
    }

    companion object {
        /** Consecutive failures, other than ITEM_LOGIN_REQUIRED, before an Item shows as `error`. */
        const val ERROR_THRESHOLD = 3

        /** LEAST and GREATEST ignore NULLs, so a NULL parameter leaves its bound alone. */
        private const val WIDEN_TX_DATES = """
            earliest_tx_date = LEAST(earliest_tx_date, CAST(:earliest AS DATE)),
            latest_tx_date = GREATEST(latest_tx_date, CAST(:latest AS DATE))
        """

        private const val COLUMNS = """
            id, plaid_item_id, institution_id, institution_name, days_requested, linked_at, status,
            replaced_by, retired_at, sync_cursor IS NOT NULL AS has_cursor, polling_since, last_sync_at,
            last_sync_added, last_error_code, last_error_message, last_error_at, consecutive_failures,
            earliest_tx_date, latest_tx_date
        """

        private fun ResultSet.instant(column: String): Instant? = getObject(column, Timestamp::class.java)?.toInstant()

        private fun ResultSet.nullableInt(column: String): Int? = getInt(column).takeUnless { wasNull() }

        private fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

        val mapper = RowMapper { rs, _ ->
            PlaidItemRow(
                id = rs.getLong("id"),
                plaidItemId = rs.getString("plaid_item_id"),
                institutionId = rs.getString("institution_id"),
                institutionName = rs.getString("institution_name"),
                daysRequested = rs.getInt("days_requested"),
                linkedAt = rs.instant("linked_at")!!,
                status = ItemStatus.valueOf(rs.getString("status")),
                replacedBy = rs.nullableLong("replaced_by"),
                retiredAt = rs.instant("retired_at"),
                hasCursor = rs.getBoolean("has_cursor"),
                pollingSince = rs.instant("polling_since"),
                lastSyncAt = rs.instant("last_sync_at"),
                lastSyncAdded = rs.nullableInt("last_sync_added"),
                lastErrorCode = rs.getString("last_error_code"),
                lastErrorMessage = rs.getString("last_error_message"),
                lastErrorAt = rs.instant("last_error_at"),
                consecutiveFailures = rs.getInt("consecutive_failures"),
                earliestTxDate = rs.getObject("earliest_tx_date", Date::class.java)?.toLocalDate(),
                latestTxDate = rs.getObject("latest_tx_date", Date::class.java)?.toLocalDate(),
            )
        }
    }
}
