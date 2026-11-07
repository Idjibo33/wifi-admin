package dev.wifiadmin.app.ui.devices

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wifiadmin.app.ui.components.AppTopBar
import dev.wifiadmin.app.ui.components.CachedDataBanner
import dev.wifiadmin.app.ui.components.DetailRow
import dev.wifiadmin.app.ui.components.EmptyState
import dev.wifiadmin.app.ui.components.ErrorState
import dev.wifiadmin.app.ui.components.LoadingState
import dev.wifiadmin.app.ui.components.NEVER_SEEN_MESSAGE
import dev.wifiadmin.app.ui.components.ScreenContent
import dev.wifiadmin.app.ui.components.SectionHeader
import dev.wifiadmin.app.ui.components.SignalBars
import dev.wifiadmin.app.ui.components.badgeColor
import dev.wifiadmin.app.ui.components.cachedBadgeColor
import dev.wifiadmin.app.ui.components.cachedLabel
import dev.wifiadmin.app.ui.components.deviceEmptyMessage
import dev.wifiadmin.app.ui.components.explanation
import dev.wifiadmin.app.ui.components.formatLastSeen
import dev.wifiadmin.app.ui.components.formatSnapshotDate
import dev.wifiadmin.app.ui.components.label
import dev.wifiadmin.app.ui.components.signalQualityLabel
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import dev.wifiadmin.app.ui.theme.WifiDims
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand

/**
 * The device list.
 *
 * The list can be narrowed with a free-text search and with a filter on the
 * band. Both are applied to the devices the router reported during the last
 * poll; the filtering is **purely local** and never triggers a read.
 *
 * Devices are grouped after filtering and sorting into Actifs / Filaires /
 * Vus précédemment, so the active device can be found in one glance. The
 * grouping preserves the sorted order inside each section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedRow by remember { mutableStateOf<DeviceRow?>(null) }
    var renamingRow by remember { mutableStateOf<DeviceRow?>(null) }

    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    val subtitle = devicesSubtitle(state)

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "Appareils",
                subtitle = subtitle,
                isRefreshing = false,
                isLoadingWithData = state.isLoading && state.devices.isNotEmpty(),
                onRefresh = viewModel::refresh
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScreenContent(
                isFirstLoad = state.isFirstLoad,
                isFatalError = state.isFatalError,
                onRetry = viewModel::refresh,
                errorMessage = state.errorMessage,
                modifier = Modifier.fillMaxSize()
            ) {
                DeviceList(
                    state = state,
                    onQueryChange = viewModel::setQuery,
                    onFilterChange = viewModel::setFilter,
                    onSortChange = viewModel::setSort,
                    onDeviceClick = { selectedRow = it },
                    onRenameClick = { renamingRow = it }
                )
            }
        }
    }

    val row = selectedRow
    if (row != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedRow = null },
            sheetState = sheetState
        ) {
            DeviceDetailSheet(
                row = row,
                cached = state.isFromCache,
                capturedAtMillis = state.lastRefreshMillis,
                onRename = {
                    selectedRow = null
                    renamingRow = row
                },
                onRemoveName = {
                    row.key?.let { viewModel.clearDeviceName(it) }
                }
            )
        }
    }

    val renaming = renamingRow
    if (renaming != null) {
        RenameDeviceDialog(
            initialName = renaming.metadata?.customName,
            originalName = renaming.originalName,
            onDismiss = { renamingRow = null },
            onConfirm = { name ->
                renaming.key?.let { viewModel.renameDevice(it, name) }
                renamingRow = null
            }
        )
    }
}

private fun devicesSubtitle(state: DevicesUiState): String? {
    if (!state.hasSnapshot) return null
    val date = formatSnapshotDate(state.lastRefreshMillis)
    val mode = if (state.isFromCache) "en cache" else "en direct"
    val count = state.devices.size
    return if (date != null) "$count appareils · $date · $mode" else "$count appareils · $mode"
}

/**
 * The search field, the filters, the sort menu and the list of devices.
 *
 * It is **public** so the instrumented tests can render it with a
 * hand-built state: the rendering is then proven without a network and
 * without waiting. It exposes no way to reach a protected screen: it only
 * draws what it is given.
 */
