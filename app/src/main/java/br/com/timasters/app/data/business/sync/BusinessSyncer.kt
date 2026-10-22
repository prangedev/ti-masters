package br.com.timasters.app.data.business.sync

import androidx.annotation.StringRes
import br.com.timasters.app.R
import br.com.timasters.app.core.time.IsoTime
import br.com.timasters.app.data.business.AppointmentDao
import br.com.timasters.app.data.business.BusinessFiles
import br.com.timasters.app.data.business.ClientDao
import br.com.timasters.app.data.business.CompanyProfileDao
import br.com.timasters.app.data.business.CompanyRepository
import br.com.timasters.app.data.business.OrderKind
import br.com.timasters.app.data.business.PricingSettingsDao
import br.com.timasters.app.data.business.ServiceItemDao
import br.com.timasters.app.data.business.ServiceOrderDao
import br.com.timasters.app.data.business.TicketDao
import br.com.timasters.app.data.business.TimeEntryDao
import br.com.timasters.app.data.business.reminders.AppointmentReminderScheduler
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncError(@param:StringRes val messageResId: Int) {
    OFFLINE(R.string.sync_error_offline),
    PLAN_REQUIRED(R.string.sync_error_plan),
    UNKNOWN(R.string.sync_error_unknown)
}

data class SyncState(val isSyncing: Boolean = false, val error: SyncError? = null)

/**
 * Two-way sync of the Business tables:
 * 1. push: upload every row changed on this phone (parents before children, for foreign keys);
 * 2. pull: download rows changed on the server since the last sync (from other phones too).
 * Conflicts follow "last upload wins"; a row still pending upload is never overwritten by a pull.
 */
