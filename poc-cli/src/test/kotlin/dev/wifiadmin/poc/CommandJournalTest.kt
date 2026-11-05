package dev.wifiadmin.poc

import dev.wifiadmin.router.RouterTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandJournalTest {

    @Test
    fun `the journal records every request exactly once`() {
        val journal = CommandJournal()
        val transport = RecordingTransport(StubTransport(), journal)

        transport.post("http://x/", """{"cmd":232,"method":"GET"}""", 4)
        transport.post("http://x/", """{"cmd":100,"method":"POST"}""", 4)
        transport.post("http://x/", """{"cmd":23,"method":"GET"}""", 4)

        assertEquals(listOf(232, 100, 23), journal.commands())
        assertEquals(
            listOf(232 to "GET", 100 to "POST", 23 to "GET"),
            journal.commandPairs(),
        )
    }

    @Test
    fun `the journal records a repeated command`() {
        val journal = CommandJournal()
        val transport = RecordingTransport(StubTransport(), journal)

        transport.post("http://x/", """{"cmd":23,"method":"GET"}""", 4)
        transport.post("http://x/", """{"cmd":23,"method":"GET"}""", 4)

        assertEquals(2, journal.countOf(23))
        assertEquals(listOf(23), journal.commands())
    }

    /**
     * The Wi-Fi client opens its own session, so it authenticates twice.
     *
     * The report must show it: a diagnostic that hides a second
     * authentication misrepresents what the run did.
     */
    @Test
    fun `the journal reports how many times each command was sent`() {
        val journal = CommandJournal()
        val transport = RecordingTransport(StubTransport(), journal)

        transport.post("http://x/", """{"cmd":232,"method":"GET"}""", 4)
        transport.post("http://x/", """{"cmd":100,"method":"POST"}""", 4)
        transport.post("http://x/", """{"cmd":223,"method":"GET"}""", 4)
        transport.post("http://x/", """{"cmd":232,"method":"GET"}""", 4)
        transport.post("http://x/", """{"cmd":100,"method":"POST"}""", 4)
        transport.post("http://x/", """{"cmd":278,"method":"GET"}""", 4)

        assertEquals(
            listOf(232 to 2, 100 to 2, 223 to 1, 278 to 1),
            journal.commandsWithCounts(),
        )
    }

    /**
     * The journal must never keep a secret.
     *
     * The request body carries the password hash, the challenge token and
     * the session id: none of them may reach the record.
     */
    @Test
    fun `the journal never keeps the request body`() {
        val journal = CommandJournal()
        val transport = RecordingTransport(StubTransport(), journal)

        transport.post(
            "http://x/",
            """{"cmd":100,"method":"POST","sessionId":"ROUTER-SESSION","passwd":"abcdef0123456789"}""",
            4,
        )

        val rendered = journal.records().toString()
        assertFalse(rendered.contains("ROUTER-SESSION"))
        assertFalse(rendered.contains("abcdef0123456789"))
        assertTrue(rendered.contains("100"))
    }

    @Test
    fun `the journal survives a body that is not json`() {
        val journal = CommandJournal()
        val transport = RecordingTransport(StubTransport(), journal)

        transport.post("http://x/", "not json", 4)

        assertTrue(journal.records().isEmpty())
    }

    @Test
    fun `the journal records a real router exchange`() {
        val journal = CommandJournal()
        val transport = RecordingTransport(StubTransport(), journal)

        transport.post("http://x/", """{"cmd":232,"method":"GET","sessionId":""}""", 4)
        transport.post("http://x/", """{"cmd":100,"method":"POST","sessionId":"S"}""", 4)
        transport.post("http://x/", """{"cmd":23,"method":"GET","sessionId":"S"}""", 4)

        assertEquals(listOf(232, 100, 23), journal.commands())
    }

    /** A transport that answers an empty object. */
    private class StubTransport : RouterTransport {
        override fun post(url: String, body: String, timeoutSeconds: Long): String =
            JSONObject().put("success", true).toString()
    }
}
