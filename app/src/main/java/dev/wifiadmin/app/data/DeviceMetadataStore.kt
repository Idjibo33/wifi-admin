package dev.wifiadmin.app.data

import dev.wifiadmin.router.DeviceSnapshot
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
 * The metadata of one device, read back from disk.
 */
data class DeviceMetadataRecord(
    val metadata: DeviceMetadata,
    val context: MetadataContext,
    val writtenAtMillis: Long,
)

/**
 * The outcome of reading the stored metadata.
 */
sealed interface MetadataRead {
    data class Found(val records: List<DeviceMetadataRecord>) : MetadataRead
    data object Missing : MetadataRead

    /**
     * The file was unusable. It has been deleted, except for
     * [Reason.IO_ERROR] where the failure may be transient.
     */
    data class Rejected(val reason: Reason) : MetadataRead

    enum class Reason { CORRUPT, UNSUPPORTED_VERSION, TOO_LARGE, FUTURE_DATED, IO_ERROR }
}

/**
 * The outcome of writing the metadata.
 */
sealed interface MetadataWrite {
    data object Stored : MetadataWrite
    data class Skipped(val reason: Reason) : MetadataWrite
    data class Failed(val cause: IOException) : MetadataWrite

    enum class Reason { TOO_LARGE, NO_CHANGE }
}

/**
 * The persistence of the device metadata.
 *
 * It is an interface so the repository and the view model can be tested with
 * an in-memory fake, and so the production implementation stays a single,
 * replaceable file.
 */
interface DeviceMetadataStorage {
    /** The metadata of the given context, never belonging to another one. */
    fun read(context: MetadataContext): MetadataRead

    /**
     * Records a **confirmed** observation of the devices.
     *
     * @param observedAtMillis when the router produced the snapshot. It is
     *   the snapshot's own capture time, never the current time: a reused
     *   snapshot must not move the observation forward.
     */
    fun recordObservation(
        snapshot: DeviceSnapshot,
        context: MetadataContext,
        observedAtMillis: Long,
    ): MetadataWrite

    /** Stores the custom name of a device. */
    fun setCustomName(key: String, name: String, context: MetadataContext): MetadataWrite

    /** Removes the custom name of a device. */
    fun clearCustomName(key: String, context: MetadataContext): MetadataWrite

    /** @return true when nothing remains on disk for this context. */
    fun clear(context: MetadataContext): Boolean
}

/**
 * The router and account a set of metadata belongs to.
 *
 * It is the same identity as [SnapshotContext], and it is normalised the
 * same way. The isolation it provides is structural: the metadata of one
 * router is never returned for another one, because the comparison is an
 * exact equality on the three parts.
 *
 * The network identity is the third part. `192.168.1.1` is the gateway of
 * practically every home router and `admin` the default account, so the
 * pair alone would be identical on two different routers.
 */
data class MetadataContext(
    val host: String,
    val account: String,
    val networkId: String = "",
) {

    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(account.isNotBlank()) { "account must not be blank" }
    }

    /**
     * The file name this context is stored under.
     *
     * The name is derived from the context, so two routers never share a
     * file even when they share an address. It is a hash, so the account
     * name does not appear on the disk.
     */
    val fileName: String
        get() = "${DeviceMetadataStore.FILE_PREFIX}${stableHash()}.json"

    /**
     * A hash of the three parts of the context.
     *
     * It is a plain FNV-1a: it does not need to resist an attacker, only
     * to separate the contexts. A cryptographic hash would add a dependency
     * for no benefit here.
     *
     * The three parts are **length-prefixed** before being hashed. A plain
     * join by a separator would be ambiguous — `("a|b", "c")` and
     * `("a", "b|c")` would produce the same string, and therefore the same
     * file for two different routers.
     */
    fun stableHash(): String {
        val source = buildString {
            listOf(host, account, networkId).forEach { part ->
                append(part.length).append(':').append(part).append('|')
            }
        }
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (byte in source.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(hash)
    }

    companion object {
        fun of(host: String, account: String, networkId: String = ""): MetadataContext =
            MetadataContext(
                host = host.trim().lowercase(Locale.ROOT),
                account = account.trim(),
                networkId = networkId.trim(),
            )
    }
}

/**
 * Stores the local metadata of the devices in one file per router.
 *
 * ## Where and why
 *
 * The files live in `Context.noBackupFilesDir`: the framework excludes that
 * directory from the cloud backup and from the device-to-device transfer. A
 * custom name is **personal data** — it names the user's own equipment — and
 * restoring it on another phone would be both a leak and a lie.
 *
 * ## The format
 *
 * The document is versioned and the keys are written one by one: a secret
 * cannot slip in. The format is the one of [SnapshotStore], so the two files
 * behave the same way in the face of a corrupted file.
 *
 * ## The isolation
 *
 * One file per router, the name derived from the context. The metadata of
 * one router is therefore **never** readable for another one: the file of
 * the other router does not even exist in the directory.
 */
class DeviceMetadataStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceMetadataStorage {

    /**
     * The lock that protects the files.
     *
     * It is **per instance**, and there is one instance in the container:
     * the renames and the observations then never race each other. It is a
     * `ReentrantLock` and not a `Mutex`, because `readLocked` is called
     * from `writeLocked` and a `Mutex` is not reentrant.
     */
    private val lock = ReentrantLock()

    override fun read(context: MetadataContext): MetadataRead = lock.withLock {
        readLocked(context)
    }

    override fun recordObservation(
        snapshot: DeviceSnapshot,
        context: MetadataContext,
        observedAtMillis: Long,
    ): MetadataWrite = lock.withLock {
        val file = fileFor(context)
        val existing = readEntriesLocked(file, context)

        var changed = false
        val updated = existing.map { entry ->
            val device = snapshot.devices.firstOrNull {
                DeviceIdentity.normalizeMac(it.mac) == entry.key
            }
            if (device == null) {
                // The device is not in this snapshot: its last observation
                // stays where it was. The application never pretends to know
                // when a device left.
                entry
            } else {
                val newIp = device.ip.trim().takeIf { it.isNotEmpty() }
                val newInterface = device.interfaceName.trim().takeIf { it.isNotEmpty() }
                val lastSeen = maxOf(entry.lastSeenMillis ?: Long.MIN_VALUE, observedAtMillis)
                if (lastSeen != entry.lastSeenMillis || newIp != entry.lastIp) {
                    changed = true
                }
                entry.copy(
                    lastSeenMillis = lastSeen,
                    lastIp = newIp ?: entry.lastIp,
                    lastInterface = newInterface ?: entry.lastInterface,
                )
            }
        }

        // The devices the snapshot shows and that no entry describes yet are
        // added: they have just been observed, even if the user never
        // renamed them.
        val known = updated.map { it.key }.toSet()
        val added = snapshot.devices.mapNotNull { device ->
            val key = DeviceIdentity.keyOf(device) ?: return@mapNotNull null
            if (key in known) return@mapNotNull null
            changed = true
            DeviceMetadata(
                key = key,
                customName = null,
                lastSeenMillis = observedAtMillis,
                lastIp = device.ip.trim().takeIf { it.isNotEmpty() },
                lastInterface = device.interfaceName.trim().takeIf { it.isNotEmpty() },
            )
        }

        // The entries that were not observed for a long time are removed
        // **here**, at the moment the file is written: the purge then costs
        // no extra read and no extra write, and it is triggered by the
        // application — never by the system.
        val retained = purgeExpiredEntries(updated + added, clock())

        // Nothing changed and nothing expired: there is nothing to write.
        // The comparison is on the retained list, so an expiry is still
        // persisted even when the snapshot itself brought nothing new.
        if (!changed && added.isEmpty() && retained.size == existing.size) {
            return@withLock MetadataWrite.Skipped(MetadataWrite.Reason.NO_CHANGE)
        }

        val all = retained.take(MAX_ENTRIES)
        writeLocked(file, context, all)
    }

    override fun setCustomName(
        key: String,
        name: String,
        context: MetadataContext,
    ): MetadataWrite = lock.withLock {
        val canonicalKey = DeviceIdentity.keyOf(key)
        if (canonicalKey == null) {
            return@withLock MetadataWrite.Skipped(MetadataWrite.Reason.NO_CHANGE)
        }
        val sanitized = CustomName.sanitize(name)
        val file = fileFor(context)
        val existing = readEntriesLocked(file, context)

        val index = existing.indexOfFirst { it.key == canonicalKey }
        val updated = if (index >= 0) {
            existing.toMutableList().apply {
                this[index] = this[index].copy(customName = sanitized)
            }
        } else {
            existing + DeviceMetadata(key = canonicalKey, customName = sanitized)
        }

        writeLocked(file, context, updated.take(MAX_ENTRIES))
    }

    override fun clearCustomName(key: String, context: MetadataContext): MetadataWrite =
        lock.withLock {
            val canonicalKey = DeviceIdentity.keyOf(key)
            if (canonicalKey == null) {
                return@withLock MetadataWrite.Skipped(MetadataWrite.Reason.NO_CHANGE)
            }
            val file = fileFor(context)
            val existing = readEntriesLocked(file, context)

            val index = existing.indexOfFirst { it.key == canonicalKey }
            if (index < 0) {
                return@withLock MetadataWrite.Skipped(MetadataWrite.Reason.NO_CHANGE)
            }

            val updated = existing.toMutableList().apply {
                this[index] = this[index].copy(customName = null)
            }
            writeLocked(file, context, updated)
        }

