package dev.wifiadmin.app.ui.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mission 15.1 — le Login affiche une indication VPN prudente sur échec.
 *
 * Le LoginScreen ne peut pas être rendu par un test JVM. Son usage de la
 * constante et des tags est donc vérifié en lisant la source, comme le fait
 * déjà `LoginMessageContractTest`.
 */
class LoginVpnHintContractTest {

    private fun loginScreenSource(): String {
        val source = File("src/main/java/dev/wifiadmin/app/ui/login/LoginScreen.kt")
        assertTrue("the login screen source was not found", source.exists())
        return source.readText()
    }

    @Test
    fun `the login screen accepts an isVpnPresent lambda`() {
        val source = loginScreenSource()
        assertTrue(
            "LoginScreen must accept isVpnPresent lambda for fresh VPN state",
            source.contains("isVpnPresent: () -> Boolean"),
        )
    }

    @Test
    fun `the login screen shows the shared VPN message`() {
        val source = loginScreenSource()
        assertTrue(
            "LoginScreen must reference VPN_MAY_AFFECT_MESSAGE",
            source.contains("VPN_MAY_AFFECT_MESSAGE"),
        )
    }

    @Test
    fun `the login screen has a vpn hint test tag`() {
        val source = loginScreenSource()
        assertTrue(
            "LoginScreen must expose login_vpn_hint test tag",
            source.contains("login_vpn_hint"),
        )
    }

    @Test
    fun `the login screen has a retry button test tag`() {
        val source = loginScreenSource()
        assertTrue(
            "LoginScreen must expose login_vpn_retry_button test tag",
            source.contains("login_vpn_retry_button"),
        )
    }

    @Test
    fun `the login screen shows vpn hint only on timeout or network error`() {
        val source = loginScreenSource()
        assertTrue(
            "LoginScreen must gate the VPN hint on Timeout or NetworkError",
            source.contains("LoginResult.Timeout") && source.contains("LoginResult.NetworkError"),
        )
    }

    // --- Behavioral tests for shouldShowLoginVpnHint ---

    @Test
    fun `vpn inactive and connection success shows no hint`() {
        assertFalse(shouldShowLoginVpnHint(LoginResult.Success, false))
    }

    @Test
    fun `vpn active and connection success shows no hint`() {
        assertFalse(shouldShowLoginVpnHint(LoginResult.Success, true))
    }

    @Test
    fun `vpn active and timeout shows hint`() {
        assertTrue(shouldShowLoginVpnHint(LoginResult.Timeout, true))
    }

    @Test
    fun `vpn active and unreachable shows hint`() {
        assertTrue(shouldShowLoginVpnHint(LoginResult.NetworkError, true))
    }

    @Test
    fun `permission denied with vpn active shows no hint`() {
        assertFalse(shouldShowLoginVpnHint(LoginResult.LocalNetworkPermissionDenied, true))
    }

    @Test
    fun `timeout without vpn shows no hint`() {
        assertFalse(shouldShowLoginVpnHint(LoginResult.Timeout, false))
    }
}