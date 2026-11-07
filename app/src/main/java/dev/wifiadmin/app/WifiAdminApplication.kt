package dev.wifiadmin.app

import android.app.Application
import dev.wifiadmin.app.data.AndroidNetworkMonitor
import dev.wifiadmin.app.data.AppClock
import dev.wifiadmin.app.data.DeviceListPreferencesStore
import dev.wifiadmin.app.data.DeviceMetadataStore
import dev.wifiadmin.app.data.NetworkMonitor
import dev.wifiadmin.app.data.RefreshCoordinator
import dev.wifiadmin.app.data.RefreshPolicy
import dev.wifiadmin.app.data.RouterAddressResolver
import android.net.ConnectivityManager
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.SettingsStore
import dev.wifiadmin.app.data.speedtest.AndroidWifiLinkInfoProvider
import dev.wifiadmin.app.data.speedtest.DisabledSpeedTestEngine
import dev.wifiadmin.app.data.speedtest.SpeedTestPolicy
import dev.wifiadmin.app.data.speedtest.SpeedTestPreferencesStore
import dev.wifiadmin.app.data.speedtest.SpeedTestRepository
import dev.wifiadmin.app.data.speedtest.WifiLinkInfoProvider
import dev.wifiadmin.app.data.SnapshotStore
import dev.wifiadmin.app.data.SystemAppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Holds the objects that live for the whole life of the app.
 *
 * A manual container makes the dependency graph obvious and testable: a
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

    /**
     * The clock the application measures durations with.
     *
     * The monotonic clock is the only safe basis for a polling interval: a
     * change of the phone's date must never shorten or lengthen it.
     */
    val clock: AppClock = SystemAppClock()

    /**
     * The observer of the local network.
     *
     * A **single** instance: every platform callback counts against the
     * system limit of outstanding callbacks per application, and two
     * observers of the same radio would duplicate the work.
     */
    val networkMonitor: NetworkMonitor = AndroidNetworkMonitor(application)

    /**
     * The last device snapshot.
     *
     * `noBackupFilesDir` is excluded from cloud backup and device-to-device
     * transfer by the framework. A device list restored on another phone
     * would be both a leak and a lie: another network, another account.
     */
    val snapshotStore: SnapshotStore = SnapshotStore(
        file = File(application.noBackupFilesDir, SnapshotStore.FILE_NAME)
    )

    /**
     * The local metadata of the devices: the custom names and the last
     * confirmed observations.
     *
     * They live in `noBackupFilesDir` too, in their own directory: a
     * custom name is **personal data** — it names the user's own
     * equipment — and restoring it on another phone would be a leak.
     *
     * One file per router, the name derived from the router's identity:
     * the metadata of one router can never be read for another one.
     */
    val deviceMetadataStore: DeviceMetadataStore = DeviceMetadataStore(
        directory = File(application.noBackupFilesDir, "device-metadata")
    )

    /**
     * The display preferences of the device list: the sort and the band
     * filter.
     *
     * They are a preference of the screen, not a setting of the router.
     */
    val deviceListPreferences: DeviceListPreferencesStore =
        DeviceListPreferencesStore(application)

    /**
     * The scope that owns the automatic refresh.
     *
     * It belongs to the application, not to a screen: the refresh must
     * survive the navigation between the dashboard and the device list.
     */
    val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val routerRepository: RouterRepository = RouterRepository(
        snapshotStore = snapshotStore,
        metadataStore = deviceMetadataStore,
        clock = clock,
        scope = applicationScope,
        networkMonitor = networkMonitor,
    )

    /** Link info provider (phone ↔ access point), no permission needed. */
    val wifiLinkInfoProvider: WifiLinkInfoProvider = AndroidWifiLinkInfoProvider(
        connectivity = application.getSystemService(ConnectivityManager::class.java)!!
    )

    /** The Internet speed test engine: disabled until a provider is validated. */
    val speedTestEngine: dev.wifiadmin.app.data.speedtest.SpeedTestEngine = DisabledSpeedTestEngine()

    val speedTestPreferences: SpeedTestPreferencesStore = SpeedTestPreferencesStore(application)

    val speedTestRepository: SpeedTestRepository = SpeedTestRepository(
        engine = speedTestEngine,
        monitor = networkMonitor,
        linkProvider = wifiLinkInfoProvider,
        clock = clock,
        scope = applicationScope,
        policy = SpeedTestPolicy(),
    )

    /**
     * The observation of the local network.
     *
     * It **must** be started, otherwise the application never learns that the
     * phone left the router's network: the reads would keep going to an
     * address that no longer belongs to the router.
     *
     * The observation lives in the application scope, so it survives the
     * navigation and the rotation.
     */
    private val networkObservation: Job = routerRepository.observeNetworkChanges()

    /**
     * The automatic refresh.
     *
     * One for the whole application: the dashboard and the device list show
     * the same data, and the router must not be asked twice for it.
     */
    val refreshCoordinator: RefreshCoordinator = RefreshCoordinator(
        repository = routerRepository,
        scope = applicationScope,
        policy = RefreshPolicy(),
    )

    init {
        // When the network comes back, the failures caused by the wrong
        // network must not delay the reads.
        routerRepository.onNetworkRestored = refreshCoordinator::resetFailures
    }

    /** Suggests the router address from the current Wi-Fi network. */
    val routerAddressResolver: RouterAddressResolver =
        RouterAddressResolver(networkMonitor)
}