@Singleton
class BusinessSyncer @Inject constructor(
    private val supabase: SupabaseClient,
    private val companyRepository: CompanyRepository,
    private val clientDao: ClientDao,
    private val ticketDao: TicketDao,
    private val appointmentDao: AppointmentDao,
    private val timeEntryDao: TimeEntryDao,
    private val serviceItemDao: ServiceItemDao,
    private val pricingSettingsDao: PricingSettingsDao,
    private val serviceOrderDao: ServiceOrderDao,
    private val companyProfileDao: CompanyProfileDao,
    private val files: BusinessFiles,
    private val reminders: AppointmentReminderScheduler
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Returns null on success (or when there is nothing to sync yet), otherwise the error. */
    suspend fun sync(): SyncError? = mutex.withLock {
        _state.update { it.copy(isSyncing = true) }
        val error = try {
            val companyId = companyRepository.ensureCompanyId()
            if (companyId != null) {
                push(companyId)
                pull(companyId)
                reminders.rescheduleAll(companyId)
                companyRepository.setLastSyncAt(companyId, System.currentTimeMillis())
            }
            null
        } catch (e: CancellationException) {
            _state.update { it.copy(isSyncing = false) }
            throw e
        } catch (e: PostgrestRestException) {
            // 42501 = row-level security refused the write: the company has no Business plan.
            if (e.code == "42501") SyncError.PLAN_REQUIRED else SyncError.UNKNOWN
        } catch (e: RestException) {
            // Storage refuses uploads of companies without the Business plan the same way.
            if (e.message?.contains("row-level security", ignoreCase = true) == true) SyncError.PLAN_REQUIRED else SyncError.UNKNOWN
        } catch (_: IOException) {
            SyncError.OFFLINE
        } catch (_: Exception) {
            SyncError.UNKNOWN
        }
        _state.value = SyncState(isSyncing = false, error = error)
        error
    }

    private suspend fun push(companyId: String) {
        pushCompany(companyId)
        upload("clients", clientDao.dirty(companyId), { it.toPush() }, { it.id to it.updatedAt }, clientDao::markClean)
        upload("tickets", ticketDao.dirty(companyId), { it.toPush() }, { it.id to it.updatedAt }, ticketDao::markClean)
        upload("appointments", appointmentDao.dirty(companyId), { it.toPush() }, { it.id to it.updatedAt }, appointmentDao::markClean)
        upload("time_entries", timeEntryDao.dirty(companyId), { it.toPush() }, { it.id to it.updatedAt }, timeEntryDao::markClean)
        upload("service_items", serviceItemDao.dirty(companyId), { it.toPush() }, { it.id to it.updatedAt }, serviceItemDao::markClean)
        upload("pricing_settings", pricingSettingsDao.dirty(companyId), { it.toPush() }, { it.id to it.updatedAt }, pricingSettingsDao::markClean)
        uploadOrderPdfs(companyId)
        // Quotes first: a service order may point to the quote it came from.
        val orders = serviceOrderDao.dirty(companyId).sortedBy { if (it.orderKind == OrderKind.QUOTE) 0 else 1 }
        upload("service_orders", orders, { it.toPush() }, { it.id to it.updatedAt }, serviceOrderDao::markClean)
    }

    /** Company name, contacts and brand; a new logo is uploaded first under a new name. */
    private suspend fun pushCompany(companyId: String) {
        var profile = companyProfileDao.get(companyId) ?: return
        if (profile.logoPendingUpload) {
            val file = files.logoFile(companyId)
            val path = if (file.exists()) {
                val newPath = BusinessFiles.logoStoragePath(companyId, System.currentTimeMillis())
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                supabase.storage.from(BusinessFiles.LOGO_BUCKET).upload(newPath, bytes) { upsert = true }
                newPath
            } else {
                null
            }
            val oldPath = profile.logoPath
            profile = profile.copy(logoPath = path, logoPendingUpload = false, isDirty = true, updatedAt = System.currentTimeMillis())
            companyProfileDao.upsert(profile)
            if (oldPath != null && oldPath != path) {
                runCatching { supabase.storage.from(BusinessFiles.LOGO_BUCKET).delete(oldPath) }
            }
        }
        if (profile.isDirty) {
            supabase.from("companies").update(profile.toPush()) { filter { eq("id", companyId) } }
            companyProfileDao.markClean(profile.id, profile.updatedAt)
        }
    }

    /** The company's copy ("via da empresa") of every PDF generated on this phone. */
    private suspend fun uploadOrderPdfs(companyId: String) {
        serviceOrderDao.pendingPdfUploads(companyId).forEach { order ->
            val file = files.orderPdfFile(order.id) ?: return@forEach
            val path = BusinessFiles.orderPdfStoragePath(companyId, order.id)
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            supabase.storage.from(BusinessFiles.ORDERS_BUCKET).upload(path, bytes) { upsert = true }
            serviceOrderDao.markPdfUploaded(order.id, path, order.updatedAt, System.currentTimeMillis())
        }
    }

    private suspend inline fun <E, reified P : Any> upload(
        table: String,
        rows: List<E>,
        toPush: (E) -> P,
        stamp: (E) -> Pair<String, Long>,
        markClean: suspend (String, Long) -> Unit
    ) {
        rows.chunked(PUSH_BATCH).forEach { batch ->
            supabase.from(table).upsert(batch.map(toPush)) { onConflict = "id" }
            batch.forEach { row -> stamp(row).let { (id, updatedAt) -> markClean(id, updatedAt) } }
        }
    }

    private suspend fun pull(companyId: String) {
        download<ClientRow>(companyId, "clients", { it.updatedAt }) { rows ->
            val dirty = clientDao.dirtyIds(rows.map { it.id }).toSet()
            clientDao.upsertAll(rows.filterNot { it.id in dirty }.map { it.toEntity() })
        }
        download<TicketRow>(companyId, "tickets", { it.updatedAt }) { rows ->
            val dirty = ticketDao.dirtyIds(rows.map { it.id }).toSet()
            ticketDao.upsertAll(rows.filterNot { it.id in dirty }.map { it.toEntity() })
        }
        download<AppointmentRow>(companyId, "appointments", { it.updatedAt }) { rows ->
            val dirty = appointmentDao.dirtyIds(rows.map { it.id }).toSet()
            appointmentDao.upsertAll(rows.filterNot { it.id in dirty }.map { it.toEntity() })
        }
        download<TimeEntryRow>(companyId, "time_entries", { it.updatedAt }) { rows ->
            val dirty = timeEntryDao.dirtyIds(rows.map { it.id }).toSet()
            timeEntryDao.upsertAll(rows.filterNot { it.id in dirty }.map { it.toEntity() })
        }
        download<ServiceItemRow>(companyId, "service_items", { it.updatedAt }) { rows ->
            val dirty = serviceItemDao.dirtyIds(rows.map { it.id }).toSet()
            serviceItemDao.upsertAll(rows.filterNot { it.id in dirty }.map { it.toEntity() })
        }
        download<PricingSettingsRow>(companyId, "pricing_settings", { it.updatedAt }) { rows ->
            val dirty = pricingSettingsDao.dirtyIds(rows.map { it.id }).toSet()
            pricingSettingsDao.upsertAll(rows.filterNot { it.id in dirty }.map { it.toEntity() })
        }
        download<ServiceOrderRow>(companyId, "service_orders", { it.updatedAt }) { rows ->
            val dirty = serviceOrderDao.dirtyIds(rows.map { it.id }).toSet()
            serviceOrderDao.upsertAll(rows.filterNot { it.id in dirty }.map { it.toEntity() })
        }
        pullCompany(companyId)
    }

    /** One row, always downloaded; the logo file is fetched only when its name changed. */
    private suspend fun pullCompany(companyId: String) {
        val row = supabase.from("companies").select { filter { eq("id", companyId) } }.decodeSingleOrNull<CompanyRow>() ?: return
        val local = companyProfileDao.get(companyId)
        if (local != null && (local.isDirty || local.logoPendingUpload)) return
        val logoPath = row.logoPath
        if (logoPath == null) {
            files.deleteLogo(companyId)
        } else if (logoPath != local?.logoPath || !files.logoFile(companyId).exists()) {
            val bytes = supabase.storage.from(BusinessFiles.LOGO_BUCKET).downloadAuthenticated(logoPath)
            withContext(Dispatchers.IO) { files.writeLogoBytes(companyId, bytes) }
        }
        companyProfileDao.upsert(row.toEntity())
    }

    /**
     * Downloads rows changed after the saved cursor, page by page. The cursor is moved back by
     * [CURSOR_OVERLAP_MILLIS] because a slow transaction may commit with an older timestamp;
     * re-applying a row that is already here is harmless.
     */
    private suspend inline fun <reified R : Any> download(
        companyId: String,
        table: String,
        updatedAt: (R) -> String,
        apply: (List<R>) -> Unit
    ) {
        val cursor = companyRepository.pullCursor(companyId, table)
        val since = cursor?.let { IsoTime.parse(it) }?.let { IsoTime.format(it - CURSOR_OVERLAP_MILLIS) }
            ?: "1970-01-01T00:00:00Z"
        var offset = 0L
        var newest = cursor
        while (true) {
            val page = supabase.from(table).select {
                filter {
                    eq("company_id", companyId)
                    gt("updated_at", since)
                }
                order("updated_at", Order.ASCENDING)
                order("id", Order.ASCENDING)
                range(offset, offset + PULL_PAGE - 1)
            }.decodeList<R>()
            if (page.isNotEmpty()) {
                apply(page)
                val last = updatedAt(page.last())
                if (newest == null || (IsoTime.parse(last) ?: 0) > (IsoTime.parse(newest) ?: 0)) newest = last
            }
            if (page.size < PULL_PAGE) break
            offset += PULL_PAGE
        }
        newest?.let { companyRepository.setPullCursor(companyId, table, it) }
    }

    private companion object {
        const val PUSH_BATCH = 200
        const val PULL_PAGE = 500L
        const val CURSOR_OVERLAP_MILLIS = 120_000L
    }
}
