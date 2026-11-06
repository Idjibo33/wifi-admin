package dev.wifiadmin.app.ui.devices

import dev.wifiadmin.app.data.DeviceMetadata
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering and the filtering of the device list.
 *
 * They are **pure functions**: no device, no coroutine, no waiting. The
 * traps they must avoid are the numeric ordering of the IP addresses, the
 * devices without a signal, and the stability of the order between two
 * polls.
 */
class DeviceOrderingTest {

    private fun device(
        mac: String = "aa:bb:cc:dd:ee:01",
        ip: String = "192.168.1.10",
        name: String = "Poste",
        bands: Set<WifiBand> = setOf(WifiBand.BAND_2_4_GHZ),
        rssi: Int? = -50,
        presence: DevicePresence = DevicePresence.ACTIVE,
    ): RouterDevice = RouterDevice(
        mac = mac,
        ip = ip,
        name = name,
        bands = bands,
        signal = rssi?.let { DeviceSignal(it, "72", "65", "maison") },
        interfaceName = "wl0",
        leaseExpiry = null,
        ipv6 = null,
        presence = presence,
    )

    private fun row(
        device: RouterDevice,
        customName: String? = null,
    ): DeviceRow = DeviceRow(
        device = device,
        metadata = DeviceMetadata(
            key = device.mac,
            customName = customName,
            lastSeenMillis = 1_700_000_000_000L,
        ),
    )

    // -----------------------------------------------------------------
    // The sort by IP
    // -----------------------------------------------------------------

    @Test
    fun `the ip addresses are sorted numerically, not alphabetically`() {
        val rows = listOf(
            row(device(ip = "192.168.1.10")),
            row(device(mac = "aa:bb:cc:dd:ee:02", ip = "192.168.1.9")),
            row(device(mac = "aa:bb:cc:dd:ee:03", ip = "192.168.1.2")),
            row(device(mac = "aa:bb:cc:dd:ee:04", ip = "192.168.1.100")),
        )

        val sorted = rows.sortedWith(DeviceOrdering.comparator(DeviceSort.IP))

        assertEquals(
            listOf(
                "192.168.1.2",
                "192.168.1.9",
                "192.168.1.10",
                "192.168.1.100",
            ),
            sorted.map { it.device.ip }
        )
    }

    @Test
    fun `the ip addresses are sorted by their significant octets`() {
        val rows = listOf(
            row(device(ip = "192.168.10.1")),
            row(device(mac = "aa:bb:cc:dd:ee:02", ip = "192.168.2.1")),
            row(device(mac = "aa:bb:cc:dd:ee:03", ip = "192.168.1.1")),
        )

        val sorted = rows.sortedWith(DeviceOrdering.comparator(DeviceSort.IP))

        assertEquals(
            listOf("192.168.1.1", "192.168.2.1", "192.168.10.1"),
            sorted.map { it.device.ip }
        )
    }

    @Test
    fun `an unreadable address is sorted last`() {
        val rows = listOf(
            row(device(ip = "")),
            row(device(mac = "aa:bb:cc:dd:ee:02", ip = "192.168.1.5")),
        )

        val sorted = rows.sortedWith(DeviceOrdering.comparator(DeviceSort.IP))

        assertEquals("192.168.1.5", sorted.first().device.ip)
        assertEquals("", sorted.last().device.ip)
    }

    // -----------------------------------------------------------------
    // The sort by name
    // -----------------------------------------------------------------

    @Test
    fun `the names are sorted without regard to the case`() {
        val rows = listOf(
            row(device(mac = "aa:bb:cc:dd:ee:01", name = "smartphone")),
            row(device(mac = "aa:bb:cc:dd:ee:02", name = "Poste")),
            row(device(mac = "aa:bb:cc:dd:ee:03", name = "Imprimante")),
        )

        val sorted = rows.sortedWith(DeviceOrdering.comparator(DeviceSort.NAME))

        assertEquals(
            listOf("Imprimante", "Poste", "smartphone"),
            sorted.map { it.device.name }
        )
    }

    @Test
    fun `the custom name is the one that is sorted`() {
        val rows = listOf(
            row(device(mac = "aa:bb:cc:dd:ee:01", name = "zzz"), customName = "Téléphone"),
            row(device(mac = "aa:bb:cc:dd:ee:02", name = "aaa")),
        )

        val sorted = rows.sortedWith(DeviceOrdering.comparator(DeviceSort.NAME))

        assertEquals("aaa", sorted.first().device.name)
        assertEquals("Téléphone", sorted.last().displayName)
    }

    // -----------------------------------------------------------------
    // The sort by signal
    // -----------------------------------------------------------------

    @Test
    fun `the strongest signal comes first`() {
        val rows = listOf(
            row(device(mac = "aa:bb:cc:dd:ee:01", rssi = -85)),
            row(device(mac = "aa:bb:cc:dd:ee:02", rssi = -47)),
            row(device(mac = "aa:bb:cc:dd:ee:03", rssi = -58)),
        )

        val sorted = rows.sortedWith(DeviceOrdering.comparator(DeviceSort.SIGNAL))

        assertEquals(listOf(-47, -58, -85), sorted.map { it.rssiDbm })
    }

