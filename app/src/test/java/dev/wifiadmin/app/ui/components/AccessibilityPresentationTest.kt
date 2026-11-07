package dev.wifiadmin.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wordings the screen reader relies on.
 */
class AccessibilityPresentationTest {

    @Test
    fun `a card is announced with its label and its value`() {
        assertEquals("Total : 3", statCardDescription("Total", "3"))
        assertEquals("Appareils actifs : 2", statCardDescription("Appareils actifs", "2"))
    }

    @Test
    fun `the signal needs a wording`() {
        assertEquals("Signal excellent", signalQualityLabel(-40))
        assertEquals("Signal bon", signalQualityLabel(-60))
        assertEquals("Signal moyen", signalQualityLabel(-70))
        assertEquals("Signal faible", signalQualityLabel(-80))
        assertEquals("Signal très faible", signalQualityLabel(-95))
        assertEquals("Signal inconnu", signalQualityLabel(null))
    }
}
