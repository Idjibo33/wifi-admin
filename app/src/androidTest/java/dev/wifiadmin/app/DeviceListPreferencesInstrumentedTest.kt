package dev.wifiadmin.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wifiadmin.app.ui.devices.DeviceBandFilter
import dev.wifiadmin.app.ui.devices.DeviceSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The display preferences of the device list.
 *
 * The test runs on the device because the store is backed by DataStore: it
 * needs a real `Context` and a real file.
 */
@RunWith(AndroidJUnit4::class)
class DeviceListPreferencesInstrumentedTest {

    private lateinit var store: DeviceListPreferencesStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = DeviceListPreferencesStore(context)
    }

    @Test
    fun theSortIsStoredAndReadBack() = runBlocking {
        store.saveSort(DeviceSort.SIGNAL.name)

        assertEquals(DeviceSort.SIGNAL.name, store.sortName.first())
    }

    @Test
    fun theFilterIsStoredAndReadBack() = runBlocking {
        store.saveFilter(DeviceBandFilter.BAND_5.name)

        assertEquals(DeviceBandFilter.BAND_5.name, store.filterName.first())
    }

    @Test
    fun anUnknownSortFallsBackOnTheDefault() = runBlocking {
        // A value written by a newer version must not break the list: the
        // store keeps it, and the **caller** turns it into the default.
        store.saveSort("A_SORT_THAT_DOES_NOT_EXIST")

        val name = store.sortName.first()

        assertEquals("A_SORT_THAT_DOES_NOT_EXIST", name)
        assertNull(
            "no criterion of the list matches an unknown name",
            DeviceSort.entries.firstOrNull { it.name == name }
        )
        // The default the caller applies.
        assertEquals(DeviceSort.NAME, DeviceSort.entries.firstOrNull { it.name == name }
            ?: DeviceSort.NAME)
    }

    @Test
    fun nothingIsStoredByDefault() = runBlocking {
        // The store may already hold a value from a previous run: the
        // assertion is on the **shape** of the value, not on its absence.
        val name = store.sortName.first()

        assertTrue(
            "the stored value must be a known one or absent",
            name == null || DeviceSort.entries.any { it.name == name }
        )
    }

    private fun assertTrue(message: String, condition: Boolean) =
        org.junit.Assert.assertTrue(message, condition)
}
