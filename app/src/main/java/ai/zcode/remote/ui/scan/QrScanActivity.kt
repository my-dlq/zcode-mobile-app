package ai.zcode.remote.ui.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import ai.zcode.remote.R
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.databinding.ActivityQrScanBinding
import ai.zcode.remote.ui.remote.RemoteControlActivity
import ai.zcode.remote.utils.ToastUtils
import ai.zcode.remote.utils.UrlParser
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScanBinding
    private var cameraExecutor: ExecutorService? = null
    private var camera: Camera? = null
    private var isTorchOn = false
    private var qrAnalyzer: QrCodeImageAnalyzer? = null
    private var hasHandledScanResult = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            ToastUtils.show(this, getString(R.string.camera_permission_required))
            finish()
        }
    }

    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            decodeImageUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        initListeners()
        checkCameraPermissionAndStart()
    }

    private fun initListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.layoutFlashlight.setOnClickListener {
            toggleTorch()
        }

        binding.layoutGallery.setOnClickListener {
            selectImageLauncher.launch("image/*")
        }
    }

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                qrAnalyzer = QrCodeImageAnalyzer { resultText ->
                    runOnUiThread {
                        handleScanResult(resultText)
                    }
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        qrAnalyzer?.let { analyzer ->
                            cameraExecutor?.let { executor ->
                                it.setAnalyzer(executor, analyzer)
                            }
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                ToastUtils.show(this, "相机初始化失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            binding.ivFlashlight.setImageResource(
                if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            )
            binding.tvFlashlight.text = getString(
                if (isTorchOn) R.string.scan_torch_off else R.string.scan_torch_on
            )
        } else {
            ToastUtils.show(this, "当前设备不支持手电筒")
        }
    }

    private fun decodeImageUri(uri: Uri) {
        val executor = cameraExecutor ?: return
        executor.execute {
            var bitmap: Bitmap? = null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IllegalArgumentException("图片尺寸无效")
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, 2048)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                contentResolver.openInputStream(uri)?.use {
                    bitmap = BitmapFactory.decodeStream(it, null, options)
                }
                val decoded = bitmap ?: throw IllegalArgumentException("图片解码失败")
                val width = decoded.width
                val height = decoded.height
                val pixels = IntArray(width * height)
                decoded.getPixels(pixels, 0, width, 0, 0, width, height)

                val source = RGBLuminanceSource(width, height, pixels)
                val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (result != null && !result.text.isNullOrBlank()) {
                            handleScanResult(result.text)
                        } else {
                            ToastUtils.show(this, "未在图片中识别到二维码")
                        }
                    }
                }
            } catch (_: OutOfMemoryError) {
                runOnUiThread { if (!isFinishing && !isDestroyed) ToastUtils.show(this, "图片过大，无法解析") }
            } catch (_: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) ToastUtils.show(this, "图片解析失败: 未识别到有效二维码") }
            } finally {
                bitmap?.recycle()
            }
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (width / sample > maxSide || height / sample > maxSide) sample *= 2
        return sample
    }

    private fun handleScanResult(rawResult: String) {
        if (hasHandledScanResult) return
        hasHandledScanResult = true

        vibrate()
        val parsed = UrlParser.parse(rawResult)
        if (!UrlParser.isValidRemoteConnection(parsed)) {
            hasHandledScanResult = false
            ToastUtils.show(this, getString(R.string.error_invalid_url))
            return
        }
        val connection = RemoteConnection(
            name = parsed.suggestedName,
            url = parsed.originalUrl,
            mid = parsed.mid,
            sid = parsed.sid
        )
        // 扫码页会直接启动远程页，不经过 MainActivity 的结果回调，
        // 因此必须在这里持久化，否则只能跳转而不会出现在设备列表。
        ConnectionRepository.getInstance(this).saveConnection(connection)

        val resultIntent = Intent().apply {
            putExtra(EXTRA_SCANNED_URL, connection.url)
            putExtra(EXTRA_SCANNED_NAME, connection.name)
        }
        setResult(RESULT_OK, resultIntent)

        RemoteControlActivity.start(this, connection.url, connection.name)
        finish()
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(80)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
    }

    companion object {
        const val EXTRA_SCANNED_URL = "extra_scanned_url"
        const val EXTRA_SCANNED_NAME = "extra_scanned_name"

        fun start(context: Context) {
            val intent = Intent(context, QrScanActivity::class.java)
            context.startActivity(intent)
        }
    }
}
