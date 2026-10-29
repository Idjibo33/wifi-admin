package dev.wifiadmin.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import dev.wifiadmin.app.navigation.WifiAdminNavHost
import dev.wifiadmin.app.ui.theme.WifiAdminTheme

/**
 * The single activity of the app.
 *
 * On Android 16+ (API 36) the local network is protected by a runtime
 * permission. Without it, the connection to the router is blocked by the
 * system, so the permission is requested as soon as the app starts.
 */
class MainActivity : ComponentActivity() {

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The screens report the failure themselves if the connection fails. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WifiAdminTheme {
                LaunchedEffect(Unit) {
                    requestLocalNetworkPermissionIfNeeded()
                }

                val app = application as WifiAdminApplication
                WifiAdminNavHost(
                    container = app.container,
                    appVersionName = appVersionName()
                )
            }
        }
    }

    /**
     * Asks for the local network permission on the platforms that require it.
     *
     * The permission only exists from Android 16; on older versions the
     * ordinary network access is enough and nothing is requested.
     */
    private fun requestLocalNetworkPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
        val granted = ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            localNetworkPermissionLauncher.launch(permission)
        }
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }
}
