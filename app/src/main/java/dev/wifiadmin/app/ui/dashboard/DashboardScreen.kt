package dev.wifiadmin.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wifiadmin.app.ui.components.AppTopBar
import dev.wifiadmin.app.ui.components.CachedDataBanner
import dev.wifiadmin.app.ui.components.ErrorState
import dev.wifiadmin.app.ui.components.InfoNoticeCard
import dev.wifiadmin.app.ui.components.LoadingState
import dev.wifiadmin.app.ui.components.ScreenContent
import dev.wifiadmin.app.ui.components.StatCard
import dev.wifiadmin.app.ui.components.formatSnapshotDate
import dev.wifiadmin.app.ui.components.rememberAnimationEnabled
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import dev.wifiadmin.app.ui.theme.WifiDims
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand

/**
 * The dashboard.
 *
 * It shows a summary of what the router reported during the last poll. Every
 * number comes from the runtime snapshot; nothing here is a constant.
 *
 * The hierarchy is deliberately short: freshness first, then the single
 * combined hero, then three equal cards, then the band associations as a
 * list (they overlap and must not be added), then the last read and the
 * firmware note, collapsed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onSeeActives: (() -> Unit)? = null,
    onOpenSpeedTest: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    val subtitle = dashboardSubtitle(state)

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "Tableau de bord",
                subtitle = subtitle,
                isRefreshing = state.isRefreshing,
                isLoadingWithData = state.isLoading && state.snapshot != null,
                onRefresh = viewModel::refresh
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)) {
            ScreenContent(
                isFirstLoad = state.isFirstLoad,
                isFatalError = state.isFatalError,
                onRetry = viewModel::refresh,
                errorMessage = state.errorMessage,
                modifier = Modifier.fillMaxSize()
            ) {
                DashboardContent(state = state, onSeeActives = onSeeActives, onOpenSpeedTest = onOpenSpeedTest)
            }
        }
    }
}

private fun dashboardSubtitle(state: DashboardUiState): String? {
    val date = formatSnapshotDate(state.lastRefreshMillis) ?: return null
    val mode = if (state.isFromCache) "en cache" else "en direct"
    return "Lecture $date · $mode"
}

/** The body of the dashboard: the counters and the explanations. */
@Composable
internal fun DashboardContent(
    state: DashboardUiState,
    onSeeActives: (() -> Unit)? = null,
    onOpenSpeedTest: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot
    val animationEnabled = rememberAnimationEnabled()
    var macExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(WifiDims.Space4),
        verticalArrangement = Arrangement.spacedBy(WifiDims.Space3)
    ) {
        if (state.hasStaleData) {
            item(key = "data-freshness", contentType = "banner") {
                state.staleDataReason?.let { reason ->
                    CachedDataBanner(
                        capturedAtMillis = state.lastRefreshMillis,
                        reason = reason,
                        detail = state.errorMessage
                    )
                }
            }
        }

        item(key = "hero", contentType = "hero") {
            val total = snapshot?.devices?.size ?: 0
            val active = snapshot?.activeCount ?: 0
            val cabled = snapshot?.cabledCount ?: 0
            val stale = snapshot?.staleCount ?: 0
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(WifiDims.Space4),
                    verticalArrangement = Arrangement.spacedBy(WifiDims.Space2)
                ) {
                    Text(
                        text = if (state.isFromCache) "Appareils connus à la dernière lecture" else "Appareils connus du routeur",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "$active actifs sur $total connus",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = when {
                            total == 0 -> "Aucun appareil signalé par le routeur."
                            state.isFromCache -> "Dernières données connues — actualisées le ${formatSnapshotDate(state.lastRefreshMillis)}."
                            else -> "Dernière lecture : ${formatLastRefresh(state.lastRefreshMillis) ?: "—"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (onSeeActives != null && active > 0) {
                        TextButton(onClick = onSeeActives) {
                            Text("Voir les $active actifs")
                        }
                    }
                }
            }
        }

        item(key = "counters", contentType = "counters") {
            val active = snapshot?.activeCount ?: 0
            val cabled = snapshot?.cabledCount ?: 0
            Row(horizontalArrangement = Arrangement.spacedBy(WifiDims.Space2)) {
                StatCard(
                    label = if (state.isFromCache) "Wi-Fi (lecture)" else "Actifs",
                    value = active.toString(),
                    icon = Icons.Default.Wifi,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Filaire",
                    value = cabled.toString(),
                    icon = Icons.Default.Lan,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Périmés",
                    value = (snapshot?.staleCount ?: 0).toString(),
                    icon = Icons.Default.History,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item(key = "bands", contentType = "bands") {
            // The band counts overlap on purpose: a device attached to both
            // radios is counted in each band. They are therefore grouped as
            // associations, never added to the total.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(WifiDims.Space4),
                    verticalArrangement = Arrangement.spacedBy(WifiDims.Space2)
                ) {
                    Text(
                        text = "Associations par bande",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "2,4 GHz",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${state.band24Count} associations",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "5 GHz",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${state.band5Count} associations",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "Un appareil bi-bande est compté dans les deux bandes. La somme des bandes peut donc dépasser le total.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item(key = "last-refresh", contentType = "refresh") {
            val formatted = formatLastRefresh(state.lastRefreshMillis)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = WifiDims.Space4, vertical = WifiDims.CardPaddingV),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dernière lecture",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(WifiDims.Space2))
                    Text(
                        text = formatted ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (onOpenSpeedTest != null) {
            item(key = "speedtest", contentType = "hero") {
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
                            text = "Test de vitesse Internet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "Mesure le débit Internet via le Wi-Fi (débits réels, plafonds 20/10 Mo, 30 s).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        TextButton(onClick = onOpenSpeedTest) {
                            Text("Ouvrir le test de vitesse")
                        }
                    }
                }
            }
        }

        item(key = "mac-filtering", contentType = "info") {
            InfoNoticeCard(
                title = "Filtrage MAC indisponible",
                body = "Le filtrage MAC Wi-Fi n'est pas disponible sur ce firmware : la commande est refusée par le routeur. " +
                    "L'application est donc en lecture seule et ne peut ni filtrer ni bloquer un appareil. " +
                    "À ne pas confondre : « filtrage Wi-Fi indisponible » signifie que l'appartenance au réseau Wi-Fi ne peut pas " +
                    "être empêchée. Cela ne dit rien du blocage d'accès à Internet, qui relève d'un autre mécanisme du routeur " +
                    "(pare-feu). Les états affichés ici décrivent la connectivité observée, pas une autorisation d'accès au réseau.",
                expanded = macExpanded,
                onToggle = { macExpanded = !macExpanded }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews — fictional data, clearly labelled as demonstration.
// ---------------------------------------------------------------------------

/** Fictional sample data, used only by the previews. */
private val sampleSnapshot = DeviceSnapshot(
    devices = listOf(
        RouterDevice(
            mac = "aa:bb:cc:dd:ee:01",
            ip = "192.168.1.10",
            name = "sample-poste",
            bands = setOf(WifiBand.BAND_2_4_GHZ),
            signal = DeviceSignal(rssiDbm = -58, txRateMbps = "72", rxRateMbps = "65", ssid = null),
            interfaceName = "wl0",
            leaseExpiry = null,
            ipv6 = null,
            presence = DevicePresence.ACTIVE
        ),
        RouterDevice(
            mac = "aa:bb:cc:dd:ee:02",
            ip = "192.168.1.11",
            name = "sample-telephone",
            bands = setOf(WifiBand.BAND_5_GHZ),
            signal = DeviceSignal(rssiDbm = -47, txRateMbps = "433", rxRateMbps = "390", ssid = null),
            interfaceName = "wl1",
            leaseExpiry = null,
            ipv6 = null,
            presence = DevicePresence.ACTIVE
        ),
        RouterDevice(
            mac = "aa:bb:cc:dd:ee:03",
            ip = "192.168.1.12",
            name = "",
            bands = emptySet(),
            signal = null,
            interfaceName = "lan",
            leaseExpiry = null,
            ipv6 = null,
            presence = DevicePresence.CABLED
        ),
        // Bi-band device: counted in both bands, once in the total.
        RouterDevice(
            mac = "aa:bb:cc:dd:ee:04",
            ip = "192.168.1.13",
            name = "sample-ordinateur",
            bands = setOf(WifiBand.BAND_2_4_GHZ, WifiBand.BAND_5_GHZ),
            signal = DeviceSignal(rssiDbm = -62, txRateMbps = "144", rxRateMbps = "130", ssid = null),
            interfaceName = "wl0",
            leaseExpiry = null,
            ipv6 = null,
            presence = DevicePresence.ACTIVE
        )
    ),
    capturedAtMillis = 1_700_000_000_000L
)

@Preview(showBackground = true, name = "Dashboard — clair")
@Composable
private fun DashboardContentPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        DashboardContent(
            state = DashboardUiState(
                isLoading = false,
                snapshot = sampleSnapshot,
                lastRefreshMillis = sampleSnapshot.capturedAtMillis,
                errorMessage = null,
                isRefreshing = false
            ),
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true, name = "Dashboard — sombre")
@Composable
private fun DashboardDarkPreview() {
    WifiAdminTheme(darkTheme = true, dynamicColor = false) {
        DashboardContent(
            state = DashboardUiState(
                isLoading = false,
                snapshot = sampleSnapshot,
                lastRefreshMillis = sampleSnapshot.capturedAtMillis,
                errorMessage = null,
                isRefreshing = false
            ),
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true, fontScale = 1.3f, widthDp = 360)
@Composable
private fun DashboardLargeFontPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        DashboardContent(
            state = DashboardUiState(
                isLoading = false,
                snapshot = sampleSnapshot,
                lastRefreshMillis = sampleSnapshot.capturedAtMillis,
                isFromCache = true,
                hasSnapshot = true
            ),
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DashboardCachedPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        DashboardContent(
            state = DashboardUiState(
                isLoading = false,
                snapshot = sampleSnapshot,
                lastRefreshMillis = sampleSnapshot.capturedAtMillis,
                isFromCache = true,
                hasSnapshot = true
            ),
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DashboardLoadingPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        LoadingState(
            message = "Connexion au routeur…",
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DashboardErrorPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        ErrorState(
            title = "Lecture impossible",
            message = "Le routeur n'a pas répondu.",
            modifier = Modifier.fillMaxSize(),
            onRetry = {}
        )
    }
}
