package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.Path

/**
 * Manages the Plaid sync cursors used for transaction synchronization.
 */
@Component
@ConditionalOnProperty(name = [ITEM_STORE_PROPERTY], havingValue = "config", matchIfMissing = true)
class CursorManager(
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath}")
    private val cursorFileDirectoryPath: String,
) : CursorStore {
    private val logger = LoggerFactory.getLogger(this::class.java)
    val cursorFilePath = Path("$cursorFileDirectoryPath/plaid_sync_cursors.txt")

    /**
     * Reads the cursor map from file storage, if it exists.
     * If it doesn't exist, returns an empty map.
     */
    override suspend fun readCursorMap(): MutableMap<PlaidItemKey, PlaidSyncCursor> {
        return withContext(Dispatchers.IO) {
            val file = cursorFilePath.toFile()
            logger.trace("Reading Plaid sync cursor map from $file")

            if (!file.exists()) {
                logger.trace("No existing Plaid sync cursor map found, starting from scratch")
                return@withContext mutableMapOf()
            }

            file
                .readLines()
                .associate { line ->
                    val parts = line.split("|", limit = 2)
                    require(parts.size == 2) {
                        "Invalid Plaid cursor file entry"
                    }
                    val storedKey = parts[0]
                    val safeKey = if (storedKey.startsWith("access-")) {
                        PlaidItem.keyForAccessToken(storedKey)
                    } else {
                        storedKey
                    }
                    Pair(safeKey, parts[1])
                }
                .toMutableMap()
        }
    }

    /**
     * Writes the cursor map to file storage.
     */
    override suspend fun writeCursorMap(map: Map<PlaidItemKey, PlaidSyncCursor>) {
        logger.trace("Writing ${map.size} Plaid sync cursors to map $cursorFilePath")
        return withContext(Dispatchers.IO) {
            Files.createDirectories(cursorFilePath.parent)
            val temporaryFile = Files.createTempFile(
                cursorFilePath.parent,
                "plaid_sync_cursors",
                ".tmp",
            )
            try {
                Files.writeString(
                    temporaryFile,
                    map.entries
                        .filter { it.value.isNotEmpty() }
                        .joinToString("\n") { (itemKey, cursor) ->
                            "$itemKey|$cursor"
                        },
                )
                try {
                    Files.setPosixFilePermissions(
                        temporaryFile,
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                        ),
                    )
                } catch (_: UnsupportedOperationException) {
                    // Non-POSIX filesystems do not expose Unix mode bits.
                }
                try {
                    Files.move(
                        temporaryFile,
                        cursorFilePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporaryFile,
                        cursorFilePath,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
        }
    }
}
