package ai.zcode.remote.ui.remote.event

import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * JS → 原生 事件桥：注入脚本把镜像的页面流量（fetch/SSE/WS）与连接状态通过
 * addJavascriptInterface 回传。核心职责是**解信封**——远端 WS 报文是两层结构：
 *
 * - 外层 wire 信封：`{"wireVersion":3,"kind":"complete","frame":{...}}` 或
 *   `{"wireVersion":3,"kind":"fragment","fragmentIndex":N,"fragmentCount":M,
 *   "logicalFrameId":"...","dataBase64":"..."}`
 * - 内层 payload：`frame.payload` 直接携带事件/会话差分，或 `dataBase64` 里
 *   base64 编码的 JSON 文本
 *
 * 分片按 `logicalFrameId` 缓存，凑齐 `fragmentCount` 片后按 `fragmentIndex` 拼接、
 * base64 解码、截取最外层 `{}` 再送解析器。非分片报文直接解 `dataBase64` 或
 * 透传原始文本。
 */
class TaskEventBridge(
    private val deviceName: String,
    private val onEvent: (TaskEventParser.TaskEvent) -> Unit,
    private val onSessionState: (up: Boolean) -> Unit,
) {
    @Volatile
    var enabled = true

    /** 分片重组槽。 */
    private data class FragmentSlot(
        val total: Int,
        val parts: HashMap<Int, ByteArray> = HashMap(),
        var got: Int = 0,
    )

    private val fragmentAsm = HashMap<String, FragmentSlot>()
    private val fragmentOrder = ArrayDeque<String>()

    @android.webkit.JavascriptInterface
    fun onTraffic(body: String) {
        if (!enabled) return
        val capped = if (body.length > MAX_BYTES) body.take(MAX_BYTES) else body
        dispatchRecursive(capped, 0)
    }

    /**
     * 递归分发：先送解析器，再尝试解 base64 信封；解出的内层文本可能又是一个
     * 信封（wireVersion 嵌套），递归处理直到无信封可解或达到深度上限。
     */
    private fun dispatchRecursive(text: String, depth: Int) {
        if (depth > 3) return
        dispatch(text)
        decodeEnvelope(text)?.let { inner -> dispatchRecursive(inner, depth + 1) }
    }

    @android.webkit.JavascriptInterface
    fun onSessionState(state: String) {
        if (!enabled) return
        val up = state == "up"
        Log.d(TAG, "session state: $state")
        onSessionState(up)
    }

    /** 把一段文本送解析器并回调事件。 */
    private fun dispatch(text: String) {
        val events = TaskEventParser.parse(text, deviceName)
        for (event in events) {
            TaskEventParser.logParsed(event)
            onEvent(event)
        }
    }

    /**
     * 解 wire 信封：识别 `dataBase64` / `kind:"fragment"` 结构，返回解码后的内层
     * JSON 文本；非信封或分片未凑齐时返回 null。
     */
    private fun decodeEnvelope(body: String): String? {
        val env = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        // base64 payload 可能在顶层 payload 或 frame.payload（两种 wire 报文格式）
        val payload = env.optJSONObject("payload")
            ?: env.optJSONObject("frame")?.optJSONObject("payload")
            ?: return null
        val dataBase64 = payload.optString("dataBase64", "")
        if (dataBase64.isEmpty()) return null

        val kind = payload.optString("kind", "")
        return if (kind == "fragment" && payload.optInt("fragmentCount", 1) > 1) {
            assembleFragment(payload, dataBase64)
        } else {
            decodeBase64Payload(dataBase64)
        }
    }

    /** 分片重组：凑齐后拼接解码。 */
    @Synchronized
    private fun assembleFragment(payload: JSONObject, dataBase64: String): String? {
        val id = payload.optString("logicalFrameId", "")
        if (id.isEmpty()) return null
        val index = payload.optInt("fragmentIndex", -1)
        val total = payload.optInt("fragmentCount", 1)
        if (index < 0 || total <= 1) return null

        var slot = fragmentAsm[id]
        if (slot == null) {
            // 容量防御：最多缓存 32 个重组槽，超出淘汰最旧
            if (fragmentOrder.size > 32) {
                val oldest = fragmentOrder.removeFirst()
                fragmentAsm.remove(oldest)
            }
            slot = FragmentSlot(total)
            fragmentAsm[id] = slot
            fragmentOrder.addLast(id)
        }
        if (index !in slot.parts) {
            slot.got++
            slot.parts[index] = Base64.decode(dataBase64, Base64.DEFAULT)
        }
        if (slot.got < slot.total) return null

        // 凑齐：按 fragmentIndex 顺序拼接
        fragmentAsm.remove(id)
        fragmentOrder.remove(id)
        var size = 0
        for (i in 0 until slot.total) size += slot.parts[i]?.size ?: 0
        if (size > MAX_BYTES) return null
        val bytes = ByteArray(size)
        var off = 0
        for (i in 0 until slot.total) {
            val part = slot.parts[i] ?: return null
            System.arraycopy(part, 0, bytes, off, part.size)
            off += part.size
        }
        return extractJson(String(bytes, Charsets.UTF_8))
    }

    /** 非分片 base64 payload 直接解码。 */
    private fun decodeBase64Payload(dataBase64: String): String? {
        return try {
            val bytes = Base64.decode(dataBase64, Base64.DEFAULT)
            if (bytes.size > MAX_BYTES) return null
            extractJson(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /** 截取最外层 {} 之间的内容（解码后的文本可能带前缀/后缀噪声）。 */
    private fun extractJson(text: String): String? {
        if (text.isEmpty()) return null
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        return if (first in 0 until last) text.substring(first, last + 1) else null
    }

    companion object {
        const val BRIDGE_NAME = "__zcodeNative"
        private const val TAG = "ZCodeEvent"
        private const val MAX_BYTES = 512 * 1024
    }
}
