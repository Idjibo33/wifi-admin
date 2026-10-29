package dev.wifiadmin.app.ui.components

import androidx.compose.ui.graphics.Color
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.WifiBand

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
