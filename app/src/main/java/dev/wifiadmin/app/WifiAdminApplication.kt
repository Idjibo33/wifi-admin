package dev.wifiadmin.app

import android.app.Application
import dev.wifiadmin.app.data.RouterAddressResolver
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.SettingsStore

/**
 * Holds the objects that live for the whole life of the app.
 *
 * A manual container keeps the dependency graph obvious and testable: a
 * ViewModel can be built with a different repository in a test without any
 * framework magic.
 */
class WifiAdminApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** The application's dependency graph. */
class AppContainer(application: Application) {

    val settingsStore: SettingsStore = SettingsStore(application)

    val routerRepository: RouterRepository = RouterRepository()

    /** Suggests the router address from the current Wi-Fi network. */
    val routerAddressResolver: RouterAddressResolver = RouterAddressResolver(application)
}
