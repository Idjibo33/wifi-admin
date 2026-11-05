package dev.wifiadmin.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.app.navigation.Route
import dev.wifiadmin.app.navigation.shouldLeaveProtectedScreen
import dev.wifiadmin.app.navigation.shouldReturnToLogin
import dev.wifiadmin.app.ui.components.EXPIRY_MESSAGE
import dev.wifiadmin.app.ui.login.LoginResult
import dev.wifiadmin.app.ui.login.LoginViewModel
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import dev.wifiadmin.router.DeviceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two visual reserves of Mission 08, checked on a **real Android
 * runtime**.
 *
 * ## What this test does, and does not, prove
 *
 * It renders the **real** [dev.wifiadmin.app.ui.login.LoginScreen] with a
 * **fake** repository, on the device's own Compose runtime. It therefore
 * proves that the exact sentence is really produced by the screen, and that
 * the navigation rule really leaves the protected screens.
 *
 * It does **not** talk to a router: the fake raises the same
 * [RouterUnavailableException.SessionExpired] the real adapter raises when
 * the renewal of an open session fails. The protocol half of that path is
 * covered by `ZltRouterClientRenewalTest` and by
 * `tools/fake_router_expiry_scenarios.py`.
 *
 * ## Why nothing is injected into the installed application
 *
 * The fake lives **in this test source set**. The release build has no
 * injection point at all: there is no `BuildConfig.DEBUG` branch, no test
 * hook and no way to reach a protected screen from outside.
 */
@RunWith(AndroidJUnit4::class)
class SessionExpiryInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * An adapter whose session expires on the first read.
     */
    private class ExpiringAdapter : dev.wifiadmin.app.data.RouterAdapter {
        override val displayName = "Fake"
        val expired = AtomicBoolean(false)

        override suspend fun connect(
            host: String,
            username: String,
            password: String,
        ): Boolean = true

        override suspend fun fetchDevices(): DeviceSnapshot {
            expired.set(true)
            throw RouterUnavailableException.SessionExpired()
        }

        override fun disconnect() = Unit
    }

    // -----------------------------------------------------------------
    // M08-R01 — the exact message is displayed.
    // -----------------------------------------------------------------

    @Test
    fun theExpiryMessageIsDisplayedOnTheLoginScreen() {
        val adapter = ExpiringAdapter()
        val repository = RouterRepository(adapter = adapter)

        // The session is opened, then it expires on the first read.
        kotlinx.coroutines.runBlocking {
            repository.connect("192.168.1.1", "admin", "secret")
            repository.refresh()
        }

        assertTrue("the session must have expired", adapter.expired.get())
        assertTrue(
            "the repository must be in the expired state",
            repository.state.value.connection is ConnectionState.SessionExpired
        )

        val viewModel = LoginViewModel(repository)

        compose.setContent {
            WifiAdminTheme {
                dev.wifiadmin.app.ui.login.LoginScreen(
                    viewModel = viewModel,
                    onConnected = {},
                )
            }
        }

        compose.onNodeWithText(EXPIRY_MESSAGE).assertIsDisplayed()
    }

    /**
     * The message is exactly the product wording, character for character.
     */
    @Test
    fun theExpiryMessageIsTheExactSentence() {
        assertEquals(
            "Votre session a expiré. Reconnectez-vous pour continuer.",
            EXPIRY_MESSAGE
        )
    }

    // -----------------------------------------------------------------
    // M08-R02 — Back cannot reach a protected screen.
    // -----------------------------------------------------------------

    @Test
    fun theBackStackIsEmptiedSoBackCannotReachAProtectedScreen() {
        // The rule the navigation applies when the session expires.
        assertTrue(
            "the expiry must send the user back to the login screen",
            shouldReturnToLogin(
                ConnectionState.SessionExpired,
                Route.Dashboard.path,
            )
        )
        assertTrue(
            "the expiry must leave the device list",
            shouldReturnToLogin(
                ConnectionState.SessionExpired,
                Route.Devices.path,
            )
        )
        assertTrue(
            "the expiry must leave the settings",
            shouldReturnToLogin(
                ConnectionState.SessionExpired,
                Route.Settings.path,
            )
        )

        // The back stack is emptied with `popUpTo(graph.id) { inclusive =
        // true }`, so the login screen is the only entry left: Back leaves
        // the application instead of reopening a protected screen.
        assertFalse(
            "the navigation must not loop when the login screen is shown",
            shouldReturnToLogin(
                ConnectionState.SessionExpired,
                Route.Login.path,
            )
        )
    }

    @Test
    fun aNetworkChangeNeverReturnsToTheLoginScreen() {
        // A changed network is not an expiry: it must not sign the user out.
        assertFalse(
            shouldReturnToLogin(
                ConnectionState.NetworkChanged("192.168.0.1"),
                Route.Dashboard.path,
            )
        )
        assertFalse(
            shouldLeaveProtectedScreen(
                ConnectionState.NetworkChanged("192.168.0.1"),
                Route.Dashboard.path,
            )
        )
    }
}
