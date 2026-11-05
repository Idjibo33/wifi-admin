package dev.wifiadmin.app.data

import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The snapshot store is the only place private device data is written to
 * disk. These tests cover what it accepts, what it refuses and what it never
 * contains.
 */
class SnapshotStoreTest {

    private lateinit var directory: File
    private lateinit var file: File
    private var now = CAPTURED_AT + 60_000L

    private val context = SnapshotContext.of("192.168.1.1", "admin")

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("snapshot-store").toFile()
        file = File(directory, SnapshotStore.FILE_NAME)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun store() = SnapshotStore(file) { now }

    private fun device(
        mac: String = "aa:bb:cc:dd:ee:01",
        ip: String = "192.168.1.10",
        name: String = "poste",
        presence: DevicePresence = DevicePresence.ACTIVE,
    ) = RouterDevice(
        mac = mac,
        ip = ip,
        name = name,
        bands = setOf(WifiBand.BAND_2_4_GHZ),
        signal = DeviceSignal(
            rssiDbm = -52,
            txRateMbps = "72",
            rxRateMbps = "65",
            ssid = "maison"
        ),
        interfaceName = "wl0",
        leaseExpiry = "2026-10-31T18:00:00Z",
        ipv6 = "fe80::1",
        presence = presence,
    )

    private fun snapshot(
        devices: List<RouterDevice> = listOf(device()),
        capturedAt: Long = CAPTURED_AT,
        payloadAbsent: Boolean = false,
    ) = DeviceSnapshot(
        devices = devices,
        capturedAtMillis = capturedAt,
        payloadAbsent = payloadAbsent,
    )

    @Test
    fun `a written snapshot can be read back`() {
        val original = snapshot()
        val store = store()

        assertEquals(SnapshotWrite.Stored, store.write(original, context))

        val read = store.read()
        assertTrue("expected a found record, got $read", read is SnapshotRead.Found)
        val record = (read as SnapshotRead.Found).record
        assertEquals(original.devices, record.snapshot.devices)
        assertEquals(original.capturedAtMillis, record.snapshot.capturedAtMillis)
        assertEquals(context, record.context)
        assertFalse(record.snapshot.payloadAbsent)
    }

    @Test
    fun `a missing file is reported as missing`() {
        assertEquals(SnapshotRead.Missing, store().read())
    }

    @Test
    fun `an absent payload is never written`() {
        val store = store()

        val result = store.write(snapshot(payloadAbsent = true), context)

        assertEquals(SnapshotWrite.Skipped(SnapshotWrite.Reason.PAYLOAD_ABSENT), result)
        assertEquals(SnapshotRead.Missing, store.read())
    }

    @Test
    fun `a corrupted file is rejected and deleted`() {
        file.writeText("{ this is not json")

        val read = store().read()

        assertEquals(SnapshotRead.Rejected(SnapshotRead.Reason.CORRUPT), read)
        assertFalse("a corrupt file must not be kept", file.exists())
    }

    @Test
    fun `a truncated document is rejected`() {
        file.writeText("""{"schema":"dev.wifiadmin.snapshot","schemaVersion":1,"devices":[""")

        val read = store().read()

        assertEquals(SnapshotRead.Rejected(SnapshotRead.Reason.CORRUPT), read)
        assertFalse(file.exists())
    }

    @Test
    fun `an unknown schema version is rejected`() {
        file.writeText(
            JSONObject()
                .put("schema", "dev.wifiadmin.snapshot")
                .put("schemaVersion", 99)
                .put("capturedAtMillis", CAPTURED_AT)
                .put("devices", org.json.JSONArray())
                .toString()
        )

        val read = store().read()

        assertEquals(SnapshotRead.Rejected(SnapshotRead.Reason.UNSUPPORTED_VERSION), read)
        assertFalse(file.exists())
    }

    @Test
    fun `a snapshot dated in the future is rejected`() {
        now = CAPTURED_AT
        val future = snapshot(capturedAt = CAPTURED_AT + 60 * 60 * 1000L)
        val store = store()
        assertEquals(SnapshotWrite.Stored, store.write(future, context))

        val read = store.read()

        assertEquals(SnapshotRead.Rejected(SnapshotRead.Reason.FUTURE_DATED), read)
        assertFalse(file.exists())
    }

    @Test
    fun `an older snapshot never replaces a newer one`() {
        val store = store()
        val newer = snapshot(capturedAt = CAPTURED_AT + 10_000L)
        val older = snapshot(capturedAt = CAPTURED_AT)

        assertEquals(SnapshotWrite.Stored, store.write(newer, context))
        val second = store.write(older, context)

        assertEquals(
            SnapshotWrite.Skipped(SnapshotWrite.Reason.OLDER_THAN_STORED),
            second
        )
        val read = store.read() as SnapshotRead.Found
        assertEquals(newer.capturedAtMillis, read.record.snapshot.capturedAtMillis)
    }

    @Test
    fun `a snapshot for another context replaces the previous one`() {
        val store = store()
        store.write(snapshot(), context)
        val other = SnapshotContext.of("192.168.0.1", "autre")

        assertEquals(SnapshotWrite.Stored, store.write(snapshot(), other))
        val read = store.read() as SnapshotRead.Found
        assertEquals(other, read.record.context)
    }

    @Test
    fun `clear removes the stored file`() {
        val store = store()
        store.write(snapshot(), context)

        assertTrue(store.clear())
        assertEquals(SnapshotRead.Missing, store.read())
        assertFalse(file.exists())
    }

    @Test
    fun `the temporary file does not survive a write`() {
        store().write(snapshot(), context)

        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test
    fun `duplicate mac addresses are deduplicated on read`() {
        val store = store()
        store.write(
            snapshot(
                devices = listOf(
                    device(mac = "aa:bb:cc:dd:ee:01", ip = "192.168.1.10"),
                    device(mac = "aa:bb:cc:dd:ee:01", ip = "192.168.1.11"),
                )
            ),
            context
        )

        val read = store.read() as SnapshotRead.Found

        assertEquals(1, read.record.snapshot.devices.size)
        assertEquals("192.168.1.10", read.record.snapshot.devices.first().ip)
    }

    @Test
    fun `more than the maximum number of devices is not stored`() {
        val devices = (0..SnapshotStore.MAX_DEVICES).map { index ->
            device(mac = "aa:bb:cc:dd:ee:%02x".format(index))
        }
        val store = store()

        val result = store.write(snapshot(devices = devices), context)

        assertEquals(SnapshotWrite.Skipped(SnapshotWrite.Reason.TOO_LARGE), result)
        assertEquals(SnapshotRead.Missing, store.read())
    }

    @Test
    fun `the file contains no credential field`() {
        store().write(snapshot(), context)

        val text = file.readText()
        val root = JSONObject(text)
        val keys = root.keys().asSequence().toSet()

        assertEquals(
            setOf("schema", "schemaVersion", "capturedAtMillis", "writtenAtMillis", "context", "devices"),
            keys
        )
        for (forbidden in listOf("password", "token", "session", "challenge", "cookie")) {
            assertFalse(
                "the snapshot must never contain $forbidden",
                text.contains(forbidden, ignoreCase = true)
            )
        }
        assertNull(root.opt("sessionId"))
    }

    companion object {
        private const val CAPTURED_AT = 1_700_000_000_000L
    }
}
