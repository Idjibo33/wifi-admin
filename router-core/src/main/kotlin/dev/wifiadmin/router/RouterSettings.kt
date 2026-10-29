package dev.wifiadmin.router

/**
 * Connection settings for the router.
 *
 * Implementations decide where the values come from (preferences, environment, a
 * password prompt). Credentials are never stored in this module.
 */
interface RouterSettings {
    /** Host name or IP address of the router, e.g. `192.168.1.1`. */
    val routerHost: String

    /** Administrator user name of the router. */
    val username: String

    /** Administrator password of the router. Never logged, never included in messages. */
    val password: String
}
