package dev.wifiadmin.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import dev.wifiadmin.app.navigation.PROTECTED_ROUTES
import dev.wifiadmin.app.navigation.Route
import dev.wifiadmin.app.navigation.shouldLeaveProtectedScreen
import dev.wifiadmin.app.navigation.shouldReturnToLogin
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La navigation reelle, sur l'appareil reel.
 *
 * Le graphe de navigation de production ne peut pas etre instancie avec un
 * repository factice : `AppContainer` est une classe finale, sans aucune
 * couture d'injection, et `WifiAdminNavHost` recoit ce conteneur. Ce test
 * reconstruit donc le **meme graphe** (memes routes, memes regles) avec le
 * **vrai** `NavController` de Compose Navigation, et verifie sur l'appareil :
 *
 * - la navigation repetee Tableau de bord <-> Appareils ne duplique aucune
 *   entree de la pile ;
 * - la regle de securite ecarte les ecrans proteges quand la session est
 *   perdue ;
 * - le Retour ne peut pas atteindre un ecran protege apres une expiration ;
 * - aucune de ces operations ne leve d'exception (pas de crash).
 */
@RunWith(AndroidJUnit4::class)
class NavigationInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun repeatedTabNavigationDoesNotDuplicateTheBackStack() {
        var controller: NavHostController? = null

        compose.setContent {
            WifiAdminTheme {
                val navController = rememberNavController()
                controller = navController
                NavHost(navController = navController, startDestination = Route.Dashboard.path) {
                    composable(Route.Dashboard.path) { }
                    composable(Route.Devices.path) { }
                    composable(Route.Settings.path) { }
                    composable(Route.Login.path) { }
                }
            }
        }
        compose.waitForIdle()

        val nav = requireNotNull(controller) { "the NavController was not created" }

        // The navigation options are exactly the ones the production bottom
        // bar uses (`WifiAdminNavHost`).
        // The navigation must be dispatched on the UI thread, like the click
        // that triggers it.
        fun navigateLikeTheBottomBar(route: String) {
            compose.runOnUiThread {
                nav.navigate(route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            compose.waitForIdle()
        }

        // La navigation repetee, comme le ferait l'utilisateur.
        var round = 0
        var failure: Throwable? = null
        while (round < 12 && failure == null) {
            try {
                navigateLikeTheBottomBar(Route.Devices.path)
                navigateLikeTheBottomBar(Route.Dashboard.path)

                val entries = nav.currentBackStack.value.map { it.destination.route }
                val dashboardCount = entries.count { it == Route.Dashboard.path }
                val devicesCount = entries.count { it == Route.Devices.path }

                println("=== T02 NAVIGATION ===")
                println("round=$round stackSize=${entries.size}")
                println("stackEntries=$entries")
                println("dashboardCount=$dashboardCount devicesCount=$devicesCount")
                println("=== END T02 NAVIGATION ===")

                // The invariant of T02: the navigation never **duplicates** an
                // entry. The bottom bar uses `saveState`/`restoreState`, so
                // the entry of the tab that was left is *saved*, not kept:
                // the stack holds the graph and the current tab, and nothing
                // more.
                assertTrue(
                    "the dashboard must appear at most once (round $round)",
                    dashboardCount <= 1
                )
                assertTrue(
                    "the device list must appear at most once (round $round)",
                    devicesCount <= 1
                )
                assertEquals(
                    "the stack must not grow (round $round)",
                    2,
                    entries.size
                )
                assertEquals(
                    "the current tab must be on top (round $round)",
                    Route.Dashboard.path,
                    nav.currentBackStack.value.last().destination.route
                )
            } catch (t: Throwable) {
                failure = t
            }
            round++
        }

        println("=== T02 NAVIGATION SUMMARY ===")
        println("roundsCompleted=${round - if (failure == null) 0 else 1}")
        println("failure=${failure?.javaClass?.name}")
        println("failureMessage=${failure?.message}")
        println("=== END T02 NAVIGATION SUMMARY ===")

        assertEquals(
            "the repeated tab navigation must not fail on the device",
            null,
            failure
        )
    }

    @Test
    fun theBackStackIsEmptyAfterAnExpirySoBackLeavesTheApplication() {
        var controller: NavHostController? = null

        compose.setContent {
            WifiAdminTheme {
                val navController = rememberNavController()
                controller = navController
                NavHost(navController = navController, startDestination = Route.Dashboard.path) {
                    composable(Route.Dashboard.path) { }
                    composable(Route.Devices.path) { }
                    composable(Route.Login.path) { }
                }
            }
        }
        compose.waitForIdle()

        val nav = requireNotNull(controller)
        compose.runOnUiThread { nav.navigate(Route.Devices.path) }
        compose.waitForIdle()
        assertEquals(
            Route.Devices.path,
            nav.currentBackStack.value.last().destination.route
        )

        // The rule the NavHost applies when the session is confirmed expired.
        assertTrue(
            "the expiry must send the user back to the login screen",
            shouldReturnToLogin(
                dev.wifiadmin.app.data.ConnectionState.SessionExpired,
                nav.currentBackStack.value.last().destination.route,
            )
        )

        // The navigation the NavHost performs: the whole back stack is
        // cleared, so Back leaves the application.
        compose.runOnUiThread {
            nav.navigate(Route.Login.path) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
                restoreState = false
            }
        }
        compose.waitForIdle()

        val entries = nav.currentBackStack.value.map { it.destination.route }
        println("=== T02 BACK STACK ===")
        println("stackAfterExpiry=$entries")
        println("=== END T02 BACK STACK ===")

        // The stack holds the graph and the login screen: the login screen
        // is the only **destination** left, so Back leaves the application
        // instead of reopening a protected screen.
        val destinations = entries.filter { it != null }
        assertEquals(
            "the login screen must be the only destination left",
            listOf(Route.Login.path),
            destinations
        )
        assertTrue(
            "no protected screen may remain",
            destinations.none { it in PROTECTED_ROUTES }
        )
    }

    @Test
    fun aNetworkChangeNeverLeavesAProtectedScreen() {
        // A network change is not an expiry: the user stays where he is.
        assertFalse(
            shouldReturnToLogin(
                dev.wifiadmin.app.data.ConnectionState.NetworkChanged("192.168.0.1"),
                Route.Dashboard.path,
            )
        )
        assertFalse(
            shouldLeaveProtectedScreen(
                dev.wifiadmin.app.data.ConnectionState.NetworkChanged("192.168.0.1"),
                Route.Devices.path,
            )
        )

        // Only a confirmed expiry leaves a protected screen.
        for (route in PROTECTED_ROUTES) {
            assertTrue(
                "the route $route must be left on a confirmed expiry",
                shouldLeaveProtectedScreen(
                    dev.wifiadmin.app.data.ConnectionState.SessionExpired,
                    route,
                )
            )
        }

        // The application context is available on the device.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("dev.wifiadmin.app", context.packageName)
    }
}