    override fun clear(context: MetadataContext): Boolean = lock.withLock {
        val file = fileFor(context)
        if (!file.exists()) return@withLock true
        deleteQuietly(file)
        !file.exists()
    }

    /**
     * Removes the entries that were not observed for too long.
     *
     * The purge is **controlled**: an entry that carries a custom name is
     * kept, because it is the user's work. An entry that only carries an
     * old observation and no name is removed.
     *
     * @param now the current time, in epoch milliseconds.
     * @return the number of entries that were removed.
     */
    fun purgeExpired(context: MetadataContext, now: Long): Int = lock.withLock {
        purgeExpiredLocked(context, now)
    }

    /**
     * The entries that are kept after the retention.
     *
     * An entry that carries a custom name is **always** kept: it is the
     * user's work, and losing it silently would be a loss of data. An
     * entry that only carries an old observation is removed once the
     * retention has passed. An entry with neither a name nor an
     * observation is removed at once: it holds nothing.
     */
    private fun purgeExpiredEntries(
        entries: List<DeviceMetadata>,
        now: Long,
    ): List<DeviceMetadata> = entries.filter { entry ->
        entry.hasCustomName || (
            entry.lastSeenMillis != null &&
                now - entry.lastSeenMillis <= RETENTION_MILLIS
            )
    }

    /**
     * The purge, with the lock already taken.
     */
    private fun purgeExpiredLocked(context: MetadataContext, now: Long): Int {
        val file = fileFor(context)
        val existing = readEntriesLocked(file, context)
        if (existing.isEmpty()) return 0

        val kept = purgeExpiredEntries(existing, now)
        val removed = existing.size - kept.size
        if (removed == 0) return 0

        writeLocked(file, context, kept)
        return removed
    }

    // -----------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------

    private fun readLocked(context: MetadataContext): MetadataRead {
        val file = fileFor(context)
        if (!file.exists()) return MetadataRead.Missing

        val text = try {
            if (file.length() > MAX_FILE_BYTES) {
                deleteQuietly(file)
                return MetadataRead.Rejected(MetadataRead.Reason.TOO_LARGE)
            }
            val buffer = ByteArray(MAX_FILE_BYTES + 1)
            FileInputStream(file).use { input ->
                val read = input.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
            }
        } catch (e: IOException) {
            // The failure may be transient: the file is **not** deleted.
            return MetadataRead.Rejected(MetadataRead.Reason.IO_ERROR)
        }

        if (text.isBlank() || !text.trimStart().startsWith("{")) {
            deleteQuietly(file)
            return MetadataRead.Rejected(MetadataRead.Reason.CORRUPT)
        }
        if (exceedsDepth(text, MAX_JSON_DEPTH)) {
            deleteQuietly(file)
            return MetadataRead.Rejected(MetadataRead.Reason.CORRUPT)
        }

        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            deleteQuietly(file)
            return MetadataRead.Rejected(MetadataRead.Reason.CORRUPT)
        }

        if (root.optString("schema", "") != SCHEMA ||
            root.optInt("schemaVersion", -1) != SCHEMA_VERSION
        ) {
            deleteQuietly(file)
            return MetadataRead.Rejected(MetadataRead.Reason.UNSUPPORTED_VERSION)
        }

        val writtenAt = root.optLong("writtenAtMillis", 0L)
        if (writtenAt > clock() + CLOCK_SKEW_TOLERANCE_MS) {
            deleteQuietly(file)
            return MetadataRead.Rejected(MetadataRead.Reason.FUTURE_DATED)
        }

        val contextJson = root.optJSONObject("context")
        if (contextJson == null) {
            deleteQuietly(file)
            return MetadataRead.Rejected(MetadataRead.Reason.CORRUPT)
        }

        val storedContext = MetadataContext(
            host = contextJson.optString("host", ""),
            account = contextJson.optString("account", ""),
            networkId = contextJson.optString("networkId", ""),
        )
        // The context is the isolation. A file that belongs to another
        // router is never returned: it is deleted, like a snapshot of
        // another router is.
        if (storedContext != context) {
            deleteQuietly(file)
            return MetadataRead.Rejected(MetadataRead.Reason.CORRUPT)
        }

        val entriesJson = root.optJSONArray("entries")
        if (entriesJson == null) {
            deleteQuietly(file)
            return MetadataRead.Rejected(MetadataRead.Reason.CORRUPT)
        }
        if (entriesJson.length() > MAX_ENTRIES) {
            deleteQuietly(file)
            return MetadataRead.Rejected(MetadataRead.Reason.TOO_LARGE)
        }

