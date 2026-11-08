package dev.wifiadmin.app.navigation

import dev.wifiadmin.app.data.ConnectionState

/**
 * The routes that show authenticated data.
 *
 * They are the screens that must never stay visible without a valid session.
 */
val PROTECTED_ROUTES: Set<String> = setOf(
    Route.Dashboard.path,
    Route.Devices.path,
    Route.Settings.path,
    Route.SpeedTest.path,
)

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

/**
 * Whether a protected screen must be left because no session is valid.
 *
 * This is the safety net for a race: the user may tap a bottom-bar tab in
 * the same instant the session expires. The expiry navigation clears the
 * back stack, but a tab navigation dispatched just after would push a
 * protected screen on top of the login screen. The repository refuses any
 * read without a session, so no data leaks — but the user would stay on an
 * authenticated screen with no way back.
 *
 * Only a **confirmed expiry** triggers it. A network failure, a timeout or a
 * malformed reply keep the user where they are, with the last known data:
 * the Mission 08 specification says an error network is never an expiry.
 * [ConnectionState.Cooldown] keeps the session, which is merely unable to
 * renew for a while, and [ConnectionState.NetworkChanged] **suspends** the
 * reads without ending the session: the user may come back to the right
 * network, and the last known devices stay on screen.
 */
fun shouldLeaveProtectedScreen(
    connection: ConnectionState,
    currentRoute: String?
): Boolean = currentRoute in PROTECTED_ROUTES &&
    connection is ConnectionState.SessionExpired
