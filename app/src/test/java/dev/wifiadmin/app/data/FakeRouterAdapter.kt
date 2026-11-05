package dev.wifiadmin.app.data

import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand

/**
 * An adapter that answers from a script and counts the reads.
 *
 * No network, no router, no timer: the tests drive it directly.
 */
class FakeRouterAdapter(
    private val answer: () -> DeviceSnapshot = { DeviceSnapshot(emptyList()) },
) : RouterAdapter {

    override val displayName = "Fake"

    /** How many times the adapter was asked for the devices. */
    var fetchCalls: Int = 0
        private set

    var connectCalls: Int = 0
        private set

    var disconnectCalls: Int = 0
        private set

    override suspend fun connect(host: String, username: String, password: String): Boolean {
        connectCalls++
        return true
    }

    override suspend fun fetchDevices(): DeviceSnapshot {
        fetchCalls++
        return answer()
    }

    override fun disconnect() {
        disconnectCalls++
    }

    companion object {
        /** A single device, enough to tell two snapshots apart. */
        fun oneDevice(capturedAtMillis: Long): DeviceSnapshot = DeviceSnapshot(
            devices = listOf(
                RouterDevice(
                    mac = "aa:bb:cc:dd:ee:01",
                    ip = "192.168.1.10",
                    name = "poste",
                    bands = setOf(WifiBand.BAND_2_4_GHZ),
                    signal = DeviceSignal(-50, "72", "65", "maison"),
                    interfaceName = "wl0",
                    leaseExpiry = null,
                    ipv6 = null,
                    presence = DevicePresence.ACTIVE,
                )
            ),
            capturedAtMillis = capturedAtMillis,
        )
    }
}
