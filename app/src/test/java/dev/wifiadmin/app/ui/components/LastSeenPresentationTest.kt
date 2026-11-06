package dev.wifiadmin.app.ui.components

import dev.wifiadmin.app.ui.devices.DeviceEmptyReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording of the last observation and of the empty states.
 *
 * The wording matters as much as the behaviour: the application must never
 * claim to know the moment a device disconnected, and it must tell the
 * user **why** the list is empty.
 */
class LastSeenPresentationTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a recent observation is seen recently`() {
        assertEquals(
            SEEN_RECENTLY_MESSAGE,
            formatLastSeen(now - 60_000L, now)
        )
    }

    @Test
    fun `an observation of a few minutes is seen recently`() {
        // Five minutes is the window: the polling is every thirty seconds,
        // so after that the absence is certain.
        assertEquals(
            SEEN_RECENTLY_MESSAGE,
            formatLastSeen(now - 4 * 60_000L, now)
        )
    }

    @Test
    fun `an observation of a few hours is expressed in hours`() {
        assertEquals("Vu il y a 3 h", formatLastSeen(now - 3 * 3_600_000L, now))
    }

    @Test
    fun `an observation of a few days is expressed in days`() {
        assertEquals("Vu il y a 2 j", formatLastSeen(now - 2 * 86_400_000L, now))
    }

    @Test
    fun `an observation older than a week shows its date`() {
        val old = now - 30 * 86_400_000L

        val wording = formatLastSeen(old, now)

        assertTrue(
            "the date must be shown, got: $wording",
            wording != null && wording.startsWith("Vu le ")
        )
    }

    @Test
    fun `an observation in the future says nothing`() {
        // The clock of the phone moved: nothing honest can be said.
        assertNull(formatLastSeen(now + 60_000L, now))
    }

    @Test
    fun `an observation that never happened says nothing`() {
        assertNull(formatLastSeen(null, now))
    }

    @Test
    fun `the wording never claims a moment of disconnection`() {
        val wordings = listOf(
            SEEN_RECENTLY_MESSAGE,
            LAST_SEEN_LABEL,
            LAST_SEEN_EXPLANATION,
            NEVER_SEEN_MESSAGE,
            formatLastSeen(now - 60_000L, now) ?: "",
            formatLastSeen(now - 3 * 3_600_000L, now) ?: "",
        )

        // The application does not know when a device left: it must never
        // say it.
        listOf("déconnecté à", "déconnecté le", "absent depuis", "hors ligne depuis")
            .forEach { forbidden ->
                wordings.forEach { wording ->
                    assertTrue(
                        "the wording must not contain '$forbidden': $wording",
                        !wording.contains(forbidden, ignoreCase = true)
                    )
                }
            }
    }

    // -----------------------------------------------------------------
    // The empty states
    // -----------------------------------------------------------------

    @Test
    fun `the empty state tells the reason apart`() {
        assertEquals(
            "Réponse du routeur vide : aucune liste d'appareils n'a été reçue.",
            deviceEmptyMessage(DeviceEmptyReason.NO_REPLY, "", "Tous")
        )
        assertEquals(
            "Aucun appareil n'était connu lors de la dernière lecture.",
            deviceEmptyMessage(DeviceEmptyReason.NO_DEVICE_CACHED, "", "Tous")
        )
        assertEquals(
            "Aucun appareil signalé par le routeur.",
            deviceEmptyMessage(DeviceEmptyReason.NO_DEVICE, "", "Tous")
        )
        assertEquals(
            "Aucun appareil ne correspond à « poste ».",
            deviceEmptyMessage(DeviceEmptyReason.NO_MATCH_QUERY, "poste", "Tous")
        )
        assertEquals(
            "Aucun appareil en 5 GHz.",
            deviceEmptyMessage(DeviceEmptyReason.NO_MATCH_FILTER, "", "5 GHz")
        )
        assertEquals(
            "Aucun appareil en 5 GHz ne correspond à « poste ».",
            deviceEmptyMessage(DeviceEmptyReason.NO_MATCH_BOTH, "poste", "5 GHz")
        )
    }

    @Test
    fun `the empty state never says that no device is connected when a filter hid them`() {
        // "Aucun appareil signalé par le routeur" would be a lie: the
        // router knows devices, the filter hid them.
        val wording = deviceEmptyMessage(DeviceEmptyReason.NO_MATCH_FILTER, "", "5 GHz")

        assertTrue(
            "the wording must not claim the network is empty: $wording",
            !wording.contains("signalé par le routeur")
        )
    }
}
