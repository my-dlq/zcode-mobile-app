package ai.zcode.remote.ui.remote.event

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务事件解析器：从注入 JS 镜像回传的页面流量（fetch 响应 / SSE 消息 / WebSocket
 * 消息）中识别任务事件。解析分两条路径：
 *
 * 1. **显式事件**：深度遍历 JSON（最多 [MAX_DEPTH] 层），找 `event`/`type` 字段值落在
 *    [KNOWN_EVENT_TYPES] 内的节点——远端部分报文直接携带事件本体。
 * 2. **会话差分**：远端会话索引报文（wireVersion 信封 → frame.payload.deltas）以
 *    `session.upserted` 推送整份会话状态，审批/提问通过
 *    `pendingInteractionSummary.permissionCount` / `userInputCount` 的 0→N 跳变体现。
 *    因此维护一份按 sessionId 的快照表做差分：0→N 发请求事件，N→0 发 resolved，
 *    phase 进入完成/错误态发 completed/error。
 *
 * 两条路径互补：差分能抓住索引流里的审批，显式事件兜底任务流里的完成/失败。
 */
object TaskEventParser {

    /** 解析后的任务事件。 */
    data class TaskEvent(
        val type: Type,
        val taskId: String,
        val taskTitle: String,
        val deviceName: String,
    ) {
        enum class Type {
            PERMISSION_REQUEST, ELICITATION_REQUEST,
            TASK_COMPLETED, TASK_FAILED, RESOLVED,
        }
    }

    /** 会话状态快照（差分输入）。 */
    private data class SessionState(
        val sessionId: String,
        val title: String?,
        val phase: String?,
        val sessionEnded: Boolean?,
        val permissionCount: Int,
        val userInputCount: Int,
        val interactionKind: String?,
        val toolName: String?,
        val description: String?,
    )

    private const val TAG = "ZCodeEvent"
    private const val MAX_DEPTH = 8

    /** 远端已知事件类型（显式事件路径的白名单）。 */
    private val KNOWN_EVENT_TYPES = setOf(
        "created", "prompt_sent", "resumed", "streaming",
        "permission_request", "permission_resolved",
        "elicitation_request", "elicitation_resolved",
        "updated", "completed", "error",
    )

    /** 完成态 phase 值。 */
    private val DONE_PHASES = setOf("completedSuccess", "completedInterrupted")

    /** 会话快照表：sessionId → 上一帧状态（差分基准）。 */
    private val prevSessions = HashMap<String, SessionState>()

    /** 从一段流量文本中解析任务事件；无法识别时返回空列表。 */
    @Synchronized
    fun parse(body: String, deviceName: String): List<TaskEvent> {
        if (body.isEmpty() || body.length > 512 * 1024) return emptyList()
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return emptyList()
        }
        val events = ArrayList<TaskEvent>()

        // 路径 1：显式事件（深度遍历找 event/type 字段）
        val explicit = ArrayList<TaskEvent>()
        walkExplicit(root, 0, explicit, deviceName)
        events.addAll(explicit)

        // 路径 2：会话差分（深度遍历找会话状态节点，与快照比对）
        val incoming = ArrayList<SessionState>()
        walkSessions(root, 0, incoming)
        if (incoming.isNotEmpty()) {
            events.addAll(diffSessions(incoming, deviceName))
        }

