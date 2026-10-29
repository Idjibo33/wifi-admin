package dev.wifiadmin.poc

import dev.wifiadmin.router.wifi.WifiBandFilter
import dev.wifiadmin.router.wifi.WifiMacEntry
import dev.wifiadmin.router.wifi.WifiMacFilterState
import dev.wifiadmin.router.wifi.WifiMacMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiFilterRendererTest {

    private val state = WifiMacFilterState(
        band = WifiBandFilter.BAND_2_4_GHZ,
        mode = WifiMacMode.DENY,
        entries = listOf(
            WifiMacEntry("AA:BB:CC:DD:EE:01", "phone"),
            WifiMacEntry("AA:BB:CC:DD:EE:02", "")
        )
    )

    @Test
    fun `the report never exposes a mac address`() {
        val output = WifiFilterRenderer.render(
            WifiBandFilter.BAND_2_4_GHZ,
            state,
            listOf("success", "datas", "datas.macfilter", "datas.maclist")
        )

        assertFalse(output.contains("AA:BB:CC:DD:EE:01"))
        assertFalse(output.contains("AA:BB:CC:DD:EE:02"))
        assertFalse(output.contains("AA:BB:CC"))
    }

    @Test
    fun `the report shows the mode the entry count and the structure`() {
        val output = WifiFilterRenderer.render(
            WifiBandFilter.BAND_2_4_GHZ,
            state,
            listOf("success", "datas.macfilter", "datas.maclist")
        )

        assertTrue(output.contains("deny"))
        assertTrue(output.contains("2.4 GHz"))
        assertTrue(output.contains("datas.macfilter"))
        assertTrue(output.contains("datas.maclist"))
    }

    @Test
    fun `the report shows how many entries carry a remark`() {
        val output = WifiFilterRenderer.render(
            WifiBandFilter.BAND_2_4_GHZ,
            state,
            emptyList()
        )

        assertTrue(output.contains("1 / 2"))
    }

    @Test
    fun `an error is reported without crashing`() {
        val output = WifiFilterRenderer.render(
            WifiBandFilter.BAND_5_GHZ,
            null,
            emptyList(),
            "the router could not be reached"
        )

        assertTrue(output.contains("ERREUR"))
        assertTrue(output.contains("5 GHz"))
    }
}
