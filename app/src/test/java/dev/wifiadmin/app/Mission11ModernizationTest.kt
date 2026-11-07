package dev.wifiadmin.app

import dev.wifiadmin.app.ui.components.signalBarsLevel
import dev.wifiadmin.app.ui.components.signalQualityLabel
import dev.wifiadmin.app.ui.components.statCardDescription
import dev.wifiadmin.app.ui.dashboard.DashboardUiState
import dev.wifiadmin.app.ui.dashboard.formatLastRefresh
import dev.wifiadmin.app.ui.devices.DeviceRow
import dev.wifiadmin.app.ui.devices.DeviceSection
import dev.wifiadmin.app.ui.devices.groupRows
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Targeted tests for the deep Mission 11 modernisation.
 *
 * They cover the invariants the redesign relies on without ever inventing
 * data: band overlap, signal thresholds, freshness wording, spoken
 * descriptions and list grouping.
 */
class Mission11ModernizationTest {

    @Test
    fun `a bi-band device is counted in both bands and once in the total`() {
        val snapshot = DeviceSnapshot(
            devices = listOf(
                device(mac = "aa:bb:cc:00:00:01", bands = setOf(WifiBand.BAND_2_4_GHZ, WifiBand.BAND_5_GHZ))
            ),
            capturedAtMillis = 1_700_000_000_000L
        )
        val state = DashboardUiState(isLoading = false, snapshot = snapshot, lastRefreshMillis = 1_700_000_000_000L)

        assertEquals(1, snapshot.devices.size)
        assertEquals(1, state.band24Count)
        assertEquals(1, state.band5Count)
        assertEquals(
            "the band sum may exceed the total; the UI must explain it, not correct it",
            2,
            state.band24Count + state.band5Count
        )
    }

    @Test
    fun `signal wording thresholds follow the router levels`() {
        assertEquals("Signal excellent", signalQualityLabel(-55))
        assertEquals("Signal bon", signalQualityLabel(-56))
        assertEquals("Signal bon", signalQualityLabel(-65))
        assertEquals("Signal moyen", signalQualityLabel(-66))
        assertEquals("Signal moyen", signalQualityLabel(-75))
        assertEquals("Signal faible", signalQualityLabel(-76))
        assertEquals("Signal faible", signalQualityLabel(-85))
        assertEquals("Signal très faible", signalQualityLabel(-86))
        assertEquals("Signal inconnu", signalQualityLabel(null))
    }

    @Test
    fun `signal bars levels follow the same thresholds`() {
        assertEquals(4, signalBarsLevel(-50))
        assertEquals(3, signalBarsLevel(-60))
        assertEquals(2, signalBarsLevel(-70))
        assertEquals(1, signalBarsLevel(-80))
        assertEquals(1, signalBarsLevel(-99))
        assertEquals(0, signalBarsLevel(null))
    }

    @Test
    fun `the last refresh wording includes the day`() {
        val formatted = formatLastRefresh(1_700_000_000_000L)
        assertNotNull(formatted)
        assertTrue(formatted!!.matches(Regex("""\d{2}/\d{2}/\d{4} à \d{2}:\d{2}:\d{2}""")))
        assertTrue(formatLastRefresh(null) == null)
    }

    @Test
    fun `the statistic card description combines label and value`() {
        assertEquals("Total : 3", statCardDescription("Total", "3"))
    }

    @Test
    fun `groupRows keeps Actives first then Wired then Previously seen`() {
        val active = device(mac = "aa:bb:cc:00:00:01", presence = DevicePresence.ACTIVE)
        val wired = device(mac = "aa:bb:cc:00:00:02", presence = DevicePresence.CABLED, bands = emptySet())
        val stale = device(mac = "aa:bb:cc:00:00:03", presence = DevicePresence.STALE)
        val rows = listOf(DeviceRow(stale, null), DeviceRow(wired, null), DeviceRow(active, null))

        val grouped = groupRows(rows)

        assertEquals(3, grouped.size)
        assertEquals(DeviceSection.ACTIVES, grouped[0].first)
        assertEquals(DeviceSection.WIRED, grouped[1].first)
        assertEquals(DeviceSection.PREVIOUSLY_SEEN, grouped[2].first)
        assertEquals("aa:bb:cc:00:00:01", grouped[0].second.first().device.mac)
    }

    @Test
    fun `groupRows omits empty sections and preserves sorted order`() {
        val a = device(mac = "aa:bb:cc:00:00:01", name = "b")
        val b = device(mac = "aa:bb:cc:00:00:02", name = "a")
        val grouped = groupRows(listOf(DeviceRow(a, null), DeviceRow(b, null)))

        assertEquals(1, grouped.size)
        assertEquals("b", grouped[0].second[0].device.name)
        assertEquals("a", grouped[0].second[1].device.name)
    }

    private fun device(
        mac: String,
        name: String = "sample",
        bands: Set<WifiBand> = setOf(WifiBand.BAND_5_GHZ),
        presence: DevicePresence = DevicePresence.ACTIVE
    ): RouterDevice = RouterDevice(
        mac = mac,
        ip = "192.168.1.10",
        name = name,
        bands = bands,
        signal = if (presence == DevicePresence.ACTIVE) DeviceSignal(-60, "72", "65", null) else null,
        interfaceName = if (presence == DevicePresence.CABLED) "lan" else "wl1",
        leaseExpiry = null,
        ipv6 = null,
        presence = presence
    )
}
