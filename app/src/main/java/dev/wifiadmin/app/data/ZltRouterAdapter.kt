package dev.wifiadmin.app.data

import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterAuthenticationException
import dev.wifiadmin.router.RouterCooldownException
import dev.wifiadmin.router.RouterNetworkException
import dev.wifiadmin.router.RouterProtocolException
import dev.wifiadmin.router.RouterSettings
import dev.wifiadmin.router.ZltRouterClient
import java.net.SocketTimeoutException

/**
 * The ZLT adapter.
 *
 * This is the **only** place in the app that knows the ZLT protocol lives
 * behind `router-core`. Everything else speaks in terms of
 * [RouterAdapter].
 */
class ZltRouterAdapter : RouterAdapter {

    override val displayName: String = "ZLT"

    private var client: ZltRouterClient? = null

    override suspend fun connect(host: String, username: String, password: String): Boolean {
        val settings = object : RouterSettings {
            override val routerHost = host
            override val username = username
            override val password = password
        }
        val newClient = ZltRouterClient(settings)
        return try {
            val accepted = newClient.login()
            if (accepted) {
                client = newClient
            } else {
                newClient.invalidateSession()
            }
            accepted
        } catch (e: RouterAuthenticationException) {
            throw RouterUnavailableException.AuthenticationFailed(e)
        } catch (e: RouterCooldownException) {
            throw RouterUnavailableException.Cooldown(e.retryAfterSeconds)
        } catch (e: RouterNetworkException) {
            throw e.toAppFailure()
        } catch (e: RouterProtocolException) {
            throw RouterUnavailableException.UnexpectedResponse(e)
        }
    }

    override suspend fun fetchDevices(): DeviceSnapshot {
        val active = client ?: throw RouterUnavailableException.NotConnected()
        return try {
            active.fetchDevices()
        } catch (e: RouterAuthenticationException) {
            // A read carries no password: this can only be a session that
            // could not be renewed. Drop it so no further read reuses it.
            disconnect()
            throw RouterUnavailableException.SessionExpired(e)
        } catch (e: RouterCooldownException) {
            throw RouterUnavailableException.Cooldown(e.retryAfterSeconds)
        } catch (e: RouterNetworkException) {
            throw e.toAppFailure()
        } catch (e: RouterProtocolException) {
            throw RouterUnavailableException.UnexpectedResponse(e)
        }
    }

    override fun disconnect() {
        // Detach first: `invalidateSession()` waits for the client's lock, and
        // a concurrent `connect()` must not have its new client cleared by the
        // tail of this call.
        val previous = client
        client = null
        previous?.invalidateSession()
    }

    private fun RouterNetworkException.toAppFailure(): RouterUnavailableException {
        val timedOut = generateSequence<Throwable>(this) { it.cause }
            .any { it is SocketTimeoutException }
        return if (timedOut) {
            RouterUnavailableException.Timeout(this)
        } else {
            RouterUnavailableException.Unreachable(this)
        }
    }
}
