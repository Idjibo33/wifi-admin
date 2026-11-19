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
    val capped: Boolean = false,
    /** True when the download hit the byte cap before its time window expired. */
    val cappedDownload: Boolean = capped,
    /** True when the upload hit the byte cap before its time window expired. */
    val cappedUpload: Boolean = capped,
    /** Measured window of the download phase (post warm-up), in ms. */
    val downloadDurationMillis: Long = durationMillis,
    /** Measured window of the upload phase, in ms. */
    val uploadDurationMillis: Long = durationMillis,
    /** Why the download phase stopped. UNKNOWN for legacy stored results. */
    val downloadEndReason: PhaseEndReason = PhaseEndReason.UNKNOWN,
    /** Why the upload phase stopped. UNKNOWN for legacy stored results. */
    val uploadEndReason: PhaseEndReason = PhaseEndReason.UNKNOWN,
    val providerLabel: String,
    val networkKey: String,
    val wifiLink: WifiLinkInfo?,
    val capturedAtWallMillis: Long,
    /** Mean absolute gap between consecutive latency probes. Null if
     * fewer than two probes succeeded or before this field existed. */
    val jitterMillis: Double? = null,
)

/**
 * Why a measurement phase stopped. Recorded explicitly so that a premature
 * stop is never reported as a stabilised measurement.
 *
 * TIMEOUT and CANCELLED abort the run (no [SpeedTestResult] is published);
 * they are listed here so every termination path has one vocabulary.
 */
enum class PhaseEndReason {
    /** The phase time window expired normally: a full, stable window. */
    TIME_WINDOW,
    /** The volume safety cap was reached before the window expired. */
    VOLUME_CAP,
    /** The server sent an empty body or ended a body early. */
    PREMATURE,
    /** Deadline / global watchdog aborted the phase. */
    TIMEOUT,
    /** User or system cancelled the run. */
    CANCELLED,
    /** Stored before this classification existed: never claim stabilised. */
    UNKNOWN,
}

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

    /**
     * L'autorisation du réseau local est requise/refusée (vérifiée).
     *
     * Aucun socket n'est ouvert, aucun octet n'est transféré. Après accord,
     * le repository reste Idle : seul le tap utilisateur relance une mesure.
     */
    data object PermissionRequired : SpeedTestFailure
}
