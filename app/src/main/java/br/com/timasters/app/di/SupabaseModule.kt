package br.com.timasters.app.di

import br.com.timasters.app.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import javax.inject.Singleton

/** Deep link that Supabase e-mails open: timasters://auth/... (declared in AndroidManifest.xml). */
const val AUTH_DEEPLINK_SCHEME = "timasters"
const val AUTH_DEEPLINK_HOST = "auth"

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    ) {
        install(Auth) {
            // PKCE keeps tokens out of the e-mail link; the code is exchanged on this device.
            flowType = FlowType.PKCE
            scheme = AUTH_DEEPLINK_SCHEME
            host = AUTH_DEEPLINK_HOST
        }
        install(Postgrest)
        // Private buckets "logos" and "service-orders" (files under "<company_id>/...").
        install(Storage)
    }
}
