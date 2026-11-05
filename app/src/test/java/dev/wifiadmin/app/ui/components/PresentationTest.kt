package dev.wifiadmin.app.ui.components

import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.router.DevicePresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The wording of the banners is part of the product contract: the exact
 * sentence must not drift, and a cached device must never be labelled as
 * currently connected.
 */
class PresentationTest {

    @Test
    fun `the cached banner uses the exact product sentence`() {
        val message = staleDataMessage(
            reason = StaleDataReason.CACHE,
            capturedAtMillis = CAPTURED_AT,
        )

        val expectedDate = SimpleDateFormat("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE)
            .format(Date(CAPTURED_AT))
        assertEquals(
            "Dernières données connues — actualisées le $expectedDate.",
            message
        )
    }

    @Test
    fun `the cached banner handles an unknown date`() {
        assertEquals(
            "Dernières données connues — date d'actualisation inconnue.",
            staleDataMessage(StaleDataReason.CACHE, null)
        )
    }

    @Test
    fun `a sync failure names the last known data`() {
        val message = staleDataMessage(StaleDataReason.SYNC_FAILED, CAPTURED_AT)

        assertTrue(message.startsWith("Synchronisation impossible"))
    }

    @Test
    fun `an incomplete reply is reported as such`() {
        val message = staleDataMessage(StaleDataReason.INCOMPLETE_REPLY, CAPTURED_AT)

        assertTrue(message.startsWith("Réponse incomplète du routeur"))
    }

    @Test
    fun `a network change has its own wording`() {
        val message = staleDataMessage(StaleDataReason.NETWORK_CHANGED, CAPTURED_AT)

        assertEquals(NETWORK_CHANGED_MESSAGE, message)
        assertNotEquals(
            "a changed network is not an expired session",
            EXPIRY_MESSAGE,
            message
        )
    }

    @Test
    fun `the four reasons are told apart`() {
        val messages = StaleDataReason.entries.map {
            staleDataMessage(it, CAPTURED_AT)
        }

        assertEquals(
            "each reason must have its own wording",
            messages.size,
            messages.toSet().size
        )
    }

    @Test
    fun `a cached device is never labelled as currently active`() {
        assertEquals("Vu actif", DevicePresence.ACTIVE.cachedLabel())
        assertNotEquals(DevicePresence.ACTIVE.label(), DevicePresence.ACTIVE.cachedLabel())
        assertEquals("Vu filaire", DevicePresence.CABLED.cachedLabel())
    }

    @Test
    fun `the cached badge is neutral for every state`() {
        val colors = DevicePresence.entries.map { it.cachedBadgeColor() }.toSet()

        assertEquals("all cached badges must share one neutral colour", 1, colors.size)
    }

    @Test
    fun `a timeout and an expiry are distinct messages`() {
        val timeout = RouterUnavailableException.Timeout().userMessage()
        val expired = RouterUnavailableException.SessionExpired().userMessage()

        assertEquals(EXPIRY_MESSAGE, expired)
        assertNotEquals(timeout, expired)
    }

    @Test
    fun `an unreachable router is not presented as an expiry`() {
        val message = RouterUnavailableException.Unreachable().userMessage()

        assertFalse(message.contains("expiré"))
    }

    @Test
    fun `a timeout is not presented as an expiry either`() {
        val message = RouterUnavailableException.Timeout().userMessage()

        assertFalse(message.contains("expiré"))
        assertFalse(message.contains("Le réseau a changé"))
    }

    @Test
    fun `a malformed response is not presented as an expiry`() {
        val message = RouterUnavailableException.UnexpectedResponse().userMessage()

        assertFalse(message.contains("expiré"))
    }

    @Test
    fun `the expiry message is never empty`() {
        assertTrue(EXPIRY_MESSAGE.isNotBlank())
        assertTrue(NETWORK_CHANGED_MESSAGE.isNotBlank())
    }

    @Test
    fun `the format of the snapshot date is the French one`() {
        val formatted = formatSnapshotDate(CAPTURED_AT)

        assertTrue(
            "the date must be written as dd/MM/yyyy, was: $formatted",
            formatted != null && formatted.matches(Regex("""\d{2}/\d{2}/\d{4} à \d{2}:\d{2}"""))
        )
    }

    @Test
    fun `an unknown date has no formatted value`() {
        assertNull(formatSnapshotDate(null))
    }

    companion object {
        private const val CAPTURED_AT = 1_700_000_000_000L
    }
}
