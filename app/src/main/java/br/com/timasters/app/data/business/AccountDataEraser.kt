package br.com.timasters.app.data.business

import android.content.Context
import br.com.timasters.app.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Account deletion (LGPD). The database removes the rows by itself, but Storage files must be
 * deleted through the Storage API, so this runs first: logos and PDFs of every company the user
 * owns. Then, after the account is gone, the copies kept on this phone are erased too.
 */
@Singleton
class AccountDataEraser @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val supabase: SupabaseClient,
    private val database: AppDatabase
) {
    /** Throws on network errors, so the account is not deleted while its files remain. */
    suspend fun deleteCloudFiles() {
        val companies = supabase.postgrest.rpc("get_my_owned_companies").decodeList<String>()
        companies.forEach { companyId ->
            listOf(BusinessFiles.LOGO_BUCKET, BusinessFiles.ORDERS_BUCKET).forEach { bucket ->
                val api = supabase.storage.from(bucket)
                while (true) {
                    val names = api.list(companyId) { limit = PAGE }.map { "$companyId/${it.name}" }
                    if (names.isEmpty()) break
                    api.delete(names)
                    if (names.size < PAGE) break
                }
            }
        }
    }

    suspend fun deleteLocalData() = withContext(Dispatchers.IO) {
        database.clearAllTables()
        File(context.filesDir, "branding").deleteRecursively()
        File(context.filesDir, "orders").deleteRecursively()
    }

    private companion object {
        const val PAGE = 100
    }
}
