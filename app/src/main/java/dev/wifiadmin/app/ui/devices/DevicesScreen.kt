package dev.wifiadmin.app.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import dev.wifiadmin.app.ui.components.CachedDataBanner
import dev.wifiadmin.app.ui.components.DetailRow
import dev.wifiadmin.app.ui.components.EmptyState
import dev.wifiadmin.app.ui.components.ErrorState
import dev.wifiadmin.app.ui.components.LoadingState
import dev.wifiadmin.app.ui.components.badgeColor
import dev.wifiadmin.app.ui.components.cachedBadgeColor
import dev.wifiadmin.app.ui.components.cachedLabel
import dev.wifiadmin.app.ui.components.explanation
import dev.wifiadmin.app.ui.components.formatSnapshotDate
import dev.wifiadmin.app.ui.components.label
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand

/**
 * The device list.
 *
 * The list can be narrowed with a free-text search and with a filter on the
 * device family. Both are applied to the devices the router reported during
 * the last poll; no count is ever hardcoded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedDevice by remember { mutableStateOf<RouterDevice?>(null) }

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
                title = { Text("Appareils") },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.isLoading
                    ) {
                        if (state.isLoading && state.devices.isNotEmpty()) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
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

                else -> DeviceList(
                    state = state,
                    onQueryChange = viewModel::setQuery,
                    onFilterChange = viewModel::setFilter,
                    onDeviceClick = { selectedDevice = it }
                )
            }
        }
    }

    val device = selectedDevice
    if (device != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedDevice = null },
            sheetState = sheetState
        ) {
            DeviceDetailSheet(
                device = device,
                cached = state.isFromCache,
                capturedAtMillis = state.lastRefreshMillis
            )
        }
    }
}

/** The search field, the filter chips and the list of devices. */
@Composable
private fun DeviceList(
    state: DevicesUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (DeviceFilter) -> Unit,
    onDeviceClick: (RouterDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    val devices = state.filteredDevices

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        if (state.hasStaleData) {
            state.staleDataReason?.let { reason ->
                CachedDataBanner(
                    capturedAtMillis = state.lastRefreshMillis,
                    reason = reason,
                    detail = state.errorMessage
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Rechercher un appareil") },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Effacer la recherche"
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DeviceFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(filter.label) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            devices.isEmpty() -> EmptyState(
                message = when {
                    // The router answered without any device list: nothing was
                    // received, which is not the same as an empty network.
                    state.payloadAbsent ->
                        "Réponse du routeur vide : aucune liste d'appareils n'a été reçue."
                    state.devices.isEmpty() && state.isFromCache ->
                        "Aucun appareil n'était connu lors de la dernière lecture."
                    state.devices.isEmpty() ->
                        "Aucun appareil signalé par le routeur."
                    else ->
                        "Aucun appareil ne correspond à votre recherche."
                },
                modifier = Modifier.fillMaxWidth()
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = devices, key = { "${it.mac}|${it.ip}" }) { device ->
                    DeviceCard(
                        device = device,
                        cached = state.isFromCache,
                        onClick = { onDeviceClick(device) }
                    )
                }
            }
        }
    }
}

/** One device in the list. */
@Composable
private fun DeviceCard(
    device: RouterDevice,
    cached: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = device.name.ifBlank { "Unknown Device" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = device.ip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = device.mac,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = device.bands.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(8.dp))

            PresenceBadge(presence = device.presence, cached = cached)
        }
    }
}

/** A small coloured chip showing the presence of a device. */
@Composable
private fun PresenceBadge(
    presence: DevicePresence,
    cached: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (cached) presence.cachedBadgeColor() else presence.badgeColor()
    ) {
        Text(
            text = if (cached) presence.cachedLabel() else presence.label(),
            style = MaterialTheme.typography.labelMedium,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * The detail of one device.
 *
 * The fields the router did not report are simply left out: showing a dash
 * would suggest the router answered something it did not.
 */
@Composable
private fun DeviceDetailSheet(
    device: RouterDevice,
    cached: Boolean,
    capturedAtMillis: Long?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = device.name.ifBlank { "Unknown Device" },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (cached) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatSnapshotDate(capturedAtMillis)?.let { date ->
                    "Données enregistrées le $date. Cet appareil n'est pas " +
                        "nécessairement connecté maintenant."
                } ?: "Données enregistrées. Cet appareil n'est pas " +
                    "nécessairement connecté maintenant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }

        Spacer(Modifier.height(4.dp))

        DetailRow(label = "MAC", value = device.mac)
        DetailRow(label = "Adresse IP", value = device.ip)

        device.ipv6?.takeIf { it.isNotBlank() }?.let {
            DetailRow(label = "IPv6", value = it)
        }

        DetailRow(label = "Bandes", value = device.bands.label())

        device.signal?.ssid?.takeIf { it.isNotBlank() }?.let {
            DetailRow(label = "Réseau", value = it)
        }

        device.signal?.rssiDbm?.let {
            DetailRow(label = "Signal", value = "$it dBm")
        }

        device.signal?.txRateMbps?.takeIf { it.isNotBlank() }?.let {
            DetailRow(label = "Débit envoi", value = "$it Mbit/s")
        }

        device.signal?.rxRateMbps?.takeIf { it.isNotBlank() }?.let {
            DetailRow(label = "Débit réception", value = "$it Mbit/s")
        }

        device.interfaceName.takeIf { it.isNotBlank() }?.let {
            DetailRow(label = "Interface", value = it)
        }

        device.leaseExpiry?.takeIf { it.isNotBlank() }?.let {
            DetailRow(label = "Bail DHCP", value = it)
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        DetailRow(
            label = if (cached) "État à la lecture" else "État",
            value = if (cached) device.presence.cachedLabel() else device.presence.label()
        )

        Text(
            text = device.presence.explanation(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// Previews — the data below is fictional and only serves the preview.
// ---------------------------------------------------------------------------

/** Fictional sample devices, used only by the previews. */
private val sampleDevices = listOf(
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
)

@Preview(showBackground = true)
@Composable
private fun DeviceListPreview() {
    WifiAdminTheme {
        DeviceList(
            state = DevicesUiState(
                isLoading = false,
                devices = sampleDevices,
                query = "",
                filter = DeviceFilter.ALL,
                lastRefreshMillis = null,
                errorMessage = null
            ),
            onQueryChange = {},
            onFilterChange = {},
            onDeviceClick = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceListCachedPreview() {
    WifiAdminTheme {
        DeviceList(
            state = DevicesUiState(
                isLoading = false,
                devices = sampleDevices,
                query = "",
                filter = DeviceFilter.ALL,
                lastRefreshMillis = 1_700_000_000_000L,
                errorMessage = null,
                isFromCache = true,
                hasSnapshot = true
            ),
            onQueryChange = {},
            onFilterChange = {},
            onDeviceClick = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceDetailPreview() {
    WifiAdminTheme {
        DeviceDetailSheet(
            device = sampleDevices.first(),
            cached = false,
            capturedAtMillis = null
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceDetailCachedPreview() {
    WifiAdminTheme {
        DeviceDetailSheet(
            device = sampleDevices.first(),
            cached = true,
            capturedAtMillis = 1_700_000_000_000L
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceEmptyPreview() {
    WifiAdminTheme {
        EmptyState(
            message = "Aucun appareil ne correspond à votre recherche.",
            modifier = Modifier.fillMaxSize()
        )
    }
}
