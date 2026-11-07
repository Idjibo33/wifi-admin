package dev.wifiadmin.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The speed test must stay separated from the router protocol and from
 * personal data. Source-boundary tests, like the Mission 08–11 ones.
 */
class Mission12BoundaryTest {

    private fun speedTestSources(): List<File> =
        File("src/main/java/dev/wifiadmin/app/data/speedtest")
            .walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() +
        File("src/main/java/dev/wifiadmin/app/ui/speedtest")
            .walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    @Test fun `speed test never imports the router client`() {
        val offenders = speedTestSources().filter {
            val t = it.readText()
            t.contains("ZltRouterClient") || t.contains("RouterTransport") ||
                t.contains("/cgi-bin/http.cgi") || t.contains("cmd 100")
        }
        assertTrue("router and Internet must stay separate: $offenders", offenders.isEmpty())
    }

    @Test fun `no real provider endpoint is hard-coded`() {
        // The data layer must not contain a measurement endpoint at all.
        val dataSources = File("src/main/java/dev/wifiadmin/app/data/speedtest")
            .walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        val offenders = dataSources.filter { it.readText().lowercase().contains("https://") }
        assertTrue("no endpoint in data layer: $offenders", offenders.isEmpty())
        // Manual browser links are plain text, never called by the app.
        val presentation = File("src/main/java/dev/wifiadmin/app/ui/speedtest/SpeedTestPresentation.kt").readText()
        assertTrue(presentation.contains("https://www.speedtest.net/"))
        assertTrue(presentation.contains("https://speed.measurementlab.net/"))
    }

    @Test fun `caps exist`() {
        val caps = speedTestSources().any {
            val t = it.readText()
            t.contains("maxDownloadBytes") && t.contains("maxUploadBytes") && t.contains("30_000")
        }
        assertTrue("caps must exist", caps)
    }

    @Test fun `no location permission request for link info`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("ACCESS_FINE_LOCATION"))
        assertFalse(manifest.contains("ACCESS_WIFI_STATE"))
    }

    @Test fun `history stores no secret`() {
        val history = File("src/main/java/dev/wifiadmin/app/data/speedtest/SpeedTestHistoryStore.kt").readText()
            .lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(history.contains("sessionId"))
        assertFalse(history.contains("password"))
        assertFalse(history.lowercase().contains("\"ssid\""))
    }

    @Test fun `navigation protects the speed test route`() {
        val session = File("src/main/java/dev/wifiadmin/app/navigation/SessionNavigation.kt").readText()
        assertTrue(session.contains("Route.SpeedTest.path"))
    }
}
