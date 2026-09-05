package ai.zcode.remote.ui.remote.event

import android.util.Base64
import org.json.JSONObject

/**
 * Wire 信封解码器：从 TaskEventBridge 提取的独立解码逻辑，
 * 供 WebView 桥（TaskEventBridge）解析页面 WS 镜像报文。
 *
 * 远端 WS 报文为两层信封：外层 wireVersion 信封，内层 payload 可能直接
 * 携带事件/差分，或在 dataBase64 中 base64 编码。分片报文按 logicalFrameId
 * 重组。
 */
object EnvelopeDecoder {

    private const val MAX_BYTES = 512 * 1024
    private const val MAX_ASM_SLOTS = 32

    /** 分片重组槽。 */
    private data class FragmentSlot(
        val total: Int,
        val parts: HashMap<Int, ByteArray> = HashMap(),
        var got: Int = 0,
    )

    private val fragmentAsm = HashMap<String, FragmentSlot>()
    private val fragmentOrder = ArrayDeque<String>()

    /**
     * 处理一段 WS 消息文本：先直接送解析，再尝试解 base64 信封；
     * 解出的内层文本可能仍是信封，递归处理。
     */
    fun dispatch(text: String, depth: Int = 0, handler: (String) -> Unit) {
        if (depth > 3) return
        handler(text)
        decodeEnvelope(text)?.let { inner -> dispatch(inner, depth + 1, handler) }
    }

    /** 解 wire 信封：识别 dataBase64 / fragment 结构。 */
    private fun decodeEnvelope(body: String): String? {
        val env = try { JSONObject(body) } catch (e: Exception) { return null }
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

    @Synchronized
    private fun assembleFragment(payload: JSONObject, dataBase64: String): String? {
        val id = payload.optString("logicalFrameId", "")
        if (id.isEmpty()) return null
        val index = payload.optInt("fragmentIndex", -1)
        val total = payload.optInt("fragmentCount", 1)
        if (index < 0 || total <= 1) return null

        var slot = fragmentAsm[id]
        if (slot == null) {
            if (fragmentOrder.size > MAX_ASM_SLOTS) {
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

    private fun decodeBase64Payload(dataBase64: String): String? {
        return try {
            val bytes = Base64.decode(dataBase64, Base64.DEFAULT)
            if (bytes.size > MAX_BYTES) return null
            extractJson(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJson(text: String): String? {
        if (text.isEmpty()) return null
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        return if (first in 0 until last) text.substring(first, last + 1) else null
    }
}
