package ai.zcode.remote.ui.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.EnumMap

class QrCodeImageAnalyzer(
    private val onQrCodeDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(com.google.zxing.BarcodeFormat.QR_CODE)
        hints[DecodeHintType.CHARACTER_SET] = "utf-8"
        hints[DecodeHintType.TRY_HARDER] = true
        setHints(hints)
    }

    private var isAnalyzing = true

    fun resume() {
        isAnalyzing = true
    }

    fun pause() {
        isAnalyzing = false
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!isAnalyzing) {
            imageProxy.close()
            return
        }

        try {
            val buffer: ByteBuffer = imageProxy.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val width = imageProxy.width
            val height = imageProxy.height

            val source = PlanarYUVLuminanceSource(
                data,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))

            val result = reader.decodeWithState(bitmap)
            if (result != null && !result.text.isNullOrBlank()) {
                isAnalyzing = false
                onQrCodeDetected(result.text)
            }
        } catch (e: Exception) {
            // 解码未命中属正常帧
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }
}
