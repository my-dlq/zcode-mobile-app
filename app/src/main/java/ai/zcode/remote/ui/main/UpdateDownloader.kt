package ai.zcode.remote.ui.main

import ai.zcode.remote.R
import ai.zcode.remote.data.model.UpdateInfo
import ai.zcode.remote.utils.ToastUtils
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * APK 下载器：后台线程流式下载到应用私有目录，进度回调到主线程，
 * 完成后通过 FileProvider 拉起系统安装器。
 *
 * 沿用 QrScanActivity 的 ExecutorService + runOnUiThread 异步模式，零新增依赖。
 */
class UpdateDownloader(private val context: Context) {

    /** 进度回调：percent 0~100，downloadedBytes/totalBytes 为字节（totalBytes 未知时为 -1） */
    fun interface ProgressListener {
        fun onProgress(percent: Int, downloadedBytes: Long, totalBytes: Long)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cancelled = false
    private var currentConnection: HttpURLConnection? = null

    /**
     * 开始下载。同一时刻只保留一个下载任务（重复调用会取消前一个）。
     * 完成后回调 onCompleted（主线程），失败/取消回调 onError（主线程）。
     */
    fun download(info: UpdateInfo, listener: ProgressListener, onCompleted: (File) -> Unit, onError: (String) -> Unit) {
        cancelled = false
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val dir = context.getExternalFilesDir("apk") ?: File(context.filesDir, "apk").apply { mkdirs() }
                val outputFile = File(dir, "zcode-mobile-app-v${info.versionName}.apk")

                connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                currentConnection = connection
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    postError(onError, "HTTP $code")
                    return@execute
                }

                val total = connection.contentLengthLong.let { if (it <= 0) info.fileSize else it }
                var downloaded = 0L
                var lastReportedPercent = -1

                connection.inputStream.use { input ->
                    outputFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled) {
                                output.close()
                                outputFile.delete()
                                mainHandler.post { onError("cancelled") }
                                return@execute
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            // 只在百分比变化时回调，避免主线程被刷屏
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt()
                                if (percent != lastReportedPercent) {
                                    lastReportedPercent = percent
                                    val p = percent
                                    mainHandler.post { listener.onProgress(p, downloaded, total) }
                                }
                            }
                        }
                    }
                }

                // 校验文件完整性（GitHub 资产有准确 size；contentLength 未知时跳过强校验）
                if (info.fileSize > 0 && outputFile.length() != info.fileSize) {
                    outputFile.delete()
                    postError(onError, "size mismatch")
                    return@execute
                }

                mainHandler.post { onCompleted(outputFile) }
            } catch (e: Exception) {
                postError(onError, e.message ?: "download error")
            } finally {
                currentConnection = null
                connection?.disconnect()
            }
        }
    }

    /** 取消当前下载，后台任务会在下一个读取循环退出并删除半成品文件 */
    fun cancel() {
        cancelled = true
        currentConnection?.disconnect()
    }

    /** 释放资源（Activity 销毁时调用） */
    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }

    /**
     * 拉起系统安装器。未授予「安装未知应用」权限时先跳转系统设置页，
     * 用户授权后需回到应用再次点击更新（onActivityResult 闭环成本高，此为简单可靠方案）。
     *
     * 注意：debug 包（ai.zcode.remote.debug）安装 release 包（ai.zcode.remote）是
     * 两个不同包名之间的全新安装，不存在签名冲突，可以正常拉起安装器。
     */
    fun installApk(context: Context, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                ToastUtils.show(context, context.getString(R.string.toast_install_redirect_failed))
            }
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            ToastUtils.show(context, context.getString(R.string.toast_install_launch_failed))
        }
    }

    private fun postError(onError: (String) -> Unit, message: String) {
        mainHandler.post { onError(message) }
    }
}
