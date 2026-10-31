package dev.wifiadmin.app.data

import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The router and account an instantané belongs to.
 *
 * The host is normalised to lower case and trimmed; the account is trimmed but
 * keeps its case. Two different routers can share the same private address, so
 * the account is part of the identity: a snapshot is never shown for another
 * login.
 */
data class SnapshotContext(val host: String, val account: String) {

    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(account.isNotBlank()) { "account must not be blank" }
    }

    companion object {
        fun of(host: String, account: String): SnapshotContext =
            SnapshotContext(
                host = host.trim().lowercase(Locale.ROOT),
                account = account.trim(),
            )
    }
}

/** A snapshot read back from disk. */
data class SnapshotRecord(
    val snapshot: DeviceSnapshot,
    val context: SnapshotContext,
    val writtenAtMillis: Long,
)

/** The outcome of reading the stored snapshot. */
sealed interface SnapshotRead {
    data class Found(val record: SnapshotRecord) : SnapshotRead
    data object Missing : SnapshotRead

    /**
     * The file was unusable. It has been deleted, except for [Reason.IO_ERROR]
     * where the failure may be transient.
     */
    data class Rejected(val reason: Reason) : SnapshotRead

    enum class Reason { CORRUPT, UNSUPPORTED_VERSION, TOO_LARGE, FUTURE_DATED, IO_ERROR }
}

/** The outcome of writing a snapshot. */
sealed interface SnapshotWrite {
    data object Stored : SnapshotWrite
    data class Skipped(val reason: Reason) : SnapshotWrite
    data class Failed(val cause: IOException) : SnapshotWrite

    enum class Reason { PAYLOAD_ABSENT, OLDER_THAN_STORED, TOO_LARGE, INVALID_CONTEXT }
}

/**
 * The persistence the repository needs.
 *
 * It is an interface so the repository can be tested with an in-memory fake,
 * and so the production implementation stays a single, replaceable file.
 */
interface SnapshotStorage {
    fun read(): SnapshotRead
    fun write(snapshot: DeviceSnapshot, context: SnapshotContext): SnapshotWrite

    /** @return true when nothing remains on disk. */
    fun clear(): Boolean
}

/**
 * Stores the last valid device snapshot in a single JSON file.
 *
 * ## Where and why
 *
 * The file lives in `Context.noBackupFilesDir`: the Android framework excludes
 * that directory from cloud backup and device-to-device transfer. A device list
 * restored on another phone would be both a leak and a lie (another network,
 * another account). `allowBackup="false"` in the manifest is a second barrier.
 *
 * ## What it never contains
 *
 * The store only accepts a [DeviceSnapshot] and a [SnapshotContext]. It never
 * sees a password, a session id, a challenge token or the router settings: the
 * JSON writer uses an explicit allowlist of keys, so a credential field is
 * structurally impossible, not merely absent by convention.
 *
 * ## Atomicity
 *
 * A write goes to `<file>.tmp`, is flushed and `fsync`ed, then moved over the
 * target with `ATOMIC_MOVE`. A reader therefore sees either the previous
 * complete document or the new complete one, never a truncated file. A failed
 * write leaves the previous file untouched.
 *
 * ## Threading
 *
 * Every method blocks on file I/O. The repository calls them on its IO
 * dispatcher; they must never run on the main thread. One instance is expected
 * per process (created in `AppContainer`).
 */
class SnapshotStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : SnapshotStorage {

    private val lock = ReentrantLock()

    private val temporaryFile: File
        get() = File(file.parentFile, file.name + TEMPORARY_SUFFIX)

    override fun read(): SnapshotRead = lock.withLock { readLocked() }

    override fun write(snapshot: DeviceSnapshot, context: SnapshotContext): SnapshotWrite =
        lock.withLock { writeLocked(snapshot, context) }

    override fun clear(): Boolean = lock.withLock {
        temporaryFile.delete()
        file.delete()
        !file.exists() && !temporaryFile.exists()
    }

    /** The path only. Never the content: it holds private device data. */
    val path: String get() = file.absolutePath

    // ---- Reading ----

    private fun readLocked(): SnapshotRead {
        if (!file.isFile) return SnapshotRead.Missing

        if (file.length() > MAX_FILE_BYTES) {
            deleteQuietly()
            return SnapshotRead.Rejected(SnapshotRead.Reason.TOO_LARGE)
        }

        val bytes = try {
            readBounded()
        } catch (e: IOException) {
            return SnapshotRead.Rejected(SnapshotRead.Reason.IO_ERROR)
        }

        val text = try {
            bytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            deleteQuietly()
            return SnapshotRead.Rejected(SnapshotRead.Reason.CORRUPT)
        }

        if (text.isBlank() || !text.trimStart().startsWith("{") || exceedsDepth(text)) {
            deleteQuietly()
            return SnapshotRead.Rejected(SnapshotRead.Reason.CORRUPT)
        }

        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            deleteQuietly()
            return SnapshotRead.Rejected(SnapshotRead.Reason.CORRUPT)
        }

