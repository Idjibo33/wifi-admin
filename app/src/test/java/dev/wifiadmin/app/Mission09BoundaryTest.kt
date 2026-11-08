package dev.wifiadmin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The boundaries of Mission 09.
 *
 * The automatic refresh must not turn the application into a background
 * service, must not ask for a new permission, and must not read the
 * identifiers of the Wi-Fi network.
 *
 * The test reads the **sources**: it is a structural guarantee, independent
 * of the Gradle module graph.
 */
class Mission09BoundaryTest {

    private fun appSources(): List<File> {
        val root = File("src/main")
        assertTrue("the application sources were not found", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    private fun manifestText(): String {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("the manifest was not found", manifest.exists())
        return manifest.readText()
    }

    private fun buildFileText(): String {
        val build = File("build.gradle.kts")
        assertTrue("the build file was not found", build.exists())
        return build.readText()
    }

    @Test
    fun `the application declares no service`() {
        val manifest = manifestText()

        assertFalse(
            "Mission 09 must not add a service: the refresh belongs to the " +
                "foreground screens",
            manifest.contains("<service")
        )
    }

    @Test
    fun `the application declares no new permission`() {
        val manifest = manifestText()

        for (permission in listOf(
            "FOREGROUND_SERVICE",
            "FOREGROUND_SERVICE_DATA_SYNC",
            "RECEIVE_BOOT_COMPLETED",
            "POST_NOTIFICATIONS",
            "SCHEDULE_EXACT_ALARM",
            "WAKE_LOCK",
            "ACCESS_FINE_LOCATION",
            "ACCESS_COARSE_LOCATION",
            "NEARBY_WIFI_DEVICES",
        )) {
            assertFalse(
                "the application must not ask for $permission",
                manifest.contains(permission)
            )
        }
    }

    @Test
    fun `the application does not use WorkManager or AlarmManager`() {
        val offenders = appSources().filter { file ->
            val text = file.readText()
            text.contains("WorkManager") ||
                text.contains("PeriodicWorkRequest") ||
                text.contains("AlarmManager") ||
                text.contains("JobScheduler")
        }

        assertTrue(
            "the refresh must not be scheduled by the system, found in: " +
                offenders.map { it.path },
            offenders.isEmpty()
        )
    }

    @Test
    fun `the build declares no work or alarm dependency`() {
        val build = buildFileText()

        assertFalse(
            "the application must not depend on WorkManager",
            build.contains("androidx.work")
        )
        assertFalse(
            "the application must not depend on AlarmManager",
            build.contains("androidx.core:core-ktx:") && build.contains("alarm")
        )
    }

    @Test
    fun `the application never reads the SSID or the BSSID`() {
        // Mission 12: WifiInfo is allowed for the link rate / RSSI / band
        // only; SSID and BSSID stay forbidden (they would need location).
        val offenders = appSources().filter { file ->
            val text = file.readText()
            text.contains("getSSID") || text.contains("getBSSID")
        }

        assertTrue(
            "the SSID and the BSSID need a location permission, which the " +
                "application deliberately does not ask for; found in: " +
                offenders.map { it.path },
            offenders.isEmpty()
        )
    }

    /**
     * The source of a file, without its comments.
     *
     * The documentation mentions the forbidden capabilities on purpose, to
     * explain why they are not used: the check must look at the code.
     */
    private fun File.codeWithoutComments(): String = readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    @Test
    fun `the application never validates the network before using it`() {
        // A local network without Internet must stay usable: the capability
        // `VALIDATED` must never be a condition.
        val offenders = appSources().filter { file ->
            file.codeWithoutComments().contains("NET_CAPABILITY_VALIDATED")
        }

        assertTrue(
            "a local network without Internet can still reach the router, " +
                "found in: " + offenders.map { it.path },
            offenders.isEmpty()
        )
    }

    @Test
    fun `the application never asks for the internet capability`() {
        val offenders = appSources().filter { file ->
            file.codeWithoutComments().contains("NET_CAPABILITY_INTERNET")
        }

        assertTrue(
            "the network request must match a Wi-Fi network, with or " +
                "without Internet, found in: " + offenders.map { it.path },
            offenders.isEmpty()
        )
    }

    @Test
    fun `the adapter still exposes only read operations`() {
        val source = File("src/main/java/dev/wifiadmin/app/data/RouterAdapter.kt")
        assertTrue("the adapter source was not found", source.exists())

        val text = source.readText()
        // The methods, and the property that is not one.
        val declared = Regex("""fun\s+(\w+)\s*\(""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet() + "displayName"

        assertEquals(
            "RouterAdapter must expose read-only operations only",
            setOf("displayName", "connect", "fetchDevices", "disconnect"),
            declared
        )
    }

    @Test
    fun `the application offers no blocking action`() {
        // The two forbidden labels are built from pieces, so that this test
        // file does not itself look like an application offering them.
        val forbidden = listOf("Blo" + "quer", "Dé" + "bloquer")

        val offenders = appSources().filter { file ->
            val text = file.readText()
            forbidden.any { label -> text.contains("\"$label") }
        }

        assertTrue(
            "the application must not offer a blocking action, found in: " +
                offenders.map { it.path },
            offenders.isEmpty()
        )
    }

    @Test
    fun `the application has no remote endpoint`() {
        // Mission 12: manual links in SpeedTestPresentation are plain book-
        // marks opened by the user in a browser (never called by the app).
        val offenders = appSources().filter { file ->
            val text = file.readText()
            text.contains("https://") && !text.contains("schemas.android.com") &&
                !file.path.endsWith("SpeedTestPresentation.kt")
        }

        assertTrue(
            "no cloud service is allowed, found in: " + offenders.map { it.path },
            offenders.isEmpty()
        )
    }
}
