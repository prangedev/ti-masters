package br.com.timasters.app.data.business

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
 * - the PDF of each quote / service order (also shared with clients through a FileProvider).
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

    companion object {
        const val LOGO_BUCKET = "logos"
        const val ORDERS_BUCKET = "service-orders"
        private const val LOGO_MAX_SIDE = 512

        /** A new name for every logo, so other phones notice the change and download it. */
        fun logoStoragePath(companyId: String, version: Long) = "$companyId/logo-$version.png"
        fun orderPdfStoragePath(companyId: String, orderId: String) = "$companyId/$orderId.pdf"

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
