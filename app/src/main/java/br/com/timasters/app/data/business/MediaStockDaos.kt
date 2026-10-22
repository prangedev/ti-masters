package br.com.timasters.app.data.business

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderPhotoDao {
    @Query("SELECT * FROM order_photos WHERE orderId = :orderId AND deletedAt IS NULL ORDER BY createdAt")
    fun observeByOrder(orderId: String): Flow<List<OrderPhotoEntity>>

    @Query("SELECT * FROM order_photos WHERE orderId = :orderId AND deletedAt IS NULL ORDER BY createdAt")
    suspend fun activeByOrder(orderId: String): List<OrderPhotoEntity>

    @Query("SELECT * FROM order_photos WHERE id = :id")
    suspend fun get(id: String): OrderPhotoEntity?

    @Upsert
    suspend fun upsert(entity: OrderPhotoEntity)

    @Upsert
    suspend fun upsertAll(entities: List<OrderPhotoEntity>)

    @Query("SELECT * FROM order_photos WHERE companyId = :companyId AND pendingUpload = 1 AND deletedAt IS NULL")
    suspend fun pendingUploads(companyId: String): List<OrderPhotoEntity>

    /** Downloaded rows whose JPEG may still be missing on this phone. */
    @Query("SELECT * FROM order_photos WHERE companyId = :companyId AND deletedAt IS NULL AND storagePath IS NOT NULL AND pendingUpload = 0")
    suspend fun uploaded(companyId: String): List<OrderPhotoEntity>

    @Query("SELECT * FROM order_photos WHERE companyId = :companyId AND isDirty = 1")
    suspend fun dirty(companyId: String): List<OrderPhotoEntity>

    @Query("SELECT id FROM order_photos WHERE id IN (:ids) AND (isDirty = 1 OR pendingUpload = 1)")
    suspend fun dirtyIds(ids: List<String>): List<String>

    @Query("UPDATE order_photos SET isDirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query(
        """UPDATE order_photos SET storagePath = :path, pendingUpload = 0, isDirty = 1, updatedAt = :now
           WHERE id = :id AND updatedAt = :updatedAt"""
    )
    suspend fun markUploaded(id: String, path: String, updatedAt: Long, now: Long): Int

    @Query("SELECT COUNT(*) FROM order_photos WHERE companyId = :companyId AND (isDirty = 1 OR pendingUpload = 1)")
    fun observeDirtyCount(companyId: String): Flow<Int>
}

@Dao
interface StockMovementDao {
    @Query(
        """SELECT i.*, COALESCE((SELECT SUM(m.quantity) FROM stock_movements m
                                  WHERE m.itemId = i.id AND m.deletedAt IS NULL), 0) AS quantity
           FROM service_items i
           WHERE i.companyId = :companyId AND i.deletedAt IS NULL AND i.trackStock = 1
           ORDER BY i.name COLLATE NOCASE"""
    )
    fun observeLevels(companyId: String): Flow<List<StockLevel>>

    @Query(
        """SELECT i.*, COALESCE((SELECT SUM(m.quantity) FROM stock_movements m
                                  WHERE m.itemId = i.id AND m.deletedAt IS NULL), 0) AS quantity
           FROM service_items i WHERE i.id = :itemId"""
    )
    fun observeLevel(itemId: String): Flow<StockLevel?>

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM stock_movements WHERE itemId = :itemId AND deletedAt IS NULL")
    suspend fun quantity(itemId: String): Double

    @Query(
        """SELECT m.*, o.number AS orderNumber FROM stock_movements m LEFT JOIN service_orders o ON o.id = m.orderId
           WHERE m.itemId = :itemId AND m.deletedAt IS NULL ORDER BY m.createdAt DESC"""
    )
    fun observeHistory(itemId: String): Flow<List<StockMovementWithOrder>>

    @Query("SELECT * FROM stock_movements WHERE orderId = :orderId AND kind = 'os'")
    suspend fun ofOrder(orderId: String): List<StockMovementEntity>

    @Query("SELECT * FROM stock_movements WHERE id = :id")
    suspend fun get(id: String): StockMovementEntity?

    @Upsert
    suspend fun upsert(entity: StockMovementEntity)

    @Upsert
    suspend fun upsertAll(entities: List<StockMovementEntity>)

    @Query("SELECT * FROM stock_movements WHERE companyId = :companyId AND isDirty = 1")
    suspend fun dirty(companyId: String): List<StockMovementEntity>

    @Query("SELECT id FROM stock_movements WHERE id IN (:ids) AND isDirty = 1")
    suspend fun dirtyIds(ids: List<String>): List<String>

    @Query("UPDATE stock_movements SET isDirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM stock_movements WHERE companyId = :companyId AND isDirty = 1")
    fun observeDirtyCount(companyId: String): Flow<Int>
}
