package dev.wifiadmin.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The design tokens of WiFi Admin.
 *
 * One grid governs the whole app: the screens must not invent a new
 * spacing, radius or touch size per screen. The values are deliberately
 * few (4 dp base) so that a reviewer can learn them once.
 */
object WifiDims {
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space6 = 24.dp
    val Space8 = 32.dp

    /** Minimum touch target for every control (Material 3 guidance). */
    val TouchMin = 48.dp

    /** Minimum height of a text field and of the main button. */
    val FieldMin = 56.dp

    val IconSm = 16.dp
    val IconMd = 24.dp
    val IconBanner = 20.dp
    val IconState = 40.dp
    val IconLogin = 48.dp

    val CardPaddingH = 16.dp
    val CardPaddingV = 12.dp
    val BadgeMinH = 24.dp
    val BadgePaddingH = 10.dp
    val BadgePaddingV = 4.dp
}
