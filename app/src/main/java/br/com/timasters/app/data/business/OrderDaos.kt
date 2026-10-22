package br.com.timasters.app.data.business

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ServiceItemDao {
    @Query("SELECT * FROM service_items WHERE companyId = :companyId AND deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAll(companyId: String): Flow<List<ServiceItemEntity>>

    @Query("SELECT * FROM service_items WHERE id = :id")
    suspend fun get(id: String): ServiceItemEntity?

    @Upsert
    suspend fun upsert(entity: ServiceItemEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ServiceItemEntity>)

    @Query("SELECT * FROM service_items WHERE companyId = :companyId AND isDirty = 1")
    suspend fun dirty(companyId: String): List<ServiceItemEntity>

    @Query("SELECT id FROM service_items WHERE id IN (:ids) AND isDirty = 1")
    suspend fun dirtyIds(ids: List<String>): List<String>

    @Query("UPDATE service_items SET isDirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM service_items WHERE companyId = :companyId AND isDirty = 1")
    fun observeDirtyCount(companyId: String): Flow<Int>
}

@Dao
interface PricingSettingsDao {
    @Query("SELECT * FROM pricing_settings WHERE id = :companyId")
    fun observe(companyId: String): Flow<PricingSettingsEntity?>

    @Query("SELECT * FROM pricing_settings WHERE id = :companyId")
    suspend fun get(companyId: String): PricingSettingsEntity?

    @Upsert
    suspend fun upsert(entity: PricingSettingsEntity)

    @Upsert
    suspend fun upsertAll(entities: List<PricingSettingsEntity>)

    @Query("SELECT * FROM pricing_settings WHERE companyId = :companyId AND isDirty = 1")
    suspend fun dirty(companyId: String): List<PricingSettingsEntity>

    @Query("SELECT id FROM pricing_settings WHERE id IN (:ids) AND isDirty = 1")
    suspend fun dirtyIds(ids: List<String>): List<String>

    @Query("UPDATE pricing_settings SET isDirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM pricing_settings WHERE companyId = :companyId AND isDirty = 1")
    fun observeDirtyCount(companyId: String): Flow<Int>
}

@Dao
interface ServiceOrderDao {
    @Query(
        """SELECT o.*, c.name AS clientName, c.phone AS clientPhone, t.number AS ticketNumber, t.title AS ticketTitle
           FROM service_orders o JOIN clients c ON c.id = o.clientId LEFT JOIN tickets t ON t.id = o.ticketId
           WHERE o.companyId = :companyId AND o.deletedAt IS NULL AND c.deletedAt IS NULL
           ORDER BY o.createdAt DESC"""
    )
    fun observeAll(companyId: String): Flow<List<ServiceOrderWithClient>>

    @Query(
        """SELECT o.*, c.name AS clientName, c.phone AS clientPhone, t.number AS ticketNumber, t.title AS ticketTitle
           FROM service_orders o JOIN clients c ON c.id = o.clientId LEFT JOIN tickets t ON t.id = o.ticketId
           WHERE o.clientId = :clientId AND o.deletedAt IS NULL ORDER BY o.createdAt DESC"""
    )
    fun observeByClient(clientId: String): Flow<List<ServiceOrderWithClient>>

    @Query(
        """SELECT o.*, c.name AS clientName, c.phone AS clientPhone, t.number AS ticketNumber, t.title AS ticketTitle
           FROM service_orders o JOIN clients c ON c.id = o.clientId LEFT JOIN tickets t ON t.id = o.ticketId
           WHERE o.ticketId = :ticketId AND o.deletedAt IS NULL ORDER BY o.createdAt DESC"""
    )
    fun observeByTicket(ticketId: String): Flow<List<ServiceOrderWithClient>>

    @Query(
        """SELECT o.*, c.name AS clientName, c.phone AS clientPhone, t.number AS ticketNumber, t.title AS ticketTitle
           FROM service_orders o JOIN clients c ON c.id = o.clientId LEFT JOIN tickets t ON t.id = o.ticketId
           WHERE o.id = :id"""
    )
    fun observe(id: String): Flow<ServiceOrderWithClient?>

    @Query("SELECT * FROM service_orders WHERE id = :id")
    suspend fun get(id: String): ServiceOrderEntity?

    /** Service orders already created from this quote. */
    @Query("SELECT * FROM service_orders WHERE sourceQuoteId = :quoteId AND deletedAt IS NULL")
    suspend fun fromQuote(quoteId: String): List<ServiceOrderEntity>

    @Query("SELECT * FROM service_orders WHERE clientId = :clientId AND deletedAt IS NULL")
    suspend fun activeByClient(clientId: String): List<ServiceOrderEntity>

    @Query("SELECT * FROM service_orders WHERE companyId = :companyId AND pdfPendingUpload = 1 AND deletedAt IS NULL")
    suspend fun pendingPdfUploads(companyId: String): List<ServiceOrderEntity>

    @Upsert
    suspend fun upsert(entity: ServiceOrderEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ServiceOrderEntity>)

    @Query("SELECT * FROM service_orders WHERE companyId = :companyId AND isDirty = 1")
    suspend fun dirty(companyId: String): List<ServiceOrderEntity>

    @Query("SELECT id FROM service_orders WHERE id IN (:ids) AND (isDirty = 1 OR pdfPendingUpload = 1)")
    suspend fun dirtyIds(ids: List<String>): List<String>

    @Query("UPDATE service_orders SET isDirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    /** After the upload: records where the company copy is, unless the PDF was generated again meanwhile. */
    @Query(
        """UPDATE service_orders SET pdfPath = :path, pdfPendingUpload = 0, isDirty = 1, updatedAt = :now
           WHERE id = :id AND updatedAt = :updatedAt"""
    )
    suspend fun markPdfUploaded(id: String, path: String, updatedAt: Long, now: Long): Int

    @Query("SELECT COUNT(*) FROM service_orders WHERE companyId = :companyId AND (isDirty = 1 OR pdfPendingUpload = 1)")
    fun observeDirtyCount(companyId: String): Flow<Int>
}

@Dao
interface CompanyProfileDao {
    @Query("SELECT * FROM company_profile WHERE id = :companyId")
    fun observe(companyId: String): Flow<CompanyProfileEntity?>

    @Query("SELECT * FROM company_profile WHERE id = :companyId")
    suspend fun get(companyId: String): CompanyProfileEntity?

    @Upsert
    suspend fun upsert(entity: CompanyProfileEntity)

    @Query("UPDATE company_profile SET isDirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM company_profile WHERE id = :companyId AND (isDirty = 1 OR logoPendingUpload = 1)")
    fun observeDirtyCount(companyId: String): Flow<Int>
}
