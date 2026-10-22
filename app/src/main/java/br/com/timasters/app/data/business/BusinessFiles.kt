package br.com.timasters.app.data.business

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files of the Business module kept in the app's private storage:
 * - the company logo (one per company, shrunk to [LOGO_MAX_SIDE] px, PNG);
 * - the PDF of each quote / service order (also shared with clients through a FileProvider);
 * - photos (JPEG) and the client's signature (PNG) of each document, under "order-media/<order>".
 * Server copies use the same names under "<company_id>/" in the Storage buckets.
 */
@Singleton
class BusinessFiles @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun logoFile(companyId: String): File = File(context.filesDir, "branding/$companyId/logo.png")

    /** Folder of one document; it holds a single PDF named for the client (see [newOrderPdfFile]). */
    private fun orderPdfDir(orderId: String): File = File(context.filesDir, "orders/$orderId")

    /** The PDF generated last for this document, if any. */
    fun orderPdfFile(orderId: String): File? =
        orderPdfDir(orderId).listFiles { file -> file.extension == "pdf" }?.firstOrNull()

    /** Replaces the previous PDF; [fileName] is what the client sees ("Orcamento-12-Empresa.pdf"). */
    fun newOrderPdfFile(orderId: String, fileName: String): File {
        val dir = orderPdfDir(orderId)
        dir.deleteRecursively()
        dir.mkdirs()
        return File(dir, fileName)
    }

    fun deleteOrderPdf(orderId: String) {
        orderPdfDir(orderId).deleteRecursively()
    }

    fun loadLogo(companyId: String): Bitmap? =
        logoFile(companyId).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Copies the picked image as the new logo. Returns false if it is not a readable image. */
    suspend fun importLogo(companyId: String, source: Uri): Boolean = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(source) ?: return@withContext false
        writeLogo(companyId, bitmap)
        true
    }

    fun writeLogoBytes(companyId: String, bytes: ByteArray) {
        val file = logoFile(companyId)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    fun deleteLogo(companyId: String) {
        logoFile(companyId).delete()
    }

    private fun writeLogo(companyId: String, bitmap: Bitmap) {
        val file = logoFile(companyId)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun decodeScaled(source: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // With inJustDecodeBounds the decoder returns null by design; only the sizes are read.
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LOGO_MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = LOGO_MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        return if (scale >= 1f) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        }
    }

    // Photos and signatures

    private fun mediaDir(orderId: String): File = File(context.filesDir, "$MEDIA_DIR/$orderId")

    fun photoFile(orderId: String, photoId: String): File = File(mediaDir(orderId), "$photoId.jpg")

    /** Copies a picked or captured image as a JPEG of at most [PHOTO_MAX_SIDE] px, upright. */
    suspend fun importPhoto(orderId: String, photoId: String, source: Uri): Boolean = withContext(Dispatchers.IO) {
        val bitmap = runCatching { decodeUpright(source, PHOTO_MAX_SIDE) }.getOrNull() ?: return@withContext false
        val file = photoFile(orderId, photoId)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it) }
        true
    }

    fun writePhotoBytes(orderId: String, photoId: String, bytes: ByteArray) {
        val file = photoFile(orderId, photoId)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    fun deletePhoto(orderId: String, photoId: String) {
        photoFile(orderId, photoId).delete()
    }

    /** A signature drawn on this phone and not uploaded yet. */
    fun localSignatureFile(orderId: String): File = File(mediaDir(orderId), LOCAL_SIGNATURE)

    /** The downloaded / uploaded signature is named like its file on the server. */
    fun syncedSignatureFile(orderId: String, storagePath: String): File =
        File(mediaDir(orderId), storagePath.substringAfterLast('/'))

    /** The signature to show and print for this document, if there is one on this phone. */
    fun signatureFile(order: ServiceOrderEntity): File? {
        val file = if (order.signaturePendingUpload) {
            localSignatureFile(order.id)
        } else {
            order.signaturePath?.let { syncedSignatureFile(order.id, it) }
        }
        return file?.takeIf { it.exists() }
    }

    /** Saves the drawn signature on a white background (it is printed on paper-like PDFs). */
    fun writeLocalSignature(orderId: String, signature: Bitmap) {
        val file = localSignatureFile(orderId)
        file.parentFile?.mkdirs()
        val opaque = Bitmap.createBitmap(signature.width, signature.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(signature, 0f, 0f, null)
        }
        file.outputStream().use { opaque.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Removes every signature file of the document except [keep]. */
    fun clearSignatures(orderId: String, keep: File? = null) {
        mediaDir(orderId).listFiles { file -> file.name.startsWith("signature") && file != keep }?.forEach { it.delete() }
    }

    fun deleteOrderMedia(orderId: String) {
        mediaDir(orderId).deleteRecursively()
    }

    /** Temporary file the camera app writes into (shared through the FileProvider). */
    fun newCameraFile(): File = File(context.cacheDir, "camera/capture-${System.currentTimeMillis()}.jpg").also {
        it.parentFile?.mkdirs()
    }

    /** Content URI another app (camera, viewer) can write or read, through the app's FileProvider. */
    fun shareUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Smaller copy for thumbnails and PDFs; null when the file is missing or unreadable. */
    fun loadScaled(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        return if (scale >= 1f) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        }
    }

    /** Decodes respecting the camera's rotation (EXIF), shrunk to at most [maxSide] px. */
    private fun decodeUpright(source: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source)) { decoder, info, _ ->
                // Software memory: the bitmap is compressed and drawn into PDFs.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = resolver.openInputStream(source)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        if (rotation == 0f && scale >= 1f) return decoded
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    companion object {
        const val LOGO_BUCKET = "logos"
        const val ORDERS_BUCKET = "service-orders"
        const val MEDIA_BUCKET = "order-media"
        const val MEDIA_DIR = "order-media"
        private const val LOGO_MAX_SIDE = 512
        private const val PHOTO_MAX_SIDE = 1600
        private const val PHOTO_QUALITY = 82
        private const val LOCAL_SIGNATURE = "signature-local.png"

        /** A new name for every logo, so other phones notice the change and download it. */
        fun logoStoragePath(companyId: String, version: Long) = "$companyId/logo-$version.png"
        fun orderPdfStoragePath(companyId: String, orderId: String) = "$companyId/$orderId.pdf"
        fun photoStoragePath(companyId: String, orderId: String, photoId: String) = "$companyId/$orderId/$photoId.jpg"
        fun signatureStoragePath(companyId: String, orderId: String, version: Long) = "$companyId/$orderId/signature-$version.png"

        /** "Orçamento nº 12 – Ação Redes" → "Orcamento-12-Acao-Redes.pdf" (safe for every app). */
        fun pdfFileName(vararg parts: String): String {
            val ascii = Normalizer.normalize(parts.filter { it.isNotBlank() }.joinToString("-"), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^A-Za-z0-9]+"), "-")
                .trim('-')
                .take(80)
            return "${ascii.ifEmpty { "documento" }}.pdf"
        }
    }
}
