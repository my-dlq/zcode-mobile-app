
package ai.zcode.remote.ui.scan

import ai.zcode.remote.ui.BaseActivity

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
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrScanActivity : BaseActivity() {

    private lateinit var binding: ActivityQrScanBinding
    private var cameraExecutor: ExecutorService? = null
    private var camera: Camera? = null
    private var isTorchOn = false
    private var qrAnalyzer: QrCodeImageAnalyzer? = null
    private var scanState = QrScanState()
    private var pendingGalleryUri: Uri? = null
    private var pendingGalleryResult: GalleryResult? = null

    private data class GalleryResult(val token: Long, val text: String?, val errorRes: Int?)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isFinishing || isDestroyed || scanState.isClosed) return@registerForActivityResult
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
        val token = scanState.galleryToken ?: return@registerForActivityResult
        if (isFinishing || isDestroyed || scanState.isClosed) return@registerForActivityResult
        if (uri != null) {
            pendingGalleryUri = uri
            decodeImageUri(uri, token)
        } else {
            pendingGalleryUri = null
            pendingGalleryResult = null
            scanState.finishGallery(token)
            syncAnalyzer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // ActivityResultRegistry 会在 STARTED 时补发相册结果，须在此之前恢复相册占用。
        // 只恢复阶段和 URI，不恢复旧相机 epoch；旧实例回调仍由其 close 状态拦截。
        pendingGalleryUri = savedInstanceState?.getString(STATE_GALLERY_URI)?.let(Uri::parse)
        scanState = QrScanState(
            galleryPending = savedInstanceState?.getBoolean(STATE_GALLERY_PENDING) == true ||
                pendingGalleryUri != null
        )
        initListeners()
        checkCameraPermissionAndStart()
        pendingGalleryUri?.let { uri ->
            scanState.galleryToken?.let { token -> decodeImageUri(uri, token) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_GALLERY_PENDING, scanState.galleryToken != null)
        outState.putString(STATE_GALLERY_URI, pendingGalleryUri?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun initListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.layoutFlashlight.setOnClickListener {
            toggleTorch()
        }

        binding.layoutGallery.setOnClickListener {
            val token = scanState.beginGallery() ?: return@setOnClickListener
            syncAnalyzer()
            try {
                selectImageLauncher.launch("image/*")
            } catch (_: Exception) {
                deliverGalleryResult(GalleryResult(token, null, R.string.toast_qr_parse_failed))
            }
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
            if (isFinishing || isDestroyed || scanState.isClosed) return@addListener
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                qrAnalyzer?.pause()
                qrAnalyzer = QrCodeImageAnalyzer { resultText, epoch ->
                    runOnUiThread {
                        handleScanResult(resultText, epoch)
                    }
                }
                syncAnalyzer()

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
                ToastUtils.show(this, getString(R.string.toast_camera_init_failed, e.message ?: ""))
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
            ToastUtils.show(this, getString(R.string.toast_torch_unsupported))
        }
    }

    private fun decodeImageUri(uri: Uri, token: Long) {
        val executor = cameraExecutor
        if (executor == null) {
            deliverGalleryResult(GalleryResult(token, null, R.string.toast_qr_parse_failed))
            return
        }
        try {
            executor.execute {
                var bitmap: Bitmap? = null
                var text: String? = null
                var errorRes: Int? = null
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        throw IllegalArgumentException(getString(R.string.toast_image_size_invalid))
                    }

                    val options = BitmapFactory.Options().apply {
                        inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, 2048)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    contentResolver.openInputStream(uri)?.use {
                        bitmap = BitmapFactory.decodeStream(it, null, options)
                    }
                    val decoded = bitmap ?: throw IllegalArgumentException(getString(R.string.toast_image_decode_failed))
                    val width = decoded.width
                    val height = decoded.height
                    val pixels = IntArray(width * height)
                    decoded.getPixels(pixels, 0, width, 0, 0, width, height)

                    val source = RGBLuminanceSource(width, height, pixels)
                    text = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))?.text
                    if (text.isNullOrBlank()) errorRes = R.string.toast_no_qr_found
                } catch (_: OutOfMemoryError) {
                    errorRes = R.string.toast_image_too_large
                } catch (_: NotFoundException) {
                    errorRes = R.string.toast_no_qr_found
                } catch (_: Exception) {
                    errorRes = R.string.toast_qr_parse_failed
                } finally {
                    bitmap?.recycle()
                }
                val result = GalleryResult(token, text, errorRes)
                runOnUiThread { deliverGalleryResult(result) }
            }
        } catch (_: Exception) {
            // Activity 退出时 executor 可能已关闭，仍统一走阶段检查，不能留下相册占用。
            deliverGalleryResult(GalleryResult(token, null, R.string.toast_qr_parse_failed))
        }
    }

    private fun deliverGalleryResult(result: GalleryResult) {
        if (isFinishing || isDestroyed || !scanState.isGalleryActive(result.token)) return
        // GetContent 回调通常早于 onResume；解码完成也不能在后台保存、跳转或恢复相机。
        if (!scanState.isResumed) {
            pendingGalleryResult = result
            return
        }
        pendingGalleryResult = null
        pendingGalleryUri = null
        if (result.errorRes != null) {
            ToastUtils.show(this, getString(result.errorRes))
            scanState.finishGallery(result.token)
            syncAnalyzer()
        } else {
            handleScanResult(result.text.orEmpty(), result.token, fromGallery = true)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (width / sample > maxSide || height / sample > maxSide) sample *= 2
        return sample
    }

    private fun handleScanResult(rawResult: String, epoch: Long, fromGallery: Boolean = false) {
        if (isFinishing || isDestroyed || !scanState.acceptsResult(epoch, fromGallery)) return

        val parsed = UrlParser.parse(rawResult)
        if (!UrlParser.isValidRemoteConnection(parsed)) {
            ToastUtils.show(this, getString(R.string.error_invalid_url))
            if (fromGallery) scanState.finishGallery(epoch) else scanState.retryCamera(epoch)
            syncAnalyzer()
            return
        }
        // 两种来源在主线程共用一次性门闩，保存和启动必须同时受它保护。
        if (!scanState.completeResult(epoch, fromGallery)) return
        syncAnalyzer()
        vibrate()
        val connection = RemoteConnection(
            name = parsed.resolveSuggestedName(this),
            url = parsed.originalUrl,
            mid = parsed.mid,
            sid = parsed.sid
        )
        // 扫码页会直接启动远程页，不经过 MainActivity 的结果回调，
        // 因此必须在这里持久化，否则只能跳转而不会出现在设备列表。
        val saved = ConnectionRepository.getInstance(this).saveConnection(connection)

        val resultIntent = Intent().apply {
            putExtra(EXTRA_SCANNED_URL, connection.url)
            putExtra(EXTRA_SCANNED_NAME, connection.name)
        }
        setResult(RESULT_OK, resultIntent)

        RemoteControlActivity.start(this, saved.url, saved.name, connectionId = saved.id)
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

    private fun syncAnalyzer() {
        if (scanState.cameraEnabled && !isFinishing && !isDestroyed) {
            qrAnalyzer?.resume(scanState.cameraEpoch)
        } else {
            qrAnalyzer?.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        scanState.resume()
        pendingGalleryResult?.let { deliverGalleryResult(it) }
        syncAnalyzer()
    }

    override fun onPause() {
        scanState.pause()
        syncAnalyzer()
        super.onPause()
    }

    override fun finish() {
        scanState.close()
        pendingGalleryUri = null
        pendingGalleryResult = null
        qrAnalyzer?.pause()
        super.finish()
    }

    override fun onDestroy() {
        scanState.close()
        pendingGalleryUri = null
        pendingGalleryResult = null
        qrAnalyzer?.pause()
        cameraExecutor?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SCANNED_URL = "extra_scanned_url"
        const val EXTRA_SCANNED_NAME = "extra_scanned_name"
        private const val STATE_GALLERY_PENDING = "scan_gallery_pending"
        private const val STATE_GALLERY_URI = "scan_gallery_uri"

        fun start(context: Context) {
            val intent = Intent(context, QrScanActivity::class.java)
            context.startActivity(intent)
        }
    }
}
