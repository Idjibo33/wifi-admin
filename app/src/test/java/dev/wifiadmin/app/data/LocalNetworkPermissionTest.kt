package dev.wifiadmin.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission 15 — le cœur pur de la décision permission LAN.
 *
 * Ces tests ne touchent jamais Android : [resolveLocalNetworkPermissionStatus]
 * est une fonction pure, et le fake ci-dessous reproduit l'idempotence
 * attendue du gateway.
 */
class LocalNetworkPermissionTest {

    @Test
    fun `API 26 a 36 ne requiert jamais la permission`() {
        for (sdk in listOf(26, 28, 33, 34, 35, 36)) {
            val state = resolveLocalNetworkPermissionStatus(
                sdkInt = sdk,
                targetSdk = 37,
                granted = false,
                rationale = false,
                previouslyRefused = false,
            )
            assertEquals(
                "sdk=$sdk doit être NotRequired",
                LocalNetworkPermissionStatus.NotRequired,
                state.status,
            )
            assertFalse(state.canAskAgain)
        }
        assertFalse(requiresLocalNetworkPermission(sdkInt = 36, targetSdk = 37))
    }

    @Test
    fun `cible SDK 36 ou moins ne requiert jamais la permission mme sur Android 17`() {
        val state = resolveLocalNetworkPermissionStatus(
            sdkInt = 37,
            targetSdk = 36,
            granted = false,
            rationale = false,
            previouslyRefused = false,
        )
        assertEquals(LocalNetworkPermissionStatus.NotRequired, state.status)
        assertFalse(requiresLocalNetworkPermission(sdkInt = 37, targetSdk = 36))
    }

    @Test
    fun `Android 17 avec permission accordee passe`() {
        val state = resolveLocalNetworkPermissionStatus(
            sdkInt = 37,
            targetSdk = 37,
            granted = true,
            rationale = false,
            previouslyRefused = false,
        )
        assertEquals(LocalNetworkPermissionStatus.Granted, state.status)
        assertTrue(requiresLocalNetworkPermission(sdkInt = 37, targetSdk = 37))
    }

    @Test
    fun `premiere demande requise mais autorisee a redemander`() {
        val state = resolveLocalNetworkPermissionStatus(
            sdkInt = 37,
            targetSdk = 37,
            granted = false,
            rationale = true,
            previouslyRefused = false,
        )
        assertEquals(LocalNetworkPermissionStatus.Required, state.status)
        assertTrue(state.canAskAgain)
    }

    @Test
    fun `refus simple reste redemandable`() {
        val state = resolveLocalNetworkPermissionStatus(
            sdkInt = 37,
            targetSdk = 37,
            granted = false,
            rationale = true,
            previouslyRefused = true,
        )
        assertEquals(LocalNetworkPermissionStatus.Refused, state.status)
        assertTrue(state.canAskAgain)
    }

    @Test
    fun `refus permanent ne redemande plus`() {
        val state = resolveLocalNetworkPermissionStatus(
            sdkInt = 37,
            targetSdk = 37,
            granted = false,
            rationale = false,
            previouslyRefused = true,
        )
        assertEquals(LocalNetworkPermissionStatus.PermanentlyRefused, state.status)
        assertFalse(state.canAskAgain)
    }

    @Test
    fun `le fake gateway ne lance jamais en double`() {
        val fake = FakeLocalNetworkPermissionGateway(
            initial = LocalNetworkPermissionState(
                status = LocalNetworkPermissionStatus.Required,
                canAskAgain = true,
            ),
        )
        var launches = 0
        assertTrue(fake.requestIfNeeded { launches++ })
        // En vol : pas de second lancement.
        assertFalse(fake.requestIfNeeded { launches++ })
        assertEquals(1, launches)
        // Après résultat, un nouveau lancement redevient possible.
        fake.onRequestResult(granted = false, rationale = true)
        assertEquals(LocalNetworkPermissionStatus.Refused, fake.state.value.status)
        assertTrue(fake.requestIfNeeded { launches++ })
        assertEquals(2, launches)
    }

