package dev.wifiadmin.app.data

/**
 * A route of the network, reduced to what the application needs.
 *
 * The extraction exists so the rules can be tested **without** the Android
 * framework: `LinkProperties` is a framework class whose methods are stubs
 * in a JVM test.
 */
data class RouteSummary(
    val isDefault: Boolean,
    val gateway: String?,
)

/**
 * The gateway of the default route, or `null`.
 *
 * A **pure function**: no Android, no I/O, no clock. The platform hands the
 * routes to the callback, and this function decides which one is the way
 * out.
 *
 * A route without a gateway (`0.0.0.0`) is ignored: it is the on-link
 * route of the local network, not the way out.
 */
fun defaultGatewayOf(routes: List<RouteSummary>): String? =
    routes.asSequence()
        .filter { route -> route.isDefault }
        .mapNotNull { route -> route.gateway }
        .firstOrNull { it.isNotBlank() && it != "0.0.0.0" }
