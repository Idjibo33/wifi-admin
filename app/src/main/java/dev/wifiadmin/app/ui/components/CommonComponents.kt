package dev.wifiadmin.app.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wifiadmin.app.ui.theme.WifiDims

/**
 * The description a screen reader gives of a statistic card.
 *
 * A card that only says "3" says nothing: the label and the value are
 * combined so the counter is announced with its unit.
 */
fun statCardDescription(label: String, value: String): String = "$label : $value"

/**
 * Whether the system animator is enabled.
 *
 * The app respects the Android "reduce animations" setting: when the
 * animator duration scale is 0, every duration becomes 0 and animated
 * items become plain modifiers. No decorative dependency is added.
 */
@Composable
fun rememberAnimationEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

@Composable
fun rememberFadeMillis(defaultMs: Int = 200): Int {
    val enabled = rememberAnimationEnabled()
    return if (enabled) defaultMs else 0
}

/** Number of filled bars for a signal level (0..4). */
fun signalBarsLevel(rssiDbm: Int?): Int = when {
    rssiDbm == null -> 0
    rssiDbm >= -55 -> 4
    rssiDbm >= -65 -> 3
    rssiDbm >= -75 -> 2
    rssiDbm >= -85 -> 1
    else -> 1
}

/**
 * A compact statistic card.
 *
 * @param value the number to show. It comes from the router at runtime; it is
 *   never a constant.
 */
@Composable
fun StatCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Boolean = false
) {
    Card(
        modifier = modifier
            .semantics {
                stateDescription = statCardDescription(label, value)
            },
        colors = CardDefaults.cardColors(
            containerColor = if (accent) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = WifiDims.CardPaddingH, vertical = WifiDims.CardPaddingV),
            verticalArrangement = Arrangement.spacedBy(WifiDims.Space1)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(WifiDims.IconSm),
                    tint = if (accent) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.width(WifiDims.Space2))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (accent) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = if (accent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

/** The full-screen loading state. */
@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(WifiDims.Space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WifiDims.Space4)
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** The full-screen error state, with an optional retry action. */
@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(WifiDims.Space8)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WifiDims.Space3)
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(WifiDims.IconState),
            tint = MaterialTheme.colorScheme.error
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(WifiDims.Space2))
                Text("Réessayer")
            }
        }
    }
}

/** The empty state, used when the router reports no device. */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(WifiDims.Space8)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WifiDims.Space3)
    ) {
        Icon(
            imageVector = Icons.Default.Devices,
            contentDescription = null,
            modifier = Modifier.size(WifiDims.IconState),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        action?.invoke()
    }
}

/**
 * The banner shown when the data on screen is not a fresh, complete read.
 *
 * It is deliberately explicit: the devices were observed at the given moment,
 * not now. The reason is part of the wording, because a lost router, a lost
 * network and a failed synchronisation are three different situations.
 *
 * @param capturedAtMillis when the displayed snapshot was read from the
 *   router, or null when the date is unknown.
 * @param reason why the data is not fresh.
 * @param detail the precise failure, shown as a second line when available.
 */
@Composable
fun CachedDataBanner(
    capturedAtMillis: Long?,
    reason: StaleDataReason,
    detail: String? = null,
    modifier: Modifier = Modifier
) {
    val message = staleDataMessage(reason, capturedAtMillis)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = WifiDims.Space4, vertical = WifiDims.CardPaddingV),
            horizontalArrangement = Arrangement.spacedBy(WifiDims.Space2),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(WifiDims.IconBanner),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Column(verticalArrangement = Arrangement.spacedBy(WifiDims.Space1)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                if (detail != null && reason != StaleDataReason.NETWORK_CHANGED) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

/** A label/value row used in the detail sheets. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = WifiDims.Space2),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        Spacer(Modifier.width(WifiDims.Space3))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.55f)
        )
    }
}

/**
 * Shared top bar: title, freshness subtitle and refresh action.
 *
 * The subtitle is the first line of the freshness information: the user sees
 * the age of the data without reading the content. The refresh button keeps
 * the 48 dp target and shows a spinner while a refresh with existing data
 * runs, never hiding the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    subtitle: String?,
    isRefreshing: Boolean,
    isLoadingWithData: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        actions = {
            IconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.heightIn(min = WifiDims.TouchMin)
            ) {
                if (isRefreshing || isLoadingWithData) {
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

/**
 * Shared screen body: fade between loading, error and content.
 *
 * The key is a stable boolean pair, never the whole UiState (copied at
 * every poll), so the fade never re-triggers on the periodic refresh.
 */
@Composable
fun ScreenContent(
    isFirstLoad: Boolean,
    isFatalError: Boolean,
    onRetry: () -> Unit,
    errorMessage: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val fadeMillis = rememberFadeMillis()
    val stateKey = when {
        isFirstLoad -> "loading"
        isFatalError -> "error"
        else -> "content"
    }
    AnimatedContent(
        targetState = stateKey,
        modifier = modifier,
        transitionSpec = {
            fadeIn(animationSpec = tween(fadeMillis)) togetherWith fadeOut(animationSpec = tween(fadeMillis))
        },
        label = "ScreenContent"
    ) { key ->
        when (key) {
            "loading" -> LoadingState(message = "Connexion au routeur…", modifier = Modifier.fillMaxWidth())
            "error" -> ErrorState(
                title = "Lecture impossible",
                message = errorMessage ?: "Le routeur n'a pas répondu.",
                modifier = Modifier.fillMaxWidth(),
                onRetry = onRetry
            )
            else -> content()
        }
    }
}

/** A section title with its item count, announced as a heading. */
@Composable
fun SectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = WifiDims.Space2)
            .semantics { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Four-bar signal visualisation.
 *
 * The colour is never the only channel: the label and the numeric value
 * stay readable, and the screen reader gets [signalQualityLabel].
 */
@Composable
fun SignalBars(
    rssiDbm: Int?,
    modifier: Modifier = Modifier
) {
    val level = signalBarsLevel(rssiDbm)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier = modifier.semantics {
            stateDescription = signalQualityLabel(rssiDbm)
        },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(4) { index ->
            val filled = index < level
            Surface(
                modifier = Modifier
                    .width(4.dp)
                    .size(width = 4.dp, height = (4 + index * 3).dp),
                shape = RoundedCornerShape(1.dp),
                color = if (filled) active else inactive
            ) {}
        }
    }
}

/** A soft banner whose detail can be expanded. */
@Composable
fun InfoNoticeCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(WifiDims.Space4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(WifiDims.IconBanner),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(WifiDims.Space2))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onToggle) { Text(if (expanded) "Réduire" else "Savoir plus") }
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = WifiDims.Space2)
                )
            }
        }
    }
}
