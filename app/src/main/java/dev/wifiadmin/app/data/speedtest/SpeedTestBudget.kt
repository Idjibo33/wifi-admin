package dev.wifiadmin.app.data.speedtest

/**
 * Construit le budget d'une mesure, avec une garantie : un budget de
 * validation ne peut que **réduire** les plafonds de production, jamais
 * les augmenter.
 *
 * Mission 16 : le premier benchmark réel doit rester un essai à faible
 * volume. La build debug peut donc recevoir des plafonds réduits via
 * `BuildConfig`, mais ces valeurs sont re-clampées ici sur
 * [SpeedTestPolicy] (20 Mio / 10 Mio / 30 s). Une propriété Gradle
 * erronée ou une valeur aberrante retombe sur la production au lieu
 * d'ouvrir un transfert plus gros.
 *
 * Le même [SpeedTestPolicy] doit être injecté dans le moteur **et** dans
 * le repository : le moteur arrête les octets, le repository applique le
 * délai et le backstop. Deux instances divergentes rendraient l'un des
 * deux contrôles inopérant.
 */
object SpeedTestBudget {

    /** Les plafonds de production, source de vérité unique. */
    val production: SpeedTestPolicy = SpeedTestPolicy()

    /**
     * Construit un budget à partir de plafonds explicites.
     *
     * Chaque valeur doit être strictement positive et inférieure ou égale
     * au plafond de production ; sinon la valeur de production est
     * conservée. C'est volontairement fail-safe : aucune configuration ne
     * peut élargir le budget au-delà du contrat produit.
     */
    fun fromCaps(
        maxDownloadBytes: Long,
        maxUploadBytes: Long,
        maxDurationMillis: Long,
    ): SpeedTestPolicy {
        val productionPolicy = production
        return SpeedTestPolicy(
            maxDurationMillis = clamp(maxDurationMillis, productionPolicy.maxDurationMillis),
            maxDownloadBytes = clamp(maxDownloadBytes, productionPolicy.maxDownloadBytes),
            maxUploadBytes = clamp(maxUploadBytes, productionPolicy.maxUploadBytes),
            latencyProbes = productionPolicy.latencyProbes,
        )
    }

    /** Vrai lorsque le budget diffère des plafonds de production. */
    fun isReduced(policy: SpeedTestPolicy): Boolean =
        policy.maxDownloadBytes != production.maxDownloadBytes ||
            policy.maxUploadBytes != production.maxUploadBytes ||
            policy.maxDurationMillis != production.maxDurationMillis

    private fun clamp(value: Long, productionValue: Long): Long =
        if (value in 1..productionValue) value else productionValue
}
