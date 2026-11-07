package dev.wifiadmin.app.ui.dashboard

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wifiadmin.app.ui.components.CachedDataBanner
import dev.wifiadmin.app.ui.components.ErrorState
import dev.wifiadmin.app.ui.components.LoadingState
import dev.wifiadmin.app.ui.components.StatCard
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The refresh follows the visibility of the screen: it runs while the
    // screen is in the foreground and stops as soon as it is not.
    //
    // The block is keyed on `Unit`: a recomposition never restarts it, and
    // `onStopOrDispose` is called on every exit from the STARTED state and
    // when the screen leaves the composition.
    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Tableau de bord") },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.isRefreshing,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Actualiser"
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)) {
            when {
                state.isFirstLoad -> LoadingState(
                    message = "Connexion au routeur…",
                    modifier = Modifier.fillMaxSize()
                )

                state.isFatalError -> ErrorState(
                    title = "Lecture impossible",
                    message = state.errorMessage
                        ?: "Le routeur n'a pas répondu.",
                    modifier = Modifier.fillMaxSize(),
                    onRetry = viewModel::refresh
                )

                else -> DashboardContent(state = state)
            }
        }
    }
}

/** The body of the dashboard: the counters and the explanations. */
@Composable
private fun DashboardContent(
    state: DashboardUiState,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.hasStaleData) {
            item(key = "data-freshness") {
                state.staleDataReason?.let { reason ->
                    CachedDataBanner(
                        capturedAtMillis = state.lastRefreshMillis,
                        reason = reason,
                        detail = state.errorMessage
                    )
                }
            }
        }

        item(key = "summary") {
            Text(
                text = "Appareils connus du routeur",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        item(key = "counters") {
            val total = snapshot?.devices?.size ?: 0
            val active = snapshot?.activeCount ?: 0
            val cabled = snapshot?.cabledCount ?: 0

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(
                        label = "Total",
                        value = total.toString(),
                        icon = Icons.Default.Devices,
                        modifier = Modifier.weight(1f),
                        accent = true
                    )
                    StatCard(
                        label = if (state.isFromCache) "Wi-Fi (lecture)" else "Appareils actifs",
                        value = active.toString(),
                        icon = Icons.Default.Wifi,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(
                        label = "Filaire",
                        value = cabled.toString(),
                        icon = Icons.Default.Lan,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "Périmés",
                        value = (snapshot?.staleCount ?: 0).toString(),
                        icon = Icons.Default.Router,
                        modifier = Modifier.weight(1f)
                    )
                }
                // The band counts overlap on purpose: a device attached to
                // both radios is counted in each band. They are therefore
                // grouped under their own heading and are not to be added
                // to the total.
                Text(
                    text = "Associations par bande — un appareil bi-bande est compté dans les deux",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(
                        label = "2,4 GHz",
                        value = state.band24Count.toString(),
                        icon = Icons.Default.Wifi,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "5 GHz",
                        value = state.band5Count.toString(),
                        icon = Icons.Default.Wifi,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item(key = "last-refresh") {
            val formatted = formatLastRefresh(state.lastRefreshMillis)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dernière lecture",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatted ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        item(key = "mac-filtering") {
            MacFilteringNotice()
        }
    }
}

/**
 * Explains what the firmware does and does not allow.
 *
 * The router exposes two distinct MAC filtering mechanisms. The Wi-Fi one
 * (cmd 278) is refused by this firmware, so the app cannot filter
 * associations. That is *not* the same thing as blocking Internet access,
 * which is the job of the firewall mechanism: an unknown capability that must
 * not be presented as an available feature.
 */
@Composable
private fun MacFilteringNotice(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Filtrage MAC indisponible",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Text(
                text = "Le filtrage MAC Wi-Fi n'est pas disponible sur ce " +
                    "firmware : la commande est refusée par le routeur. " +
                    "L'application est donc en lecture seule et ne peut " +
                    "ni filtrer ni bloquer un appareil.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Text(
                text = "À ne pas confondre : « filtrage Wi-Fi indisponible » " +
                    "signifie que l'appartenance au réseau Wi-Fi ne peut pas " +
                    "être empêchée. Cela ne dit rien du blocage d'accès à " +
                    "Internet, qui relève d'un autre mécanisme du routeur " +
                    "(pare-feu) dont l'effet n'est pas vérifié ici. Un " +
                    "appareil bloqué côté Internet resterait d'ailleurs " +
                    "visible et connecté dans cette liste.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Text(
                text = "Les états affichés ici décrivent la connectivité " +
                    "observée, pas une autorisation d'accès au réseau.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews — the data below is fictional and only serves the preview.
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
        )
    ),
    capturedAtMillis = 1_700_000_000_000L
)

@Preview(showBackground = true)
@Composable
private fun DashboardContentPreview() {
    WifiAdminTheme {
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

@Preview(showBackground = true)
@Composable
private fun DashboardCachedPreview() {
    WifiAdminTheme {
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
    WifiAdminTheme {
        LoadingState(
            message = "Connexion au routeur…",
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DashboardErrorPreview() {
    WifiAdminTheme {
        ErrorState(
            title = "Lecture impossible",
            message = "Le routeur n'a pas répondu.",
            modifier = Modifier.fillMaxSize(),
            onRetry = {}
        )
    }
}
