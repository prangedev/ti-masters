package br.com.timasters.app.data.tools

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

/** Encodes the Wi-Fi QR with the same settings as the app and reads it back, like a phone camera. */
class WifiQrRoundTripTest {

    private fun roundTrip(content: String): String {
        val size = 400
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun wifiQr_decodesBackToTheSameText() {
        listOf(
            PasswordTools.wifiQr("Cliente_Teste", "P2\$F3gCibvjHjG4q"),
            PasswordTools.wifiQr("Café da Esquina", "çãé;:\\\"senha"),
            PasswordTools.wifiQr("Visitantes", "", hidden = true)
        ).forEach { content -> assertEquals(content, roundTrip(content)) }
    }
}
