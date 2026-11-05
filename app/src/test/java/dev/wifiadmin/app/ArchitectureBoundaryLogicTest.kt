package dev.wifiadmin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the logic of [ArchitectureBoundaryTest] itself.
 *
 * A boundary test that cannot fail is worthless: this checks that the
 * detection actually works on a synthetic violation.
 */
class ArchitectureBoundaryLogicTest {

    @Test
    fun `the adapter scan detects a mutating operation`() {
        val source = """
            interface RouterAdapter {
                val displayName: String
                suspend fun connect(host: String, username: String, password: String): Boolean
                suspend fun fetchDevices(): DeviceSnapshot
                fun blockDevice(mac: String): Boolean
                fun disconnect()
            }
        """.trimIndent()

        val declared = Regex("""fun\s+(\w+)\s*\(""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()

        assertTrue("the scan must find the injected method", "blockDevice" in declared)
        assertFalse(
            "the scan must report the violation",
            (declared - setOf("displayName", "connect", "fetchDevices", "disconnect")).isEmpty(),
        )
    }

    @Test
    fun `the adapter scan accepts the current surface`() {
        val source = """
            interface RouterAdapter {
                val displayName: String
                suspend fun connect(host: String, username: String, password: String): Boolean
                suspend fun fetchDevices(): DeviceSnapshot
                fun disconnect()
            }
        """.trimIndent()

        val declared = Regex("""fun\s+(\w+)\s*\(""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(emptySet<String>(), declared - setOf("displayName", "connect", "fetchDevices", "disconnect"))
    }

    @Test
    fun `the import scan detects a real import and ignores a mention`() {
        val imported = """
            import dev.wifiadmin.router.firewall.FirewallMacFilterClient
            class A
        """.trimIndent()
        val mentioned = """
            // see dev.wifiadmin.router.firewall for the analysis code
            class B
        """.trimIndent()

        val pattern = Regex(
            """^\s*import\s+""" + Regex.escape("dev.wifiadmin.router.firewall") + """\b""",
            RegexOption.MULTILINE,
        )

        assertTrue("a real import must be detected", pattern.containsMatchIn(imported))
        assertFalse("a mere mention must not be detected", pattern.containsMatchIn(mentioned))
    }
}
