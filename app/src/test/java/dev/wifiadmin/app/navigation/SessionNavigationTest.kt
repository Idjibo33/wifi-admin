package dev.wifiadmin.app.navigation

import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.RouterUnavailableException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation rule is the guard against a reconnect loop and against a
 * network failure signing the user out by mistake.
 */
class SessionNavigationTest {

    @Test
    fun `a confirmed expiry returns to login`() {
        assertTrue(
            shouldReturnToLogin(ConnectionState.SessionExpired, Route.Dashboard.path)
        )
    }

    @Test
    fun `an expiry while already on login does not navigate again`() {
        assertFalse(
            shouldReturnToLogin(ConnectionState.SessionExpired, Route.Login.path)
        )
    }

    @Test
    fun `an expiry without a current destination returns to login`() {
        assertTrue(shouldReturnToLogin(ConnectionState.SessionExpired, null))
    }

    @Test
    fun `a connected session stays on the authenticated screen`() {
        assertFalse(shouldReturnToLogin(ConnectionState.Connected, Route.Devices.path))
    }

    @Test
    fun `a network failure never returns to login`() {
        val failure = ConnectionState.Error(RouterUnavailableException.Unreachable())
        assertFalse(shouldReturnToLogin(failure, Route.Dashboard.path))
    }

    @Test
    fun `a timeout never returns to login`() {
        val failure = ConnectionState.Error(RouterUnavailableException.Timeout())
        assertFalse(shouldReturnToLogin(failure, Route.Dashboard.path))
    }

    @Test
    fun `a cooldown never returns to login`() {
        assertFalse(
            shouldReturnToLogin(ConnectionState.Cooldown(30), Route.Dashboard.path)
        )
    }

    @Test
    fun `an authentication failure at login never loops`() {
        val failure = ConnectionState.Error(RouterUnavailableException.AuthenticationFailed())
        assertFalse(shouldReturnToLogin(failure, Route.Login.path))
    }
}
