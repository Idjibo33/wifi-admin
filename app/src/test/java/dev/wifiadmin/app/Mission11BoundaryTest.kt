package dev.wifiadmin.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Mission 11 design tokens must not decay back into alpha blends.
 *
 * These are source tests, like the other boundary tests: an alpha on
 * `surfaceVariant` and a hardcoded colour must fail loudly, so the design
 * system cannot silently dissolve into one-off values again.
 */
class Mission11BoundaryTest {

    private fun mainSources(): List<File> =
        File("src/main/java/dev/wifiadmin/app")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()

    @Test
    fun `no semi transparent surface role`() {
        val offenders = mainSources().filter { file ->
            val text = file.readText()
            text.contains("surfaceVariant.copy(alpha") ||
                text.contains("surfaceVariant.copy(alpha ".replace(" ", ""))
        }

        assertTrue(
            "the cards must use a surface container role, not an alpha blend: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the cards use the surface container roles`() {
        val used = mainSources().any { it.readText().contains("surfaceContainerHigh") }

        assertTrue(
            "the cards must use the Material 3 surface container roles",
            used
        )
    }

    @Test
    fun `the theme defines both the light and the dark surface containers`() {
        val theme = File("src/main/java/dev/wifiadmin/app/ui/theme/Theme.kt").readText()

        listOf(
            "surfaceContainerLow",
            "surfaceContainer",
            "surfaceContainerHigh",
            "surfaceContainerHighest",
        ).forEach { role ->
            assertTrue(
                "the theme must define the $role in both colour schemes",
                theme.split(role).size > 2
            )
        }
    }

    @Test
    fun `the date of the last refresh includes the day`() {
        val source = File(
            "src/main/java/dev/wifiadmin/app/ui/dashboard/DashboardViewModel.kt"
        ).readText()

        assertTrue(
            "the last refresh wording must include the day, not only the hour",
            source.contains("dd/MM/yyyy")
        )
    }

    @Test
    fun `the screens announce their states to the screen reader`() {
        val errorState = File(
            "src/main/java/dev/wifiadmin/app/ui/components/CommonComponents.kt"
        ).readText()
        assertTrue(
            "the error state must be a live region",
            errorState.contains("liveRegion = LiveRegionMode.Assertive")
        )
        assertTrue(
            "the empty state must be a live region",
            errorState.contains("liveRegion = LiveRegionMode.Polite")
        )

        val login = File(
            "src/main/java/dev/wifiadmin/app/ui/login/LoginScreen.kt"
        ).readText()
        assertTrue(
            "the login messages must announce themselves",
            login.contains("liveRegion = LiveRegionMode.Assertive")
        )
    }

    @Test
    fun `the cards announce their value aloud`() {
        val components = File(
            "src/main/java/dev/wifiadmin/app/ui/components/CommonComponents.kt"
        ).readText()

        assertTrue(
            "the statistic cards must expose their value as a state description",
            components.contains("stateDescription")
        )
    }

    @Test
    fun `the detail row is not fixed width`() {
        val components = File(
            "src/main/java/dev/wifiadmin/app/ui/components/CommonComponents.kt"
        ).readText()

        assertTrue(
            "the detail label must not be pinned to a fixed width",
            !components.contains("Modifier.width(120.dp)")
        )
        assertTrue(
            "the detail row must use weights",
            components.contains("Modifier.weight(0.45f)")
        )
    }
}
