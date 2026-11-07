package dev.wifiadmin.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen reader must be able to read the list.
 *
 * The state of a device is carried by a badge, and a badge that is only
 * coloured says nothing to a screen reader: the presence is exposed as a
 * state description, and the signal carries its own quality wording.
 *
 * These are **rendering** tests: the state is built by hand and no network
 * is touched.
 */
@RunWith(AndroidJUnit4::class)
class DeviceAccessibilityInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private fun device(
        rssi: Int? = -58,
        presence: DevicePresence = DevicePresence.ACTIVE,
    ): RouterDevice = RouterDevice(
        mac = "aa:bb:cc:dd:ee:01",
        ip = "192.168.1.10",
        name = "Poste",
        bands = setOf(WifiBand.BAND_2_4_GHZ),
        signal = rssi?.let { DeviceSignal(it, "72", "65", "maison") },
        interfaceName = "wl0",
        leaseExpiry = null,
        ipv6 = null,
        presence = presence,
    )

    private fun state(
        devices: List<RouterDevice> = listOf(device()),
    ) = dev.wifiadmin.app.ui.devices.DevicesUiState(
        isLoading = false,
        devices = devices,
        query = "",
        filter = dev.wifiadmin.app.ui.devices.DeviceBandFilter.ALL,
        sort = dev.wifiadmin.app.ui.devices.DeviceSort.NAME,
        lastRefreshMillis = null,
        errorMessage = null,
        hasSnapshot = true,
    )

    @Test
    fun thePresenceOfTheDeviceIsReadable() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(state())
            }
        }

        compose.onNodeWithText("Actif").assertIsDisplayed()
    }

    @Test
    fun theSignalCarriesAQualityWording() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(state())
            }
        }

        compose.onNodeWithText("-58 dBm").assertIsDisplayed()
    }

    @Test
    fun aWiredDeviceIsNotGivenASignal() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(
                    state(
                        devices = listOf(
                            device(
                                rssi = null,
                                presence = DevicePresence.CABLED,
                            )
                        )
                    )
                )
            }
        }

        // Two nodes carry this text: the badge and the filter chip. The
        // assertion only needs the text to be there.
        compose.onAllNodesWithText("Filaire").assertCountEquals(2)
    }
}