        if (root.optString("schema", "") != SCHEMA ||
            root.optInt("schemaVersion", -1) != SCHEMA_VERSION
        ) {
            deleteQuietly()
            return SnapshotRead.Rejected(SnapshotRead.Reason.UNSUPPORTED_VERSION)
        }

        val capturedAt = root.optLong("capturedAtMillis", -1L)
        if (capturedAt <= 0L) {
            deleteQuietly()
            return SnapshotRead.Rejected(SnapshotRead.Reason.CORRUPT)
        }
        if (capturedAt > clock() + CLOCK_SKEW_TOLERANCE_MS) {
            deleteQuietly()
            return SnapshotRead.Rejected(SnapshotRead.Reason.FUTURE_DATED)
        }

        return try {
            val contextJson = root.optJSONObject("context")
                ?: throw SnapshotFormatException()
            val context = SnapshotContext.of(
                host = contextJson.requiredString("host"),
                account = contextJson.requiredString("account"),
            )

            val devicesJson = root.optJSONArray("devices")
                ?: throw SnapshotFormatException()
            if (devicesJson.length() > MAX_DEVICES) {
                throw SnapshotFormatException()
            }

            val devices = decodeDevices(devicesJson)
            val writtenAt = root.optLong("writtenAtMillis", capturedAt)

            SnapshotRead.Found(
                SnapshotRecord(
                    snapshot = DeviceSnapshot(
                        devices = devices,
                        capturedAtMillis = capturedAt,
                        // An absent payload is never persisted: the file only
                        // ever describes a real, complete read.
                        payloadAbsent = false,
                    ),
                    context = context,
                    writtenAtMillis = writtenAt,
                )
            )
        } catch (e: SnapshotFormatException) {
            deleteQuietly()
            SnapshotRead.Rejected(SnapshotRead.Reason.CORRUPT)
        } catch (e: JSONException) {
            deleteQuietly()
            SnapshotRead.Rejected(SnapshotRead.Reason.CORRUPT)
        }
    }

    private fun readBounded(): ByteArray {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(MAX_FILE_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
            return buffer.copyOf(total)
        }
    }

    private fun decodeDevices(array: JSONArray): List<RouterDevice> {
        val seenMacs = HashSet<String>()
        val devices = ArrayList<RouterDevice>(array.length())

        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: throw SnapshotFormatException()
            val mac = json.requiredString("mac", MAX_MAC_CHARS)
            if (mac.isBlank()) throw SnapshotFormatException()
            // `DevicesScreen` uses the MAC as a Compose key: a duplicate would
            // crash the list. The first entry wins.
            if (!seenMacs.add(mac.lowercase(Locale.ROOT))) continue

            devices += RouterDevice(
                mac = mac,
                ip = json.requiredString("ip"),
                name = json.requiredString("name"),
                bands = decodeBands(json.optJSONArray("bands")),
                signal = decodeSignal(json.optJSONObject("signal")),
                interfaceName = json.requiredString("interfaceName"),
                leaseExpiry = json.optionalString("leaseExpiry"),
                ipv6 = json.optionalString("ipv6"),
                presence = decodePresence(json.optionalString("presence")),
            )
        }
        return devices
    }

    private fun decodeBands(array: JSONArray?): Set<WifiBand> {
        if (array == null) return emptySet()
        val bands = LinkedHashSet<WifiBand>()
        for (index in 0 until array.length()) {
            val token = array.optString(index, "")
            when (token) {
                WifiBand.BAND_2_4_GHZ.name -> bands += WifiBand.BAND_2_4_GHZ
                WifiBand.BAND_5_GHZ.name -> bands += WifiBand.BAND_5_GHZ
                // Unknown values are ignored: a newer band must not make an
                // otherwise readable snapshot unusable.
                else -> Unit
            }
        }
        return bands
    }

    private fun decodeSignal(json: JSONObject?): DeviceSignal? {
        if (json == null) return null
        val rssi = if (json.has("rssiDbm") && !json.isNull("rssiDbm")) {
            val value = json.opt("rssiDbm")
            if (value !is Int) throw SnapshotFormatException()
            value
        } else {
            null
        }
        return DeviceSignal(
            rssiDbm = rssi,
            txRateMbps = json.optionalString("txRateMbps"),
            rxRateMbps = json.optionalString("rxRateMbps"),
            ssid = json.optionalString("ssid"),
        )
    }

    private fun decodePresence(token: String?): DevicePresence =
        DevicePresence.entries.firstOrNull { it.name == token } ?: DevicePresence.UNKNOWN

    // ---- Writing ----

    private fun writeLocked(snapshot: DeviceSnapshot, context: SnapshotContext): SnapshotWrite {
        if (snapshot.payloadAbsent) {
            return SnapshotWrite.Skipped(SnapshotWrite.Reason.PAYLOAD_ABSENT)
        }
        if (snapshot.devices.size > MAX_DEVICES) {
            return SnapshotWrite.Skipped(SnapshotWrite.Reason.TOO_LARGE)
        }
        if (context.host.isBlank() || context.account.isBlank()) {
            return SnapshotWrite.Skipped(SnapshotWrite.Reason.INVALID_CONTEXT)
        }

        val bytes = try {
            encode(snapshot, context)
        } catch (e: JSONException) {
            return SnapshotWrite.Failed(IOException("The snapshot could not be encoded", e))
        }
        if (bytes.size > MAX_FILE_BYTES) {
            return SnapshotWrite.Skipped(SnapshotWrite.Reason.TOO_LARGE)
        }

        // Never regress: a read that is older than the stored one must not
        // replace it, even if it arrived later.
        val existing = readLocked()
        if (existing is SnapshotRead.Found &&
            existing.record.snapshot.capturedAtMillis > snapshot.capturedAtMillis
        ) {
            return SnapshotWrite.Skipped(SnapshotWrite.Reason.OLDER_THAN_STORED)
        }

        val directory = file.absoluteFile.parentFile
            ?: return SnapshotWrite.Failed(IOException("The snapshot has no parent directory"))

        val temporary = temporaryFile
        return try {
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("The snapshot directory could not be created")
            }
            temporary.delete()

            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }

            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            SnapshotWrite.Stored
        } catch (e: IOException) {
            temporary.delete()
            SnapshotWrite.Failed(e)
        } catch (e: UnsupportedOperationException) {
            // No atomic move available: refuse rather than risk a truncated
            // document. This cannot happen on the same file system in practice.
            temporary.delete()
            SnapshotWrite.Failed(IOException("Atomic move is not supported", e))
        }
    }

    private fun encode(snapshot: DeviceSnapshot, context: SnapshotContext): ByteArray {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("capturedAtMillis", snapshot.capturedAtMillis)
        root.put("writtenAtMillis", clock())
        root.put(
            "context",
            JSONObject()
                .put("host", context.host)
                .put("account", context.account)
        )

        val devices = JSONArray()
        snapshot.devices.forEach { device -> devices.put(encodeDevice(device)) }
        root.put("devices", devices)

        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun encodeDevice(device: RouterDevice): JSONObject {
        val json = JSONObject()
        json.put("mac", device.mac.take(MAX_MAC_CHARS))
        json.put("ip", device.ip.take(MAX_FIELD_CHARS))
        json.put("name", device.name.take(MAX_FIELD_CHARS))
        json.put(
            "bands",
            JSONArray().apply {
                device.bands.sortedBy { it.ordinal }.forEach { put(it.name) }
            }
        )
        device.signal?.let { signal ->
            json.put(
                "signal",
                JSONObject().apply {
                    signal.rssiDbm?.let { put("rssiDbm", it) }
                    signal.txRateMbps?.let { put("txRateMbps", it.take(MAX_FIELD_CHARS)) }
                    signal.rxRateMbps?.let { put("rxRateMbps", it.take(MAX_FIELD_CHARS)) }
                    signal.ssid?.let { put("ssid", it.take(MAX_FIELD_CHARS)) }
                }
            )
        }
        json.put("interfaceName", device.interfaceName.take(MAX_FIELD_CHARS))
        device.leaseExpiry?.let { json.put("leaseExpiry", it.take(MAX_FIELD_CHARS)) }
        device.ipv6?.let { json.put("ipv6", it.take(MAX_FIELD_CHARS)) }
        json.put("presence", device.presence.name)
        return json
    }

    // ---- Helpers ----

    private fun deleteQuietly() {
        try {
            file.delete()
        } catch (e: SecurityException) {
            // The next read will reject it again; the app stays usable.
        }
    }

    /**
     * A cheap depth guard, run before [JSONObject].
     *
     * `org.json` is recursive: a pathologically nested document could raise a
     * `StackOverflowError`, which no `catch` can recover from safely. Brackets
     * inside strings are ignored.
     */
    private fun exceedsDepth(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > MAX_JSON_DEPTH) return true
                }
                '}', ']' -> if (depth > 0) depth--
            }
        }
        return false
    }

    private fun JSONObject.requiredString(name: String, max: Int = MAX_FIELD_CHARS): String {
        if (!has(name) || isNull(name)) throw SnapshotFormatException()
        val value = opt(name)
        if (value !is String) throw SnapshotFormatException()
        if (value.length > max) throw SnapshotFormatException()
        return value
    }

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val value = opt(name)
        if (value !is String) throw SnapshotFormatException()
        if (value.length > MAX_FIELD_CHARS) throw SnapshotFormatException()
        return value
    }

    private class SnapshotFormatException : Exception()

    companion object {
        const val FILE_NAME = "last-device-snapshot.json"

        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val SCHEMA = "dev.wifiadmin.snapshot"
        private const val SCHEMA_VERSION = 1

        internal const val MAX_FILE_BYTES = 512 * 1024
        internal const val MAX_DEVICES = 256
        internal const val MAX_FIELD_CHARS = 256
        internal const val MAX_MAC_CHARS = 64
        internal const val MAX_JSON_DEPTH = 32
        internal const val CLOCK_SKEW_TOLERANCE_MS = 5 * 60 * 1000L
    }
}
