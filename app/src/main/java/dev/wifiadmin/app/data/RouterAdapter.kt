package dev.wifiadmin.app.data

import dev.wifiadmin.router.DeviceSnapshot

/**
 * The operations the application needs from a router.
 *
 * The UI never talks to a protocol client directly: it goes through a
 * [RouterAdapter]. That keeps the ZLT protocol inside `router-core` and makes
 * it possible to support another router later by adding an adapter, without
 * touching a single screen.
 *
 * Every method is **read-only**. No adapter exposes a mutating operation:
 * the firmware this app targets does not allow the filtering operations, and
 * an accidental write is the one thing this app must never do.
 */
interface RouterAdapter {

    /** A human-readable name of the router family, shown in the UI. */
    val displayName: String

    /**
     * Opens a session and returns whether the credentials were accepted.
     *
     * @throws RouterUnavailableException if the router cannot be reached.
     */
    suspend fun connect(host: String, username: String, password: String): Boolean

    /** Reads the devices the router knows about. */
    suspend fun fetchDevices(): DeviceSnapshot

    /** Drops the session so the next call opens a new one. */
    fun disconnect()
}

/**
 * A failure the UI can present to the user.
 *
 * The router exceptions are mapped onto this type at the adapter boundary so
 * the screens never have to know about the protocol.
 */
sealed class RouterUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The router did not answer: wrong address, unplugged, no route. */
    class Unreachable(cause: Throwable? = null) :
        RouterUnavailableException("The router is not reachable", cause)

    /** The router answered too late. A timeout is not a session expiry. */
    class Timeout(cause: Throwable? = null) :
        RouterUnavailableException("The router did not answer in time", cause)

    /** The credentials were refused by a **connection attempt**. */
    class AuthenticationFailed(cause: Throwable? = null) :
        RouterUnavailableException("The router refused the credentials", cause)

    /**
     * A session that was already open could not be renewed.
     *
     * This is **not** a wrong password: a refresh never carries a password.
     * The user has to sign in again.
     */
    class SessionExpired(cause: Throwable? = null) :
        RouterUnavailableException("The session expired and could not be renewed", cause)

    /** A read was asked for while no session was open. Not an expiry. */
    class NotConnected : RouterUnavailableException("No session is open")

    /**
     * The router is refusing logins after too many failures.
     *
     * [retryAfterSeconds] is `null` when the router did not say how long it
     * will last: the user must wait, not check the password.
     */
    class Cooldown(val retryAfterSeconds: Int?) :
        RouterUnavailableException(
            if (retryAfterSeconds == null) {
                "The router did not open a session"
            } else {
                "The router refuses logins for another $retryAfterSeconds s"
            }
        )

    /** The router answered something the client cannot use. */
    class UnexpectedResponse(cause: Throwable? = null) :
        RouterUnavailableException("The router returned an unexpected response", cause)

    /**
     * Le réseau local est bloqué par l'autorisation Android absente.
     *
     * Sur Android 17+ (API 37, cible 37+), l'accès au réseau local exige
     * `ACCESS_LOCAL_NETWORK`. Sans elle, le système bloque les sockets TCP
     * (timeout générique). Cette exception représente **cette cause
     * précise** : elle ne doit être posée qu'après vérification réelle de
     * l'état de la permission, jamais pour un timeout sans preuve.
     *
     * Elle **conserve la session** : elle n'est jamais un expiry.
     */
    class LocalNetworkPermissionDenied(cause: Throwable? = null) :
        RouterUnavailableException("L'autorisation d'accès au réseau local est requise", cause)
}
