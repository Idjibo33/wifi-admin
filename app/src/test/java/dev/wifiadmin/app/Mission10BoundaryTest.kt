package dev.wifiadmin.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The boundaries of the Mission 10.
 *
 * The tests read the **sources** rather than the classpath: they are
 * structural guarantees, independent of the Gradle module graph.
 *
 * The custom names are **local data**. The application must never send them
 * to the router, never log them, and never use them as a key that could
 * collide.
 */
class Mission10BoundaryTest {

    /**
     * The root of the project.
     *
     * The resolution is robust: the working directory differs between the
     * IDE, the Gradle build and a test run.
     */
    private fun projectRoot(): File {
        var directory = File("").absoluteFile
        while (directory.parentFile != null) {
            if (File(directory, "app/build.gradle.kts").exists()) return directory
            directory = directory.parentFile
        }
        throw AssertionError("the project root was not found")
    }

    private fun appSources(): List<File> {
        val root = File(projectRoot(), "app/src/main")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    private fun sourceOf(relativePath: String): String {
        val file = File(projectRoot(), relativePath)
        assertTrue("the source was not found: $relativePath", file.exists())
        return file.readText()
    }

    // -----------------------------------------------------------------
    // The router is never written to.
    // -----------------------------------------------------------------

    @Test
    fun `the adapter still exposes only read operations`() {
        val source = sourceOf("app/src/main/java/dev/wifiadmin/app/data/RouterAdapter.kt")

        val declared = Regex("""fun\s+(\w+)\s*\(""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet() + "displayName"

        assertTrue(
            "RouterAdapter must expose read-only operations only, found: $declared",
            declared == setOf("displayName", "connect", "fetchDevices", "disconnect")
        )
    }

    @Test
    fun `the custom names never reach the router layer`() {
        // The metadata are written through the local store only. The
        // repository must never hand a name to the adapter.
        val repository = sourceOf(
            "app/src/main/java/dev/wifiadmin/app/data/RouterRepository.kt"
        )

        // Every call of the adapter that is not one of the three read
        // operations would be a write. The check is on the **shape** of
        // the call, not on a list of names: a method that nobody thought
        // of would be caught too.
        val calls = Regex("""adapter\s*\.\s*(\w+)\s*\(""")
            .findAll(repository)
            .map { it.groupValues[1] }
            .toSet()

        val allowed = setOf("connect", "fetchDevices", "disconnect")
        val extra = calls - allowed

        assertTrue(
            "the repository must only read through the adapter, found: $extra",
            extra.isEmpty()
        )
    }

    @Test
    fun `the custom names are never logged`() {
        // A custom name is personal data. It must never reach a log — in
        // **any** file of the application, not only in the files of the
        // feature.
        val offenders = appSources().filter { file ->
            val text = file.readText()
            Regex("""\bLog\.""").containsMatchIn(text) ||
                Regex("""\bprintln\(""").containsMatchIn(text) ||
                Regex("""\bSystem\.out\b""").containsMatchIn(text) ||
                Regex("""\bTimber\.""").containsMatchIn(text)
        }

        assertTrue(
            "the application must never write a log, found in: $offenders",
            offenders.isEmpty()
        )
    }

    // -----------------------------------------------------------------
    // The isolation
    // -----------------------------------------------------------------

    @Test
    fun `the custom names are stored in the no-backup directory`() {
        val application = sourceOf(
            "app/src/main/java/dev/wifiadmin/app/WifiAdminApplication.kt"
        )

        // The store is built from a directory that is **inside**
        // `noBackupFilesDir`, not from a sibling of it: a store built
        // elsewhere would be restored on another phone.
        val storeDeclaration = Regex(
            """val\s+deviceMetadataStore[^=]*=\s*DeviceMetadataStore\(([^)]*)\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(application)

        assertTrue(
            "the metadata store must be built in the container",
            storeDeclaration != null
        )
        assertTrue(
            "the metadata store must live in the no-backup directory, found: " +
                storeDeclaration!!.groupValues[1],
            storeDeclaration.groupValues[1].contains("noBackupFilesDir")
        )
        assertTrue(
            "the metadata store must have its own directory",
            storeDeclaration.groupValues[1].contains("device-metadata")
        )
    }

    @Test
    fun `the metadata are never written into the snapshot file`() {
        // The snapshot format is a delivered contract of the Mission 08: it
        // must not change.
        val snapshotStore = sourceOf(
            "app/src/main/java/dev/wifiadmin/app/data/SnapshotStore.kt"
        )

        assertFalse(
            "the snapshot file must not carry the custom names",
            snapshotStore.contains("customName")
        )
        assertFalse(
            "the snapshot file must not carry the last observation",
            snapshotStore.contains("lastSeen")
        )
    }

    @Test
    fun `the metadata context carries the three parts of the router identity`() {
        val store = sourceOf(
            "app/src/main/java/dev/wifiadmin/app/data/DeviceMetadataStore.kt"
        )

        // The host, the account and the network: without the network, the
        // pair alone is the default of every home router.
        assertTrue(
            "the context must carry the host",
            store.contains("val host: String")
        )
        assertTrue(
            "the context must carry the account",
            store.contains("val account: String")
        )
        assertTrue(
            "the context must carry the network identity",
            store.contains("val networkId: String")
        )
    }

    // -----------------------------------------------------------------
    // The local filtering
    // -----------------------------------------------------------------

    @Test
    fun `the filtering never triggers a read`() {
        val viewModel = sourceOf(
            "app/src/main/java/dev/wifiadmin/app/ui/devices/DevicesViewModel.kt"
        )

        // The three local operations must not touch the repository.
        val localOperations = listOf(
            "fun setQuery",
            "fun setFilter",
            "fun setSort",
        )

        localOperations.forEach { signature ->
            val start = viewModel.indexOf(signature)
            assertTrue("the operation was not found: $signature", start >= 0)

            // The body of the function, up to its closing brace.
            val body = viewModel.substring(start, minOf(start + 400, viewModel.length))
            val end = body.indexOf("\n    }")
            val function = if (end > 0) body.substring(0, end) else body

            listOf("repository.", "refresh", "coordinator.").forEach { forbidden ->
                assertFalse(
                    "the $signature must be purely local, it must not use $forbidden",
                    function.contains(forbidden)
                )
            }
        }
    }

    @Test
    fun `the custom name never becomes a compose key`() {
        val screen = sourceOf(
            "app/src/main/java/dev/wifiadmin/app/ui/devices/DevicesScreen.kt"
        )

        // Two devices may carry the same custom name: the key of the list
        // must not be the name, or Compose would crash with a duplicated
        // key.
        val keyLine = Regex("""key\s*=\s*\{([^}]*)\}""").find(screen)
        assertTrue("the key of the list was not found", keyLine != null)
        assertFalse(
            "the compose key must not use the custom name",
            keyLine!!.groupValues[1].contains("customName")
        )
        assertFalse(
            "the compose key must not use the display name",
            keyLine!!.groupValues[1].contains("displayName")
        )
    }

    // -----------------------------------------------------------------
    // The system is not asked to do the work
    // -----------------------------------------------------------------

    @Test
    fun `the purge is not a system job`() {
        val forbidden = listOf(
            "WorkManager",
            "PeriodicWorkRequest",
            "AlarmManager",
            "JobScheduler",
            "WorkRequest",
        )

        val offenders = appSources().filter { file ->
            val text = file.readText()
            forbidden.any { it in text }
        }

        assertTrue(
            "the purge must be triggered by the application, found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the last observation never comes from the wifi framework`() {
        // SSID/BSSID stay forbidden; WifiInfo for link rate is allowed in
        // the speedtest provider (no location permission needed).
        val forbidden = listOf(
            "getSSID",
            "getBSSID",
        )

        val offenders = appSources().filter { file ->
            val text = file.readText()
            forbidden.any { it in text }
        }

        assertTrue(
            "the observation must come from the router, not from the " +
                "framework, found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the application has no remote endpoint`() {
        // Mission 12 manual links (SpeedTestPresentation) are never called.
        val offenders = appSources().filter { file ->
            val text = file.readText()
            text.contains("https://") && !text.contains("schemas.android.com") &&
                !file.path.endsWith("SpeedTestPresentation.kt")
        }

        assertTrue(
            "the application must not call a remote service, found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the custom names never reach a path or an intent`() {
        // The name is data, never a fragment of a path, an intent or an
        // HTML document.
        val forbidden = listOf(
            "Uri.parse",
            "Uri.Builder",
            "Intent(",
            "WebView",
            "HtmlCompat",
        )

        val featureFiles = appSources().filter { file ->
            file.name.contains("DeviceMetadata") ||
                file.name.contains("DeviceIdentity") ||
                file.name.contains("DevicePresentation") ||
                file.name.contains("DevicesViewModel") ||
                file.name.contains("DevicesScreen")
        }

        val offenders = featureFiles.filter { file ->
            val text = file.readText()
            forbidden.any { it in text }
        }

        assertTrue(
            "the custom names must never be interpreted, found: $offenders",
            offenders.isEmpty()
        )
    }
}
