package dev.wifiadmin.router

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class ZltRouterClientTest {

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

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun stubLogin() {
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION") }
    }

    private fun stubEmptyReads() {
        router.onJson(223) { Fixtures.dhcpReply(emptyList()) }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }
    }

    // ---- Authentication ----

    @Test
    fun `login sends sha256 of challenge token plus password`() {
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION") }

        assertTrue(client.login())

        val login = router.sent(100, "POST").single().json
        assertEquals(sha256Hex("CHALLENGEadmin"), login.getString("passwd"))
    }

    @Test
    fun `login forwards the username`() {
        stubLogin()

        client.login()

        assertEquals("root", router.sent(100, "POST").single().json.getString("username"))
    }

    @Test
    fun `login never sends the password in clear text`() {
        stubLogin()

        client.login()

        val body = router.sent(100, "POST").single().body
        assertTrue(!body.contains("admin"))
    }

    @Test
    fun `the session id returned by the router is used for later requests`() {
        stubLogin()
        stubEmptyReads()

        client.login()
        client.fetchDevices()

        assertEquals("ROUTER-SESSION", router.sent(223, "GET").single().json.getString("sessionId"))
    }

    @Test
    fun `the client generated session id is kept when the router omits it`() {
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        // A bare `success` is not enough to prove the login: the router must
        // also say so explicitly. This firmware answers `success: true` for a
        // wrong password, so the positive signal is mandatory.
        router.onJson(100) { JSONObject().put("success", true).put("message", "success") }
        stubEmptyReads()

        client.login()
        client.fetchDevices()

        val generated = router.sent(100, "POST").single().json.getString("sessionId")
        assertEquals(64, generated.length)
        assertTrue(generated.all { it.isDigit() || it in 'a'..'f' })
        assertEquals(generated, router.sent(223, "GET").single().json.getString("sessionId"))
    }

    @Test
    fun `the challenge token request has the expected shape`() {
        stubLogin()

        client.login()

        val request = router.sent(232, "GET").single().json
        assertEquals(232, request.getInt("cmd"))
        assertEquals("GET", request.getString("method"))
        assertEquals("", request.getString("sessionId"))
    }

    @Test
    fun `the login request has the expected shape`() {
        stubLogin()

        client.login()

        val request = router.sent(100, "POST").single().json
        assertEquals(100, request.getInt("cmd"))
        assertEquals("POST", request.getString("method"))
        assertEquals("root", request.getString("username"))
        assertEquals("0", request.getString("isAutoUpgrade"))
        assertEquals(0, request.getInt("subcmd"))
        assertEquals(64, request.getString("sessionId").length)
    }

    @Test
    fun `login fails when the router rejects the credentials`() {
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) { JSONObject().put("success", false).put("message", "INVALID_PASSWORD") }

        assertTrue(!client.login())
    }

    /**
     * This firmware answers `success: true` even when the password is wrong.
     * The real signal is the `login_fail` flag. Accepting on `success` alone
     * let a wrong password through the login screen.
     */
    @Test
    fun `login fails when the router flags a failure even if success is true`() {
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) {
            JSONObject()
                .put("success", true)
                .put("login_fail", "fail")
                .put("login_times", "1")
        }

        assertTrue(!client.login())
    }

    /**
     * After too many failures the router answers a bare `success: true`
     * without any session: it is a refusal, not an accepted login.
     */
    @Test
    fun `login fails on a bare success without any positive signal`() {
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) { JSONObject().put("success", true) }

        try {
            client.login()
            fail("a bare success must not be reported as a successful login")
        } catch (e: RouterCooldownException) {
            // No duration is known: the UI must ask the user to wait.
            assertTrue(e.retryAfterSeconds == null)
        }
    }

    /** A failure flag set to a success value must not be read as a failure. */
    @Test
    fun `login accepts a response whose failure flag is a success value`() {
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) {
            JSONObject()
                .put("success", true)
                .put("login_fail", "success")
                .put("sessionId", "ROUTER-SESSION")
        }

        assertTrue(client.login())
    }

    /**
     * While the router is in its penalty window, the client must not send the
     * credentials. The remaining wait is `180 - netx_login_time`, exactly as
     * the router's own web UI computes it.
     */
    @Test
    fun `a login inside the penalty window is refused without sending the credentials`() {
        router.onJson(232) {
            JSONObject()
                .put("success", true)
                .put("token", "CHALLENGE")
                .put("buffer", "0")
                .put("netx_login_time", 37)
        }

        try {
            client.login()
            fail("a penalty window must not be reported as a successful login")
        } catch (e: RouterCooldownException) {
            assertEquals(143, e.retryAfterSeconds)
        }

        assertTrue(router.sent(100, "POST").isEmpty())
    }

    @Test
    fun `an elapsed penalty window does not block the login`() {
        router.onJson(232) {
            JSONObject()
                .put("success", true)
                .put("token", "CHALLENGE")
                .put("buffer", "0")
                .put("netx_login_time", 200)
        }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION") }

        assertTrue(client.login())
    }

    @Test
    fun `a challenge reply outside the penalty window does not block the login`() {
        router.onJson(232) {
            JSONObject()
                .put("success", true)
                .put("token", "CHALLENGE")
                .put("buffer", "1")
                .put("netx_login_time", 864)
        }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION") }

        assertTrue(client.login())
    }

    // ---- Session expiry ----

    @Test
    fun `an expired session triggers re-login and repeats the read exactly once`() {
        stubLogin()
        var calls = 0
        router.onJson(223) {
            if (calls++ == 0) JSONObject().put("success", false).put("message", "NO_AUTH")
            else Fixtures.dhcpReply(
                listOf(Fixtures.dhcpEntry("aa:aa:aa:aa:aa:aa", "192.168.1.10", "phone"))
            )
        }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        val snapshot = client.fetchDevices()

        assertEquals(2, router.sent(223, "GET").size)
        assertEquals(2, router.sent(100, "POST").size)
        assertEquals(1, snapshot.devices.size)
        assertEquals("phone", snapshot.devices.single().name)
    }

    @Test
    fun `a login timeout triggers re-login and repeats the read exactly once`() {
        stubLogin()
        var calls = 0
        router.onJson(224) {
            if (calls++ == 0) JSONObject().put("success", false).put("message", "LOGIN_TIMEOUT")
            else Fixtures.wifi24Reply(emptyList())
        }
        router.onJson(223) { Fixtures.dhcpReply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        client.fetchDevices()

        assertEquals(2, router.sent(224, "GET").size)
        assertEquals(2, router.sent(100, "POST").size)
    }

    @Test
    fun `a session that stays expired after re-auth raises an authentication exception`() {
        stubLogin()
        router.onJson(223) { JSONObject().put("success", false).put("message", "NO_AUTH") }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        try {
            client.fetchDevices()
            fail("expected an authentication exception")
        } catch (expected: RouterAuthenticationException) {
            assertTrue(!expected.message.orEmpty().contains("CHALLENGE"))
            assertTrue(!expected.message.orEmpty().contains("ROUTER-SESSION"))
        }
        assertEquals(2, router.sent(223, "GET").size)
    }

    // ---- Failures ----

    @Test
    fun `a network failure raises a network exception`() {
        stubLogin()
        router.onNetworkFailure(223)

        try {
            client.fetchDevices()
            fail("expected a network exception")
        } catch (expected: RouterNetworkException) {
            // expected
        }
    }

    @Test
    fun `malformed json raises a protocol exception`() {
        stubLogin()
        router.onRaw(223) { "this is not json" }

        try {
            client.fetchDevices()
            fail("expected a protocol exception")
        } catch (expected: RouterProtocolException) {
            // expected
        }
    }

    @Test
    fun `a non-success reply raises a protocol exception`() {
        stubLogin()
        router.onJson(223) { JSONObject().put("success", false).put("message", "LIMITED_ACCESS") }

        try {
            client.fetchDevices()
            fail("expected a protocol exception")
        } catch (expected: RouterProtocolException) {
            // expected
        }
    }

    @Test
    fun `an http error reply raises a protocol exception`() {
        stubLogin()
        router.onRaw(223) { JSONObject().put("error", "HTTP 500").toString() }

        try {
            client.fetchDevices()
            fail("expected a protocol exception")
        } catch (expected: RouterProtocolException) {
            // expected
        }
    }

    @Test
    fun `an exception message never carries the session id or a token`() {
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION") }
        router.onJson(223) {
            JSONObject().put("success", false).put("message", "bad session ROUTER-SESSION a1b2c3d4e5f60718")
        }

        try {
            client.fetchDevices()
            fail("expected a protocol exception")
        } catch (expected: RouterProtocolException) {
            val message = expected.message.orEmpty()
            assertTrue(!message.contains("ROUTER-SESSION"))
            assertTrue(!message.contains("a1b2c3d4e5f60718"))
            assertTrue(!message.contains("CHALLENGE"))
            assertTrue(!message.contains("admin"))
        }
    }

    @Test
    fun `an exception message never carries a non hex challenge token echoed by the router`() {
        // The token format is not constrained by the protocol: it is not
        // necessarily hexadecimal. A router that echoes it back must not make it
        // reach an exception message.
        router.onJson(232) { JSONObject().put("success", true).put("token", "T0KEN-SECRET-XYZ") }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION") }
        router.onJson(223) {
            JSONObject().put("success", false).put("message", "token T0KEN-SECRET-XYZ is invalid")
        }

        try {
            client.fetchDevices()
            fail("expected a protocol exception")
        } catch (expected: RouterProtocolException) {
            val message = expected.message.orEmpty()
            assertTrue(!message.contains("T0KEN-SECRET-XYZ"))
        }
    }

    @Test
    fun `only the read commands are ever sent`() {
        stubLogin()
        stubEmptyReads()

        client.fetchDevices()

        val allowed = setOf(232, 100, 223, 224, 225)
        assertTrue(router.requests.all { it.cmd in allowed })
    }

    @Test
    fun `a read that fails on the network is retried once`() {
        stubLogin()
        stubEmptyReads()
        var attempts = 0
        router.onJson(223) {
            if (attempts++ == 0) throw java.io.IOException("stale connection") else Fixtures.dhcpReply(emptyList())
        }

        client.fetchDevices()

        assertEquals(2, router.sent(223, "GET").size)
    }

    @Test
    fun `a read that keeps failing on the network is not retried more than once`() {
        stubLogin()
        router.onNetworkFailure(223)
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        try {
            client.fetchDevices()
            fail("expected a network exception")
        } catch (expected: RouterNetworkException) {
            // expected
        }

        assertEquals(2, router.sent(223, "GET").size)
    }

    // ---- Parsing ----

    @Test
    fun `dhcp entries are parsed into devices`() {
        stubLogin()
        router.onJson(223) {
            Fixtures.dhcpReply(
                listOf(
                    Fixtures.dhcpEntry(
                        mac = "AA:BB:CC:DD:EE:FF",
                        ip = "192.168.1.50",
                        hostname = "pixel-6",
                        interfaceName = "wlan",
                        expires = "3600",
                        ipv6 = "fe80::1",
                    )
                )
            )
        }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        val device = client.fetchDevices().devices.single()

        assertEquals("aa:bb:cc:dd:ee:ff", device.mac)
        assertEquals("192.168.1.50", device.ip)
        assertEquals("pixel-6", device.name)
        assertEquals("wlan", device.interfaceName)
        assertEquals("3600", device.leaseExpiry)
        assertEquals("fe80::1", device.ipv6)
    }

    @Test
    fun `the hostname NULL is normalized to Unknown Device`() {
        stubLogin()
        router.onJson(223) {
            Fixtures.dhcpReply(
                listOf(Fixtures.dhcpEntry("aa:bb:cc:dd:ee:ff", "192.168.1.50", "NULL"))
            )
        }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        assertEquals("Unknown Device", client.fetchDevices().devices.single().name)
    }

    @Test
    fun `a blank hostname is normalized to Unknown Device`() {
        stubLogin()
        router.onJson(223) {
            Fixtures.dhcpReply(
                listOf(Fixtures.dhcpEntry("aa:bb:cc:dd:ee:ff", "192.168.1.50", "   "))
            )
        }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        assertEquals("Unknown Device", client.fetchDevices().devices.single().name)
    }

    @Test
    fun `numeric fields arriving as strings are parsed`() {
        stubLogin()
        router.onJson(223) {
            Fixtures.dhcpReply(
                listOf(
                    Fixtures.dhcpEntry(
                        mac = "aa:bb:cc:dd:ee:ff",
                        ip = "192.168.1.50",
                        hostname = "phone",
                        interfaceName = "wlan",
                        expires = 3600,
                    )
                )
            )
        }
        router.onJson(224) {
            Fixtures.wifi24Reply(
                listOf(Fixtures.wifiEntry("aa:bb:cc:dd:ee:ff", "192.168.1.50", rssi = "-60"))
            )
        }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        val device = client.fetchDevices().devices.single()

        assertEquals("3600", device.leaseExpiry)
        assertEquals(-60, device.signal?.rssiDbm)
    }

    @Test
    fun `wifi 24ghz associations are parsed`() {
        stubLogin()
        router.onJson(223) {
            Fixtures.dhcpReply(
                listOf(Fixtures.dhcpEntry("aa:bb:cc:dd:ee:01", "192.168.1.61", "phone"))
            )
        }
        router.onJson(224) {
            Fixtures.wifi24Reply(
                listOf(
                    Fixtures.wifiEntry(
                        mac = "AA:BB:CC:DD:EE:01",
                        ip = "192.168.1.61",
                        rssi = "-55",
                        txrate = "72.2",
                        rxrate = "54.0",
                        ssid = "Home24",
                    )
                )
            )
        }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        val device = client.fetchDevices().devices.single()

        assertEquals(setOf(WifiBand.BAND_2_4_GHZ), device.bands)
        assertEquals(-55, device.signal?.rssiDbm)
        assertEquals("72.2", device.signal?.txRateMbps)
        assertEquals("54.0", device.signal?.rxRateMbps)
        assertEquals("Home24", device.signal?.ssid)
        assertEquals(DevicePresence.ACTIVE, device.presence)
    }

    @Test
    fun `wifi 5 ghz associations are parsed`() {
        stubLogin()
        router.onJson(223) {
            Fixtures.dhcpReply(
                listOf(Fixtures.dhcpEntry("aa:bb:cc:dd:ee:02", "192.168.1.62", "laptop"))
            )
        }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) {
            Fixtures.wifi5gReply(
                listOf(
                    Fixtures.wifiEntry(
                        mac = "aa:bb:cc:dd:ee:02",
                        ip = "192.168.1.62",
                        rssi = "-41",
                        txrate = "866.0",
                        rxrate = "780.0",
                        ssid = "Home5",
                    )
                )
            )
        }

        val device = client.fetchDevices().devices.single()

        assertEquals(setOf(WifiBand.BAND_5_GHZ), device.bands)
        assertEquals(-41, device.signal?.rssiDbm)
        assertEquals("866.0", device.signal?.txRateMbps)
        assertEquals("Home5", device.signal?.ssid)
    }

    @Test
    fun `a numeric expires value arriving as a string is parsed`() {
        stubLogin()
        router.onJson(223) {
            Fixtures.dhcpReply(
                listOf(
                    Fixtures.dhcpEntry(
                        mac = "aa:bb:cc:dd:ee:ff",
                        ip = "192.168.1.50",
                        hostname = "phone",
                        interfaceName = "wlan",
                        expires = "3600",
                    )
                )
            )
        }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        assertEquals("3600", client.fetchDevices().devices.single().leaseExpiry)
    }

    @Test
    fun `every request goes to the router cgi endpoint`() {
        stubLogin()
        stubEmptyReads()

        client.fetchDevices()

        assertTrue(router.requests.all { it.url == "http://192.168.1.1/cgi-bin/http.cgi" })
    }

    @Test
    fun `the request timeout is configurable`() {
        val slowClient = ZltRouterClient(settings, transport = router, timeoutSeconds = 9)
        stubLogin()
        stubEmptyReads()

        slowClient.fetchDevices()

        assertTrue(router.requests.all { it.timeoutSeconds == 9L })
    }

    @Test
    fun `the default request timeout is four seconds`() {
        stubLogin()
        stubEmptyReads()

        client.fetchDevices()

        assertTrue(router.requests.all { it.timeoutSeconds == 4L })
    }

    @Test
    fun `a wifi lease without a current association and an expired lease is stale`() {
        stubLogin()
        router.onJson(223) {
            Fixtures.dhcpReply(
                listOf(
                    Fixtures.dhcpEntry(
                        mac = "aa:bb:cc:dd:ee:ff",
                        ip = "192.168.1.50",
                        hostname = "phone",
                        interfaceName = "wlan",
                        expires = "expired",
                    )
                )
            )
        }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        assertEquals(DevicePresence.STALE, client.fetchDevices().devices.single().presence)
    }

    @Test
    fun `a wired lease without a wifi association is cabled`() {
        stubLogin()
        router.onJson(223) {
            Fixtures.dhcpReply(
                listOf(
                    Fixtures.dhcpEntry(
                        mac = "aa:bb:cc:dd:ee:ff",
                        ip = "192.168.1.50",
                        hostname = "desktop",
                        interfaceName = "lan",
                    )
                )
            )
        }
        router.onJson(224) { Fixtures.wifi24Reply(emptyList()) }
        router.onJson(225) { Fixtures.wifi5gReply(emptyList()) }

        assertEquals(DevicePresence.CABLED, client.fetchDevices().devices.single().presence)
    }
}
