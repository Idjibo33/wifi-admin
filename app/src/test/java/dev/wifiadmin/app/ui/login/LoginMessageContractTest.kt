package dev.wifiadmin.app.ui.login

import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.app.ui.components.EXPIRY_MESSAGE
import dev.wifiadmin.app.ui.components.userMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The exact sentence shown when the session has expired.
 *
 * The message is part of the product contract, and it is used in two places:
 * the login screen and the error messages of the screens. They must never
 * drift apart, so both are checked here.
 *
 * The login screen itself cannot be rendered by a JVM test — it would need a
 * Compose UI test and an emulator. Its use of the shared constant is
 * therefore checked by reading the source, the same way
 * `ArchitectureBoundaryTest` already reads the sources.
 */
class LoginMessageContractTest {

    private fun loginScreenSource(): String {
        val source = File("src/main/java/dev/wifiadmin/app/ui/login/LoginScreen.kt")
        assertTrue(
            "the login screen source was not found",
            source.exists()
        )
        return source.readText()
    }

    @Test
    fun `the expiry sentence is the exact product wording`() {
        assertEquals(
            "Votre session a expiré. Reconnectez-vous pour continuer.",
            EXPIRY_MESSAGE
        )
    }

    @Test
    fun `the login screen shows the shared expiry constant`() {
        val source = loginScreenSource()

        assertTrue(
            "the login screen must show the shared EXPIRY_MESSAGE constant, " +
                "so the wording cannot drift from the one that is tested",
            source.contains("EXPIRY_MESSAGE")
        )
    }

    @Test
    fun `the login screen does not hardcode another expiry sentence`() {
        val source = loginScreenSource()
        // Every sentence that mentions an expiry must be the shared one.
        val expirySentences = Regex("\"[^\"]*expir[^\"]*\"").findAll(source)
            .map { it.value }
            .toList()

        for (sentence in expirySentences) {
            assertEquals(
                "the login screen must not carry its own expiry wording",
                "\"$EXPIRY_MESSAGE\"",
                sentence
            )
        }
    }

    @Test
    fun `the expiry message of the error layer is the same sentence`() {
        assertEquals(
            EXPIRY_MESSAGE,
            RouterUnavailableException.SessionExpired().userMessage()
        )
    }

    @Test
    fun `no other failure is reported as an expiry`() {
        val failures = listOf(
            RouterUnavailableException.Unreachable(),
            RouterUnavailableException.Timeout(),
            RouterUnavailableException.AuthenticationFailed(),
            RouterUnavailableException.Cooldown(30),
            RouterUnavailableException.UnexpectedResponse(),
            RouterUnavailableException.NotConnected(),
        )

        for (failure in failures) {
            assertFalse(
                "${failure::class.simpleName} must not be reported as an expiry",
                failure.userMessage().contains("expiré")
            )
        }
    }
}
