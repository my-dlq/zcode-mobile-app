package ai.zcode.remote.utils

import android.net.Uri

object UrlParser {

    data class ParsedInfo(
        val originalUrl: String,
        val suggestedName: String,
        val mid: String,
        val sid: String,
        val isValidZCodeUrl: Boolean
    )

    fun parse(rawUrl: String): ParsedInfo {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return ParsedInfo(
                originalUrl = "",
                suggestedName = "我的远程设备",
                mid = "",
                sid = "",
                isValidZCodeUrl = false
            )
        }

        val urlToParse = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }

        try {
            val uri = Uri.parse(urlToParse)
            val host = uri.host ?: ""
            val name = uri.getQueryParameter("name")
            val mid = uri.getQueryParameter("mid") ?: ""
            val sid = uri.getQueryParameter("sid") ?: ""

            val isZCode = host.contains("zcode") || host.contains("z.ai") || uri.path?.contains("remote") == true

            val suggestedName = when {
                !name.isNullOrBlank() -> name
                mid.isNotBlank() -> "设备 ${mid.take(8)}"
                else -> "ZCode 设备"
            }

            return ParsedInfo(
                originalUrl = urlToParse,
                suggestedName = suggestedName,
                mid = mid,
                sid = sid,
                isValidZCodeUrl = isZCode
            )
        } catch (e: Exception) {
            return ParsedInfo(
                originalUrl = urlToParse,
                suggestedName = "远程设备",
                mid = "",
                sid = "",
                isValidZCodeUrl = false
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
