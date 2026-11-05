package dev.wifiadmin.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Materialises the boundary between the operational app and the analysis
 * modules.
 *
 * The firewall MAC filter is **UNAVAILABLE** on this firmware: the
 * router's own web interface receives the same empty reply as our client
 * (see `docs/FIREWALL_ANALYSIS.md`). The analysis code is kept for the
 * record, but the application must never reach it.
 *
 * This test reads the **sources** rather than the classpath: it is a
 * structural guarantee, independent of the Gradle module graph.
 */
class ArchitectureBoundaryTest {

    @Test
    fun `the app never imports the firewall package`() {
        val offenders = findInApp("dev.wifiadmin.router.firewall")

        assertTrue(
            "the firewall module is UNAVAILABLE and must not be reachable " +
                "from the application, found in: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the app never imports the wifi filter package`() {
        val offenders = findInApp("dev.wifiadmin.router.wifi")

        assertTrue(
            "the Wi-Fi filter is refused by the firmware and must not be " +
                "reachable from the application, found in: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the adapter exposes no mutating operation`() {
        val source = sourceFile("app/src/main/java/dev/wifiadmin/app/data/RouterAdapter.kt")
        assertTrue("the adapter source was not found", source.exists())

        val text = source.readText()
        val declared = Regex("""fun\s+(\w+)\s*\(""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()

        val allowed = setOf("displayName", "connect", "fetchDevices", "disconnect")
        val extra = declared - allowed

        assertTrue(
            "RouterAdapter must expose read-only operations only, found: $extra",
            extra.isEmpty(),
        )
    }

    @Test
    fun `the app never mentions a blocking action`() {
        val offenders = appSources()
            .filter { file ->
                val text = file.readText()
                text.contains("\"Bloquer") || text.contains("\"Débloquer")
            }

        assertTrue(
            "the application must not offer a blocking action, found in: " +
                offenders.map { it.path },
            offenders.isEmpty(),
        )
    }

    /** Every source file of the application, main and test. */
    private fun appSources(): List<File> {
        val root = projectRoot().resolve("app/src")
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // These files name the forbidden packages and actions on purpose:
            // they must not match themselves.
            .filterNot {
                it.name == "ArchitectureBoundaryTest.kt" ||
                    it.name == "ArchitectureBoundaryLogicTest.kt"
            }
            .toList()
    }

    private fun findInApp(importPrefix: String): List<String> =
        appSources()
            .filter { file ->
                // A real import, not a mention inside a string.
                Regex("""^\s*import\s+""" + Regex.escape(importPrefix) + """\b""", RegexOption.MULTILINE)
                    .containsMatchIn(file.readText())
            }
            .map { it.path }

    private fun sourceFile(relativePath: String): File =
        projectRoot().resolve(relativePath)

    /** The repository root, found from the test's working directory. */
    private fun projectRoot(): File {
        val start = System.getProperty("user.dir") ?: "."
        var directory: File? = File(start).absoluteFile
        while (directory != null) {
            if (File(directory, "app/build.gradle.kts").exists()) return directory
            directory = directory.parentFile
        }
        return File(start).absoluteFile
    }
}
