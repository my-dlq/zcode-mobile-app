package ai.zcode.remote.ui.remote.event

import android.util.Log
import org.json.JSONObject

/**
 * 任务事件解析器：从注入 JS 镜像回传的页面流量（fetch 响应 / SSE 消息 / WebSocket
 * 消息）中识别任务事件。解析逻辑与远端报文结构解耦——只做关键词级匹配 + 尽力
 * 提取 taskId/taskTitle，字段缺失时降级为无任务信息的通用通知，远端改版不易失效。
 */
object TaskEventParser {

    /** 解析后的任务事件。 */
    data class TaskEvent(
        val type: Type,
        val taskId: String,
        val taskTitle: String,
        val deviceName: String,
    ) {
        enum class Type { PERMISSION_REQUEST, TASK_COMPLETED, TASK_FAILED, SESSION_UP, SESSION_DOWN }
    }

    private const val TAG = "ZCodeEvent"

    private val PERMISSION_KEYWORDS = listOf(
        "permission_request", "permissionRequest", "requires_approval",
        "requiresApproval", "waiting_for_approval", "approval_required"
    )
    private val COMPLETED_KEYWORDS = listOf(
        "\"status\":\"completed\"", "\"state\":\"completed\"", "task_completed",
        "taskCompleted", "\"status\": \"completed\"", "\"state\": \"completed\""
    )
    private val FAILED_KEYWORDS = listOf(
        "\"status\":\"failed\"", "\"state\":\"failed\"", "task_failed", "taskFailed",
        "\"status\": \"failed\"", "\"state\": \"failed\"", "\"status\":\"error\""
    )

    /** 判断一段流量文本是否值得继续深挖（快速预筛，避免每个响应都跑 JSON 解析）。 */
    fun isCandidate(body: String): Boolean {
        if (body.length > 512 * 1024) return false
        return body.contains("permission") || body.contains("approval") ||
            body.contains("task") || body.contains("session") ||
            body.contains("\"status\"") || body.contains("\"state\"")
    }

    /** 从一段流量文本中解析任务事件；无法识别时返回 null。 */
    fun parse(body: String, deviceName: String): TaskEvent? {
        if (!isCandidate(body)) return null
        return try {
            val json = JSONObject(body)
            parseJson(json, deviceName)
        } catch (e: Exception) {
            parseLoose(body, deviceName)
        }
    }

    private fun parseJson(json: JSONObject, deviceName: String): TaskEvent? {
        val type = detectType(json.toString()) ?: return null
        val taskId = findString(json, listOf("taskId", "task_id", "id", "sessionId", "session_id"))
        val title = findString(json, listOf("title", "name", "taskTitle", "message", "prompt"))
        return TaskEvent(type, taskId.orEmpty(), title.orEmpty().take(80), deviceName)
    }

    /** 非 JSON 片段（SSE 行、半截 WS 帧）退化为关键词匹配。 */
    private fun parseLoose(body: String, deviceName: String): TaskEvent? {
        val type = detectType(body) ?: return null
        return TaskEvent(type, "", "", deviceName)
    }

    private fun detectType(text: String): TaskEvent.Type? = when {
        // 审批请求优先级最高（最需要用户即时知晓）
        PERMISSION_KEYWORDS.any { text.contains(it) } -> TaskEvent.Type.PERMISSION_REQUEST
        FAILED_KEYWORDS.any { text.contains(it) } -> TaskEvent.Type.TASK_FAILED
        COMPLETED_KEYWORDS.any { text.contains(it) } -> TaskEvent.Type.TASK_COMPLETED
        else -> null
    }

    private fun findString(json: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            if (json.has(key) && json.opt(key) is String) return json.getString(key)
        }
        // 递归找一层嵌套对象（data/task/payload 等常见包装）
        for (key in listOf("data", "task", "payload", "session")) {
            val child = json.optJSONObject(key) ?: continue
            findString(child, keys)?.let { return it }
        }
        return null
    }

    fun logParsed(event: TaskEvent) {
        Log.d(TAG, "task event: type=${event.type} id=${event.taskId} title=${event.taskTitle}")
    }
}
