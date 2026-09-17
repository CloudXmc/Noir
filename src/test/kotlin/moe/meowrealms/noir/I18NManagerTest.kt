package moe.meowrealms.noir

import moe.meowrealms.noir.i18n.I18NManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class I18NManagerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `upgrade supplements missing language keys without overwriting owner text`() {
        val languageDirectory = temporaryDirectory.resolve("lang")
        Files.createDirectories(languageDirectory)
        val languageFile = languageDirectory.resolve("zh_CN.lang")
        Files.writeString(languageFile, "noir.prefix=<green>[服主前缀]</green> ")

        val manager = I18NManager()
        manager.initialize(temporaryDirectory, "zh_CN")
        manager.initialize(temporaryDirectory, "zh_CN")

        val lines = Files.readAllLines(languageFile)
        assertEquals("noir.prefix=<green>[服主前缀]</green> ", lines.first())
        assertTrue(lines.any { it.startsWith("noir.announcement.sync_success=") })
        assertEquals(1, lines.count { it.startsWith("noir.announcement.sync_success=") })
    }
}