        val records = mutableListOf<DeviceMetadataRecord>()
        for (index in 0 until entriesJson.length()) {
            val item = entriesJson.optJSONObject(index) ?: continue
            val key = DeviceIdentity.keyOf(item.optString("key", "")) ?: continue
            val customName = item.optString("customName", "")
                .takeIf { it.isNotEmpty() }
                ?.let { CustomName.sanitize(it) }
                ?.takeIf { it.isNotEmpty() }
            val lastSeen = if (item.has("lastSeenMillis")) {
                item.optLong("lastSeenMillis", 0L).takeIf { it > 0L }
            } else {
                null
            }

            records += DeviceMetadataRecord(
                metadata = DeviceMetadata(
                    key = key,
                    customName = customName,
                    lastSeenMillis = lastSeen,
                    lastIp = item.optString("lastIp", "").takeIf { it.isNotEmpty() },
                    lastInterface = item.optString("lastInterface", "")
                        .takeIf { it.isNotEmpty() },
                ),
                context = context,
                writtenAtMillis = writtenAt,
            )
        }

        return MetadataRead.Found(records)
    }

    /**
     * The entries of the context, or an empty list when the file is missing
     * or unusable. A corrupted file is a lost convenience, not a crash.
     */
    private fun readEntriesLocked(file: File, context: MetadataContext): List<DeviceMetadata> {
        val read = readLocked(context)
        return (read as? MetadataRead.Found)?.records?.map { it.metadata } ?: emptyList()
    }

    // -----------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------

    private fun writeLocked(
        file: File,
        context: MetadataContext,
        entries: List<DeviceMetadata>,
    ): MetadataWrite {
        if (entries.size > MAX_ENTRIES) {
            return MetadataWrite.Skipped(MetadataWrite.Reason.TOO_LARGE)
        }

        val bytes = try {
            encode(entries, context).toByteArray(Charsets.UTF_8)
        } catch (e: JSONException) {
            return MetadataWrite.Failed(IOException("the metadata could not be encoded", e))
        }
        if (bytes.size > MAX_FILE_BYTES) {
            return MetadataWrite.Skipped(MetadataWrite.Reason.TOO_LARGE)
        }

        if (!directory.exists() && !directory.mkdirs()) {
            return MetadataWrite.Failed(
                IOException("the metadata directory could not be created")
            )
        }

        val temporary = File(file.parentFile, file.name + TEMPORARY_SUFFIX)
        try {
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
        } catch (e: IOException) {
            temporary.delete()
            return MetadataWrite.Failed(e)
        } catch (e: UnsupportedOperationException) {
            temporary.delete()
            return MetadataWrite.Failed(IOException("Atomic move is not supported", e))
        }
        return MetadataWrite.Stored
    }

    private fun encode(entries: List<DeviceMetadata>, context: MetadataContext): String {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("writtenAtMillis", clock())
        root.put(
            "context",
            JSONObject()
                .put("host", context.host)
                .put("account", context.account)
                .put("networkId", context.networkId)
        )

        val array = JSONArray()
        entries.forEach { entry ->
            val json = JSONObject()
            json.put("key", entry.key)
            entry.customName?.takeIf { it.isNotEmpty() }?.let { json.put("customName", it) }
            entry.lastSeenMillis?.let { json.put("lastSeenMillis", it) }
            entry.lastIp?.takeIf { it.isNotEmpty() }?.let { json.put("lastIp", it) }
            entry.lastInterface?.takeIf { it.isNotEmpty() }?.let {
                json.put("lastInterface", it)
            }
            array.put(json)
        }
        root.put("entries", array)

        return root.toString()
    }

    // -----------------------------------------------------------------
    // Files
    // -----------------------------------------------------------------

    private fun fileFor(context: MetadataContext): File = File(directory, context.fileName)

    private fun deleteQuietly(file: File) {
        try {
            file.delete()
        } catch (e: SecurityException) {
            // The application stays usable even when the file cannot be
            // removed.
        }
    }

    /**
     * Whether the document nests deeper than the limit.
     *
     * `org.json` is recursive: a pathologically nested document would
     * overflow the stack, and **no catch can catch a stack overflow**. The
     * braces inside a string are ignored.
     */
    private fun exceedsDepth(text: String, limit: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in text) {
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > limit) return true
                }
                '}', ']' -> depth--
            }
        }
        return false
    }

    companion object {
        /** The prefix of the metadata file names. */
        const val FILE_PREFIX = "device-metadata-"

        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val SCHEMA = "dev.wifiadmin.devicemetadata"
        private const val SCHEMA_VERSION = 1

        internal const val MAX_FILE_BYTES = 512 * 1024
        internal const val MAX_ENTRIES = 512
        internal const val MAX_JSON_DEPTH = 32
        internal const val CLOCK_SKEW_TOLERANCE_MS = 5 * 60 * 1000L

        /**
         * How long the metadata of a device is kept.
         *
         * Thirty days: long enough for a device that comes and goes to keep
         * its name, short enough for the file not to accumulate the
         * entries of the devices that randomise their address.
         */
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
