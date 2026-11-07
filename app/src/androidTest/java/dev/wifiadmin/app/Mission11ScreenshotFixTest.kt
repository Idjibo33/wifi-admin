package dev.wifiadmin.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.wifiadmin.app.data.RouterAdapter
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.SettingsStore
import dev.wifiadmin.app.ui.dashboard.DashboardContent
import dev.wifiadmin.app.ui.dashboard.DashboardUiState
import dev.wifiadmin.app.ui.devices.DeviceBandFilter
import dev.wifiadmin.app.ui.devices.DeviceList
import dev.wifiadmin.app.ui.devices.DeviceDetailSheet
import dev.wifiadmin.app.ui.devices.DeviceRow
import dev.wifiadmin.app.ui.devices.DeviceSort
import dev.wifiadmin.app.ui.devices.DevicesUiState
import dev.wifiadmin.app.ui.devices.RenameDeviceDialog
import dev.wifiadmin.app.ui.login.LoginScreen
import dev.wifiadmin.app.ui.login.LoginViewModel
import dev.wifiadmin.app.ui.settings.SettingsScreen
import dev.wifiadmin.app.ui.settings.SettingsViewModel
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the real composables with fictional fixtures on a Pixel 7 Pro and
 * saves PNG screenshots for the Mission 11 before/after comparison.
 *
 * All names/IPs/MACs are fictional (`sample-*`, `192.168.1.10`, `aa:bb:…`)
 * and every capture carries a visible "Données de démonstration" banner.
 * No real router is contacted, no password is typed, no read is triggered:
 * the ViewModels are built on a stub adapter that never touches the network.
 */