    @Test
    fun `le fake gateway ne demande rien quand ce n-est pas requis ou deja accorde`() {
        for (status in listOf(
            LocalNetworkPermissionStatus.NotRequired,
            LocalNetworkPermissionStatus.Granted,
            LocalNetworkPermissionStatus.PermanentlyRefused,
        )) {
            val fake = FakeLocalNetworkPermissionGateway(
                initial = LocalNetworkPermissionState(
                    status = status,
                    canAskAgain = false,
                ),
            )
            assertFalse(
                "status=$status ne doit pas lancer",
                fake.requestIfNeeded { },
            )
        }
    }

    @Test
    fun `le fake gateway retient le refus puis le passage Granted`() {
        val fake = FakeLocalNetworkPermissionGateway()
        val refused = fake.refreshStatus(granted = false, rationale = true)
        assertEquals(LocalNetworkPermissionStatus.Refused, refused.status)
        fake.onRequestResult(granted = true, rationale = false)
        assertEquals(LocalNetworkPermissionStatus.Granted, fake.state.value.status)
    }
}

/**
 * Fake déterministe du gateway, utilisé par les tests JVM.
 *
 * Vit dans la source de test (et non en production) : la production utilise
 * l'implémentation Android qui lit `ContextCompat.checkSelfPermission`.
 */
class FakeLocalNetworkPermissionGateway(
    initial: LocalNetworkPermissionState = LocalNetworkPermissionState(
        status = LocalNetworkPermissionStatus.NotRequired,
        canAskAgain = false,
    ),
) : LocalNetworkPermissionGateway {

    private val _state = MutableStateFlow(initial)
    override val state: kotlinx.coroutines.flow.StateFlow<LocalNetworkPermissionState> =
        _state.asStateFlow()

    private var previouslyRefused = initial.status == LocalNetworkPermissionStatus.Refused ||
        initial.status == LocalNetworkPermissionStatus.PermanentlyRefused

    override fun refreshStatus(): LocalNetworkPermissionState =
        refreshStatus(
            granted = _state.value.status == LocalNetworkPermissionStatus.Granted,
            rationale = false,
        )

    fun refreshStatus(granted: Boolean, rationale: Boolean): LocalNetworkPermissionState {
        if (!granted && rationale) previouslyRefused = true
        if (!granted && !rationale && previouslyRefused) {
            // Un refus sans rationale après une demande compte comme permanent.
            val perm = LocalNetworkPermissionState(
                status = LocalNetworkPermissionStatus.PermanentlyRefused,
                canAskAgain = false,
            )
            _state.value = perm
            return perm
        }
        val next = resolveLocalNetworkPermissionStatus(
            sdkInt = 37,
            targetSdk = 37,
            granted = granted,
            rationale = rationale,
            previouslyRefused = previouslyRefused,
        )
        _state.value = next
        return next
    }

    override fun requestIfNeeded(launch: (String) -> Unit): Boolean {
        val current = _state.value
        if (current.isRequestInFlight) return false
        if (current.status != LocalNetworkPermissionStatus.Required &&
            current.status != LocalNetworkPermissionStatus.Refused
        ) {
            return false
        }
        _state.value = current.copy(isRequestInFlight = true)
        launch(ACCESS_LOCAL_NETWORK_PERMISSION)
        return true
    }

    override fun onRequestResult(granted: Boolean, rationale: Boolean) {
        if (granted) {
            previouslyRefused = false
        } else if (rationale) {
            previouslyRefused = true
        } else {
            // Refus sans rationale après une demande = refus permanent.
            if (_state.value.status == LocalNetworkPermissionStatus.Required ||
                _state.value.status == LocalNetworkPermissionStatus.Refused
            ) {
                previouslyRefused = true
            }
        }
        val next = resolveLocalNetworkPermissionStatus(
            sdkInt = 37,
            targetSdk = 37,
            granted = granted,
            rationale = rationale,
            previouslyRefused = previouslyRefused,
        )
        _state.value = next.copy(isRequestInFlight = false)
    }
}
