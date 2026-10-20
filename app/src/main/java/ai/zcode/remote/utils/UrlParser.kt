package ai.zcode.remote.utils

import android.content.Context
import android.net.Uri
import ai.zcode.remote.R

/**
 * ZCode 远程 URL 解析。
 *
 * `suggestedName` 的取值优先级：
 *   1. URL 中显式 `name` 参数 → 原样使用（远端下发的文案，可能是任意语言）；
 *   2. URL 中 `mid` 参数 → 用 [R.string.device_name_with_mid_format] 渲染；
 *   3. 兜底 → [R.string.default_zcode_device] / [R.string.default_remote_device] /
 *      [R.string.default_device_fallback] 等本地化资源。
 *
 * 解析函数本身不直接持有 Context，由 [ParsedInfo.resolveSuggestedName] 在使用处
 * 渲染，便于单测纯 JVM 跑 parse()（不依赖资源），也便于语言切换后新打开的页面
 * 拿到与当前语言一致的建议名。
 */
object UrlParser {

    data class ParsedInfo(
        val originalUrl: String,
        /** 仅在 URL 自带 `name` 参数时有值；其余场景为 null，由调用方按当前语言渲染。 */
        val explicitName: String?,
        val mid: String,
        val sid: String,
        val isValidZCodeUrl: Boolean,
        /** 标记兜底类型：URL 为空 / 解析失败 / 仅 mid 可用 / 完全兜底。 */
        val fallback: FallbackKind
    ) {
        /** 按当前语言渲染建议名；调用方应优先使用本方法而不是直接读字段。 */
        fun resolveSuggestedName(context: Context): String {
            explicitName?.takeIf { it.isNotBlank() }?.let { return it }
            return when (fallback) {
                FallbackKind.EMPTY_URL -> context.getString(R.string.default_remote_device)
                FallbackKind.PARSE_FAILED -> context.getString(R.string.default_device_fallback)
                FallbackKind.USE_MID -> context.getString(
                    R.string.device_name_with_mid_format, mid.take(8)
                )
                FallbackKind.GENERIC -> context.getString(R.string.default_zcode_device)
            }
        }

        /** 与 [resolveSuggestedName] 区分：这里是为了"判断输入框当前是否仍是默认建议名"。 */
        fun isDefaultSuggestedName(context: Context, value: String): Boolean {
            if (value.isBlank()) return true
            val defaults = listOf(
                context.getString(R.string.default_remote_device),
                context.getString(R.string.default_zcode_device),
                context.getString(R.string.default_device_fallback),
            )
            return defaults.contains(value)
        }
    }

    /** 建议名兜底类型；用于本地化渲染。 */
    enum class FallbackKind {
        /** URL 为空串。 */
        EMPTY_URL,
        /** Uri.parse 抛异常。 */
        PARSE_FAILED,
        /** 只有 mid，渲染成「设备 xxxxxxxx」。 */
        USE_MID,
        /** 无 name 也无 mid，兜底为「ZCode 设备」。 */
        GENERIC
    }

    fun parse(rawUrl: String): ParsedInfo {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return ParsedInfo(
                originalUrl = "",
                explicitName = null,
                mid = "",
                sid = "",
                isValidZCodeUrl = false,
                fallback = FallbackKind.EMPTY_URL,
            )
        }

        val urlToParse = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }

        return try {
            val uri = Uri.parse(urlToParse)
            val host = uri.host ?: ""
            val name = uri.getQueryParameter("name")
            val mid = uri.getQueryParameter("mid") ?: ""
            val sid = uri.getQueryParameter("sid") ?: ""

            val isZCode = host.contains("zcode") || host.contains("z.ai") ||
                    uri.path?.contains("remote") == true

            val fallback = when {
                !name.isNullOrBlank() -> FallbackKind.GENERIC // explicitName 已覆盖，fallback 不会被用到
                mid.isNotBlank() -> FallbackKind.USE_MID
                else -> FallbackKind.GENERIC
            }

            ParsedInfo(
                originalUrl = urlToParse,
                explicitName = name?.takeIf { it.isNotBlank() },
                mid = mid,
                sid = sid,
                isValidZCodeUrl = isZCode,
                fallback = fallback,
            )
        } catch (e: Exception) {
            ParsedInfo(
                originalUrl = urlToParse,
                explicitName = null,
                mid = "",
                sid = "",
                isValidZCodeUrl = false,
                fallback = FallbackKind.PARSE_FAILED,
            )
        }
    }

    fun isValidRemoteConnection(info: ParsedInfo): Boolean {
        return info.isValidZCodeUrl && info.mid.isNotBlank() && info.sid.isNotBlank()
    }

    fun isLikelyZCodeUrl(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        return (lower.startsWith("http://") || lower.startsWith("https://")) &&
                (lower.contains("zcode") || lower.contains("remote/v") || lower.contains("z.ai"))
    }
}
