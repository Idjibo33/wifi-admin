package dev.wifiadmin.router

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The session renewal path of [ZltRouterClient], exercised end to end.
 *
 * These tests **count the requests**, so they prove that the renewal is
 * attempted exactly once and never loops. They are the protocol half of the
 * Mission 08 session-expiry scenario; the Android half is covered by
 * `SessionExpiryFlowTest` in the `app` module.
 *
 * The sequence under test is the one the real firmware produces:
 *
 *     login OK  ->  read says NO_AUTH  ->  renewal refused
 *
 * The renewal is refused, not merely repeated, because that is the case that
 * must be reported as an expiry.
 */
class ZltRouterClientRenewalTest {

    private val settings = object : RouterSettings {
        override val routerHost = "192.168.1.1"
        override val username = "root"
        override val password = "admin"
    }

    private lateinit var router: FakeRouter
    private lateinit var client: ZltRouterClient

    @Before
    fun setUp() {
        router = FakeRouter()
        client = ZltRouterClient(settings, transport = router)
    }

    /** A challenge that always succeeds. */
    private fun stubChallenge() {
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
    }

    /** A login that succeeds only the first time: the renewal is refused. */
    private fun stubLoginSucceedingOnce() {
        var calls = 0
        router.onJson(100) {
            calls++
            if (calls == 1) {
                JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION")
            } else {
                // The shape the firmware really sends for a refused login.
                JSONObject()
                    .put("success", true)
                    .put("login_fail", "fail")
                    .put("login_times", "1")
            }
        }
    }

    /** Every device read reports an expired session. */
    private fun stubReadsExpired(message: String = "NO_AUTH") {
        router.onJson(223) { JSONObject().put("success", false).put("message", message) }
        router.onJson(224) { JSONObject().put("success", false).put("message", message) }
        router.onJson(225) { JSONObject().put("success", false).put("message", message) }
    }

    // -----------------------------------------------------------------
    // The renewal itself
    // -----------------------------------------------------------------

    @Test
    fun `a refused renewal raises an authentication exception`() {
        stubChallenge()
        stubLoginSucceedingOnce()
        stubReadsExpired()

        try {
            client.fetchDevices()
            fail("expected an authentication exception")
        } catch (expected: RouterAuthenticationException) {
            assertTrue(
                "the message must name the renewal, not the credentials",
                expected.message.orEmpty().contains("re-login")
            )
        }
    }

    @Test
    fun `a refused renewal is attempted exactly once`() {
        stubChallenge()
        stubLoginSucceedingOnce()
        stubReadsExpired()

        runCatching { client.fetchDevices() }

        assertEquals(
            "two logins: the initial one and one renewal",
            2,
            router.sent(100, "POST").size
        )
        assertEquals(
            "one challenge per login",
            2,
            router.sent(232, "GET").size
        )
    }

    @Test
    fun `a refused renewal does not repeat the read`() {
        stubChallenge()
        stubLoginSucceedingOnce()
        stubReadsExpired()

        runCatching { client.fetchDevices() }

        assertEquals(
            "the read must not be repeated after a refused renewal",
            1,
            router.sent(223, "GET").size
        )
    }

    @Test
    fun `a successful renewal repeats the read exactly once`() {
        stubChallenge()
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION") }
        var reads = 0
        router.onJson(223) {
            reads++
            if (reads == 1) {
                JSONObject().put("success", false).put("message", "NO_AUTH")
            } else {
                Fixtures.dhcpReply(emptyList())
            }
        }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        client.fetchDevices()

        assertEquals(2, router.sent(223, "GET").size)
        assertEquals(2, router.sent(100, "POST").size)
    }

    @Test
    fun `a login timeout follows the same renewal path as no auth`() {
        stubChallenge()
        stubLoginSucceedingOnce()
        stubReadsExpired(message = "LOGIN_TIMEOUT")

        try {
            client.fetchDevices()
            fail("expected an authentication exception")
        } catch (expected: RouterAuthenticationException) {
            assertTrue(expected.message.orEmpty().contains("re-login"))
        }

        assertEquals(
            "the renewal must be attempted exactly once",
            2,
            router.sent(100, "POST").size
        )
    }

    @Test
    fun `a valid session is never renewed`() {
        stubChallenge()
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION") }
        router.onJson(223) { Fixtures.dhcpReply(emptyList()) }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        client.fetchDevices()

        assertEquals(1, router.sent(100, "POST").size)
        assertEquals(1, router.sent(232, "GET").size)
    }

    // -----------------------------------------------------------------
    // The renewal must not be mistaken for another failure
    // -----------------------------------------------------------------

    @Test
    fun `a renewal blocked by the penalty window is a cooldown, not an expiry`() {
        var challenges = 0
        router.onJson(232) {
            challenges++
            if (challenges == 1) {
                JSONObject().put("success", true).put("token", "CHALLENGE")
            } else {
                // The firmware announces the 180 s penalty this way.
                JSONObject()
                    .put("success", true)
                    .put("token", "CHALLENGE")
                    .put("buffer", "0")
                    .put("netx_login_time", 30)
            }
        }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION") }
        stubReadsExpired()

        try {
            client.fetchDevices()
            fail("expected a cooldown exception")
        } catch (expected: RouterCooldownException) {
            assertEquals(150, expected.retryAfterSeconds)
        }
    }

    @Test
    fun `a renewal without any positive signal is a cooldown, not an expiry`() {
        stubChallenge()
        var logins = 0
        router.onJson(100) {
            logins++
            if (logins == 1) {
                JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION")
            } else {
                // The bare success the firmware sends while refusing logins.
                JSONObject().put("success", true)
            }
        }
        stubReadsExpired()

        try {
            client.fetchDevices()
            fail("expected a cooldown exception")
        } catch (expected: RouterCooldownException) {
            assertEquals(null, expected.retryAfterSeconds)
        }
    }

    @Test
    fun `the credentials are not sent during the penalty window`() {
        var challenges = 0
        router.onJson(232) {
            challenges++
            if (challenges == 1) {
                JSONObject().put("success", true).put("token", "CHALLENGE")
            } else {
                JSONObject()
                    .put("success", true)
                    .put("token", "CHALLENGE")
                    .put("buffer", "0")
                    .put("netx_login_time", 30)
            }
        }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION") }
        stubReadsExpired()

        runCatching { client.fetchDevices() }

        assertEquals(
            "the renewal must be refused before the credentials are sent",
            1,
            router.sent(100, "POST").size
        )
    }

    @Test
    fun `an expiry message never carries a token or a session id`() {
        stubChallenge()
        stubLoginSucceedingOnce()
        stubReadsExpired()

        try {
            client.fetchDevices()
            fail("expected an authentication exception")
        } catch (expected: RouterAuthenticationException) {
            val message = expected.message.orEmpty()
            assertTrue(!message.contains("CHALLENGE"))
            assertTrue(!message.contains("ROUTER-SESSION"))
        }
    }
}
