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
class RouterProtocolException(
    message: String,
    cause: Throwable? = null,
) : RouterException(message, cause)

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
