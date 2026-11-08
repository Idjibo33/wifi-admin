package dev.wifiadmin.app.data.speedtest

import org.junit.Assert.assertTrue
import org.junit.Test

/** Seam factory : sans endpoint configuré, l'usine reste désactivée. */
class SpeedTestEngineFactoryTest {

    @Test fun `blank endpoint disables the engine`() {
        assertTrue(SpeedTestEngineFactory.create("") is DisabledSpeedTestEngine)
    }

    @Test fun `invalid endpoint disables the engine`() {
        assertTrue(
            SpeedTestEngineFactory.create("http://public.example.org/") is DisabledSpeedTestEngine,
        )
    }

    @Test fun `valid loopback endpoint enables LibreSpeed`() {
        assertTrue(
            SpeedTestEngineFactory.create("http://127.0.0.1:8765/") is LibreSpeedEngine,
        )
    }
}
