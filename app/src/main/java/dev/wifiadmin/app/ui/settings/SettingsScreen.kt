package dev.wifiadmin.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wifiadmin.app.ui.components.DetailRow
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import dev.wifiadmin.app.ui.theme.WifiDims

/**
 * The settings screen.
 *
 * Only the administrator name is persisted. The password is deliberately
 * absent: the user types it again on each session. The router address is not
 * a setting either: it is read from the current Wi-Fi network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    appVersionName: String,
    routerDisplayName: String,
    onDisconnected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDisconnect by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Réglages") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(WifiDims.Space4),
            verticalArrangement = Arrangement.spacedBy(WifiDims.Space4)
        ) {
            SettingsSection(title = "Compte administrateur") {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChanged,
                    label = { Text("Nom d'utilisateur") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = WifiDims.FieldMin)
                )
                OutlinedButton(
                    onClick = viewModel::save,
                    enabled = !state.isSaving && state.username.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = WifiDims.TouchMin)
                        .testTag("settings_save_button")
                ) {
                    Text("Enregistrer")
                }
                if (state.savedConfirmation) {
                    Text(
                        text = "Paramètres enregistrés.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Le mot de passe n'est pas conservé. Saisissez-le à chaque " +
                        "connexion depuis l'écran de connexion.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(WifiDims.Space4),
                    verticalArrangement = Arrangement.spacedBy(WifiDims.Space3)
                ) {
                    Text(
                        text = "Session",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Se déconnecter fermera la session sur ce téléphone. Les données consultées seront effacées de la mémoire.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    OutlinedButton(
                        onClick = { confirmDisconnect = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = WifiDims.TouchMin)
                            .testTag("settings_disconnect_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(WifiDims.Space2))
                        Text("Se déconnecter", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            SettingsSection(title = "Informations techniques") {
                DetailRow("Type de routeur", routerDisplayName)
                HorizontalDivider()
                DetailRow("Version de l'application", appVersionName)
                HorizontalDivider()
                DetailRow("Mode de filtrage", "Lecture seule — aucune commande d'écriture")
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(WifiDims.Space4),
                    horizontalArrangement = Arrangement.spacedBy(WifiDims.Space2)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(WifiDims.IconBanner),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "Le filtrage MAC Wi-Fi (commande 278) est refusé par ce " +
                            "firmware (LIMITED_ACCESS). Le filtrage MAC du pare-feu " +
                            "(commande 23) est distinct : il filtre le trafic et non " +
                            "l'association Wi-Fi. Aucune de ces fonctions n'est exposée " +
                            "comme fonctionnalité active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Se déconnecter ?") },
            text = { Text("La session sera fermée et les données affichées seront effacées de la mémoire. Vous devrez vous reconnecter pour consulter la liste des appareils.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        viewModel.disconnect(onDisconnected)
                    },
                    modifier = Modifier.testTag("settings_disconnect_confirm")
                ) { Text("Se déconnecter") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text("Annuler") }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(WifiDims.Space4),
            verticalArrangement = Arrangement.spacedBy(WifiDims.Space3),
            content = { content() }
        )
    }
}

@Preview(showBackground = true, name = "Réglages — clair")
@Composable
private fun SettingsSectionPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        SettingsSection(title = "Compte administrateur") {
            Text("Aperçu — données de démonstration")
        }
    }
}

@Preview(showBackground = true, name = "Réglages — sombre")
@Composable
private fun SettingsDarkPreview() {
    WifiAdminTheme(darkTheme = true, dynamicColor = false) {
        SettingsSection(title = "Compte administrateur") {
            Text("Aperçu — données de démonstration")
        }
    }
}
