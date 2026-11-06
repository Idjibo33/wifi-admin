package dev.wifiadmin.app.ui.devices

import dev.wifiadmin.app.data.DeviceIdentity
import dev.wifiadmin.app.data.DeviceMetadata
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import java.text.Collator
import java.util.Locale

/**
 * A device as the list shows it: the router's data plus the local metadata.
 *
 * The two are kept apart on purpose: [device] is what the router said, and
 * [metadata] is what the application knows. The custom name never enters
 * [device], and the router is never told about it.
 */
data class DeviceRow(
    val device: RouterDevice,
    val metadata: DeviceMetadata?,
) {

    /**
     * The identity of the device.
     *
     * It is the canonical MAC address, or null when the router reported no
     * usable address. A device without an identity cannot carry a custom
     * name: the application would have no way to find it again.
     */
    val key: String? get() = DeviceIdentity.keyOf(device)

    /** True when the device carries a custom name. */
    val hasCustomName: Boolean get() = metadata?.hasCustomName == true

    /**
     * The name to show: the custom one first, then the router's.
     *
     * The router's name is used even when there is no metadata at all: the
     * metadata are a **local addition**, and their absence must never hide
     * what the router said.
     */
    val displayName: String
        get() {
            val custom = metadata?.customName?.trim()
            if (!custom.isNullOrEmpty()) return custom

            val original = device.name.trim()
            return if (original.isEmpty() || original == DeviceIdentity.UNKNOWN_DEVICE_NAME) {
                DeviceMetadata.UNNAMED_DEVICE_LABEL
            } else {
                original
            }
        }

    /**
     * The name the router reported, when it is different from the name
     * shown.
     *
     * The router's name stays visible under the custom one: the user must
     * be able to tell which device it really is.
     */
    val originalName: String?
        get() {
            if (!hasCustomName) return null
            val original = device.name.trim()
            return original.takeIf {
                it.isNotEmpty() && it != DeviceIdentity.UNKNOWN_DEVICE_NAME
            }
        }

    /** When the device was last observed by a confirmed read. */
    val lastSeenMillis: Long? get() = metadata?.lastSeenMillis

    /** The signal level, when the router reported one. */
    val rssiDbm: Int? get() = device.signal?.rssiDbm

    /**
     * The bands the device is associated with.
     *
     * A wired device has none: it holds a lease on an Ethernet interface
     * and is associated with no radio.
     */
    val bands: Set<WifiBand> get() = device.bands

    /** True when the device is on no known radio. */
    val isWired: Boolean get() = device.bands.isEmpty()
}

/**
 * The order the device list is shown in.
 */
enum class DeviceSort(val label: String) {
    /** By name, case-insensitive, with the French collation rules. */
    NAME("Nom"),

    /** By IPv4 address, octet by octet, numerically. */
    IP("Adresse IP"),

    /** By signal strength, the strongest first. */
    SIGNAL("Signal"),
}

/**
 * The band filter of the device list.
 */
enum class DeviceBandFilter(val label: String) {
    /** Every device the router knows about. */
    ALL("Tous"),

    /** Associated with the 2.4 GHz radio. */
    BAND_2_4("2,4 GHz"),

    /** Associated with the 5 GHz radio. */
    BAND_5("5 GHz"),

    /** Associated with no radio: a wired interface. */
    WIRED("Filaire"),
}

/**
 * The ordering of the device list.
 *
 * The rules are **pure functions**: they hold no state and can be tested
 * without a device, without a coroutine and without waiting.
 */
object DeviceOrdering {

    /**
     * The French collation.
     *
     * It sorts the accented letters where they belong — "Écran" between
     * "E" and "F" — and the digits before the letters, which is the
     * convention of every file explorer.
     */
    private val COLLATOR: Collator = Collator.getInstance(Locale.FRANCE).apply {
        strength = Collator.PRIMARY
    }

