package dev.wifiadmin.poc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the CLI's declared command allowlist.
 *
 * The list says what the POC **may** do. It is not the history of a run:
 * that comes from the [CommandJournal]. A regression that adds `20` (apply)
 * or `233` (write token) must fail here.
 */
class CliAllowlistTest {

    @Test
    fun `the allowlist contains the firewall read commands`() {
        assertTrue(DECLARED_READ_ONLY_COMMANDS.contains(23))
        assertTrue(DECLARED_READ_ONLY_COMMANDS.contains(30))
    }

    @Test
    fun `the allowlist contains no firewall write command`() {
        assertFalse("cmd 20 applies pending rules", DECLARED_READ_ONLY_COMMANDS.contains(20))
        assertFalse("cmd 233 returns a write token", DECLARED_READ_ONLY_COMMANDS.contains(233))
    }

    @Test
    fun `the allowlist contains no mutating router command`() {
        val forbidden = listOf(
            2, 3, 6, 21, 22, 24, 25, 26, 27, 29, 31, 32,
            160, 161, 211, 222, 226, 288,
        )
        forbidden.forEach { cmd ->
            assertFalse(
                "cmd $cmd must stay out of the read-only allowlist",
                DECLARED_READ_ONLY_COMMANDS.contains(cmd),
            )
        }
    }

    /**
     * The declared allowlist must not contradict what the POC can send.
     *
     * Command 28 is the older variant of the default policy, used by
     * `--firewall-variant cmd28`.
     */
    @Test
    fun `the allowlist declares the variant of the default policy`() {
        assertTrue(DECLARED_READ_ONLY_COMMANDS.contains(28))
    }
}
