package net.djvk.fireflyPlaidConnector2.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SecretValueTest {
    @Test
    fun `resolves and trims a file-backed secret`(@TempDir tempDir: Path) {
        val secretFile = tempDir.resolve("secret")
        Files.writeString(secretFile, "super-secret\n")

        val value = SecretValue.resolve("", secretFile.toString(), "example.secret")

        assertThat(value).isEqualTo("super-secret")
    }

    @Test
    fun `rejects ambiguous secret sources`(@TempDir tempDir: Path) {
        val secretFile = tempDir.resolve("secret")
        Files.writeString(secretFile, "file-secret")

        assertThatThrownBy {
            SecretValue.resolve("inline-secret", secretFile.toString(), "example.secret")
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Configure exactly one of example.secret or example.secretFile")
            .hasMessageNotContaining("inline-secret")
            .hasMessageNotContaining("file-secret")
    }

    @Test
    fun `rejects an empty secret file without exposing its path`(@TempDir tempDir: Path) {
        val secretFile = tempDir.resolve("secret")
        Files.writeString(secretFile, "\n")

        assertThatThrownBy {
            SecretValue.resolve("", secretFile.toString(), "example.secret")
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Secret file configured by example.secretFile is empty")
            .hasMessageNotContaining(secretFile.toString())
    }
}
