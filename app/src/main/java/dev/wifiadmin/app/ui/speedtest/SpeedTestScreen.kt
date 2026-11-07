package dev.wifiadmin.app.ui.speedtest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wifiadmin.app.data.speedtest.SpeedTestFailure
import dev.wifiadmin.app.data.speedtest.SpeedTestResult
import dev.wifiadmin.app.data.speedtest.SpeedTestCalculator
import dev.wifiadmin.app.data.speedtest.WifiLinkInfo
import dev.wifiadmin.app.ui.components.DetailRow
import dev.wifiadmin.app.ui.components.InfoNoticeCard
import dev.wifiadmin.app.ui.components.SignalBars
import dev.wifiadmin.app.ui.components.StatCard
import dev.wifiadmin.app.ui.components.formatSnapshotDate
import dev.wifiadmin.app.ui.components.rememberFadeMillis
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import dev.wifiadmin.app.ui.theme.WifiDims

/**
 * The speed test screen.
 *
 * The real Internet measurement is disabled in this version: the screen
 * stays honest, shows the Wi-Fi link (never confused with the Internet
 * rate), and tells the user where to measure externally. Consuming data
 * only ever happens on an explicit tap on a validated provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    viewModel: SpeedTestViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(SpeedTestStrings.TITLE) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { innerPadding ->
        SpeedTestContent(
            state = state,
            onStart = viewModel::start,
            onCancel = viewModel::cancel,
            onDismissConsent = viewModel::dismissConsent,
            onAcceptConsent = viewModel::acceptConsentAndStart,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** The body, public for instrumented tests: no network to render it. */
