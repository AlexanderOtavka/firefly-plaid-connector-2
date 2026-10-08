package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CursorManagerTest {

    @Test
    fun `test read and write cursor map`(@TempDir tempDir: Path) = runBlocking {
        // Setup
        val cursorManager = CursorManager(tempDir.toString())
        
        // Initial map should be empty
        val initialMap = cursorManager.readCursorMap()
        assertEquals<Map<String, String>>(emptyMap(), initialMap)
        
        // Write some data
        val testMap = mutableMapOf(
            "item-1" to "cursor_1",
            "item-2" to "cursor_2"
        )
        cursorManager.writeCursorMap(testMap)
        
        // Read it back and verify
        val readMap = cursorManager.readCursorMap()
        assertEquals<Map<String, String>>(testMap, readMap)
    }
    
    @Test
    fun `test write cursor map filters empty cursors`(@TempDir tempDir: Path) = runBlocking {
        // Setup
        val cursorManager = CursorManager(tempDir.toString())
        
        // Map with empty cursor
        val testMap = mutableMapOf(
            "item-1" to "cursor_1",
            "item-2" to "" // Empty cursor should be filtered out
        )
        
        // Write the map
        cursorManager.writeCursorMap(testMap)
        
        // Read it back and verify empty cursor was filtered
        val readMap = cursorManager.readCursorMap()
        assertEquals(1, readMap.size)
        assertEquals("cursor_1", readMap["item-1"])
        assertEquals(null, readMap["item-2"])
    }

    @Test
    fun `migrates legacy access-token cursor keys before rewriting`(@TempDir tempDir: Path) = runBlocking {
        val cursorManager = CursorManager(tempDir.toString())
        val accessToken = "access-production-sensitive-value"
        Files.writeString(cursorManager.cursorFilePath, "$accessToken|cursor_1")

        val migrated = cursorManager.readCursorMap()
        cursorManager.writeCursorMap(migrated)

        val persisted = Files.readString(cursorManager.cursorFilePath)
        assertEquals("cursor_1", migrated[PlaidItem.keyForAccessToken(accessToken)])
        assertFalse(persisted.contains(accessToken))
        assertEquals(
            "${PlaidItem.keyForAccessToken(accessToken)}|cursor_1",
            persisted,
        )
    }

    @Test
    fun `writes owner-only cursor permissions on POSIX filesystems`(@TempDir tempDir: Path) = runBlocking {
        val cursorManager = CursorManager(tempDir.toString())

        cursorManager.writeCursorMap(mapOf("item-1" to "cursor_1"))

        try {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(cursorManager.cursorFilePath),
            )
        } catch (_: UnsupportedOperationException) {
            // The production container and NixOS use POSIX filesystems.
        }
    }
}