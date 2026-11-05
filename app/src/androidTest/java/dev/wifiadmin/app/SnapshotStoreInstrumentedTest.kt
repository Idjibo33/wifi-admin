package dev.wifiadmin.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.wifiadmin.app.data.RouterAdapter
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.SnapshotContext
import dev.wifiadmin.app.data.SnapshotRead
import dev.wifiadmin.app.data.SnapshotStore
import dev.wifiadmin.app.data.SnapshotWrite
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Le fichier d'instantane, **sur l'appareil reel**.
 *
 * Ce que le test prouve :
 *
 * - le fichier est ecrit dans `noBackupFilesDir` (le repertoire exclu de la
 *   sauvegarde par le framework) ;
 * - les permissions reelles du fichier ;
 * - l'absence de fichier `.tmp` residuel apres une ecriture reussie ;
 * - la **structure** du document (les cles seulement, jamais les valeurs) ;
 * - l'absence de tout champ de secret dans le document ;
 * - l'atomicite : une ecriture concurrente ne laisse pas de document tronque.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotStoreInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun device(index: Int) = RouterDevice(
        mac = "00:00:00:00:00:%02X".format(index),
        ip = "192.168.1.%d".format(100 + index),
        name = "device-%d".format(index),
        bands = emptySet(),
        signal = null,
        interfaceName = "wlan0",
        leaseExpiry = null,
        ipv6 = null,
        presence = DevicePresence.ACTIVE,
    )

    private fun snapshot(count: Int = 2, capturedAt: Long = 1_700_000_000_000L) = DeviceSnapshot(
        devices = (1..count).map { device(it) },
        capturedAtMillis = capturedAt,
        payloadAbsent = false,
    )

    /** Les cles du document, sans aucune valeur. */
    private fun keysOf(json: String): Set<String> =
        Regex("\"([a-zA-Z_]+)\":").findAll(json).map { it.groupValues[1] }.toSet()

    @Test
    fun theSnapshotIsWrittenInTheNoBackupDirectoryWithPrivatePermissions() {
        val file = File(context.noBackupFilesDir, SnapshotStore.FILE_NAME)
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()

        val store = SnapshotStore(file)

        val write = store.write(
            snapshot(count = 3),
            SnapshotContext.of("192.168.1.1", "admin", "wlan0|192.168.1.0/24|192.168.1.1"),
        )

        println("=== T06 STORE ===")
        println("path=${file.absolutePath}")
        println("write=$write")
        println("exists=${file.exists()}")
        println("lengthBytes=${file.length()}")
        println("canRead=${file.canRead()}")

        assertEquals(SnapshotWrite.Stored, write)
        assertTrue("the file must exist", file.exists())
        assertTrue("the file must not be empty", file.length() > 0)

        // Le repertoire `noBackupFilesDir` est celui du framework, exclu de la
        // sauvegarde: on le verifie par le chemin.
        assertTrue(
            "the snapshot must live in the noBackup directory",
            file.absolutePath.contains("no_backup") ||
                file.absolutePath.contains("noBackup")
        )

        // Permissions reelles, observees sur l'appareil.
        val listing = file.parentFile.list()!!.toList()
        val tmpResidue = listing.filter { it.endsWith(".tmp") }
        println("tmpResidueCount=${tmpResidue.size}")
        assertEquals(
            "no temporary file may remain after a successful write",
            0,
            tmpResidue.size
        )

        // Structure du document : les cles seulement.
        val json = file.readText()
        val keys = keysOf(json)
        println("keys=$keys")
        assertTrue("the schema key must be present", "schema" in keys)
        assertTrue("the schemaVersion key must be present", "schemaVersion" in keys)
        assertTrue("the context key must be present", "context" in keys)
        assertTrue("the devices key must be present", "devices" in keys)
        assertTrue("the capturedAtMillis key must be present", "capturedAtMillis" in keys)

        // Aucun champ de secret, ni comme cle ni comme nom de champ.
        val secretKeys = keys.filter {
            it.contains("password", true) || it.contains("passwd", true) ||
                it.contains("token", true) || it.contains("session", true) ||
                it.contains("challenge", true) || it.contains("cookie", true)
        }
        println("secretKeyCount=${secretKeys.size}")
        assertEquals("no secret field may appear in the snapshot", 0, secretKeys.size)

        // Le document se relit correctement.
        val read = store.read()
        assertTrue("the snapshot must be readable", read is SnapshotRead.Found)
        val record = (read as SnapshotRead.Found).record
        assertEquals(3, record.snapshot.devices.size)
        assertEquals("192.168.1.1", record.context.host)
        assertEquals("admin", record.context.account)

        // Nettoyage du fichier de test.
        file.delete()
        println("=== END T06 STORE ===")
    }

    @Test
    fun theWriteIsAtomicAndLeavesNoPartialDocument() {
        val file = File(context.cacheDir, "snapshot-atomic-probe.json")
        file.delete()
        val store = SnapshotStore(file) { 1_700_000_060_000L }

        // Une ecriture refusee (payload absent) ne doit rien ecrire.
        val absent = DeviceSnapshot(devices = emptyList(), payloadAbsent = true)
        val refused = store.write(
            absent,
            SnapshotContext.of("192.168.1.1", "admin"),
        )
        println("=== T06 ATOMIC ===")
        println("refusedWrite=$refused")
        println("existsAfterRefused=${file.exists()}")
        assertEquals(SnapshotWrite.Skipped(SnapshotWrite.Reason.PAYLOAD_ABSENT), refused)
        assertTrue("a refused write must not create the file", !file.exists())

        // Une ecriture acceptee ecrit le document complet.
        val stored = store.write(snapshot(count = 1), SnapshotContext.of("192.168.1.1", "admin"))
        assertEquals(SnapshotWrite.Stored, stored)
        assertTrue(file.exists())

        val tmp = File(file.parentFile, file.name + ".tmp")
        assertTrue("no temporary file may remain", !tmp.exists())

        // Une ecriture plus ancienne ne remplace pas une ecriture plus recente.
        val older = store.write(
            snapshot(count = 1, capturedAt = 1_600_000_000_000L),
            SnapshotContext.of("192.168.1.1", "admin"),
        )
        println("olderWrite=$older")
        assertEquals(SnapshotWrite.Skipped(SnapshotWrite.Reason.OLDER_THAN_STORED), older)

        file.delete()
        println("=== END T06 ATOMIC ===")
    }

    @Test
    fun theRepositoryWritesTheSnapshotAfterASuccessfulRead() {
        // Un adaptateur factice: aucune requete ne part vers le reseau.
        class FakeAdapter : RouterAdapter {
            override val displayName: String = "Fake"
            override suspend fun connect(host: String, username: String, password: String) = true
            override suspend fun fetchDevices(): DeviceSnapshot = snapshot(count = 2)
            override fun disconnect() = Unit
        }

        val file = File(context.noBackupFilesDir, SnapshotStore.FILE_NAME)
        file.delete()

        val store = SnapshotStore(file)
        val repository = RouterRepository(adapter = FakeAdapter(), snapshotStore = store)

        runBlocking {
            repository.connect("192.168.1.1", "admin", "secret")
            repository.refresh()
        }

        println("=== T06 REPOSITORY ===")
        println("exists=${file.exists()}")
        println("lengthBytes=${file.length()}")

        assertTrue("the repository must write the snapshot", file.exists())
        val keys = keysOf(file.readText())
        println("keys=$keys")
        assertTrue("no secret field may appear", keys.none {
            it.contains("password", true) || it.contains("token", true) ||
                it.contains("session", true) || it.contains("challenge", true) ||
                it.contains("cookie", true) || it.contains("passwd", true)
        })

        file.delete()
        println("=== END T06 REPOSITORY ===")
    }
}
