package dev.wifiadmin.router.wifi

/**
 * Failures specific to the Wi-Fi MAC filter.
 *
 * The categories are kept apart on purpose: an authentication problem, a
 * validation problem, a firmware-compatibility problem and a communication
 * problem call for four different reactions from the caller.
 */
sealed class WifiMacFilterException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The router refused the credentials, or the session expired and could not be
 * renewed.
 *
 * The message never carries the password, the challenge token or the session
 * id.
 */
class WifiMacAuthenticationException(
    message: String,
    cause: Throwable? = null,
) : WifiMacFilterException(message, cause)

/**
 * The requested operation is not allowed by the rules of this client.
 *
 * These are refused **before** anything is sent to the router.
 */
sealed class WifiMacValidationException(
    message: String,
) : WifiMacFilterException(message) {

    /**
     * The operation would lock every wireless device out of the band.
     *
     * `allow` with an empty list means "only the listed MACs may associate",
     * and an empty list excludes everybody — including the device running the
     * client. This is the single most damaging mistake this API can make, so
     * it is refused outright.
     */
    class WouldLockOutEveryDevice(
        val band: WifiBandFilter,
    ) : WifiMacValidationException(
        "Refusing to write $band in ALLOW mode with an empty list: " +
            "it would lock every wireless device out of that band"
    )

    /**
     * The state read from the router no longer matches the state the caller
     * intended to modify.
     *
     * The write replaces the whole list, so a write based on a stale snapshot
     * would silently erase rules added in the meantime.
     */
    class StaleState(
        val band: WifiBandFilter,
        val current: WifiMacFilterState,
    ) : WifiMacValidationException(
        "The $band filter changed on the router since it was read; " +
            "refusing to overwrite it with a stale copy"
    )

    /** The MAC address is not in the `AA:BB:CC:DD:EE:FF` form. */
    class InvalidMac(
        val mac: String,
    ) : WifiMacValidationException("Invalid MAC address: $mac")

    /** The MAC is a broadcast, an all-zero or a multicast address. */
    class ForbiddenMac(
        val mac: String,
    ) : WifiMacValidationException(
        "Refusing a broadcast, all-zero or multicast MAC address: $mac"
    )

    /** The same MAC appears twice in the list. */
    class DuplicateMac(
        val mac: String,
    ) : WifiMacValidationException("Duplicate MAC address in the list: $mac")

    /** The list exceeds the router's limit of 1024 entries per band. */
    class TooManyEntries(
        val band: WifiBandFilter,
        val count: Int,
    ) : WifiMacValidationException(
        "The router accepts at most 1024 entries per band; $count were given for $band"
    )
}

/**
 * The router answered, but with something this client cannot use.
 *
 * The usual cause is a firmware that does not implement this feature, or that
 * speaks a slightly different dialect.
 */
sealed class WifiMacCompatibilityException(
    message: String,
) : WifiMacFilterException(message) {

    /** The response did not contain the expected `datas` object. */
    class MissingDatas(
        val band: WifiBandFilter,
    ) : WifiMacCompatibilityException(
        "The $band filter response did not contain a 'datas' object"
    )

    /** The `macfilter` value is not one of `close`, `allow`, `deny`. */
    class UnknownMode(
        val band: WifiBandFilter,
        val value: String,
    ) : WifiMacCompatibilityException(
        "Unknown MAC filter mode '$value' for $band; " +
            "this firmware may use a mode this client does not know"
    )

    /** The `maclist` entries do not have the expected shape. */
    class MalformedList(
        val band: WifiBandFilter,
    ) : WifiMacCompatibilityException(
        "The $band filter response did not contain a usable 'maclist' array"
    )

    /** The command was rejected by the router (for example `LIMITED_ACCESS`). */
    class CommandRefused(
        reason: String,
    ) : WifiMacCompatibilityException(reason)
}

/**
 * The router could not be reached, or the exchange was interrupted.
 */
class WifiMacCommunicationException(
    message: String,
    cause: Throwable? = null,
) : WifiMacFilterException(message, cause)