    /**
     * The comparator of each sort criterion.
     *
     * Every comparator ends with a **tie-breaker**: two devices with the
     * same name, the same address or the same signal must keep the same
     * relative order between two polls. Without it the list would flicker
     * every time the router is polled.
     */
    fun comparator(sort: DeviceSort): Comparator<DeviceRow> {
        val primary: Comparator<DeviceRow> = when (sort) {
            DeviceSort.NAME -> compareBy(COLLATOR) { it.displayName }
            DeviceSort.IP -> compareBy(IPV4_COMPARATOR) { it.device.ip }
            DeviceSort.SIGNAL -> SIGNAL_COMPARATOR
        }
        return primary
            .thenBy { it.device.ip }
            .thenBy { it.device.mac }
            .thenBy { it.key.orEmpty() }
    }

    /**
     * Compares two IPv4 addresses by their octets, numerically.
     *
     * A lexicographic comparison would sort "192.168.1.100" before
     * "192.168.1.2" and "192.168.10.1" before "192.168.2.1": both are
     * wrong. The octets are compared as numbers, from the most significant
     * one.
     */
    private val IPV4_COMPARATOR: Comparator<String> = Comparator { left, right ->
        val a = left.toIpv4Octets()
        val b = right.toIpv4Octets()
        when {
            a == null && b == null -> left.compareTo(right)
            a == null -> 1
            b == null -> -1
            else -> {
                var result = 0
                for (index in 0 until minOf(a.size, b.size)) {
                    result = a[index].compareTo(b[index])
                    if (result != 0) break
                }
                if (result == 0) a.size.compareTo(b.size) else result
            }
        }
    }

    /** The four octets of an IPv4 address, or null when it is not one. */
    private fun String.toIpv4Octets(): List<Int>? {
        val parts = split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        return octets
    }

    /**
     * Orders the devices by signal strength, the strongest first.
     *
     * A device without a signal — a wired one, or a Wi-Fi association the
     * router did not give a level for — is **not** given a fake value: it
     * goes to the end of the list. Mixing it in would claim a level that
     * does not exist, and would put a wired device before a very distant
     * Wi-Fi one.
     */
    private val SIGNAL_COMPARATOR: Comparator<DeviceRow> = Comparator { left, right ->
        val a = left.rssiDbm
        val b = right.rssiDbm
        when {
            a != null && b != null -> b.compareTo(a)
            a != null -> -1
            b != null -> 1
            else -> 0
        }
    }
}

/**
 * The filtering of the device list.
 *
 * It is **purely local**: it never reaches the router and never triggers a
 * read. The devices are already in memory.
 */
object DeviceFiltering {

    /**
     * Whether a device matches the band filter.
     *
     * The filters **overlap** on purpose: a device associated with both
     * radios appears in the 2.4 GHz filter **and** in the 5 GHz one,
     * because the router really sees it on both.
     */
    fun matchesBand(device: RouterDevice, filter: DeviceBandFilter): Boolean =
        when (filter) {
            DeviceBandFilter.ALL -> true
            DeviceBandFilter.BAND_2_4 -> WifiBand.BAND_2_4_GHZ in device.bands
            DeviceBandFilter.BAND_5 -> WifiBand.BAND_5_GHZ in device.bands
            DeviceBandFilter.WIRED -> device.bands.isEmpty()
        }

    /**
     * Whether a device matches the search.
     *
     * The search covers the custom name, the name the router reported, the
     * address and the MAC address. The comparison ignores the case and the
     * accent composition.
     */
    fun matchesQuery(row: DeviceRow, needle: String): Boolean {
        if (needle.isBlank()) return true
        return dev.wifiadmin.app.data.CustomName.matches(row.metadata?.customName, needle) ||
            dev.wifiadmin.app.data.CustomName.matches(row.device.name, needle) ||
            row.device.ip.contains(needle.trim(), ignoreCase = true) ||
            row.device.mac.contains(needle.trim(), ignoreCase = true)
    }
}
