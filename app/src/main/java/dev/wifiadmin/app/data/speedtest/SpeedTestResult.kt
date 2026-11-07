package dev.wifiadmin.app.data.speedtest

/**
 * One finished Internet speed measurement.
 *
 * The Internet rate (transferred bytes), the Wi-Fi link (negotiated
 * between phone and access point) and the identity of the network are
 * three different things and stay in separate fields. No secret is ever
 * stored: no router password, session, token, MAC, SSID or public IP.
 */
data class SpeedTestResult(
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val latencyMillis: Long?,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val durationMillis: Long,
    val capped: Boolean,
    val providerLabel: String,
    val networkKey: String,
    val wifiLink: WifiLinkInfo?,
    val capturedAtWallMillis: Long,
)

/** The state of a run, observed by the ViewModel. */
sealed interface SpeedTestState {
    data object Idle : SpeedTestState
    data class Running(
        val phase: SpeedTestPhase,
        val downloadMbps: Double? = null,
        val uploadMbps: Double? = null,
        val downloadBytes: Long = 0,
        val uploadBytes: Long = 0,
        val elapsedMillis: Long = 0,
    ) : SpeedTestState
    data class Done(val result: SpeedTestResult) : SpeedTestState
    data object Cancelled : SpeedTestState
    data class Failed(val reason: SpeedTestFailure) : SpeedTestState
}

enum class SpeedTestPhase {
    CHECKING_NETWORK, LATENCY, DOWNLOAD, UPLOAD, FINALIZING,
}

/** Cancellation reason, kept distinct from an error for honest UI. */
enum class CancelReason { USER, NETWORK_LOST, NETWORK_CHANGED }

/** Why a measurement cannot run or cannot finish. */
sealed interface SpeedTestFailure {
    data object NoWiFi : SpeedTestFailure
    data object InternetUnavailable : SpeedTestFailure
    data object ServerUnavailable : SpeedTestFailure
    data object TooBusy : SpeedTestFailure
    data object Disabled : SpeedTestFailure
    data object Timeout : SpeedTestFailure
    data object Incomplete : SpeedTestFailure
    data object Unexpected : SpeedTestFailure
}
