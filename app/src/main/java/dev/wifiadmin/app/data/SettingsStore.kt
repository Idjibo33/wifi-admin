package dev.wifiadmin.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wifi_admin_settings")

/**
 * The settings that survive a restart.
 *
 * Only the administrator name is stored. **The administrator password is
 * deliberately not stored**: it is kept in memory for the duration of the
 * session only. If persistence is ever added, it must go through the Android
 * Keystore, never to this file.
 *
 * The router address is not a setting: it is read from the current Wi-Fi
 * network, so there is nothing to persist or to get wrong.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val USERNAME = stringPreferencesKey("router_username")
    }

    val username: Flow<String> = context.dataStore.data.map { it[Keys.USERNAME] ?: DEFAULT_USERNAME }

    suspend fun saveUsername(username: String) {
        context.dataStore.edit { it[Keys.USERNAME] = username.trim() }
    }

    companion object {
        const val DEFAULT_USERNAME = "admin"
    }
}