    @Test
    fun `a device without a signal is sorted last`() {
        // A wired device has no signal: it must not be given a fake value,
        // and it must not come before a distant Wi-Fi device.
        val rows = listOf(
            row(device(mac = "aa:bb:cc:dd:ee:01", rssi = -85)),
            row(
                device(
                    mac = "aa:bb:cc:dd:ee:02",
                    bands = emptySet(),
                    rssi = null,
                    presence = DevicePresence.CABLED,
                )
            ),
        )

        val sorted = rows.sortedWith(DeviceOrdering.comparator(DeviceSort.SIGNAL))

        assertEquals(-85, sorted.first().rssiDbm)
        assertEquals(null, sorted.last().rssiDbm)
    }

    @Test
    fun `a device whose signal has no level is sorted last`() {
        // The signal exists but carries no level: it is a different case
        // from a device that has no signal at all.
        val rows = listOf(
            row(device(mac = "aa:bb:cc:dd:ee:01", rssi = -85)),
            row(device(mac = "aa:bb:cc:dd:ee:02", rssi = null)),
        )

        val sorted = rows.sortedWith(DeviceOrdering.comparator(DeviceSort.SIGNAL))

        assertEquals(-85, sorted.first().rssiDbm)
        assertEquals(null, sorted.last().rssiDbm)
    }

    // -----------------------------------------------------------------
    // The stability
    // -----------------------------------------------------------------

    @Test
    fun `the order does not change between two identical polls`() {
        val rows = listOf(
            row(device(mac = "aa:bb:cc:dd:ee:01", name = "Meme", rssi = -50)),
            row(device(mac = "aa:bb:cc:dd:ee:02", name = "Meme", rssi = -50)),
            row(device(mac = "aa:bb:cc:dd:ee:03", name = "Meme", rssi = -50)),
        )

        val first = rows.sortedWith(DeviceOrdering.comparator(DeviceSort.SIGNAL))
        // The same devices, in another order: the result must be the same.
        val second = rows.reversed().sortedWith(DeviceOrdering.comparator(DeviceSort.SIGNAL))

        assertEquals(first.map { it.device.mac }, second.map { it.device.mac })
    }

    // -----------------------------------------------------------------
    // The filtering
    // -----------------------------------------------------------------

    @Test
    fun `the band filters keep the devices of that band`() {
        val dual = device(
            mac = "aa:bb:cc:dd:ee:04",
            bands = setOf(WifiBand.BAND_2_4_GHZ, WifiBand.BAND_5_GHZ),
        )
        val wired = device(
            mac = "aa:bb:cc:dd:ee:05",
            bands = emptySet(),
            presence = DevicePresence.CABLED,
        )
        val all = listOf(
            device(mac = "aa:bb:cc:dd:ee:01", bands = setOf(WifiBand.BAND_2_4_GHZ)),
            device(mac = "aa:bb:cc:dd:ee:02", bands = setOf(WifiBand.BAND_5_GHZ)),
            dual,
            wired,
        )

        val on24 = all.filter {
            DeviceFiltering.matchesBand(it, DeviceBandFilter.BAND_2_4)
        }
        val on5 = all.filter {
            DeviceFiltering.matchesBand(it, DeviceBandFilter.BAND_5)
        }
        val cabled = all.filter {
            DeviceFiltering.matchesBand(it, DeviceBandFilter.WIRED)
        }
        val everything = all.filter {
            DeviceFiltering.matchesBand(it, DeviceBandFilter.ALL)
        }

        assertEquals(2, on24.size)
        assertEquals(2, on5.size)
        assertEquals(1, cabled.size)
        assertEquals(4, everything.size)
        // A dual-band device is really on both radios: it belongs to both
        // filters.
        assertTrue(on24.any { it.mac == dual.mac })
        assertTrue(on5.any { it.mac == dual.mac })
    }

    @Test
    fun `the search finds the custom name`() {
        val renamed = row(device(name = "poste"), customName = "Téléphone de Marie")

        assertTrue(DeviceFiltering.matchesQuery(renamed, "téléphone"))
        assertTrue(DeviceFiltering.matchesQuery(renamed, "MARIE"))
        assertFalse(DeviceFiltering.matchesQuery(renamed, "poste") == false)
    }

    @Test
    fun `the search finds the name of the router`() {
        val renamed = row(device(name = "poste"), customName = "Téléphone de Marie")

        assertTrue(DeviceFiltering.matchesQuery(renamed, "poste"))
    }

    @Test
    fun `the search finds the address and the mac`() {
        val row = row(device(ip = "192.168.1.24", mac = "aa:bb:cc:dd:ee:07"))

        assertTrue(DeviceFiltering.matchesQuery(row, "192.168.1.24"))
        assertTrue(DeviceFiltering.matchesQuery(row, "aa:bb:cc:dd:ee:07"))
    }

    @Test
    fun `an empty search keeps every device`() {
        val row = row(device())

        assertTrue(DeviceFiltering.matchesQuery(row, ""))
        assertTrue(DeviceFiltering.matchesQuery(row, "   "))
    }
}
