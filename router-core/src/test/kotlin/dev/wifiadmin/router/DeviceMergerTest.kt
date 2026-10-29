package dev.wifiadmin.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMergerTest {

    private val now = 1_700_000_000L // 2023-11-14T22:13:20Z

    private fun lease(
        mac: String = "aa:bb:cc:dd:ee:01",
        ip: String = "192.168.1.50",
        hostname: String? = "phone",
        interfaceName: String? = "wlan",
        expires: String? = null,
        ipv6: String? = null,
    ) = DhcpLease(
        mac = mac,
        ip = ip,
        hostname = hostname,
        interfaceName = interfaceName,
        leaseExpiry = expires,
        ipv6 = ipv6,
    )

    private fun association(
        mac: String? = "aa:bb:cc:dd:ee:01",
        ip: String? = "192.168.1.50",
        rssi: Int? = -60,
        txRate: String? = "72.2",
        rxRate: String? = "54.0",
        ssid: String? = "Home",
    ) = WifiAssociation(
        mac = mac,
        ip = ip,
        rssiDbm = rssi,
        txRateMbps = txRate,
        rxRateMbps = rxRate,
        ssid = ssid,
    )

    @Test
    fun `a wifi association is joined to the dhcp lease by mac`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(mac = "AA:BB:CC:DD:EE:01")),
            wifi24 = listOf(association(mac = "aa:bb:cc:dd:ee:01")),
            wifi5 = emptyList(),
            nowEpochSeconds = now,
        )

        val device = snapshot.devices.single()
        assertEquals("aa:bb:cc:dd:ee:01", device.mac)
        assertEquals(setOf(WifiBand.BAND_2_4_GHZ), device.bands)
        assertEquals(DevicePresence.ACTIVE, device.presence)
        assertEquals(-60, device.signal?.rssiDbm)
    }

    @Test
    fun `ip matching is used only when the mac does not match anything`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(mac = "11:22:33:44:55:66", ip = "192.168.1.77")),
            wifi24 = emptyList(),
            wifi5 = listOf(association(mac = "aa:bb:cc:dd:ee:09", ip = "192.168.1.77")),
            nowEpochSeconds = now,
        )

        val device = snapshot.devices.single()
        assertEquals("11:22:33:44:55:66", device.mac)
        assertEquals(setOf(WifiBand.BAND_5_GHZ), device.bands)
        assertEquals(DevicePresence.ACTIVE, device.presence)
    }

    @Test
    fun `a matching mac wins over a matching ip`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(mac = "aa:bb:cc:dd:ee:01", ip = "192.168.1.50")),
            wifi24 = listOf(association(mac = "aa:bb:cc:dd:ee:01", ip = "192.168.1.50")),
            wifi5 = listOf(association(mac = "99:99:99:99:99:99", ip = "192.168.1.50")),
            nowEpochSeconds = now,
        )

        // The lease joins the association with the same MAC. An association with
        // another MAC but the same IP is not a second device: the MAC match wins.
        val leased = snapshot.devices.first { it.mac == "aa:bb:cc:dd:ee:01" }
        assertEquals(setOf(WifiBand.BAND_2_4_GHZ), leased.bands)
        assertEquals(1, snapshot.devices.size)
    }

    @Test
    fun `a mac present in both bands keeps both bands`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease()),
            wifi24 = listOf(association(rssi = -70)),
            wifi5 = listOf(association(rssi = -45)),
            nowEpochSeconds = now,
        )

        val device = snapshot.devices.single()
        assertEquals(setOf(WifiBand.BAND_2_4_GHZ, WifiBand.BAND_5_GHZ), device.bands)
        assertEquals(1, snapshot.devices.size)
    }

    @Test
    fun `a 24ghz entry does not overwrite the 5ghz signal`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease()),
            wifi24 = listOf(association(rssi = -70, txRate = "72.2", ssid = "Home24")),
            wifi5 = listOf(association(rssi = -45, txRate = "866.0", ssid = "Home5")),
            nowEpochSeconds = now,
        )

        val device = snapshot.devices.single()
        assertEquals(-45, device.signal?.rssiDbm)
        assertEquals("866.0", device.signal?.txRateMbps)
        assertEquals("Home5", device.signal?.ssid)
    }

    @Test
    fun `duplicate dhcp leases with the same mac are deduplicated`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(
                lease(mac = "AA:BB:CC:DD:EE:01", ip = "192.168.1.50"),
                lease(mac = "aa:bb:cc:dd:ee:01", ip = "192.168.1.51"),
            ),
            wifi24 = emptyList(),
            wifi5 = emptyList(),
            nowEpochSeconds = now,
        )

        assertEquals(1, snapshot.devices.size)
    }

    @Test
    fun `a lease with a blank mac is kept`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(mac = "", ip = "192.168.1.90", hostname = "guest")),
            wifi24 = emptyList(),
            wifi5 = emptyList(),
            nowEpochSeconds = now,
        )

        assertEquals(1, snapshot.devices.size)
        assertEquals("guest", snapshot.devices.single().name)
    }

    @Test
    fun `a wired interface without a wifi association is cabled`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(interfaceName = "lan")),
            wifi24 = emptyList(),
            wifi5 = emptyList(),
            nowEpochSeconds = now,
        )

        assertEquals(DevicePresence.CABLED, snapshot.devices.single().presence)
    }

    @Test
    fun `a wifi interface with a current association is active`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(interfaceName = "wlan")),
            wifi24 = listOf(association()),
            wifi5 = emptyList(),
            nowEpochSeconds = now,
        )

        assertEquals(DevicePresence.ACTIVE, snapshot.devices.single().presence)
    }

    @Test
    fun `a wifi interface without an association and an unparseable expiry is unknown`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(interfaceName = "wlan", expires = "24h")),
            wifi24 = emptyList(),
            wifi5 = emptyList(),
            nowEpochSeconds = now,
        )

        assertEquals(DevicePresence.UNKNOWN, snapshot.devices.single().presence)
    }

    @Test
    fun `a wifi interface without an association and an expired lease is stale`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(interfaceName = "wlan", expires = (now - 3600).toString())),
            wifi24 = emptyList(),
            wifi5 = emptyList(),
            nowEpochSeconds = now,
        )

        assertEquals(DevicePresence.STALE, snapshot.devices.single().presence)
    }

    @Test
    fun `a wifi interface without an association and an expired marker is stale`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(interfaceName = "wlan", expires = "expired")),
            wifi24 = emptyList(),
            wifi5 = emptyList(),
            nowEpochSeconds = now,
        )

        assertEquals(DevicePresence.STALE, snapshot.devices.single().presence)
    }

    @Test
    fun `a blank interface is unknown`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(interfaceName = null)),
            wifi24 = emptyList(),
            wifi5 = emptyList(),
            nowEpochSeconds = now,
        )

        assertEquals(DevicePresence.UNKNOWN, snapshot.devices.single().presence)
    }

    @Test
    fun `a device missing from dhcp but present in wifi is included as active`() {
        val snapshot = DeviceMerger.merge(
            leases = emptyList(),
            wifi24 = emptyList(),
            wifi5 = listOf(association(mac = "ab:cd:ef:01:23:45", ip = "192.168.1.123")),
            nowEpochSeconds = now,
        )

        val device = snapshot.devices.single()
        assertEquals("ab:cd:ef:01:23:45", device.mac)
        assertEquals(DevicePresence.ACTIVE, device.presence)
        assertEquals(setOf(WifiBand.BAND_5_GHZ), device.bands)
    }

    @Test
    fun `a blank hostname becomes Unknown Device`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(hostname = null)),
            wifi24 = emptyList(),
            wifi5 = emptyList(),
            nowEpochSeconds = now,
        )

        assertEquals("Unknown Device", snapshot.devices.single().name)
    }

    @Test
    fun `the hostname NULL becomes Unknown Device`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(lease(hostname = "NULL")),
            wifi24 = emptyList(),
            wifi5 = emptyList(),
            nowEpochSeconds = now,
        )

        assertEquals("Unknown Device", snapshot.devices.single().name)
    }

    @Test
    fun `the snapshot order is deterministic`() {
        val snapshot = DeviceMerger.merge(
            leases = listOf(
                lease(mac = "cc:cc:cc:cc:cc:cc", ip = "192.168.1.33", hostname = "zeta", interfaceName = "lan"),
                lease(mac = "bb:bb:bb:bb:bb:bb", ip = "192.168.1.22", hostname = "alpha", interfaceName = "lan"),
                lease(mac = "dd:dd:dd:dd:dd:dd", ip = "192.168.1.44", hostname = "middle", interfaceName = "lan"),
            ),
            wifi24 = emptyList(),
            wifi5 = listOf(association(mac = "ee:ee:ee:ee:ee:ee", ip = "192.168.1.55")),
            nowEpochSeconds = now,
        )

        // The order only depends on the data, never on the order the router
        // reported the devices in. Devices without a real name come last.
        assertEquals(
            listOf("alpha", "middle", "zeta", "Unknown Device"),
            snapshot.devices.map { it.name },
        )

        // Merging the same data in another order gives the same list.
        val shuffled = DeviceMerger.merge(
            leases = listOf(lease(mac = "bb:bb:bb:bb:bb:bb", ip = "192.168.1.22", hostname = "alpha", interfaceName = "lan"),
                lease(mac = "dd:dd:dd:dd:dd:dd", ip = "192.168.1.44", hostname = "middle", interfaceName = "lan"),
                lease(mac = "cc:cc:cc:cc:cc:cc", ip = "192.168.1.33", hostname = "zeta", interfaceName = "lan")),
            wifi24 = emptyList(),
            wifi5 = listOf(association(mac = "ee:ee:ee:ee:ee:ee", ip = "192.168.1.55")),
            nowEpochSeconds = now,
        )
        assertEquals(
            snapshot.devices.map { it.name to it.ip },
            shuffled.devices.map { it.name to it.ip },
        )
    }
}
