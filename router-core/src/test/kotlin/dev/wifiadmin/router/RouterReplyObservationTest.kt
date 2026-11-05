package dev.wifiadmin.router

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterReplyObservationTest {

    // ---- Categorisation ----

    @Test
    fun `an absent message is reported as absent`() {
        assertEquals(
            RouterMessageCategory.ABSENT,
            RouterMessageSanitizer.categorize(false, null),
        )
    }

    @Test
    fun `a null message is reported as null`() {
        assertEquals(
            RouterMessageCategory.NULL,
            RouterMessageSanitizer.categorize(true, JSONObject.NULL),
        )
    }

    @Test
    fun `an empty message is reported as empty`() {
        assertEquals(
            RouterMessageCategory.EMPTY,
            RouterMessageSanitizer.categorize(true, "   "),
        )
    }

    /**
     * The reply the real firmware sent on 30/10/2026: `message` is present
     * and **empty**.
     *
     * An empty message is not an absent message: the distinction matters
     * when reading the router's behaviour.
     */
    @Test
    fun `an empty string message is empty and not absent`() {
        val observation = RouterReplyObservation.of(
            JSONObject().put("cmd", 23).put("message", "").put("success", true)
        )

        assertTrue(observation.messagePresent)
        assertEquals(RouterMessageCategory.EMPTY, observation.messageCategory)
        assertFalse(observation.datasPresent)
        assertEquals(listOf("cmd", "message", "success"), observation.topLevelKeys)
    }

    @Test
    fun `an empty string message is never displayed`() {
        assertEquals(
            "<message>",
            RouterMessageSanitizer.displayValue(true, ""),
        )
    }

    @Test
    fun `a null message is not an empty message`() {
        val observation = RouterReplyObservation.of(
            JSONObject().put("cmd", 23).put("message", JSONObject.NULL).put("success", true)
        )

        assertTrue(observation.messagePresent)
        assertEquals(RouterMessageCategory.NULL, observation.messageCategory)
    }

    @Test
    fun `the session messages are recognised`() {
        assertEquals(
            RouterMessageCategory.SESSION_EXPIRED,
            RouterMessageSanitizer.categorize(true, "NO_AUTH"),
        )
        assertEquals(
            RouterMessageCategory.SESSION_TIMEOUT,
            RouterMessageSanitizer.categorize(true, "LOGIN_TIMEOUT"),
        )
    }

    @Test
    fun `a firmware refusal is recognised`() {
        assertEquals(
            RouterMessageCategory.LIMITED_ACCESS,
            RouterMessageSanitizer.categorize(true, "LIMITED_ACCESS"),
        )
    }

    @Test
    fun `a missing parameter is recognised without publishing its wording`() {
        assertEquals(
            RouterMessageCategory.MISSING_PARAMETER,
            RouterMessageSanitizer.categorize(true, "SessionId parameter is missing"),
        )
        assertEquals(
            "<message>",
            RouterMessageSanitizer.displayValue(true, "SessionId parameter is missing"),
        )
    }

    @Test
    fun `an unknown message is never displayed`() {
        assertEquals(
            RouterMessageCategory.UNKNOWN,
            RouterMessageSanitizer.categorize(true, "user=admin password=hunter2"),
        )
        assertEquals(
            "<message>",
            RouterMessageSanitizer.displayValue(true, "user=admin password=hunter2"),
        )
    }

    @Test
    fun `a message that only starts with a known token is not displayed`() {
        assertEquals(
            "<message>",
            RouterMessageSanitizer.displayValue(true, "LIMITED_ACCESS: user=admin"),
        )
    }

    @Test
    fun `a non string message is not displayed`() {
        assertEquals(
            RouterMessageCategory.UNKNOWN,
            RouterMessageSanitizer.categorize(true, 123),
        )
        assertEquals("<message>", RouterMessageSanitizer.displayValue(true, 123))
    }

    @Test
    fun `a known token is displayed`() {
        assertEquals("LIMITED_ACCESS", RouterMessageSanitizer.displayValue(true, "LIMITED_ACCESS"))
        assertEquals("NO_AUTH", RouterMessageSanitizer.displayValue(true, "NO_AUTH"))
        assertEquals("success", RouterMessageSanitizer.displayValue(true, "success"))
    }

    // ---- Observation ----

    @Test
    fun `the observation records the success flag and its presence`() {
        val observation = RouterReplyObservation.of(
            JSONObject().put("success", true).put("cmd", 23)
        )

        assertEquals(true, observation.success)
        assertTrue(observation.successPresent)
    }

    @Test
    fun `an absent success flag is null and not present`() {
        val observation = RouterReplyObservation.of(JSONObject().put("cmd", 23))

        assertNull(observation.success)
        assertFalse(observation.successPresent)
    }

    @Test
    fun `the observation records the type of datas without its value`() {
        val observation = RouterReplyObservation.of(
            JSONObject().put("datas", JSONArray())
        )

        assertTrue(observation.datasPresent)
        assertEquals(RouterJsonType.ARRAY, observation.datasType)
    }

    @Test
    fun `a null datas is reported as present and null`() {
        val observation = RouterReplyObservation.of(
            JSONObject().put("datas", JSONObject.NULL)
        )

        assertTrue(observation.datasPresent)
        assertEquals(RouterJsonType.NULL, observation.datasType)
    }

    @Test
    fun `an absent datas is reported as absent`() {
        val observation = RouterReplyObservation.of(JSONObject().put("success", true))

        assertFalse(observation.datasPresent)
        assertNull(observation.datasType)
    }

    @Test
    fun `an error field that is null is not an error`() {
        val observation = RouterReplyObservation.of(
            JSONObject().put("success", true).put("error", JSONObject.NULL)
        )

        assertFalse(observation.hasErrorField)
    }

    @Test
    fun `an error field with a value is an error`() {
        val observation = RouterReplyObservation.of(
            JSONObject().put("success", true).put("error", "boom")
        )

        assertTrue(observation.hasErrorField)
    }

    @Test
    fun `the observation keeps no raw content`() {
        val observation = RouterReplyObservation.of(
            JSONObject()
                .put("cmd", 23)
                .put("message", "a secret text")
                .put("success", true)
                .put("datas", JSONArray().put("AA:BB:CC:DD:EE:01"))
        )

        val rendered = observation.toString()
        assertFalse(rendered.contains("secret text"))
        assertFalse(rendered.contains("AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun `the top level keys are sorted`() {
        val observation = RouterReplyObservation.of(
            JSONObject().put("success", true).put("cmd", 23).put("datas", JSONArray())
        )

        assertEquals(listOf("cmd", "datas", "success"), observation.topLevelKeys)
    }
}
