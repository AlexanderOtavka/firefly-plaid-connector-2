package net.djvk.fireflyPlaidConnector2.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves a secret from either an inline configuration value or a file.
 *
 * File-backed values are intended for container secrets and NixOS-managed
 * credentials. Exactly one source must be configured.
 */
object SecretValue {
    fun resolve(
        inlineValue: String?,
        filePath: String?,
        propertyName: String,
    ): String {
        val inline = inlineValue?.trim()?.takeIf { it.isNotEmpty() }
        val file = filePath?.trim()?.takeIf { it.isNotEmpty() }

        require((inline == null) != (file == null)) {
            "Configure exactly one of $propertyName or ${propertyName}File"
        }

        if (inline != null) {
            return inline
        }

        val value = Files.readString(Path.of(file!!)).trim()
        require(value.isNotEmpty()) {
            "Secret file configured by ${propertyName}File is empty"
        }
        return value
    }
}