        return events
    }

    // ---- 路径 1：显式事件 ------------------------------------------------

    private fun walkExplicit(
        node: Any?, depth: Int, out: MutableList<TaskEvent>, deviceName: String,
    ) {
        if (depth > MAX_DEPTH || node == null) return
        when (node) {
            is JSONObject -> {
                val eventType = explicitTypeOf(node)
                if (eventType != null) {
                    explicitEventFrom(node, eventType, deviceName)?.let { out.add(it) }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    walkExplicit(node.opt(keys.next()), depth + 1, out, deviceName)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkExplicit(node.opt(i), depth + 1, out, deviceName)
                }
            }
        }
    }

    private fun explicitTypeOf(node: JSONObject): String? {
        for (key in listOf("event", "type")) {
            val v = node.optString(key, "")
            if (v in KNOWN_EVENT_TYPES) return v
        }
        return null
    }

    private fun explicitEventFrom(
        node: JSONObject, type: String, deviceName: String,
    ): TaskEvent? {
        val taskId = firstString(node, "taskId", "task_id").orEmpty()
        val summary = firstString(node, "title", "description", "kind", "toolName").orEmpty()
        val mapped = when (type) {
            "permission_request" -> TaskEvent.Type.PERMISSION_REQUEST
            "elicitation_request" -> TaskEvent.Type.ELICITATION_REQUEST
            "completed" -> TaskEvent.Type.TASK_COMPLETED
            "error" -> TaskEvent.Type.TASK_FAILED
            else -> return null // created/streaming/updated 等不需要通知
        }
        return TaskEvent(mapped, taskId, summary.take(80), deviceName)
    }

    // ---- 路径 2：会话差分 ------------------------------------------------

    private fun walkSessions(node: Any?, depth: Int, out: MutableList<SessionState>) {
        if (depth > MAX_DEPTH || node == null) return
        when (node) {
            is JSONObject -> {
                sessionStateOf(node)?.let { out.add(it) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    walkSessions(node.opt(keys.next()), depth + 1, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkSessions(node.opt(i), depth + 1, out)
                }
            }
        }
    }

    /** 判断一个 JSON 节点是否是会话状态（有 sessionId + 至少一个状态字段）。 */
    private fun sessionStateOf(node: JSONObject): SessionState? {
        val id = node.optString("sessionId", "")
        if (id.isEmpty()) return null
        val hasSummary = node.optJSONObject("pendingInteractionSummary") != null
        val hasEnded = node.has("sessionEnded")
        val hasPhase = node.optString("phase", "").isNotEmpty()
        if (!hasSummary && !hasEnded && !hasPhase) return null

        var permCount = 0
        var userInputCount = 0
        node.optJSONObject("pendingInteractionSummary")?.let { s ->
            permCount = s.optInt("permissionCount", 0)
            userInputCount = s.optInt("userInputCount", 0)
        }

        var interactionKind: String? = null
        var toolName: String? = null
        var description: String? = null
        node.optJSONObject("pendingInteraction")?.let { inter ->
            interactionKind = inter.optString("kind", "").ifEmpty { null }
            toolName = inter.optString("toolName", "").ifEmpty { null }
            description = firstString(inter, "description", "summary")
        }

        return SessionState(
            sessionId = id,
            title = node.optString("title", "").ifEmpty { null },
            phase = node.optString("phase", "").ifEmpty { null },
            sessionEnded = if (node.has("sessionEnded")) node.optBoolean("sessionEnded") else null,
            permissionCount = permCount,
            userInputCount = userInputCount,
            interactionKind = interactionKind,
            toolName = toolName,
            description = description,
        )
    }

    /** 与快照表差分，产生事件。 */
    private fun diffSessions(
        incoming: List<SessionState>, deviceName: String,
    ): List<TaskEvent> {
        val events = ArrayList<TaskEvent>()
        for (next in incoming) {
            val prev = prevSessions[next.sessionId]
            prevSessions[next.sessionId] = next

            val prevPerm = prev?.permissionCount ?: 0
            val prevInput = prev?.userInputCount ?: 0

            // 审批/提问 0→N：发请求事件
            if (next.permissionCount > 0 && prevPerm == 0) {
                events.add(TaskEvent(
                    TaskEvent.Type.PERMISSION_REQUEST,
                    next.sessionId,
                    (next.description ?: next.title ?: "").take(80),
                    deviceName,
                ))
            }
            if (next.userInputCount > 0 && prevInput == 0) {
                events.add(TaskEvent(
                    TaskEvent.Type.ELICITATION_REQUEST,
                    next.sessionId,
                    (next.description ?: next.title ?: "").take(80),
                    deviceName,
                ))
            }

            // N→0：发 resolved（通知撤回信号，不发系统通知）
            if ((prevPerm > 0 && next.permissionCount == 0) ||
                (prevInput > 0 && next.userInputCount == 0)
            ) {
                events.add(TaskEvent(
                    TaskEvent.Type.RESOLVED,
                    next.sessionId,
                    next.title ?: "",
                    deviceName,
                ))
            }

            // phase 跳变：完成/错误
            val phase = next.phase
            val prevPhase = prev?.phase
            val isDone = phase != null && phase in DONE_PHASES
            val wasDone = prevPhase != null && prevPhase in DONE_PHASES
            if (prev != null && isDone && !wasDone) {
                events.add(TaskEvent(
                    TaskEvent.Type.TASK_COMPLETED,
                    next.sessionId, next.title ?: "", deviceName,
                ))
            }
            if (prev != null && phase == "error" && prevPhase != "error") {
                events.add(TaskEvent(
                    TaskEvent.Type.TASK_FAILED,
                    next.sessionId, next.title ?: "", deviceName,
                ))
            }
        }
        return events
    }

    // ---- 工具 ------------------------------------------------------------

    private fun firstString(node: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val v = node.optString(key, "")
            if (v.isNotEmpty()) return v
        }
        return null
    }

    fun logParsed(event: TaskEvent) {
        Log.d(TAG, "task event: type=${event.type} id=${event.taskId} title=${event.taskTitle}")
    }
}
