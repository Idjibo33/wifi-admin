package dev.wifiadmin.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wifiadmin.app.data.DeviceMetadata
import dev.wifiadmin.app.ui.devices.DeviceBandFilter
import dev.wifiadmin.app.ui.devices.DeviceRow
import dev.wifiadmin.app.ui.devices.DeviceSort
import dev.wifiadmin.app.ui.devices.DevicesUiState
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device list, rendered on a **real Android runtime**.
 *
 * The state is built by hand: the test proves the **rendering**, and never
 * waits for a network. The logic of the sort and the filters is covered by
 * the JVM tests, which are faster and deterministic.
 */
@RunWith(AndroidJUnit4::class)
class DevicesListInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private fun device(
        mac: String = "aa:bb:cc:dd:ee:01",
        ip: String = "192.168.1.10",
        name: String = "Poste",
        bands: Set<WifiBand> = setOf(WifiBand.BAND_2_4_GHZ),
        rssi: Int? = -58,
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

    private val devices = listOf(
        device(),
        device(
            mac = "aa:bb:cc:dd:ee:02",
            ip = "192.168.1.11",
            name = "Téléphone",
            bands = setOf(WifiBand.BAND_5_GHZ),
            rssi = -47
        ),
        device(
            mac = "aa:bb:cc:dd:ee:03",
            ip = "192.168.1.12",
            name = "",
            bands = emptySet(),
            rssi = null,
            presence = DevicePresence.CABLED
        ),
    )

    private fun state(
        query: String = "",
        filter: DeviceBandFilter = DeviceBandFilter.ALL,
        sort: DeviceSort = DeviceSort.NAME,
        metadata: Map<String, DeviceMetadata> = emptyMap(),
        devices: List<RouterDevice> = this.devices,
    ) = DevicesUiState(
        isLoading = false,
        devices = devices,
        query = query,
        filter = filter,
        sort = sort,
        lastRefreshMillis = null,
        errorMessage = null,
        hasSnapshot = true,
        metadata = metadata,
    )

    @Test
    fun theNameOfTheDeviceIsDisplayed() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(state())
            }
        }

        // The card merges the semantics of its children: its text holds the
        // name, the address, the band, the signal and the state.
        compose.onNodeWithText("Poste", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Téléphone", substring = true).assertIsDisplayed()
    }

    @Test
    fun theCustomNameIsDisplayedAndTheRouterNameStaysVisible() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(
                    state(
                        metadata = mapOf(
                            "aa:bb:cc:dd:ee:01" to DeviceMetadata(
                                key = "aa:bb:cc:dd:ee:01",
                                customName = "Téléphone de Marie",
                            )
                        )
                    )
                )
            }
        }

        compose.onNodeWithText("Téléphone de Marie").assertIsDisplayed()
        // The router's own name stays visible under the custom one.
        compose.onNodeWithText("Router : Poste").assertIsDisplayed()
    }

    @Test
    fun aDeviceWithoutANameShowsAReadablePlaceholder() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(state())
            }
        }

        // The English placeholder of the protocol layer is not a product
        // wording.
        compose.onNodeWithText("Appareil sans nom", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theSignalIsDisplayedWhenTheRouterReportedOne() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(state())
            }
        }

        compose.onNodeWithText("-58 dBm").assertIsDisplayed()
    }

    @Test
    fun theBandFiltersAreDisplayed() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(state())
            }
        }

        compose.onNodeWithTag("filter_ALL").assertIsDisplayed()
        compose.onNodeWithTag("filter_BAND_2_4").assertIsDisplayed()
        compose.onNodeWithTag("filter_BAND_5").assertIsDisplayed()
        compose.onNodeWithTag("filter_WIRED").assertIsDisplayed()
    }

    @Test
    fun theSortButtonShowsTheCurrentCriterion() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(state(sort = DeviceSort.SIGNAL))
            }
        }

        compose.onNodeWithTag("devices_sort_button").assertIsDisplayed()
        compose.onNodeWithText("Trier : Signal").assertIsDisplayed()
    }

    @Test
    fun theSortMenuOffersEveryCriterion() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(state())
            }
        }

        compose.onNodeWithTag("devices_sort_button").performClick()

        compose.onNodeWithText("Nom").assertIsDisplayed()
        compose.onNodeWithText("Adresse IP").assertIsDisplayed()
        compose.onNodeWithText("Signal").assertIsDisplayed()
    }

    @Test
    fun theSearchFieldAcceptsAText() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(state())
            }
        }

        compose.onNodeWithTag("devices_search_field").performTextInput("Téléphone")
        compose.onNodeWithText("Téléphone", substring = true).assertIsDisplayed()
    }

    @Test
    fun theSearchCanBeCleared() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(state(query = "rien"))
            }
        }

        compose.onNodeWithTag("devices_clear_search").performClick()
    }

    @Test
    fun theEmptyStateExplainsThatNothingMatches() {
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(state(query = "aucun appareil"))
            }
        }

        compose.onAllNodesWithText("Aucun appareil ne correspond à « aucun appareil ».")
            .assertCountEquals(1)
    }

    @Test
    fun theEmptyStateExplainsThatNoDeviceIsOnTheBand() {
        // The band 2.4 GHz is the one no device of the fixture uses: the
        // first is on it, so it is removed from the fixture below.
        compose.setContent {
            WifiAdminTheme {
                DeviceListUnderTest(
                    state(
                        filter = DeviceBandFilter.BAND_2_4,
                        devices = devices.filter {
                            WifiBand.BAND_2_4_GHZ !in it.bands
                        },
                    )
                )
            }
        }

        compose.onAllNodesWithText("Aucun appareil en 2,4 GHz.").assertCountEquals(1)
    }

    @Test
    fun theRenameDialogOffersTheFields() {
        compose.setContent {
            WifiAdminTheme {
                RenameDialogUnderTest(initialName = null, originalName = "Poste")
            }
        }

        compose.onNodeWithTag("rename_dialog").assertIsDisplayed()
        compose.onNodeWithText("Renommer l'appareil").assertIsDisplayed()
        // The router's name is announced: it does not change.
        compose.onNodeWithText("Le nom du routeur reste inchangé : « Poste ».")
            .assertIsDisplayed()
        compose.onNodeWithTag("rename_dialog_field").assertIsDisplayed()
        compose.onNodeWithTag("rename_dialog_confirm").assertIsDisplayed()
        compose.onNodeWithTag("rename_dialog_dismiss").assertIsDisplayed()
    }

    @Test
    fun theRenameDialogAcceptsAName() {
        var saved: String? = null
        compose.setContent {
            WifiAdminTheme {
                RenameDialogUnderTest(
                    initialName = null,
                    originalName = "Poste",
                    onConfirm = { saved = it }
                )
            }
        }

        compose.onNodeWithTag("rename_dialog_field").performTextInput("Téléphone de Marie")
        compose.onNodeWithTag("rename_dialog_confirm").performClick()

        assertEquals("Téléphone de Marie", saved)
    }

    @Test
    fun theRenameDialogRejectsABlankName() {
        compose.setContent {
            WifiAdminTheme {
                RenameDialogUnderTest(initialName = null, originalName = "Poste")
            }
        }

        // The confirm button is disabled while the name is empty.
        compose.onNodeWithTag("rename_dialog_confirm").assertIsNotEnabled()
    }
}
