package ai.zcode.remote.utils

import ai.zcode.remote.data.model.UpdateInfo
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 更新检查器
 *
 * 请求 https://api.github.com/repos/my-dlq/zcode-mobile-app/releases/latest，
 * 解析 tag_name 与 APK 资产（资产命名约定：zcode-mobile-app-v<versionName>.apk，
 * 见 app/build.gradle.kts 的 outputFileName 配置；debug 产物带 -debug 后缀需排除）。
 *
 * GitHub API 对公开仓库免鉴权（60 次/小时/IP），所有方法均为同步阻塞，
 * 必须在后台线程调用。
 */
object UpdateChecker {

    private const val RELEASE_API_URL = "https://api.github.com/repos/my-dlq/zcode-mobile-app/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** GitHub API 响应中我们关心的字段（Gson 按字段名映射，未声明字段自动忽略） */
    private data class ReleaseResponse(
        @SerializedName("tag_name") val tagName: String?,
        @SerializedName("body") val body: String?,
        @SerializedName("assets") val assets: List<Asset>?
    )

    private data class Asset(
        val name: String?,
        @SerializedName("browser_download_url") val downloadUrl: String?,
        val size: Long = 0
    )

    /**
     * 查询最新 Release 并提取 APK 下载信息。
     * 网络异常、HTTP 非 200、无 APK 资产时返回 null。
     */
    fun checkLatestRelease(): UpdateInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            parseRelease(json)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** 解析 Release JSON，挑选 release 产出的 APK 资产 */
    private fun parseRelease(json: String): UpdateInfo? {
        return try {
            val release = Gson().fromJson(json, ReleaseResponse::class.java)
            val tag = release.tagName?.takeIf { it.isNotBlank() } ?: return null

            // 优先匹配仓库约定的 release 产物名（排除 -debug），找不到时回退任一 apk 资产
            val assets = release.assets.orEmpty().filterNotNull()
            val apkAsset = assets.firstOrNull { it.name?.matches(RELEASE_APK_REGEX) == true }
                ?: assets.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }
                ?: return null
            val url = apkAsset.downloadUrl?.takeIf { it.isNotBlank() } ?: return null

            UpdateInfo(
                tagName = tag,
                versionName = tag.removePrefix("v").removePrefix("V"),
                downloadUrl = url,
                fileSize = apkAsset.size,
                releaseNotes = release.body.orEmpty()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 版本号比较：按 "." 分段逐段转 Int 比较，段数不足补 0。
     * 返回值 >0 表示 latest 更新，=0 相同，<0 表示 current 更新。
     * 非数字段按 0 处理，保证 "0.1.1" vs "0.1.2"、"0.1" vs "0.1.0" 都能正确比较。
     */
    fun compareVersion(current: String, latest: String): Int {
        val curSegs = current.split(".")
        val latSegs = latest.split(".")
        val maxLen = maxOf(curSegs.size, latSegs.size)
        for (i in 0 until maxLen) {
            val cur = curSegs.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val lat = latSegs.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (cur != lat) return cur.compareTo(lat)
        }
        return 0
    }

    /** 构建 Release 页面地址（备用跳转入口） */
    fun releasePageUrl(): String = "https://github.com/my-dlq/zcode-mobile-app/releases/latest"

    private val RELEASE_APK_REGEX = Regex("""zcode-mobile-app-v\d[\w.\-]*\.apk""", RegexOption.IGNORE_CASE)
}
