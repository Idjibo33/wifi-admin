package dev.wifiadmin.app.ui.components

import androidx.compose.ui.graphics.Color
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.WifiBand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The message shown when the device was seen recently.
 *
 * The router never says when a device disconnected: it only says which
 * devices it knows at the moment of the read. The application therefore
 * knows the moment of the last read that saw the device, and **nothing**
 * more.
 */
const val SEEN_RECENTLY_MESSAGE = "Vu récemment"

/**
 * The label of the last confirmed observation of a device.
 *
 * It is a **bound**, not an instant: the device was there at that moment,
 * and it may have left at any time since. The wording must never suggest
 * a known moment of disconnection.
 */
const val LAST_SEEN_LABEL = "Dernière observation connue"

/**
 * The explanation shown under the last observation.
 *
 * It says explicitly what the application does not know.
 */
const val LAST_SEEN_EXPLANATION =
    "Cet appareil était présent lors de cette lecture. L'heure de sa " +
        "déconnexion n'est pas connue."

/**
 * The label used when a device was never observed by a confirmed read.
 */
const val NEVER_SEEN_MESSAGE = "Aucune observation confirmée"

/**
 * The relative age of an observation, in French.
 *
 * The wording is deliberately a **bound**: "vu il y a 3 h" says the device
 * was there at least three hours ago, which is what the application knows.
 *
 * @param observedAtMillis when the device was last observed, or null.
 * @param nowMillis the current time.
 * @return the wording, or null when there is no observation to show.
 */
fun formatLastSeen(observedAtMillis: Long?, nowMillis: Long): String? {
    if (observedAtMillis == null) return null

    val age = nowMillis - observedAtMillis
    // A date in the future means the clock of the phone moved: nothing
    // honest can be said about it.
    if (age < 0) return null

    return when {
        age <= RECENT_WINDOW_MILLIS -> SEEN_RECENTLY_MESSAGE
        age < 60 * 60_000L -> "Vu il y a ${(age / 60_000L).coerceAtLeast(1)} min"
        age < 24 * 60 * 60_000L -> "Vu il y a ${age / 3_600_000L} h"
        age < 7 * 24 * 60 * 60_000L -> "Vu il y a ${age / 86_400_000L} j"
        else -> formatSnapshotDate(observedAtMillis)?.let { "Vu le $it" }
    }
}

/**
 * How long an observation stays "recent".
 *
 * The polling is every thirty seconds, so a device that was seen is
 * detected as absent within a minute. After five minutes the absence is
 * certain and "vu récemment" would be misleading.
 */
const val RECENT_WINDOW_MILLIS = 5 * 60 * 1000L

/**
 * The message shown when the device list is empty.
 *
 * The reason matters: "no device at all" and "no device matching the
 * current search" call for two different actions, and a single message
 * would send the user the wrong way.
 */
fun deviceEmptyMessage(
    reason: dev.wifiadmin.app.ui.devices.DeviceEmptyReason,
    query: String,
    bandLabel: String,
): String = when (reason) {
    dev.wifiadmin.app.ui.devices.DeviceEmptyReason.NO_REPLY ->
        "Réponse du routeur vide : aucune liste d'appareils n'a été reçue."

    dev.wifiadmin.app.ui.devices.DeviceEmptyReason.NO_DEVICE_CACHED ->
        "Aucun appareil n'était connu lors de la dernière lecture."

    dev.wifiadmin.app.ui.devices.DeviceEmptyReason.NO_DEVICE ->
        "Aucun appareil signalé par le routeur."

    dev.wifiadmin.app.ui.devices.DeviceEmptyReason.NO_MATCH_QUERY ->
        "Aucun appareil ne correspond à « $query »."

    dev.wifiadmin.app.ui.devices.DeviceEmptyReason.NO_MATCH_FILTER ->
        "Aucun appareil en $bandLabel."

    dev.wifiadmin.app.ui.devices.DeviceEmptyReason.NO_MATCH_BOTH ->
        "Aucun appareil en $bandLabel ne correspond à « $query »."
}

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
 * light and the dark theme. Every one of them is light enough for
 * [Color.White] to pass WCAG AA contrast (>= 4.5:1).
 */
fun DevicePresence.badgeColor(): Color = when (this) {
    DevicePresence.ACTIVE -> Color(0xFF2E7D32)
    DevicePresence.CABLED -> Color(0xFF1565C0)
    DevicePresence.STALE -> Color(0xFFBF5600)
    DevicePresence.UNKNOWN -> Color(0xFF616161)
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
 * The signal quality wording for an RSSI level.
 *
 * The signal is only ever shown for an active radio association, and it is
 * not only coloured: the wording is what a reader of the screen (or of a
 * screen reader) gets.
 */
fun signalQualityLabel(rssiDbm: Int?): String = when {
    rssiDbm == null -> "Signal inconnu"
    rssiDbm >= -55 -> "Signal excellent"
    rssiDbm >= -65 -> "Signal bon"
    rssiDbm >= -75 -> "Signal moyen"
    rssiDbm >= -85 -> "Signal faible"
    else -> "Signal très faible"
}

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