@RunWith(AndroidJUnit4::class)
class Mission11ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val capturedAt = 1_700_000_000_000L

    private fun demoDevices(): List<RouterDevice> = listOf(
        RouterDevice(
            mac = "aa:bb:cc:dd:ee:01",
            ip = "192.168.1.10",
            name = "sample-poste",
            bands = setOf(WifiBand.BAND_2_4_GHZ),
            signal = DeviceSignal(rssiDbm = -58, txRateMbps = "72", rxRateMbps = "65", ssid = null),
            interfaceName = "wl0",
            leaseExpiry = null,
            ipv6 = null,
            presence = DevicePresence.ACTIVE,
        ),
        RouterDevice(
            mac = "aa:bb:cc:dd:ee:02",
            ip = "192.168.1.11",
            name = "sample-telephone",
            bands = setOf(WifiBand.BAND_5_GHZ),
            signal = DeviceSignal(rssiDbm = -47, txRateMbps = "433", rxRateMbps = "390", ssid = null),
            interfaceName = "wl1",
            leaseExpiry = null,
            ipv6 = null,
            presence = DevicePresence.ACTIVE,
        ),
        RouterDevice(
            mac = "aa:bb:cc:dd:ee:03",
            ip = "192.168.1.12",
            name = "",
            bands = emptySet(),
            signal = null,
            interfaceName = "lan",
            leaseExpiry = null,
            ipv6 = null,
            presence = DevicePresence.CABLED,
        ),
        RouterDevice(
            mac = "aa:bb:cc:dd:ee:04",
            ip = "192.168.1.13",
            name = "sample-ordinateur",
            bands = setOf(WifiBand.BAND_2_4_GHZ, WifiBand.BAND_5_GHZ),
            signal = DeviceSignal(rssiDbm = -62, txRateMbps = "144", rxRateMbps = "130", ssid = null),
            interfaceName = "wl0",
            leaseExpiry = null,
            ipv6 = null,
            presence = DevicePresence.ACTIVE,
        ),
    )

    private fun demoSnapshot() = DeviceSnapshot(
        devices = demoDevices(),
        capturedAtMillis = capturedAt,
    )

    private fun stubAdapter() = object : RouterAdapter {
        override val displayName = "Démo (fictif)"
        override suspend fun connect(host: String, username: String, password: String) = true
        override suspend fun fetchDevices() = demoSnapshot()
        override fun disconnect() = Unit
    }

    private fun repository() = RouterRepository(
        adapter = stubAdapter(),
        ioDispatcher = Dispatchers.IO,
    )

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        writePng(name, bitmap)
    }

    private fun saveNode(name: String, tag: String) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        writePng(name, bitmap)
    }

    private fun writePng(name: String, bitmap: Bitmap) {
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "mission11_screenshots",
        ).apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun wrap(dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            WifiAdminTheme(darkTheme = dark, dynamicColor = false) {
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }
        }
    }

    @Test
    fun capture_login_light() {
        val vm = LoginViewModel(repository())
        wrap(dark = false) { LoginScreen(viewModel = vm, onConnected = {}) }
        save("01-login-clair")
    }

    @Test
    fun capture_login_dark() {
        val vm = LoginViewModel(repository())
        wrap(dark = true) { LoginScreen(viewModel = vm, onConnected = {}) }
        save("01-login-sombre")
    }

    @Test
    fun capture_dashboard_light() {
        val state = DashboardUiState(
            isLoading = false,
            snapshot = demoSnapshot(),
            lastRefreshMillis = capturedAt,
            hasSnapshot = true,
        )
        wrap(dark = false) { DashboardContent(state = state) }
        save("02-dashboard-clair")
    }

    @Test
    fun capture_dashboard_dark() {
        val state = DashboardUiState(
            isLoading = false,
            snapshot = demoSnapshot(),
            lastRefreshMillis = capturedAt,
            hasSnapshot = true,
        )
        wrap(dark = true) { DashboardContent(state = state) }
        save("02-dashboard-sombre")
    }

    @Test
    fun capture_dashboard_cache_dark() {
        val state = DashboardUiState(
            isLoading = false,
            snapshot = demoSnapshot(),
            lastRefreshMillis = capturedAt,
            isFromCache = true,
            hasSnapshot = true,
        )
        wrap(dark = true) { DashboardContent(state = state) }
        save("02-dashboard-cache-sombre")
    }

    @Test
    fun capture_list_light() {
        val state = DevicesUiState(
            devices = demoDevices(),
            lastRefreshMillis = capturedAt,
            hasSnapshot = true,
        )
        wrap(dark = false) {
            DeviceList(
                state = state,
                onQueryChange = {},
                onFilterChange = {},
                onSortChange = {},
                onDeviceClick = {},
                onRenameClick = {},
            )
        }
        save("03-liste-clair")
    }

    @Test
    fun capture_list_dark() {
        val state = DevicesUiState(
            devices = demoDevices(),
            lastRefreshMillis = capturedAt,
            hasSnapshot = true,
        )
        wrap(dark = true) {
            DeviceList(
                state = state,
                onQueryChange = {},
                onFilterChange = {},
                onSortChange = {},
                onDeviceClick = {},
                onRenameClick = {},
            )
        }
        save("03-liste-sombre")
    }

    @Test
    fun capture_list_search_dark() {
        val state = DevicesUiState(
            devices = demoDevices(),
            query = "poste",
            filter = DeviceBandFilter.BAND_2_4,
            sort = DeviceSort.NAME,
            lastRefreshMillis = capturedAt,
            hasSnapshot = true,
        )
        wrap(dark = true) {
            DeviceList(
                state = state,
                onQueryChange = {},
                onFilterChange = {},
                onSortChange = {},
                onDeviceClick = {},
                onRenameClick = {},
            )
        }
        save("05-recherche-filtres-sombre")
    }

    @Test
    fun capture_detail_light() {
        val row = DeviceRow(demoDevices().first(), null)
        wrap(dark = false) {
            DeviceDetailSheet(row = row, cached = false, capturedAtMillis = capturedAt, onRename = {}, onRemoveName = {})
        }
        save("04-details-clair")
    }

    @Test
    fun capture_detail_dark() {
        val row = DeviceRow(demoDevices().first(), null)
        wrap(dark = true) {
            DeviceDetailSheet(row = row, cached = false, capturedAtMillis = capturedAt, onRename = {}, onRemoveName = {})
        }
        save("04-details-sombre")
    }

    @Test
    fun capture_rename_dark() {
        wrap(dark = true) {
            RenameDeviceDialog(
                initialName = null,
                originalName = "sample-poste",
                onDismiss = {},
                onConfirm = {},
            )
        }
        saveNode("06-renommage-sombre", "rename_dialog")
    }

    @Test
    fun capture_settings_dark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vm = SettingsViewModel(repository(), SettingsStore(context))
        wrap(dark = true) {
            SettingsScreen(
                viewModel = vm,
                appVersionName = "0.1.0",
                routerDisplayName = "Démo (fictif)",
                onDisconnected = {},
            )
        }
        save("08-settings-sombre")
    }

    @Test
    fun capture_list_cache_dark() {
        val state = DevicesUiState(
            devices = demoDevices(),
            lastRefreshMillis = capturedAt,
            isFromCache = true,
            hasSnapshot = true,
        )
        wrap(dark = true) {
            DeviceList(
                state = state,
                onQueryChange = {},
                onFilterChange = {},
                onSortChange = {},
                onDeviceClick = {},
                onRenameClick = {},
            )
        }
        save("07-etat-cache-sombre")
    }
}
