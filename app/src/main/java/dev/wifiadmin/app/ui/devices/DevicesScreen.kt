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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wifiadmin.app.ui.components.CachedDataBanner
import dev.wifiadmin.app.ui.components.DetailRow
import dev.wifiadmin.app.ui.components.EmptyState
import dev.wifiadmin.app.ui.components.ErrorState
import dev.wifiadmin.app.ui.components.LoadingState
import dev.wifiadmin.app.ui.components.NEVER_SEEN_MESSAGE
import dev.wifiadmin.app.ui.components.badgeColor
import dev.wifiadmin.app.ui.components.cachedBadgeColor
import dev.wifiadmin.app.ui.components.cachedLabel
import dev.wifiadmin.app.ui.components.deviceEmptyMessage
import dev.wifiadmin.app.ui.components.explanation
import dev.wifiadmin.app.ui.components.formatLastSeen
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
 * band. Both are applied to the devices the router reported during the last
 * poll; the filtering is **purely local** and never triggers a read.
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
    // The current time is read once for the whole list: every card shows
    // the same moment.
    val nowMillis = System.currentTimeMillis()

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
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.testTag("devices_clear_search")
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

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A lazy row: the four chips do not always fit on a narrow
            // screen, and a lazy row only composes the visible ones. Its
            // height is explicit: a lazy layout measures itself, and
            // without a height it would take none and leave the list
            // without any room.
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 8.dp)
            ) {
                items(DeviceBandFilter.entries) { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { Text(filter.label) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("filter_${filter.name}")
                    )
                }
            }

            SortMenu(sort = state.sort, onSortChange = onSortChange)
        }

        Spacer(Modifier.height(8.dp))

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
                // The list takes the space the fixed part of the column
                // leaves. A `fillMaxSize` would ask for the whole height of
                // an unbounded parent and end up with no visible item.
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = rows, key = { it.key ?: "pos:${it.device.ip}|${it.device.mac}" }) { row ->
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
                .heightIn(min = 48.dp)
                .testTag("devices_sort_button")
        ) {
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
    // The signal only exists for a radio association: a wired device has
    // none, and must not be given a placeholder value.
    val showSignal = rssi != null && device.presence == DevicePresence.ACTIVE

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
                modifier = Modifier
                    .weight(1f)
                    // The card merges the semantics of its children, so the
                    // tag of a device goes on the column that holds its
                    // name: it survives the merge.
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

                // The router's name stays visible: the user must be able to
                // tell which device it really is.
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

                // The last observation is only shown for a device that is
                // not there any more: for a connected one, the presence
                // badge already says it.
                if (device.presence != DevicePresence.ACTIVE &&
                    device.presence != DevicePresence.CABLED
                ) {
                    row.lastSeenMillis?.let { lastSeen ->
                        // "Vu récemment" is a **bound**: the device was there
                        // at least that long ago. The date is the fact, and
                        // it is shown with it.
                        val relative = formatLastSeen(lastSeen, nowMillis)
                        Text(
                            text = if (relative != null) {
                                "$relative — dernière observation : " +
                                    (formatSnapshotDate(lastSeen) ?: "")
                            } else {
                                "Dernière observation : " +
                                    (formatSnapshotDate(lastSeen) ?: "")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            if (showSignal) {
                Text(
                    text = "$rssi dBm",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
            }

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

    // A blank name is not an error: the button stays disabled and no message
    // is shown. The user simply has not finished typing.
    val trimmed = value.trim()
    val canSave = trimmed.isNotEmpty() && trimmed != initialName?.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("rename_dialog"),
        title = { Text("Renommer l'appareil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .testTag("device_detail_name")
            )

            IconButton(
                onClick = onRename,
                modifier = Modifier.testTag("device_detail_rename_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Renommer l'appareil"
                )
            }
        }

        row.originalName?.let { original ->
            Text(
                text = "Nom du routeur : $original",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("device_detail_original_name")
            )
        }

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
            value = if (cached) device.presence.cachedLabel() else device.presence.label(),
            modifier = Modifier.testTag("device_detail_state")
        )

        Text(
            text = device.presence.explanation(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // The last confirmed observation. The application never pretends to
        // know the moment of the disconnection: it only knows the last read
        // that saw the device.
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        val lastSeen = row.lastSeenMillis
        DetailRow(
            label = "Dernière observation connue",
            value = if (lastSeen == null) {
                NEVER_SEEN_MESSAGE
            } else {
                // The relative wording and the date: the date is the fact,
                // the relative wording is the convenience.
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

        if (row.hasCustomName) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onRemoveName,
                modifier = Modifier.testTag("device_detail_remove_alias_button")
            ) {
                Text(
                    text = "Supprimer le nom personnalisé",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
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

@Preview(showBackground = true)
@Composable
private fun DeviceListCachedPreview() {
    WifiAdminTheme {
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
    WifiAdminTheme {
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
    WifiAdminTheme {
        EmptyState(
            message = "Aucun appareil ne correspond à « poste ».",
            modifier = Modifier.fillMaxSize()
        )
    }
}
