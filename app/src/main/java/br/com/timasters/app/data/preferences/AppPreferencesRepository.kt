package br.com.timasters.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appPreferences: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

/** Small per-device settings: onboarding tour and the network-scanner usage notice. */
@Singleton
class AppPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val tourCompletedKey = booleanPreferencesKey("tour_completed")
    private val scannerNoticeAcceptedAtKey = longPreferencesKey("scanner_notice_accepted_at")

    val tourCompleted: Flow<Boolean> = context.appPreferences.data.map { it[tourCompletedKey] ?: false }

    /** The user confirmed they only scan networks they manage or are authorized to test. */
    val scannerNoticeAccepted: Flow<Boolean> =
        context.appPreferences.data.map { it[scannerNoticeAcceptedAtKey] != null }

    suspend fun setTourCompleted(completed: Boolean) {
        context.appPreferences.edit { it[tourCompletedKey] = completed }
    }

    suspend fun acceptScannerNotice() {
        context.appPreferences.edit { it[scannerNoticeAcceptedAtKey] = System.currentTimeMillis() }
    }
}
