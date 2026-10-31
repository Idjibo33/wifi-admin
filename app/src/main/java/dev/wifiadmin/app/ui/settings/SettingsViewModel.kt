package dev.wifiadmin.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The state of the settings screen. */
data class SettingsUiState(
    val username: String = SettingsStore.DEFAULT_USERNAME,
    val isSaving: Boolean = false,
    val savedConfirmation: Boolean = false
)

/**
 * Drives the settings screen.
 *
 * The password is **never** part of this state: it is not stored, not
 * displayed and not written to disk. The router address is not a setting: it
 * is detected from the current Wi-Fi network.
 */
class SettingsViewModel(
    private val repository: RouterRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.username.collect { username ->
                _state.value = _state.value.copy(username = username)
            }
        }
    }

    fun onUsernameChanged(username: String) {
        _state.value = _state.value.copy(username = username, savedConfirmation = false)
    }

    /** Saves the administrator name. */
    fun save() {
        if (_state.value.isSaving) return
        _state.value = _state.value.copy(isSaving = true, savedConfirmation = false)
        viewModelScope.launch {
            settingsStore.saveUsername(_state.value.username)
            _state.value = _state.value.copy(isSaving = false, savedConfirmation = true)
        }
    }

    /** Drops the session and returns to the login screen. */
    fun disconnect(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.disconnect()
            onDone()
        }
    }
}
