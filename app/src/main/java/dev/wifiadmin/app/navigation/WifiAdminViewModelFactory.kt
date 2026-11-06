package dev.wifiadmin.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.wifiadmin.app.AppContainer
import dev.wifiadmin.app.ui.dashboard.DashboardViewModel
import dev.wifiadmin.app.ui.devices.DevicesViewModel
import dev.wifiadmin.app.ui.login.LoginViewModel
import dev.wifiadmin.app.ui.settings.SettingsViewModel

/**
 * Creates the ViewModels of the app.
 *
 * The ViewModels are built through a factory rather than directly in a
 * composable: a ViewModel created inside a composable would be recreated on
 * every recomposition and would lose its state.
 */
class WifiAdminViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(LoginViewModel::class.java) ->
            LoginViewModel(
                container.routerRepository,
                container.routerAddressResolver
            ) as T

        modelClass.isAssignableFrom(DashboardViewModel::class.java) ->
            DashboardViewModel(
                container.routerRepository,
                container.refreshCoordinator,
            ) as T

        modelClass.isAssignableFrom(DevicesViewModel::class.java) ->
            DevicesViewModel(
                container.routerRepository,
                container.refreshCoordinator,
                container.deviceListPreferences,
            ) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(container.routerRepository, container.settingsStore) as T

        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
