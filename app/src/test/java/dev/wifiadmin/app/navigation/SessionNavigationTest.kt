package dev.wifiadmin.app.navigation

import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.RouterUnavailableException
import org.junit.Assert.assertEquals
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

    // ---- The safety net for a concurrent navigation ----

    @Test
    fun `a protected screen must be left when the session expired`() {
        assertTrue(
            shouldLeaveProtectedScreen(
                ConnectionState.SessionExpired,
                Route.Dashboard.path
            )
        )
        assertTrue(
            shouldLeaveProtectedScreen(
                ConnectionState.SessionExpired,
                Route.Devices.path
            )
        )
        assertTrue(
            shouldLeaveProtectedScreen(
                ConnectionState.SessionExpired,
                Route.Settings.path
            )
        )
    }

    @Test
    fun `a protected screen stays while the session is valid`() {
        assertFalse(
            shouldLeaveProtectedScreen(ConnectionState.Connected, Route.Dashboard.path)
        )
    }

    @Test
    fun `a cooldown keeps the protected screen`() {
        // The session is still valid: it is only temporarily unable to renew.
        assertFalse(
            shouldLeaveProtectedScreen(ConnectionState.Cooldown(30), Route.Dashboard.path)
        )
    }

    @Test
    fun `a network failure keeps the protected screen`() {
        assertFalse(
            shouldLeaveProtectedScreen(
                ConnectionState.Error(RouterUnavailableException.Unreachable()),
                Route.Dashboard.path
            )
        )
        assertFalse(
            shouldLeaveProtectedScreen(
                ConnectionState.Error(RouterUnavailableException.Timeout()),
                Route.Dashboard.path
            )
        )
    }

    @Test
    fun `the login screen is never left by the safety net`() {
        assertFalse(
            shouldLeaveProtectedScreen(ConnectionState.SessionExpired, Route.Login.path)
        )
        assertFalse(
            shouldLeaveProtectedScreen(ConnectionState.Disconnected, Route.Login.path)
        )
    }

    // ---- The race with the bottom-bar navigation ----

    /**
     * A tab navigation dispatched at the very instant of the expiry must not
     * stack a protected screen on top of the login screen.
     *
     * The expiry navigation clears the back stack, but the tab navigation
     * does not check the session: without the safety net the user would stay
     * on an authenticated screen with no way back.
     */
    @Test
    fun `a tab navigation at the instant of the expiry cannot stack a protected screen`() {
        assertTrue(
            shouldLeaveProtectedScreen(
                ConnectionState.SessionExpired,
                Route.Dashboard.path
            )
        )
        assertFalse(
            shouldLeaveProtectedScreen(ConnectionState.Connected, Route.Dashboard.path)
        )
        assertFalse(
            shouldLeaveProtectedScreen(ConnectionState.Cooldown(30), Route.Dashboard.path)
        )
    }

    @Test
    fun `every protected route is covered by the safety net`() {
        for (route in PROTECTED_ROUTES) {
            assertTrue(
                "the route $route must be protected",
                shouldLeaveProtectedScreen(ConnectionState.SessionExpired, route)
            )
        }
        assertEquals(3, PROTECTED_ROUTES.size)
    }
}
