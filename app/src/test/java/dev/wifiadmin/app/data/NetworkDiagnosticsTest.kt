package dev.wifiadmin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission 15 — le VPN n'est jamais présenté comme une cause certaine.
 *
 * Seule la vérification réelle de la permission peut poser « permission
 * refusée ». Un VPN présent ne prouve jamais, à lui seul, que le LAN est
 * inaccessible (split-tunnel, route Wi-Fi épinglée).
 */
class NetworkDiagnosticsTest {

    private val wifi = LocalNetworkStatus.LocalWifi(
        NetworkIdentity(interfaceName = "wlan0", addressPrefix = "192.168.1.0/24", gateway = "192.168.1.1"),
    )

    @Test
    fun `sans echec aucune indication VPN`() {
        val d = diagnoseNetwork(
            permissionBlocks = false,
            wifiStatus = wifi,
            vpnPresent = true,
            failure = null,
        )
        assertEquals(NetworkProblem.NONE, d.problem)
        assertFalse(d.shouldShowVpnHint)
    }

    @Test
    fun `permission bloque prime sur tout le reste`() {
        val d = diagnoseNetwork(
            permissionBlocks = true,
            wifiStatus = wifi,
            vpnPresent = true,
            failure = RouterUnavailableException.Timeout(null),
        )
        assertEquals(NetworkProblem.PERMISSION_REQUIRED, d.problem)
        assertFalse(d.shouldShowVpnHint)
    }

    @Test
    fun `wifi absent prime avant routeur et VPN`() {
        val d = diagnoseNetwork(
            permissionBlocks = false,
            wifiStatus = LocalNetworkStatus.NoWifi,
            vpnPresent = true,
            failure = RouterUnavailableException.Unreachable(null),
        )
        assertEquals(NetworkProblem.NO_WIFI, d.problem)
        assertFalse(d.shouldShowVpnHint)
    }

    @Test
    fun `VPN actif avec LAN accessible donne aucun probleme`() {
        // Succès : la preuve du succès l'emporte sur la présence du VPN.
        val d = diagnoseNetwork(
            permissionBlocks = false,
            wifiStatus = wifi,
            vpnPresent = true,
            failure = null,
        )
        assertEquals(NetworkProblem.NONE, d.problem)
        assertFalse(d.shouldShowVpnHint)
    }

    @Test
    fun `VPN actif avec echec router montre indication prudente`() {
        val d = diagnoseNetwork(
            permissionBlocks = false,
            wifiStatus = wifi,
            vpnPresent = true,
            failure = RouterUnavailableException.Unreachable(null),
        )
        assertEquals(NetworkProblem.ROUTER_UNREACHABLE, d.problem)
        assertTrue(d.shouldShowVpnHint)
        assertTrue(VPN_MAY_AFFECT_MESSAGE.contains("peut affecter"))
    }

    @Test
    fun `VPN actif avec timeout montre indication prudente sans certitude`() {
        val d = diagnoseNetwork(
            permissionBlocks = false,
            wifiStatus = wifi,
            vpnPresent = true,
            failure = RouterUnavailableException.Timeout(null),
        )
        assertEquals(NetworkProblem.TIMEOUT_UNKNOWN, d.problem)
        assertTrue(d.shouldShowVpnHint)
    }

    @Test
    fun `timeout sans VPN ni permission reste indetermine sans indication VPN`() {
        val d = diagnoseNetwork(
            permissionBlocks = false,
            wifiStatus = wifi,
            vpnPresent = false,
            failure = RouterUnavailableException.Timeout(null),
        )
        assertEquals(NetworkProblem.TIMEOUT_UNKNOWN, d.problem)
        assertFalse(d.shouldShowVpnHint)
        assertNotEquals(NetworkProblem.PERMISSION_REQUIRED, d.problem)
    }

    @Test
    fun `echec sans VPN ne montre pas indication VPN`() {
        val d = diagnoseNetwork(
            permissionBlocks = false,
            wifiStatus = wifi,
            vpnPresent = false,
            failure = RouterUnavailableException.Unreachable(null),
        )
        assertEquals(NetworkProblem.ROUTER_UNREACHABLE, d.problem)
        assertFalse(d.shouldShowVpnHint)
    }

    @Test
    fun `changement identite wifi est detecte sans diagnostic VPN ou permission`() {
        val d = diagnoseNetwork(
            permissionBlocks = false,
            wifiStatus = LocalNetworkStatus.DifferentNetwork(
                NetworkIdentity(interfaceName = "wlan0", addressPrefix = "192.168.0.0/24", gateway = "192.168.0.1"),
            ),
            vpnPresent = true,
            failure = null,
        )
        assertEquals(NetworkProblem.NETWORK_CHANGED, d.problem)
        assertFalse(d.shouldShowVpnHint)
    }
}
