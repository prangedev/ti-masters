package br.com.timasters.app.data.business

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import br.com.timasters.app.data.auth.AuthRepository
import br.com.timasters.app.data.auth.AuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.businessPreferences: DataStore<Preferences> by preferencesDataStore(name = "business_sync")

/**
 * Which company the signed-in user works for (every account gets one at sign-up), cached so the
 * Business module also opens offline. Also stores the sync bookkeeping per company.
 */
@Singleton
class CompanyRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val supabase: SupabaseClient,
    private val authRepository: AuthRepository
) {
    private val store get() = context.businessPreferences

    val userId: Flow<String?> = authRepository.authState
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()

    val companyId: Flow<String?> = combine(userId, store.data) { user, preferences ->
        user?.let { preferences[companyKey(it)] }
    }.distinctUntilChanged()

    /** Cached company, or asks the server (needs internet only the first time). */
    suspend fun ensureCompanyId(): String? {
        val user = userId.first() ?: return null
        companyId.first()?.let { return it }
        val fetched = supabase.postgrest.rpc("get_my_company").decodeAs<String?>() ?: return null
        store.edit { it[companyKey(user)] = fetched }
        return fetched
    }

    fun lastSyncAt(companyId: String): Flow<Long?> = store.data.map { it[lastSyncKey(companyId)] }

    suspend fun setLastSyncAt(companyId: String, millis: Long) {
        store.edit { it[lastSyncKey(companyId)] = millis }
    }

    suspend fun pullCursor(companyId: String, table: String): String? =
        store.data.first()[cursorKey(companyId, table)]

    suspend fun setPullCursor(companyId: String, table: String, cursor: String) {
        store.edit { it[cursorKey(companyId, table)] = cursor }
    }

    private fun companyKey(userId: String) = stringPreferencesKey("company_$userId")
    private fun lastSyncKey(companyId: String) = longPreferencesKey("last_sync_$companyId")
    private fun cursorKey(companyId: String, table: String) = stringPreferencesKey("cursor_${companyId}_$table")
}
