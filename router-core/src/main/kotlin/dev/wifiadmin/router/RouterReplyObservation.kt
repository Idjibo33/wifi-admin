package dev.wifiadmin.router

import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON type of a value, without the value itself.
 */
enum class RouterJsonType {
    OBJECT,
    ARRAY,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    UNKNOWN;

    companion object {
        /** Determines the type of a value read with [JSONObject.opt]. */
        fun of(value: Any?): RouterJsonType = when {
            value == null || value == JSONObject.NULL -> NULL
            value is JSONObject -> OBJECT
            value is JSONArray -> ARRAY
            value is String -> STRING
            value is Number -> NUMBER
            value is Boolean -> BOOLEAN
            else -> UNKNOWN
        }
    }
}

/**
 * What the `message` field of a router reply means.
 *
 * The firmware uses `message` as a **polymorphic channel**: a status token on
 * a failure, a result code on some commands (`"0"` means success on `cmd
 * 132`), or even a serialised payload (`cmd 82`). It is therefore never
 * displayed as-is: only its category is kept.
 *
 * > Verified in the router's own `main.js` (`postJSON`): `message` is read
 * > **only** on the `success == false` branch. On a successful reply the web
 * > UI never looks at it, so a non-empty `message` on a success means the
 * > reply came from a different code path than the device listings.
 */
enum class RouterMessageCategory {
    /** No `message` field in the reply. */
    ABSENT,

    /** `message` is explicitly `null`. */
    NULL,

    /** `message` is an empty string. */
    EMPTY,

    /** The session has expired (`NO_AUTH`). */
    SESSION_EXPIRED,

    /** The session has expired (`LOGIN_TIMEOUT`). */
    SESSION_TIMEOUT,

    /** The firmware refuses this command for this account (`LIMITED_ACCESS`). */
    LIMITED_ACCESS,

    /** A positive confirmation (`success`). */
    SUCCESS,

    /** A parameter is missing (the firmware's own wording is not published). */
    MISSING_PARAMETER,

    /** A known token that is safe to display. */
    DISPLAYABLE,

    /** An unknown value: it is never displayed. */
    UNKNOWN,
}

/**
 * The only router messages that may be shown to the user.
 *
 * These are enumeration tokens, not free text: they carry no personal data
 * and no secret. Every other value is replaced by [RouterMessageSanitizer.REDACTED].
 */
internal val DISPLAYABLE_ROUTER_MESSAGES: Set<String> = setOf(
    "NO_AUTH",
    "LOGIN_TIMEOUT",
    "LIMITED_ACCESS",
    "UNSUPPORTED_CMD",
    "READ_ONLY_FAKE",
    "SUCCESS",
)

/**
 * Turns a router `message` into a category, and into a safe display value.
 *
 * The raw value is **never** returned: a router is not a trusted source, and
 * its `message` may contain anything (a password in clear text, a base64
 * blob, a newline that would forge a report line).
 */
object RouterMessageSanitizer {

    /** What an unrecognised message is replaced with. */
    const val REDACTED = "<message>"

    /** The firmware's own wording for a missing parameter. */
    private const val MISSING_PARAMETER_PREFIX = "SessionId parameter is missing"

    /**
     * Classifies the `message` field of a reply.
     *
     * @param messagePresent whether the field exists at all.
     * @param raw the raw value, or null when the field is absent.
     */
    fun categorize(messagePresent: Boolean, raw: Any?): RouterMessageCategory = when {
        !messagePresent -> RouterMessageCategory.ABSENT
        raw == null || raw == JSONObject.NULL -> RouterMessageCategory.NULL
        raw !is String -> RouterMessageCategory.UNKNOWN
        else -> {
            val value = raw.trim()
            when {
                value.isEmpty() -> RouterMessageCategory.EMPTY
                value.equals("NO_AUTH", ignoreCase = true) ->
                    RouterMessageCategory.SESSION_EXPIRED
                value.equals("LOGIN_TIMEOUT", ignoreCase = true) ->
                    RouterMessageCategory.SESSION_TIMEOUT
                value.equals("LIMITED_ACCESS", ignoreCase = true) ->
                    RouterMessageCategory.LIMITED_ACCESS
                value.equals("success", ignoreCase = true) ->
                    RouterMessageCategory.SUCCESS
                value.startsWith(MISSING_PARAMETER_PREFIX, ignoreCase = true) ->
                    RouterMessageCategory.MISSING_PARAMETER
                value.uppercase() in DISPLAYABLE_ROUTER_MESSAGES ->
                    RouterMessageCategory.DISPLAYABLE
                else -> RouterMessageCategory.UNKNOWN
            }
        }
    }

    /**
     * The value that may be displayed for a `message` field.
     *
     * Only the tokens of [DISPLAYABLE_ROUTER_MESSAGES] are shown; anything
     * else becomes [REDACTED]. The comparison is exact: a value that merely
     * starts with a known token (`"LIMITED_ACCESS: user=admin"`) is not
     * displayed.
     */
    fun displayValue(messagePresent: Boolean, raw: Any?): String {
        if (!messagePresent) return REDACTED
        val value = (raw as? String)?.trim().orEmpty()
        return if (value.uppercase() in DISPLAYABLE_ROUTER_MESSAGES) value else REDACTED
    }}

/**
 * What a router reply allowed us to observe — and nothing more.
 *
 * This is the **only** representation of a reply the firewall client keeps.
 * It deliberately holds no raw body, no free text and no value of any data
 * field: only booleans, categories, a JSON type and the list of key names.
 *
 * @property success the value of `success`, or null when the field is absent.
 * @property successPresent whether the field exists.
 * @property messageCategory what the `message` means, never its value.
 * @property messagePresent whether the field exists.
 * @property messageDisplayable the safe display value of `message`.
 * @property datasPresent whether `datas` exists.
 * @property datasType the JSON type of `datas`, or null when it is absent.
 * @property hasErrorField whether a non-null `error` field exists.
 * @property topLevelKeys the names of the reply's top-level keys.
 * @property echoedCmd the `cmd` the router echoed, or null.
 */
data class RouterReplyObservation(
    val success: Boolean?,
    val successPresent: Boolean,
    val messageCategory: RouterMessageCategory,
    val messagePresent: Boolean,
    val messageDisplayable: String,
    val datasPresent: Boolean,
    val datasType: RouterJsonType?,
    val hasErrorField: Boolean,
    val topLevelKeys: List<String>,
    val echoedCmd: Int?,
) {
    companion object {
        /** Observes a reply without keeping any of its content. */
        fun of(reply: JSONObject): RouterReplyObservation {
            val messagePresent = reply.has("message")
            val rawMessage = if (messagePresent) reply.opt("message") else null
            val datasPresent = reply.has("datas")
            val rawDatas = if (datasPresent) reply.opt("datas") else null

            return RouterReplyObservation(
                success = if (reply.has("success")) reply.optBoolean("success", false) else null,
                successPresent = reply.has("success"),
                messageCategory = RouterMessageSanitizer.categorize(messagePresent, rawMessage),
                messagePresent = messagePresent,
                messageDisplayable = RouterMessageSanitizer.displayValue(messagePresent, rawMessage),
                datasPresent = datasPresent,
                datasType = if (datasPresent) RouterJsonType.of(rawDatas) else null,
                hasErrorField = reply.has("error") && !reply.isNull("error"),
                topLevelKeys = reply.keys().asSequence().sorted().toList(),
                echoedCmd = if (reply.has("cmd") && reply.opt("cmd") is Number) {
                    reply.optInt("cmd")
                } else {
                    null
                },
            )
        }
    }
}
