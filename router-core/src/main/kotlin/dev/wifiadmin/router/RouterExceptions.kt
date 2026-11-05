package dev.wifiadmin.router

/**
 * Base class for every failure this module reports.
 *
 * Messages never contain the password, the challenge token or the session id.
 */
open class RouterException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The router could not be reached: connection refused, timeout, TLS/DNS problem.
 */
class RouterNetworkException(
    message: String,
    cause: Throwable? = null,
) : RouterException(message, cause)

/**
 * The router answered, but not with something this module can use:
 * malformed JSON, a failed `success` flag or an HTTP error.
 */
open class RouterProtocolException(
    message: String,
    cause: Throwable? = null,
) : RouterException(message, cause)

/**
 * The firmware refuses a command for this account: it answers
 * `{"success": false, "message": "LIMITED_ACCESS"}`.
 *
 * This is not a wrong password and not a network problem: the command exists
 * but is disabled on this firmware. Retrying cannot help.
 *
 * It extends [RouterProtocolException] so that existing callers keep their
 * catch blocks, while a diagnostic can still tell the two apart.
 */
class RouterFeatureUnavailableException(
    val feature: String,
    /**
     * Why the command is refused, as a **sanitised** description.
     *
     * It never contains the router's raw message: it is built from an
     * enumeration token or from a fixed sentence.
     */
    val routerMessage: String,
) : RouterProtocolException("The router refuses the $feature feature: $routerMessage")

/**
 * The router refused the credentials, or the session expired and could not be
 * renewed.
 */
class RouterAuthenticationException(
    message: String,
    cause: Throwable? = null,
) : RouterException(message, cause)

/**
 * The router is refusing logins for a while after too many failures.
 *
 * Sending another attempt during that window would only extend it, so the
 * client stops before sending the credentials.
 *
 * [retryAfterSeconds] is `null` when the router refused the login without
 * saying how long it will last: the caller must then ask the user to wait,
 * not to check the password.
 */
class RouterCooldownException(
    val retryAfterSeconds: Int?,
) : RouterException(
    if (retryAfterSeconds == null) {
        "The router did not open a session and did not say why"
    } else {
        "The router refuses logins for another $retryAfterSeconds s"
    }
)
