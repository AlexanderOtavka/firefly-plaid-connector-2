package net.djvk.fireflyPlaidConnector2.sync

/**
 * Persistence for Plaid `/transactions/sync` cursors.
 *
 * Local change: [CursorManager] (a file) is the upstream store and stays the default. The
 * database item store keeps each cursor on its Item's row instead.
 */
interface CursorStore {
    suspend fun readCursorMap(): MutableMap<PlaidItemKey, PlaidSyncCursor>

    suspend fun writeCursorMap(map: Map<PlaidItemKey, PlaidSyncCursor>)
}
