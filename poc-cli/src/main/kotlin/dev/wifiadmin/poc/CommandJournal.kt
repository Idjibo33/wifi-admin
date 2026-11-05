package dev.wifiadmin.poc

import dev.wifiadmin.router.RouterTransport
import org.json.JSONObject

/**
 * One request that was actually sent to the router.
 *
 * The record holds **only** the command number and the JSON method. The
 * request body is never kept: it carries the password hash, the challenge
 * token and the session id.
 */
data class RouterRequestRecord(
    val cmd: Int,
    val method: String,
)

/**
 * Records the commands a run really sent.
 *
 * The journal lives at the transport level because that is the only place
 * that sees **every** request, including the ones of a client with its own
 * session and the retries after a network failure or an expired session.
 */
class CommandJournal {

    private val records = mutableListOf<RouterRequestRecord>()

    /** Records one request. */
    fun add(record: RouterRequestRecord) {
        records += record
    }

    /** Every request, in order. */
    fun records(): List<RouterRequestRecord> = records.toList()

    /**
     * The distinct command numbers, in the order they were first sent.
     */
    fun commands(): List<Int> = records.map { it.cmd }.distinct()

    /**
     * The distinct command numbers, with how many times each was sent.
     *
     * A command sent twice — the Wi-Fi client opens its own session, so it
     * logs in again — must be visible as such: the report is an audit
     * trail, and hiding a second authentication would misrepresent it.
     */
    fun commandsWithCounts(): List<Pair<Int, Int>> {
        val firstIndex = mutableMapOf<Int, Int>()
        records.forEachIndexed { index, record ->
            if (record.cmd !in firstIndex) firstIndex[record.cmd] = index
        }
        return records.groupingBy { it.cmd }.eachCount().toList()
            .sortedBy { (cmd, _) -> firstIndex[cmd] ?: 0 }
            .map { (cmd, count) -> cmd to count }
    }

    /**
     * The distinct `(cmd, method)` pairs, in the order they were first sent.
     */
    fun commandPairs(): List<Pair<Int, String>> =
        records.map { it.cmd to it.method }.distinct()

    /** How many times a command was sent. */
    fun countOf(cmd: Int): Int = records.count { it.cmd == cmd }
}

/**
 * A transport that notes the requests before delegating them.
 *
 * Only `cmd` and `method` are read from the body. Nothing else is copied,
 * so the journal cannot leak a secret by construction.
 */
class RecordingTransport(
    private val delegate: RouterTransport,
    private val journal: CommandJournal,
) : RouterTransport {

    override fun post(url: String, body: String, timeoutSeconds: Long): String {
        runCatching {
            val request = JSONObject(body)
            RouterRequestRecord(
                cmd = request.optInt("cmd", -1),
                method = request.optString("method"),
            )
        }.getOrNull()?.let(journal::add)

        return delegate.post(url, body, timeoutSeconds)
    }
}
