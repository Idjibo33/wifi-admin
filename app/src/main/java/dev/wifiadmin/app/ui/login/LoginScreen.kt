package dev.wifiadmin.app.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wifiadmin.app.ui.components.EXPIRY_MESSAGE
import dev.wifiadmin.app.ui.components.LOCAL_NETWORK_PERMISSION_MESSAGE
import dev.wifiadmin.app.ui.theme.WifiAdminTheme
import dev.wifiadmin.app.ui.theme.WifiDims

/**
 * The connection screen.
 *
 * The password is held in a local Compose state and is neither part of the
 * ViewModel's state nor persisted anywhere.
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
    localNetworkPermissionState: dev.wifiadmin.app.data.LocalNetworkPermissionState? = null,
    onRequestLocalNetworkPermission: (() -> Unit)? = null,
    onOpenAppSettings: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Après un accord (dialogue ou retour des réglages), l'ancienne erreur
    // de permission est effacée sans redémarrage : l'utilisateur peut
    // immédiatement réessayer avec « Se connecter ».
    androidx.compose.runtime.LaunchedEffect(
        localNetworkPermissionState?.status,
    ) {
        if (localNetworkPermissionState?.status ==
            dev.wifiadmin.app.data.LocalNetworkPermissionStatus.Granted
        ) {
            viewModel.clearLocalNetworkPermissionDenied()
        }
    }

    if (result == LoginResult.Success) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            password = ""
            onConnected()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(WifiDims.Space6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WifiDims.Space4)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WifiDims.Space2)
            ) {
                Icon(
                    imageVector = Icons.Default.Router,
                    contentDescription = null,
                    modifier = Modifier.size(WifiDims.IconLogin),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "WiFi Admin",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Administration locale de votre routeur",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(WifiDims.Space6),
                    verticalArrangement = Arrangement.spacedBy(WifiDims.Space4)
                ) {
                    DetectedRouterRow(
                        host = state.host,
                        wasDetected = state.hostWasDetected
                    )

                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::onUsernameChanged,
                        label = { Text("Nom d'utilisateur") },
                        singleLine = true,
                        enabled = !state.isConnecting,
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null)
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = WifiDims.FieldMin)
                            .testTag("login_username_field")
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Mot de passe") },
                        singleLine = true,
                        enabled = !state.isConnecting,
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null)
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                modifier = Modifier.heightIn(min = WifiDims.TouchMin)
                            ) {
                                Icon(
                                    imageVector = if (passwordVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (passwordVisible) {
                                        "Masquer le mot de passe"
                                    } else {
                                        "Afficher le mot de passe"
                                    }
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = WifiDims.FieldMin)
                            .testTag("login_password_field")
                    )

                    Button(
                        onClick = { viewModel.connect(state.host, state.username, password) },
                        enabled = !state.isConnecting &&
                            state.host.isNotBlank() &&
                            state.username.isNotBlank() &&
                            password.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = WifiDims.FieldMin)
                            .testTag("login_connect_button")
                    ) {
                        if (state.isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(WifiDims.Space2))
                            Text("Connexion…")
                        } else {
                            Text("Se connecter")
                        }
                    }

                    Text(
                        text = "Le mot de passe n'est pas conservé. Il sera redemandé à chaque session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LoginResultMessage(
                result = result,
                localNetworkPermissionState = localNetworkPermissionState,
                onRequestLocalNetworkPermission = onRequestLocalNetworkPermission,
                onOpenAppSettings = onOpenAppSettings,
            )

            // Bandeau permission avant toute erreur (cas initial Required).
            if (localNetworkPermissionState?.blocksLocalNetwork == true &&
                result == LoginResult.None
            ) {
                PermissionBanner(
                    state = localNetworkPermissionState,
                    onRequestLocalNetworkPermission = onRequestLocalNetworkPermission,
                    onOpenAppSettings = onOpenAppSettings,
                )
            }

            Text(
                text = "Connexion locale uniquement. Le mot de passe n'est pas conservé.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("login_local_only_notice")
            )
        }
    }
}

@Composable
private fun DetectedRouterRow(host: String, wasDetected: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(WifiDims.Space4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WifiDims.Space2)
        ) {
            Icon(
                imageVector = if (wasDetected) {
                    Icons.Default.CheckCircle
                } else {
                    Icons.Default.Router
                },
                contentDescription = null,
                modifier = Modifier.size(WifiDims.IconBanner),
                tint = if (wasDetected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (wasDetected) "Routeur détecté" else "Routeur non détecté",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (wasDetected) {
                        host
                    } else {
                        "$host (adresse par défaut) — vérifiez le Wi-Fi"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LoginResultMessage(
    result: LoginResult,
    localNetworkPermissionState: dev.wifiadmin.app.data.LocalNetworkPermissionState? = null,
    onRequestLocalNetworkPermission: (() -> Unit)? = null,
    onOpenAppSettings: (() -> Unit)? = null,
) {
    AnimatedVisibility(visible = result != LoginResult.None && result != LoginResult.Success) {
        when (result) {
            LoginResult.None -> Unit
            LoginResult.Success -> Unit
            LoginResult.InvalidCredentials -> MessageCard(
                text = "Identifiants refusés par le routeur.",
                isError = true
            )
            LoginResult.NetworkError -> MessageCard(
                text = "Le routeur est injoignable. Vérifiez que le téléphone est " +
                    "connecté au Wi-Fi du routeur, puis réessayez.",
                isError = true
            )
            LoginResult.Timeout -> MessageCard(
                text = "Le routeur n'a pas répondu à temps. Vérifiez le Wi-Fi, " +
                    "puis réessayez.",
                isError = true
            )
            LoginResult.SessionExpired -> MessageCard(
                text = EXPIRY_MESSAGE,
                isError = false
            )
            LoginResult.UnexpectedResponse -> MessageCard(
                text = "Le routeur a répondu de façon inattendue.",
                isError = true
            )
            LoginResult.LocalNetworkPermissionDenied -> MessageCard(
                text = LOCAL_NETWORK_PERMISSION_MESSAGE,
                isError = true,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(WifiDims.Space2),
                    modifier = Modifier.padding(horizontal = WifiDims.Space4, vertical = WifiDims.Space2),
                ) {
                    if (localNetworkPermissionState?.canAskAgain == true &&
                        onRequestLocalNetworkPermission != null
                    ) {
                        Button(
                            onClick = onRequestLocalNetworkPermission,
                            modifier = Modifier.testTag("login_permission_allow_button"),
                        ) {
                            Text("Autoriser l'accès")
                        }
                    }
                    if (onOpenAppSettings != null) {
                        androidx.compose.material3.TextButton(
                            onClick = onOpenAppSettings,
                            modifier = Modifier.testTag("login_permission_settings_button"),
                        ) {
                            Text("Ouvrir les paramètres")
                        }
                    }
                }
            }
            is LoginResult.Cooldown -> MessageCard(
                text = result.retryAfterSeconds?.let { seconds ->
                    "Trop de tentatives. Le routeur refuse les connexions " +
                        "pendant encore $seconds secondes."
                } ?: "Le routeur n'a pas ouvert de session. Patientez quelques " +
                    "minutes avant de réessayer.",
                isError = true
            )
        }
    }
}

@Composable
private fun MessageCard(
    text: String,
    isError: Boolean,
    actions: @Composable (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
    ) {
        Column(modifier = Modifier.padding(WifiDims.Space4)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            if (actions != null) {
                actions()
            }
        }
    }
}

@Composable
private fun PermissionBanner(
    state: dev.wifiadmin.app.data.LocalNetworkPermissionState,
    onRequestLocalNetworkPermission: (() -> Unit)?,
    onOpenAppSettings: (() -> Unit)?,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(WifiDims.Space4)) {
            Text(
                text = LOCAL_NETWORK_PERMISSION_MESSAGE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(WifiDims.Space2),
                modifier = Modifier.padding(top = WifiDims.Space2),
            ) {
                if (state.canAskAgain && onRequestLocalNetworkPermission != null) {
                    Button(
                        onClick = onRequestLocalNetworkPermission,
                        modifier = Modifier.testTag("login_permission_banner_allow"),
                    ) {
                        Text("Autoriser l'accès")
                    }
                }
                if (onOpenAppSettings != null) {
                    androidx.compose.material3.TextButton(
                        onClick = onOpenAppSettings,
                        modifier = Modifier.testTag("login_permission_banner_settings"),
                    ) {
                        Text("Ouvrir les paramètres")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Connexion — clair")
@Composable
private fun LoginPreview() {
    WifiAdminTheme(darkTheme = false, dynamicColor = false) {
        DetectedRouterRow(host = "192.168.1.1", wasDetected = true)
    }
}

@Preview(showBackground = true, name = "Connexion — sombre")
@Composable
private fun LoginDarkPreview() {
    WifiAdminTheme(darkTheme = true, dynamicColor = false) {
        DetectedRouterRow(host = "192.168.1.1", wasDetected = false)
    }
}
