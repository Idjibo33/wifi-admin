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
 * The wording of the cached-data banner is part of the product contract: the
 * exact sentence must not drift, and a cached device must never be labelled as
 * currently connected.
 */
class PresentationTest {

    @Test
    fun `the cached banner uses the exact product sentence`() {
        val message = cachedDataMessage(
            capturedAtMillis = CAPTURED_AT,
            isFromCache = true,
            isSyncFailed = false,
            payloadAbsent = false,
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
            cachedDataMessage(null, isFromCache = true, isSyncFailed = false, payloadAbsent = false)
        )
    }

    @Test
    fun `a sync failure with live data names the last known data`() {
        val message = cachedDataMessage(
            capturedAtMillis = CAPTURED_AT,
            isFromCache = false,
            isSyncFailed = true,
            payloadAbsent = false,
        )

        assertTrue(message!!.startsWith("Synchronisation impossible"))
    }

    @Test
    fun `an incomplete reply is reported as such`() {
        val message = cachedDataMessage(
            capturedAtMillis = CAPTURED_AT,
            isFromCache = false,
            isSyncFailed = false,
            payloadAbsent = true,
        )

        assertTrue(message!!.startsWith("Réponse incomplète du routeur"))
    }

    @Test
    fun `fresh data has no banner`() {
        assertNull(
            cachedDataMessage(
                capturedAtMillis = CAPTURED_AT,
                isFromCache = false,
                isSyncFailed = false,
                payloadAbsent = false,
            )
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

        assertEquals("Votre session a expiré. Reconnectez-vous pour continuer.", expired)
        assertNotEquals(timeout, expired)
    }

    @Test
    fun `an unreachable router is not presented as an expiry`() {
        val message = RouterUnavailableException.Unreachable().userMessage()

        assertFalse(message.contains("expiré"))
    }

    companion object {
        private const val CAPTURED_AT = 1_700_000_000_000L
    }
}