@Composable
fun DeviceList(
    state: DevicesUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (DeviceBandFilter) -> Unit,
    onSortChange: (DeviceSort) -> Unit,
    onDeviceClick: (DeviceRow) -> Unit,
    onRenameClick: (DeviceRow) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = state.filteredRows
    val groups = groupRows(rows)
    val nowMillis = System.currentTimeMillis()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = WifiDims.Space4)
    ) {
        if (state.hasStaleData) {
            state.staleDataReason?.let { reason ->
                CachedDataBanner(
                    capturedAtMillis = state.lastRefreshMillis,
                    reason = reason,
                    detail = state.errorMessage
                )
            }
            Spacer(Modifier.height(WifiDims.Space2))
        }

        Column(modifier = Modifier.imePadding()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text("Rechercher un appareil") },
                placeholder = { Text("Nom, adresse IP ou MAC") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier
                                .heightIn(min = WifiDims.TouchMin)
                                .testTag("devices_clear_search")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Effacer la recherche"
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("devices_search_field")
            )

            Spacer(Modifier.height(WifiDims.Space2))

            // Chips on one line, sort on the next: on a small phone the sort
            // must never push the chips off-screen.
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = WifiDims.TouchMin),
                horizontalArrangement = Arrangement.spacedBy(WifiDims.Space2),
                contentPadding = PaddingValues(end = WifiDims.Space2)
            ) {
                items(DeviceBandFilter.entries) { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { Text(filter.label) },
                        modifier = Modifier
                            .heightIn(min = WifiDims.TouchMin)
                            .testTag("filter_${filter.name}")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                SortMenu(sort = state.sort, onSortChange = onSortChange)
            }
        }

        Spacer(Modifier.height(WifiDims.Space2))

        when {
            rows.isEmpty() -> {
                val reason = state.emptyReason
                EmptyState(
                    message = if (reason == null) {
                        "Aucun appareil."
                    } else {
                        deviceEmptyMessage(
                            reason = reason,
                            query = state.query.trim(),
                            bandLabel = state.filter.label,
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = WifiDims.Space1, bottom = WifiDims.Space6),
                verticalArrangement = Arrangement.spacedBy(WifiDims.Space2)
            ) {
                groups.forEach { (section, sectionRows) ->
                    item(key = "header-${section.name}", contentType = "header") {
                        SectionHeader(title = section.title, count = sectionRows.size)
                    }
                    items(
                        items = sectionRows,
                        key = { it.key ?: "pos:${it.device.ip}|${it.device.mac}" },
                        contentType = { "device" }
                    ) { row ->
                        DeviceCard(
                            row = row,
                            cached = state.isFromCache,
                            nowMillis = nowMillis,
                            onClick = { onDeviceClick(row) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * The menu that changes the order of the list.
 *
 * A menu and not a segmented control: the sort is a setting used
 * occasionally, and a menu shows the current criterion without being
 * opened.
 */
@Composable
private fun SortMenu(
    sort: DeviceSort,
    onSortChange: (DeviceSort) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier
                .heightIn(min = WifiDims.TouchMin)
                .testTag("devices_sort_button")
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(WifiDims.Space1))
            Text("Trier : ${sort.label}")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("devices_sort_menu")
        ) {
            DeviceSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSortChange(option)
                        expanded = false
                    },
                    leadingIcon = {
                        RadioButton(selected = option == sort, onClick = null)
                    },
                    modifier = Modifier.testTag("devices_sort_option_${option.name}")
                )
            }
        }
    }
}

/** One device in the list. */
@Composable
private fun DeviceCard(
    row: DeviceRow,
    cached: Boolean,
    nowMillis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val device = row.device
    val rssi = row.rssiDbm
    val showSignal = rssi != null && device.presence == DevicePresence.ACTIVE

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag("device_card_${row.key.orEmpty()}"),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (row.hasCustomName) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                row.originalName?.let { original ->
                    Text(
                        text = "Router : $original",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = buildString {
                        append(device.ip)
                        append(" · ")
                        append(device.bands.label())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (device.presence != DevicePresence.ACTIVE &&
                    device.presence != DevicePresence.CABLED
                ) {
                    row.lastSeenMillis?.let { lastSeen ->
                        val relative = formatLastSeen(lastSeen, nowMillis)
                        Text(
                            text = if (relative != null) {
                                "$relative — ${formatSnapshotDate(lastSeen) ?: ""}"
                            } else {
                                "Dernière observation : ${formatSnapshotDate(lastSeen) ?: ""}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.width(WifiDims.Space2))

            if (showSignal) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    SignalBars(rssiDbm = rssi)
                    Text(
                        text = "$rssi dBm",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            stateDescription = signalQualityLabel(rssi)
                        }
                    )
                    Text(
                        text = signalQualityLabel(rssi),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(WifiDims.Space2))
            }

            PresenceBadge(presence = device.presence, cached = cached)
        }
    }
}

/**
 * A small coloured chip showing the presence of a device.
 *
 * The wording is read by a screen reader, not only by the eye: the label
 * carries a state description so the presence is not conveyed by colour
 * alone. The minimum height keeps the chip readable at large font scales.
 */
@Composable
private fun PresenceBadge(
    presence: DevicePresence,
    cached: Boolean,
    modifier: Modifier = Modifier
) {
    val text = if (cached) presence.cachedLabel() else presence.label()
    Surface(
        modifier = modifier
            .heightIn(min = WifiDims.BadgeMinH)
            .semantics {
                stateDescription = text
            },
        shape = RoundedCornerShape(50),
        color = if (cached) presence.cachedBadgeColor() else presence.badgeColor()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = WifiDims.BadgePaddingH, vertical = WifiDims.BadgePaddingV)
        )
    }
}

/**
 * The rename dialog.
 *
 * The name is a **local** name: the router's name is never modified, and
 * the original stays visible in the list.
 */
@Composable
fun RenameDeviceDialog(
    initialName: String?,
    originalName: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var value by remember { mutableStateOf(initialName.orEmpty()) }

    val trimmed = value.trim()
    val canSave = trimmed.isNotEmpty() && trimmed != initialName?.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("rename_dialog"),
        title = { Text("Renommer l'appareil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WifiDims.Space3)) {
                if (originalName != null) {
                    Text(
                        text = "Le nom du routeur reste inchangé : « $originalName ».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= MAX_ALIAS_LENGTH) value = it },
                    label = { Text("Nom personnalisé") },
                    placeholder = { Text("ex. Téléphone de Marie") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        autoCorrectEnabled = false
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_dialog_field")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = canSave,
                modifier = Modifier.testTag("rename_dialog_confirm")
            ) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("rename_dialog_dismiss")
            ) {
                Text("Annuler")
            }
        }
    )
}

/** The longest name the dialog accepts. */
private const val MAX_ALIAS_LENGTH = 64

/**
 * The detail of one device.
 *
 * The fields the router did not report are simply left out: showing a dash
 * would suggest the router answered something it did not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDetailSheet(
    row: DeviceRow,
    cached: Boolean,
    capturedAtMillis: Long?,
    onRename: () -> Unit,
    onRemoveName: () -> Unit,
    modifier: Modifier = Modifier
) {
    val device = row.device
    var detailsExpanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = WifiDims.Space6, end = WifiDims.Space6, bottom = WifiDims.Space8),
        verticalArrangement = Arrangement.spacedBy(WifiDims.Space1)
    ) {
        // Identity card: the four identifying pieces, on a tonal surface.
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("device_detail_name")
                    )
                    PresenceBadge(presence = device.presence, cached = cached)
                }
                if (row.originalName != null) {
                    Text(
                        text = "Nom du routeur : ${row.originalName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.testTag("device_detail_original_name")
                    )
                } else if (device.name.isNotBlank()) {
                    Text(
                        text = "Nom du routeur : ${device.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.testTag("device_detail_original_name")
                    )
                }
                Text(
                    text = buildString {
                        append(device.ip)
                        append(" · ")
                        append(device.bands.label())
                        device.signal?.rssiDbm?.let {
                            append(" · $it dBm — ${signalQualityLabel(it)}")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(WifiDims.Space2))

        // Two actions up front: rename is the primary gesture, removing the
        // alias is secondary but no longer at the bottom of the scroll.
        Row(horizontalArrangement = Arrangement.spacedBy(WifiDims.Space2)) {
            FilledTonalButton(
                onClick = onRename,
                modifier = Modifier
                    .heightIn(min = WifiDims.TouchMin)
                    .testTag("device_detail_rename_button")
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(WifiDims.Space2))
                Text("Renommer")
            }
            if (row.hasCustomName) {
                TextButton(
                    onClick = onRemoveName,
                    modifier = Modifier
                        .heightIn(min = WifiDims.TouchMin)
                        .testTag("device_detail_remove_alias_button")
                ) {
                    Text(
                        text = "Retirer le surnom",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Text(
            text = "Statut",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = WifiDims.Space3)
        )
        DetailRow(label = "État", value = if (cached) {
            device.presence.cachedLabel()
        } else {
            device.presence.label()
        }, modifier = Modifier.testTag("device_detail_state"))

        Text(
            text = device.presence.explanation(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (cached) {
            Text(
                text = formatSnapshotDate(capturedAtMillis)?.let { date ->
                    "Données enregistrées le $date. Cet appareil n'est pas " +
                        "nécessairement connecté maintenant."
                } ?: "Données enregistrées. Cet appareil n'est pas " +
                    "nécessairement connecté maintenant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = "Chronologie",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = WifiDims.Space3)
        )
        val lastSeen = row.lastSeenMillis
        DetailRow(
            label = "Dernière observation connue",
            value = if (lastSeen == null) {
                NEVER_SEEN_MESSAGE
            } else {
                val date = formatSnapshotDate(lastSeen) ?: ""
                val relative = formatLastSeen(lastSeen, System.currentTimeMillis())
                if (relative != null) "$relative — $date" else date
            },
            modifier = Modifier.testTag("device_detail_last_seen")
        )

        if (lastSeen != null) {
            Text(
                text = dev.wifiadmin.app.ui.components.LAST_SEEN_EXPLANATION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Détails techniques",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(top = WifiDims.Space3)
            )
            TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(if (detailsExpanded) "Réduire" else "Voir tout")
            }
        }

        AnimatedVisibility(visible = detailsExpanded) {
            Column {
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
                    DetailRow(
                        label = "Signal",
                        value = "$it dBm — ${signalQualityLabel(it)}"
                    )
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
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews — fictional data, clearly labelled as demonstration.
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

@Preview(showBackground = true, name = "Liste — clair")
@Composable
private fun DeviceListPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        DeviceList(
            state = DevicesUiState(
                isLoading = false,
                devices = sampleDevices,
                query = "",
                filter = DeviceBandFilter.ALL,
                sort = DeviceSort.NAME,
                lastRefreshMillis = null,
                errorMessage = null
            ),
            onQueryChange = {},
            onFilterChange = {},
            onSortChange = {},
            onDeviceClick = {},
            onRenameClick = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true, name = "Liste — sombre")
@Composable
private fun DeviceListDarkPreview() {
    WifiAdminTheme(darkTheme = true, dynamicColor = false) {
        DeviceList(
            state = DevicesUiState(
                isLoading = false,
                devices = sampleDevices,
                query = "",
                filter = DeviceBandFilter.ALL,
                sort = DeviceSort.NAME,
                lastRefreshMillis = null,
                errorMessage = null
            ),
            onQueryChange = {},
            onFilterChange = {},
            onSortChange = {},
            onDeviceClick = {},
            onRenameClick = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true, fontScale = 1.3f, widthDp = 360)
@Composable
private fun DeviceListLargeFontPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        DeviceList(
            state = DevicesUiState(
                isLoading = false,
                devices = sampleDevices,
                query = "",
                filter = DeviceBandFilter.ALL,
                sort = DeviceSort.NAME,
                lastRefreshMillis = 1_700_000_000_000L,
                errorMessage = null,
                isFromCache = true,
                hasSnapshot = true
            ),
            onQueryChange = {},
            onFilterChange = {},
            onSortChange = {},
            onDeviceClick = {},
            onRenameClick = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceListCachedPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        DeviceList(
            state = DevicesUiState(
                isLoading = false,
                devices = sampleDevices,
                query = "",
                filter = DeviceBandFilter.ALL,
                sort = DeviceSort.NAME,
                lastRefreshMillis = 1_700_000_000_000L,
                errorMessage = null,
                isFromCache = true,
                hasSnapshot = true
            ),
            onQueryChange = {},
            onFilterChange = {},
            onSortChange = {},
            onDeviceClick = {},
            onRenameClick = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceDetailPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        DeviceDetailSheet(
            row = DeviceRow(sampleDevices.first(), null),
            cached = false,
            capturedAtMillis = null,
            onRename = {},
            onRemoveName = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceEmptyPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        EmptyState(
            message = "Aucun appareil ne correspond à « poste ».",
            modifier = Modifier.fillMaxSize()
        )
    }
}
