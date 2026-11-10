package dev.wifiadmin.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wifiadmin.app.data.ACCESS_LOCAL_NETWORK_PERMISSION
import dev.wifiadmin.app.data.LocalNetworkPermissionStatus
import dev.wifiadmin.app.navigation.WifiAdminNavHost
import dev.wifiadmin.app.ui.theme.WifiAdminTheme

/**
 * The single activity of the app.
 *
 * Sur Android 17+ (API 37, cible 37+), le réseau local est protégé par
 * l'autorisation runtime `ACCESS_LOCAL_NETWORK`. Sans elle, le système
 * bloque les sockets LAN (timeout générique).
 *
 * Cette activité est le **seul pont Android** de l'autorisation : le
 * launcher, la re-vérification `onResume` et le rationale. La décision
 * reste dans [dev.wifiadmin.app.data.resolveLocalNetworkPermissionStatus]
 * (fonction pure), et l'état vit dans le gateway du conteneur. **Aucune
 * demande automatique au démarrage** : la demande part uniquement d'un
 * geste utilisateur (« Autoriser l'accès »), après explication. Un revers
 * de révocation depuis les réglages est détecté au retour.
 */
class MainActivity : ComponentActivity() {

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val app = application as WifiAdminApplication
        val gateway = app.container.localNetworkPermissionGateway
        val rationale = shouldShowRequestPermissionRationale(ACCESS_LOCAL_NETWORK_PERMISSION)
        gateway.onRequestResult(granted = granted, rationale = rationale)
        if (granted) {
            // Accord : effacer l'erreur et reprendre sans redémarrage.
            app.container.routerRepository.onLocalNetworkPermissionGranted()
        } else {
            app.container.routerRepository.onLocalNetworkPermissionRevoked()
        }
        // Le repository émet son erreur ; le ViewModel mappe le résultat.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as WifiAdminApplication
        app.container.localNetworkPermissionGateway.bindRationaleProvider {
            shouldShowRequestPermissionRationale(ACCESS_LOCAL_NETWORK_PERMISSION)
        }

        setContent {
            WifiAdminTheme {
                val permissionState by app.container.localNetworkPermissionGateway.state
                    .collectAsStateWithLifecycle()

                // Jauge de compatibilité : aucun bandeau, aucune demande sur
                // les appareils où la permission n'est pas exigée (API < 37 ou
                // cible < 37). Le repository laisse passer les sockets.
                WifiAdminNavHost(
                    container = app.container,
                    appVersionName = appVersionName(),
                    localNetworkPermissionState = permissionState,
                    onRequestLocalNetworkPermission = {
                        app.container.localNetworkPermissionGateway.requestIfNeeded { permission ->
                            localNetworkPermissionLauncher.launch(permission)
                        }
                    },
                    onOpenAppSettings = { openAppSettings() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Révocation détectée au retour des réglages (ou après accord).
        val app = application as WifiAdminApplication
        val gateway = app.container.localNetworkPermissionGateway
        val before = gateway.state.value.status
        val after = gateway.refreshStatus()
        if (before != after.status) {
            if (after.status == LocalNetworkPermissionStatus.Granted) {
                app.container.routerRepository.onLocalNetworkPermissionGranted()
            } else if (after.blocksLocalNetwork) {
                app.container.routerRepository.onLocalNetworkPermissionRevoked()
                // Annule aussi un benchmark en cours même sans session routeur
                // ouverte (le repository seul ne notifie que session ouverte).
                app.container.speedTestRepository.onLocalNetworkPermissionRevoked()
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        runCatching { startActivity(intent) }
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }
}
