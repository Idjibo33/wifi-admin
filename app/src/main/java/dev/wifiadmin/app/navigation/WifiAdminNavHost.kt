package dev.wifiadmin.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.wifiadmin.app.AppContainer
import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.ui.dashboard.DashboardScreen
import dev.wifiadmin.app.ui.dashboard.DashboardViewModel
import dev.wifiadmin.app.ui.devices.DevicesScreen
import dev.wifiadmin.app.ui.devices.DevicesViewModel
import dev.wifiadmin.app.ui.login.LoginScreen
import dev.wifiadmin.app.ui.login.LoginViewModel
import dev.wifiadmin.app.ui.settings.SettingsScreen
import dev.wifiadmin.app.ui.settings.SettingsViewModel
import dev.wifiadmin.app.ui.speedtest.SpeedTestScreen
import dev.wifiadmin.app.ui.speedtest.SpeedTestViewModel

/** The screens of the application. */
sealed class Route(val path: String) {
    data object Login : Route("login")
    data object Dashboard : Route("dashboard")
    data object Devices : Route("devices")
    data object Settings : Route("settings")
    data object SpeedTest : Route("speedtest")
}

/** The entries of the bottom navigation bar. */
private enum class Tab(
    val route: Route,
    val label: String,
    val icon: ImageVector
) {
    DASHBOARD(Route.Dashboard, "Tableau de bord", Icons.Default.Dashboard),
    DEVICES(Route.Devices, "Appareils", Icons.Default.Devices),
    SETTINGS(Route.Settings, "Réglages", Icons.Default.Settings)
}

/**
 * The navigation graph.
 *
 * The dashboard, the device list and the settings share one bottom bar. The
 * login screen has none.
 */
@Composable
fun WifiAdminNavHost(
    container: AppContainer,
    appVersionName: String,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val viewModelFactory = remember(container) { WifiAdminViewModelFactory(container) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val routerState by container.routerRepository.state.collectAsStateWithLifecycle()
    val currentRoute = currentDestination?.route

    // A confirmed expiry is the only event that sends the user back to the
    // login screen. The whole back stack is cleared so Back cannot reopen a
    // protected screen.
    //
    // The effect is keyed on the connection **and** on the current route: a
    // tab navigation dispatched at the very instant of the expiry would
    // otherwise leave the user on an authenticated screen, because the
    // connection state alone would not have changed again.
    LaunchedEffect(routerState.connection, currentRoute) {
        val onLogin = currentRoute == Route.Login.path
        val mustGoBack = shouldReturnToLogin(
            connection = routerState.connection,
            currentRoute = currentRoute,
        )
        val onProtectedWithoutSession = shouldLeaveProtectedScreen(
            connection = routerState.connection,
            currentRoute = currentRoute,
        )

        if (!onLogin && (mustGoBack || onProtectedWithoutSession)) {
            navController.navigate(Route.Login.path) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
                restoreState = false
            }
        }
    }

    val showBottomBar = Tab.entries.any { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.route.path } == true
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                BottomBar(
                    currentRoute = currentDestination?.hierarchy?.firstOrNull()?.route,
                    onTabClick = { tab ->
                        navController.navigate(tab.route.path) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Route.Login.path,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Route.Login.path) {
                val viewModel: LoginViewModel = viewModel(factory = viewModelFactory)
                LoginScreen(
                    viewModel = viewModel,
                    onConnected = {
                        navController.navigate(Route.Dashboard.path) {
                            popUpTo(Route.Login.path) { inclusive = true }
                        }
                    }
                )
            }
            composable(Route.Dashboard.path) {
                val viewModel: DashboardViewModel = viewModel(factory = viewModelFactory)
                DashboardScreen(
                    viewModel = viewModel,
                    onSeeActives = {
                        navController.navigate(Route.Devices.path) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenSpeedTest = {
                        navController.navigate(Route.SpeedTest.path) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Route.SpeedTest.path) {
                val viewModel: SpeedTestViewModel = viewModel(factory = viewModelFactory)
                SpeedTestScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Route.Devices.path) {
                val viewModel: DevicesViewModel = viewModel(factory = viewModelFactory)
                DevicesScreen(viewModel = viewModel)
            }
            composable(Route.Settings.path) {
                val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
                SettingsScreen(
                    viewModel = viewModel,
                    appVersionName = appVersionName,
                    routerDisplayName = container.routerRepository.routerDisplayName,
                    onDisconnected = {
                        navController.navigate(Route.Login.path) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}

/** The shared bottom bar: same behaviour, extracted so it can be previewed. */
@Composable
private fun BottomBar(
    currentRoute: String?,
    onTabClick: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        Tab.entries.forEach { tab ->
            val selected = currentRoute == tab.route.path
            NavigationBarItem(
                selected = selected,
                onClick = { onTabClick(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) }
            )
        }
    }
}
