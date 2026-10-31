package dev.wifiadmin.app.navigation

import dev.wifiadmin.app.data.ConnectionState

/**
 * Whether the navigation must return to the login screen.
 *
 * The rule is deliberately narrow: **only** a confirmed [ConnectionState.SessionExpired]
 * sends the user back. A network failure, a timeout, a cooldown or a malformed
 * reply must leave the authenticated screens alone.
 *
 * The current route is checked too: when the login screen is already on top,
 * navigating again would push a duplicate entry and could loop. The login
 * destination is reached with an empty back stack, so pressing Back exits the
 * app instead of reopening a protected screen.
 */
fun shouldReturnToLogin(
    connection: ConnectionState,
    currentRoute: String?
): Boolean = connection is ConnectionState.SessionExpired &&
    currentRoute != Route.Login.path
