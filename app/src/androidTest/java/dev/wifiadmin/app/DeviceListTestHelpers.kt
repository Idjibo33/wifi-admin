package dev.wifiadmin.app

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.wifiadmin.app.ui.devices.DeviceList
import dev.wifiadmin.app.ui.devices.DevicesUiState
import dev.wifiadmin.app.ui.devices.RenameDeviceDialog

/**
 * The thin wrappers the instrumented tests render.
 *
 * They exist so the test can drive the real composables with a state it
 * builds by hand: no network, no coroutine, no waiting.
 */
@Composable
internal fun DeviceListUnderTest(
    state: DevicesUiState,
    modifier: Modifier = Modifier,
) {
    // The screen gives the list a bounded height. The test must do the
    // same: a list in an unbounded parent would have no room to draw.
    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize()
    ) {
        DeviceList(
            state = state,
            onQueryChange = {},
            onFilterChange = {},
            onSortChange = {},
            onDeviceClick = {},
            onRenameClick = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun RenameDialogUnderTest(
    initialName: String?,
    originalName: String?,
    onConfirm: (String) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    RenameDeviceDialog(
        initialName = initialName,
        originalName = originalName,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
