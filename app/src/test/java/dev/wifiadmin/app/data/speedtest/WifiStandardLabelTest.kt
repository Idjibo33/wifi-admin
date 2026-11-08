package dev.wifiadmin.app.data.speedtest

import android.net.wifi.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wifi standards through official ScanResult constants. */
class WifiStandardLabelTest {

    @Test fun `wifi 4 is 11n not wifi 5`() {
        assertEquals("Wi-Fi 4 (802.11n)", wifiStandardLabel(ScanResult.WIFI_STANDARD_11N))
    }

    @Test fun `wifi 5 is 11ac`() {
        assertEquals("Wi-Fi 5 (802.11ac)", wifiStandardLabel(ScanResult.WIFI_STANDARD_11AC))
    }

    @Test fun `wifi 6 is 11ax on 5 ghz`() {
        assertEquals("Wi-Fi 6 (802.11ax)", wifiStandardLabel(ScanResult.WIFI_STANDARD_11AX, 5180))
    }

    @Test fun `wifi 6e is 11ax on 6 ghz`() {
        assertEquals("Wi-Fi 6E (802.11ax, 6 GHz)", wifiStandardLabel(ScanResult.WIFI_STANDARD_11AX, 6105))
    }

    @Test fun `wigig is 11ad`() {
        assertEquals("WiGig (802.11ad, 60 GHz)", wifiStandardLabel(ScanResult.WIFI_STANDARD_11AD))
    }

    @Test fun `wifi 7 is 11be`() {
        assertEquals("Wi-Fi 7 (802.11be)", wifiStandardLabel(ScanResult.WIFI_STANDARD_11BE))
    }

    @Test fun `legacy and unknown`() {
        assertEquals("802.11a/b/g", wifiStandardLabel(ScanResult.WIFI_STANDARD_LEGACY))
        assertNull(wifiStandardLabel(ScanResult.WIFI_STANDARD_UNKNOWN))
        assertNull(wifiStandardLabel(99))
        assertNull(wifiStandardLabel(-1))
    }

    @Test fun `frequency bands`() {
        assertEquals("2,4 GHz", frequencyBandLabel(2412))
        assertNull(frequencyBandLabel(2485))
        assertEquals("5 GHz", frequencyBandLabel(5180))
        assertNull(frequencyBandLabel(5925))
        assertEquals("6 GHz", frequencyBandLabel(5935))
        assertEquals("6 GHz", frequencyBandLabel(6105))
        assertEquals("60 GHz (WiGig)", frequencyBandLabel(58320))
        assertNull(frequencyBandLabel(null))
        assertNull(frequencyBandLabel(850))
    }
}
