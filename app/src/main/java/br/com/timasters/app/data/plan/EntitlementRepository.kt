package br.com.timasters.app.data.plan

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import br.com.timasters.app.BuildConfig
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private val Context.entitlementDataStore: DataStore<Preferences> by preferencesDataStore(name = "entitlement")

@Serializable
private data class EntitlementResponse(
    val plan: String,
    @SerialName("expires_at") val expiresAt: String? = null
)

/**
 * The plan comes from the server (`get_my_entitlement`, backed by the `subscriptions` table that
 * only server code writes). The last answer is cached per user for offline use.
 */
@Singleton
class EntitlementRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val supabase: SupabaseClient,
    private val authRepository: AuthRepository
) {
    private val userIdKey = stringPreferencesKey("user_id")
    private val planKey = stringPreferencesKey("plan")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val verifiedAtKey = longPreferencesKey("verified_at")
    private val simulatedPlanKey = stringPreferencesKey("debug_simulated_plan")

    private val currentUserId: Flow<String?> = authRepository.authState
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()

    val entitlement: Flow<Entitlement> = combine(
        context.entitlementDataStore.data,
        currentUserId
    ) { preferences, userId ->
        val cache = preferences.toCache()
        val accountPlan = cache?.planFor(userId, System.currentTimeMillis()) ?: SubscriptionPlan.BASIC
        val simulatedPlan = preferences[simulatedPlanKey]
            ?.takeIf { BuildConfig.DEBUG }
            ?.let { name -> SubscriptionPlan.entries.firstOrNull { it.name == name } }
        Entitlement(
            plan = simulatedPlan ?: accountPlan,
            accountPlan = accountPlan,
            verifiedAtMillis = cache?.takeIf { it.userId == userId }?.verifiedAtMillis,
            isSimulated = simulatedPlan != null
        )
    }

    /** Asks the server for the current plan. Throws on network/server errors. */
    suspend fun refresh() {
        val userId = currentUserId.first() ?: return
        val response = supabase.postgrest.rpc("get_my_entitlement").decodeAs<EntitlementResponse>()
        context.entitlementDataStore.edit { preferences ->
            preferences[userIdKey] = userId
            preferences[planKey] = SubscriptionPlan.fromServerId(response.plan).serverId
            val expiresAt = EntitlementCache.parseServerTimestamp(response.expiresAt)
            if (expiresAt != null) preferences[expiresAtKey] = expiresAt else preferences.remove(expiresAtKey)
            preferences[verifiedAtKey] = System.currentTimeMillis()
        }
    }

    /** Debug builds only: pretend to have [plan] on this device (null returns to the account plan). */
    suspend fun setSimulatedPlan(plan: SubscriptionPlan?) {
        if (!BuildConfig.DEBUG) return
        context.entitlementDataStore.edit { preferences ->
            if (plan != null) preferences[simulatedPlanKey] = plan.name else preferences.remove(simulatedPlanKey)
        }
    }

    private fun Preferences.toCache(): EntitlementCache? {
        val userId = this[userIdKey] ?: return null
        val verifiedAt = this[verifiedAtKey] ?: return null
        return EntitlementCache(
            userId = userId,
            plan = SubscriptionPlan.fromServerId(this[planKey]),
            expiresAtMillis = this[expiresAtKey],
            verifiedAtMillis = verifiedAt
        )
    }
}