@Composable
fun SpeedTestContent(
    state: SpeedTestUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismissConsent: () -> Unit,
    onAcceptConsent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(WifiDims.Space4),
        verticalArrangement = Arrangement.spacedBy(WifiDims.Space3),
    ) {
        item(key = "hero") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(WifiDims.Space4),
                    verticalArrangement = Arrangement.spacedBy(WifiDims.Space2),
                ) {
                    Text(
                        text = "Réseau actuel",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = if (state.isNetworkOk) "Wi-Fi connecté" else "Wi-Fi indisponible",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = state.previousResult?.historyLine()
                            ?: "Aucun test effectué sur ce réseau.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        item(key = "gauge") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(WifiDims.Space4),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(WifiDims.Space2),
                ) {
                    SpeedGauge(
                        valueMbps = state.currentMbps,
                        phaseLabel = phaseLabel(state.phase),
                        running = state.isRunning,
                    )
                    Text(
                        text = "${formatMbps(state.currentMbps)} Mbit/s",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    Text(
                        text = phaseLabel(state.phase),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.isRunning) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = { 0.5f },
                        )
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.heightIn(min = WifiDims.TouchMin)
                                .testTag("speedtest_cancel_button"),
                        ) { Text("Annuler") }
                    } else {
                        val label = if (state.previousResult != null) "Relancer" else "Lancer le test"
                        Button(
                            onClick = onStart,
                            enabled = state.isNetworkOk && state.failure !is SpeedTestFailure.Disabled,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = WifiDims.FieldMin)
                                .testTag("speedtest_start_button"),
                        ) { Text(label) }
                    }
                }
            }
        }

        if (state.failure != null) {
            item(key = "error") {
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                    colors = CardDefaults.cardColors(
                        containerColor = when (state.failure) {
                            SpeedTestFailure.Disabled -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(WifiDims.Space4)) {
                        Text(
                            text = failureMessage(state.failure!!),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (state.failure) {
                                SpeedTestFailure.Disabled -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                        if (state.failure is SpeedTestFailure.Disabled) {
                            Spacer(Modifier.size(WifiDims.Space2))
                            Text(
                                text = SpeedTestStrings.DISABLED_BODY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            ExternalLinks()
                        }
                    }
                }
            }
        }

        item(key = "results") {
            val result = state.result ?: state.previousResult
            Row(horizontalArrangement = Arrangement.spacedBy(WifiDims.Space2)) {
                StatCard(
                    label = "Download",
                    value = formatMbps(result?.downloadMbps),
                    icon = Icons.Default.Download,
                    modifier = Modifier.weight(1f),
                    accent = true,
                )
                StatCard(
                    label = "Upload",
                    value = formatMbps(result?.uploadMbps),
                    icon = Icons.Default.Upload,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "Latence",
                    value = formatLatency(result?.latencyMillis),
                    icon = Icons.Default.Timer,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item(key = "meta") {
            val result = state.result ?: state.previousResult
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(WifiDims.Space4)) {
                    DetailRow(
                        label = "Date du test",
                        value = result?.let { formatSnapshotDate(it.capturedAtWallMillis) } ?: SpeedTestStrings.UNKNOWN,
                    )
                    if (result != null) {
                        DetailRow(label = "Fournisseur", value = result.providerLabel)
                        if (result.capped) {
                            Text(
                                text = "Plafond atteint avant stabilisation : valeur minimale, pas débit maximal.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item(key = "link") {
            val link = state.wifiLink
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(WifiDims.Space4)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(WifiDims.IconBanner))
                        Spacer(Modifier.size(WifiDims.Space2))
                        Text(
                            text = SpeedTestStrings.LINK_TITLE,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.size(WifiDims.Space2))
                    if (link == null) {
                        Text(
                            text = "Informations de liaison indisponibles.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        link.linkSpeedMbps?.let { DetailRow("Débit de liaison", "$it Mbit/s") }
                            ?: Text(
                                "Débit de liaison indisponible.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        if (link.rssiDbm != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Signal : ${link.rssiDbm} dBm",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                SignalBars(rssiDbm = link.rssiDbm)
                            }
                        }
                        if (link.frequencyMhz != null) {
                            DetailRow("Fréquence", "${link.frequencyMhz} MHz (${link.bandLabel ?: ""})".trim())
                        }
                        if (link.wifiStandard != null) {
                            DetailRow("Standard", link.wifiStandard)
                        }
                    }
                    Spacer(Modifier.size(WifiDims.Space2))
                    Text(
                        text = SpeedTestStrings.LINK_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (state.showConsent) {
        AlertDialog(
            onDismissRequest = onDismissConsent,
            title = { Text("Avant de lancer un test") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(WifiDims.Space2)) {
                    Text("Serveur : aucun fournisseur configuré dans cette version. Aucun trafic ne sera envoyé.")
                    Text("Données : seuls le débit mesuré, la latence et l'heure seraient conservés localement, sans identifiant routeur ni adresse IP publique.")
                    Text("Consommation : le test télécharge et envoie des données réelles ; évitez un forfait mobile limité. Plafonds : 20 Mo en téléchargement, 10 Mo en envoi, 30 s.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onAcceptConsent,
                    modifier = Modifier.testTag("speedtest_consent_confirm"),
                ) { Text("J'accepte et je lance") }
            },
            dismissButton = {
                TextButton(onClick = onDismissConsent) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun SpeedGauge(
    valueMbps: Double?,
    phaseLabel: String,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val max = SpeedTestCalculator.gaugeScaleMaxMbps(valueMbps)
    val fraction = ((valueMbps ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
    Canvas(modifier = modifier.size(160.dp)) {
        val full = size.width
        drawArc(
            color = Color(0xFFC1C7CE),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            style = Stroke(width = 14.dp.toPx()),
            topLeft = Offset(0f, 0f),
            size = Size(full, full / 2),
        )
        if (fraction > 0f) {
            drawArc(
                color = Color(0xFF00629B),
                startAngle = 180f,
                sweepAngle = 180f * fraction,
                useCenter = false,
                style = Stroke(width = 14.dp.toPx()),
            )
        }
    }
}

@Composable
private fun ExternalLinks() {
    val links = httpsExternalLinks()
    val annotated = buildAnnotatedString {
        for ((index, pair) in links.withIndex()) {
            if (index > 0) append("  ·  ")
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                append(pair.first)
            }
        }
    }
    Text(
        text = "Mesurer sur le web : ",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    ClickableText(
        text = annotated,
        onClick = { /* External links open in the browser, never in the app. */ },
        style = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun SpeedTestContentPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        SpeedTestContent(
            state = SpeedTestUiState(
                wifiLink = WifiLinkInfo(linkSpeedMbps = 866, rssiDbm = -58, frequencyMhz = 5180, bandLabel = "5 GHz", wifiStandard = "Wi-Fi 6"),
                previousResult = SpeedTestResult(
                    downloadMbps = 42.5, uploadMbps = 18.2, latencyMillis = 24,
                    downloadBytes = 5_000_000, uploadBytes = 2_500_000, durationMillis = 8_000,
                    capped = false, providerLabel = "Moteur simulé (fictif)", networkKey = "simulated",
                    wifiLink = null, capturedAtWallMillis = 1_700_000_000_000L,
                ),
                result = SpeedTestResult(
                    downloadMbps = 42.5, uploadMbps = 18.2, latencyMillis = 24,
                    downloadBytes = 5_000_000, uploadBytes = 2_500_000, durationMillis = 8_000,
                    capped = false, providerLabel = "Moteur simulé (fictif)", networkKey = "simulated",
                    wifiLink = null, capturedAtWallMillis = 1_700_000_000_000L,
                ),
            ),
            onStart = {}, onCancel = {}, onDismissConsent = {}, onAcceptConsent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SpeedTestDisabledPreview() {
    WifiAdminTheme(darkTheme = true, dynamicColor = false) {
        SpeedTestContent(
            state = SpeedTestUiState(failure = SpeedTestFailure.Disabled),
            onStart = {}, onCancel = {}, onDismissConsent = {}, onAcceptConsent = {},
        )
    }
}
