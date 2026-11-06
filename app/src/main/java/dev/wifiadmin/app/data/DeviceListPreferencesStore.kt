package dev.wifiadmin.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.deviceListPreferences by preferencesDataStore(
    name = "wifi_admin_device_list"
)

/**
 * The display preferences of the device list.
 *
 * They describe **how the list is shown**, never what the router is asked
 * to do: they are not a setting of the router, and they are not personal
 * data either.
 *
 * The store is a file of its own, apart from the application settings: the
 * preferences of the list can then evolve without touching them.
 *
 * The values are the **names** of the enumerations. A value that is not
 * known — a file written by a newer version, a corrupted preference — falls
 * back on the default instead of failing.
 */
class DeviceListPreferencesStore(private val context: Context) {

    private object Keys {
        /** The sort criterion, as the name of the enumeration. */
        val SORT = stringPreferencesKey("device_list_sort")

        /** The band filter, as the name of the enumeration. */
        val FILTER = stringPreferencesKey("device_list_filter")
    }

    /**
     * The sort criterion.
     *
     * The enumeration lives in the presentation layer, and this store lives
     * in the data layer: the store therefore only carries the **name**, and
     * the caller resolves it. An unknown name yields null, which the
     * caller turns into the default.
     */
    val sortName: Flow<String?> = context.deviceListPreferences.data
        .map { it[Keys.SORT] }

    /** The band filter, as the name of the enumeration. */
    val filterName: Flow<String?> = context.deviceListPreferences.data
        .map { it[Keys.FILTER] }

    /** Stores the sort criterion. */
    suspend fun saveSort(name: String) {
        context.deviceListPreferences.edit { it[Keys.SORT] = name }
    }

    /** Stores the band filter. */
    suspend fun saveFilter(name: String) {
        context.deviceListPreferences.edit { it[Keys.FILTER] = name }
    }
}
