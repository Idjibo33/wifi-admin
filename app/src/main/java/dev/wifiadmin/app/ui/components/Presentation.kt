package dev.wifiadmin.app.ui.components

import androidx.compose.ui.graphics.Color
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.WifiBand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The exact sentence shown when a session has expired.
 *
 * It is a constant so the screen and the tests can never drift apart.
 */
const val EXPIRY_MESSAGE = "Votre session a expiré. Reconnectez-vous pour continuer."

/**
 * The exact sentence shown when the phone left the network of the router.
 *
 * It is deliberately different from [EXPIRY_MESSAGE]: the session has not
 * expired, the router is simply not reachable from here any more.
 */
const val NETWORK_CHANGED_MESSAGE =
    "Le réseau a changé. Le routeur n'est plus joignable ici."

/**
 * The reason the data on screen is not fresh.
 *
 * The screens show a different banner for each: the user must be able to
 * tell a lost router from a lost network from a failed synchronisation.
 */
enum class StaleDataReason {
    /** The data comes from the disk and was read during an earlier session. */
    CACHE,

    /** The phone is not on the router's network any more. */
    NETWORK_CHANGED,

    /** The last synchronisation failed. */
    SYNC_FAILED,

    /** The router answered without any device list. */
    INCOMPLETE_REPLY,
}

/**
 * The message of the banner that explains why the data is not fresh.
 *
 * @param reason why the data is not fresh.
 * @param capturedAtMillis when the data was read, or null.
 */
fun staleDataMessage(
    reason: StaleDataReason,
    capturedAtMillis: Long?,
): String {
    val date = formatSnapshotDate(capturedAtMillis)
    return when (reason) {
        StaleDataReason.CACHE -> if (date != null) {
            "Dernières données connues — actualisées le $date."
        } else {
            "Dernières données connues — date d'actualisation inconnue."
        }

        StaleDataReason.NETWORK_CHANGED -> NETWORK_CHANGED_MESSAGE

        StaleDataReason.SYNC_FAILED -> if (date != null) {
            "Synchronisation impossible — dernières données connues le $date."
        } else {
            "Synchronisation impossible — les données affichées peuvent être anciennes."
        }

        StaleDataReason.INCOMPLETE_REPLY -> if (date != null) {
            "Réponse incomplète du routeur — dernières données connues le $date."
        } else {
            "Réponse incomplète du routeur : aucune liste d'appareils reçue."
        }
    }
}

/** The label shown for a device presence state. */
fun DevicePresence.label(): String = when (this) {
    DevicePresence.ACTIVE -> "Actif"
    DevicePresence.CABLED -> "Filaire"
    DevicePresence.STALE -> "Périmé"
    DevicePresence.UNKNOWN -> "Indéterminé"
}

/**
 * Why the device got that state.
 *
 * Shown in the detail sheet so the user can tell a conclusion backed by the
 * router from an absence of information.
 */
fun DevicePresence.explanation(): String = when (this) {
    DevicePresence.ACTIVE ->
        "Présent dans la liste d'associations Wi-Fi du routeur."
    DevicePresence.CABLED ->
        "Bail DHCP sur une interface filaire, sans association Wi-Fi."
    DevicePresence.STALE ->
        "Bail Wi-Fi expiré et sans association radio."
    DevicePresence.UNKNOWN ->
        "Les informations du routeur ne permettent pas de conclure."
}

/** The label shown for a Wi-Fi band. */
fun WifiBand.label(): String = when (this) {
    WifiBand.BAND_2_4_GHZ -> "2,4 GHz"
    WifiBand.BAND_5_GHZ -> "5 GHz"
}

/** The label used when a device is on no known radio. */
fun Set<WifiBand>.label(): String = when {
    isEmpty() -> "Sans bande"
    size == 1 -> first().label()
    else -> sortedBy { it.ordinal }.joinToString(" + ") { it.label() }
}

/**
 * The colour of the presence badge.
 *
 * These are theme-independent tints so the badge stays legible in both the
 * light and the dark theme.
 */
fun DevicePresence.badgeColor(): Color = when (this) {
    DevicePresence.ACTIVE -> Color(0xFF2E7D32)
    DevicePresence.CABLED -> Color(0xFF1565C0)
    DevicePresence.STALE -> Color(0xFFEF6C00)
    DevicePresence.UNKNOWN -> Color(0xFF757575)
}

/**
 * The label used when the device comes from a cached snapshot.
 *
 * A cached list says what the router reported at the time of the read, not
 * what is connected now. Saying "Actif" would claim a live fact the app does
 * not have; "Vu actif" only reports the past observation.
 */
fun DevicePresence.cachedLabel(): String = when (this) {
    DevicePresence.ACTIVE -> "Vu actif"
    DevicePresence.CABLED -> "Vu filaire"
    DevicePresence.STALE -> "Périmé"
    DevicePresence.UNKNOWN -> "Indéterminé"
}

/**
 * The neutral colour of a presence badge in cached mode.
 *
 * Every state uses the same grey: a colour that usually means "currently
 * connected" must not suggest a live reading.
 */
fun DevicePresence.cachedBadgeColor(): Color = Color(0xFF616161)

/**
 * Formats the moment a snapshot was captured, for the "last known data"
 * banner.
 *
 * @param millis the epoch milliseconds, or null when unknown.
 * @return a French date and time such as `31/10/2026 à 14:32`, or null.
 */
fun formatSnapshotDate(millis: Long?): String? {
    if (millis == null) return null
    val formatter = SimpleDateFormat("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE)
    return formatter.format(Date(millis))
}

/**
 * The French message shown for a router failure.
 *
 * The distinctions matter: a timeout, an unreachable router, a cooldown and
 * an expired session are four different situations and must never be
 * presented as the same thing.
 */
fun RouterUnavailableException.userMessage(): String = when (this) {
    is RouterUnavailableException.Unreachable ->
        "Le routeur est injoignable. Vérifiez que le téléphone est connecté à son Wi-Fi."
    is RouterUnavailableException.Timeout ->
        "Le routeur n'a pas répondu à temps."
    is RouterUnavailableException.AuthenticationFailed ->
        "Identifiants refusés par le routeur."
    is RouterUnavailableException.SessionExpired -> EXPIRY_MESSAGE
    is RouterUnavailableException.NotConnected ->
        "Aucune session n'est ouverte."
    is RouterUnavailableException.Cooldown ->
        retryAfterSeconds?.let { seconds ->
            "Le routeur refuse temporairement les connexions pendant encore $seconds secondes."
        } ?: "Le routeur refuse temporairement les connexions. Patientez quelques minutes."
    is RouterUnavailableException.UnexpectedResponse ->
        "Le routeur a répondu de façon inattendue."
}
