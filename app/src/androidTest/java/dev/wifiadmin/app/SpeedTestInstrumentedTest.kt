package dev.wifiadmin.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkIdentity
import dev.wifiadmin.app.data.NetworkMonitor
import dev.wifiadmin.app.data.speedtest.FakeSpeedTestEngine
import dev.wifiadmin.app.data.speedtest.SpeedTestRepository
import dev.wifiadmin.app.data.speedtest.WifiLinkInfo
import dev.wifiadmin.app.ui.speedtest.SpeedTestContent
import dev.wifiadmin.app.ui.speedtest.SpeedTestUiState
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import dev.wifiadmin.router.DevicePresence
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap

/** Renders the fake states; no network is ever touched. */
@RunWith(AndroidJUnit4::class)
class SpeedTestInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "mission12_screenshots",
        ).apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun wrap(dark: Boolean, state: SpeedTestUiState, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            WifiAdminTheme(darkTheme = dark, dynamicColor = false) {
                androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) { content() }
            }
        }
    }

    @Test fun capture_ready_light() {
        wrap(false, SpeedTestUiState()) {
            SpeedTestContent(
                state = SpeedTestUiState(
                    wifiLink = WifiLinkInfo(linkSpeedMbps = 866, rssiDbm = -58, frequencyMhz = 5180, bandLabel = "5 GHz", wifiStandard = "Wi-Fi 6"),
                ),
                onStart = {}, onCancel = {}, onDismissConsent = {}, onAcceptConsent = {},
            )
        }
        save("ready-clair")
    }

    @Test fun capture_running() {
        val monitor = object : NetworkMonitor {
            val flow = MutableStateFlow<LocalNetworkStatus>(
                LocalNetworkStatus.LocalWifi(NetworkIdentity("wlan0", "192.168.1.0/24", "192.168.1.1"))
            )
            override fun observeStatus(): Flow<LocalNetworkStatus> = flow
            override fun currentStatus(): LocalNetworkStatus = flow.value
        }
        val repo = SpeedTestRepository(
            engine = FakeSpeedTestEngine(),
            monitor = monitor,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
        wrap(false, SpeedTestUiState(isRunning = true)) {
            SpeedTestContent(
                state = SpeedTestUiState(
                    isRunning = true,
                    currentMbps = 42.0,
                    phase = dev.wifiadmin.app.data.speedtest.SpeedTestPhase.DOWNLOAD,
                ),
                onStart = {}, onCancel = {}, onDismissConsent = {}, onAcceptConsent = {},
            )
        }
        save("running-clair")
    }

    @Test fun capture_disabled_dark() {
        wrap(true, SpeedTestUiState()) {
            SpeedTestContent(
                state = SpeedTestUiState(failure = dev.wifiadmin.app.data.speedtest.SpeedTestFailure.Disabled),
                onStart = {}, onCancel = {}, onDismissConsent = {}, onAcceptConsent = {},
            )
        }
        save("error-disabled-sombre")
    }

    @Test fun capture_result_dark() {
        wrap(true, SpeedTestUiState()) {
            SpeedTestContent(
                state = SpeedTestUiState(
                    previousResult = dev.wifiadmin.app.data.speedtest.SpeedTestResult(
                        downloadMbps = 42.5, uploadMbps = 18.2, latencyMillis = 24,
                        downloadBytes = 5_000_000, uploadBytes = 2_500_000, durationMillis = 8_000,
                        capped = false, providerLabel = "Moteur simulé (fictif)", networkKey = "simulated",
                        wifiLink = null, capturedAtWallMillis = 1_700_000_000_000L,
                    ),
                    result = dev.wifiadmin.app.data.speedtest.SpeedTestResult(
                        downloadMbps = 42.5, uploadMbps = 18.2, latencyMillis = 24,
                        downloadBytes = 5_000_000, uploadBytes = 2_500_000, durationMillis = 8_000,
                        capped = false, providerLabel = "Moteur simulé (fictif)", networkKey = "simulated",
                        wifiLink = null, capturedAtWallMillis = 1_700_000_000_000L,
                    ),
                    downloadMbps = 42.5, uploadMbps = 18.2, latencyMillis = 24,
                ),
                onStart = {}, onCancel = {}, onDismissConsent = {}, onAcceptConsent = {},
            )
        }
        save("result-sombre")
    }

    @Test fun start_button_is_disabled_without_a_provider() {
        wrap(false, SpeedTestUiState(hasConsented = true)) {
            SpeedTestContent(
                state = SpeedTestUiState(hasConsented = true),
                onStart = {}, onCancel = {}, onDismissConsent = {}, onAcceptConsent = {},
            )
        }
        compose.onNodeWithTag("speedtest_start_button").assertIsNotEnabled()
    }
}
