package br.com.timasters.app.data.business

import android.graphics.Bitmap
import android.net.Uri
import br.com.timasters.app.data.business.sync.BusinessSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class AddPhotoResult { ADDED, NOT_AN_IMAGE, LIMIT_REACHED }

/** Before/after photos and the client's signature of quotes and service orders (offline first). */
@Singleton
class OrderMediaRepository @Inject constructor(
    private val photoDao: OrderPhotoDao,
    private val serviceOrderDao: ServiceOrderDao,
    private val files: BusinessFiles,
    private val syncScheduler: BusinessSyncScheduler
) {
    private fun now() = System.currentTimeMillis()

    fun photos(orderId: String): Flow<List<OrderPhotoEntity>> = photoDao.observeByOrder(orderId)

    fun photoFile(photo: OrderPhotoEntity): File = files.photoFile(photo.orderId, photo.id)

    suspend fun addPhoto(orderId: String, stage: PhotoStage, source: Uri): AddPhotoResult {
        val order = serviceOrderDao.get(orderId) ?: return AddPhotoResult.NOT_AN_IMAGE
        if (photoDao.activeByOrder(orderId).count { it.photoStage == stage } >= MAX_PHOTOS_PER_STAGE) {
            return AddPhotoResult.LIMIT_REACHED
        }
        val id = UUID.randomUUID().toString()
        if (!files.importPhoto(orderId, id, source)) return AddPhotoResult.NOT_AN_IMAGE
        val time = now()
        photoDao.upsert(
            OrderPhotoEntity(
                id = id,
                companyId = order.companyId,
                orderId = orderId,
                stage = stage.serverId,
                pendingUpload = true,
                createdAt = time,
                updatedAt = time
            )
        )
        syncScheduler.requestSync()
        return AddPhotoResult.ADDED
    }

    suspend fun deletePhoto(id: String) {
        val existing = photoDao.get(id) ?: return
        val time = now()
        photoDao.upsert(existing.copy(deletedAt = time, updatedAt = time, isDirty = true, pendingUpload = false))
        files.deletePhoto(existing.orderId, existing.id)
        syncScheduler.requestSync()
    }

    /** Called when the document itself is deleted. */
    suspend fun deleteAllOfOrder(orderId: String) {
        photoDao.activeByOrder(orderId).forEach { deletePhoto(it.id) }
        files.deleteOrderMedia(orderId)
    }

    fun signatureFile(order: ServiceOrderEntity): File? = files.signatureFile(order)

    suspend fun saveSignature(orderId: String, signature: Bitmap, signer: String) {
        val existing = serviceOrderDao.get(orderId) ?: return
        withContext(Dispatchers.IO) { files.writeLocalSignature(orderId, signature) }
        val time = now()
        serviceOrderDao.upsert(
            existing.copy(
                signedBy = signer.trim().take(160).ifEmpty { null },
                signedAt = time,
                signaturePendingUpload = true,
                updatedAt = time,
                isDirty = true
            )
        )
        syncScheduler.requestSync()
    }

    suspend fun removeSignature(orderId: String) {
        val existing = serviceOrderDao.get(orderId) ?: return
        files.localSignatureFile(orderId).delete()
        val time = now()
        serviceOrderDao.upsert(
            existing.copy(signedBy = null, signedAt = null, signaturePendingUpload = true, updatedAt = time, isDirty = true)
        )
        syncScheduler.requestSync()
    }

    companion object {
        const val MAX_PHOTOS_PER_STAGE = 8
    }
}
