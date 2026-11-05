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
            WifiFilterError.NETWORK
        )

        assertTrue(output.contains("ERREUR"))
        assertTrue(output.contains("5 GHz"))
    }

    // ---- Mission 06 : intégrité et confidentialité du rapport ----

    /**
     * The Wi-Fi report is printed next to the firewall one, so it must offer
     * the same guarantee: no raw router message.
     */
    @Test
    fun `the report never prints a raw router message`() {
        val output = WifiFilterRenderer.render(
            WifiBandFilter.BAND_2_4_GHZ,
            null,
            emptyList(),
            WifiFilterError.REFUSED,
        )

        assertFalse("the raw message must not be printed", output.contains("hunter2"))
        assertFalse(output.contains("user=admin"))
        assertTrue(output.contains("ERREUR"))
    }

    @Test
    fun `the report never prints a key that looks like a secret`() {
        val output = WifiFilterRenderer.render(
            WifiBandFilter.BAND_2_4_GHZ,
            state,
            listOf("cmd", "sessionId", "passwd"),
            null,
        )

        assertFalse(output.contains("sessionId"))
        assertFalse(output.contains("passwd"))
        assertTrue(output.contains("cmd"))
    }

    /**
     * A key name is chosen by the router. A name containing a line break
     * could otherwise forge a line of the report.
     */
    @Test
    fun `the report cannot be forged with a key containing a line break`() {
        val output = WifiFilterRenderer.render(
            WifiBandFilter.BAND_2_4_GHZ,
            state,
            listOf("mac\nrègles lues              99"),
            null,
        )

        assertFalse("no forged line may appear", output.contains("règles lues"))
    }
}
