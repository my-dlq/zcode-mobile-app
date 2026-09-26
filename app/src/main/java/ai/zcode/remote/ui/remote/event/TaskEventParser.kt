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
 * 2. **状态差分**：远端会话索引报文（wireVersion 信封 → frame.payload.deltas）以
 *    `session.upserted` 推送整份会话状态，审批/提问通过
 *    `pendingInteractionSummary.permissionCount` / `userInputCount` 的 0→N 跳变体现。
 *    因此维护一份按 sessionId 的快照表做差分：0→N 发请求事件，N→0 发 resolved，
 *    phase 进入完成/错误态发 completed/error；新版任务索引的 `task.upserted`、
 *    snapshot.tasks 与 result.tasks 同样会归一化到这一状态表。
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
        /** 远端交互实例的稳定身份；同一任务的第二次审批必须使用不同 key。 */
        val eventKey: String = "",
    ) {
        enum class Type {
            PERMISSION_REQUEST, ELICITATION_REQUEST,
            TASK_COMPLETED, TASK_FAILED, RESOLVED,
        }
    }

    private const val TAG = "ZCodeEvent"
    private const val MAX_DEPTH = 8
    private const val MAX_MESSAGE_CHARS = 4 * 1024 * 1024

    /** 远端已知事件类型（显式事件路径的白名单）。 */
    private val KNOWN_EVENT_TYPES = setOf(
        "created", "prompt_sent", "resumed", "streaming",
        "permission_request", "permission_resolved",
        "elicitation_request", "elicitation_resolved",
        "updated", "completed", "error",
    )

    private val sessionTracker = TaskSessionTracker()
    private val backgroundTaskTracker = BackgroundTaskTracker()

    /** 从一段流量文本中解析任务事件；无法识别时返回空列表。 */
    @Synchronized
    fun parse(body: String, deviceName: String, sourceId: String): List<TaskEvent> {
        if (body.isEmpty() || body.length > MAX_MESSAGE_CHARS) return emptyList()
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return emptyList()
        }
        val events = ArrayList<TaskEvent>()

        // 路径 1：显式事件（深度遍历找 event/type 字段）
        val explicit = ArrayList<TaskEvent>()
        walkExplicit(root, 0, explicit, deviceName, sourceId)

        // 路径 2：会话差分（深度遍历找会话状态节点，与快照比对）
        val incoming = ArrayList<TaskSessionTracker.SessionState>()
        walkSessions(root, 0, incoming)
        walkTaskIndex(root, 0, incoming)
        val diff = if (incoming.isNotEmpty()) {
            sessionTracker.update(sourceId, incoming, deviceName)
        } else {
            emptyList()
        }

        // 后台执行器（exec_...）完成不是会话本身完成，会话可能仍保持 running。
        // 但切到桌面后用户需要知道后台任务结束，按 executionId 生成独立事件键。
        val backgroundEvents = ArrayList<TaskEvent>()
        backgroundTaskTracker.beginFrame(sourceId)
        walkBackgroundTasks(root, 0, backgroundEvents, deviceName, sourceId)
        walkBackgroundWorks(root, 0, null, backgroundEvents, deviceName, sourceId)
        backgroundTaskTracker.endFrame(sourceId)

        // 同一帧可能同时含显式事件与会话 upsert，按任务与类型合并。
        val seen = HashSet<String>()
        for (event in explicit + diff + backgroundEvents) {
            // 只在同一帧内合并显式事件和状态差分，不能跨帧用 taskId+type 去重，
            // 否则同一会话的第二次审批会被误认为历史事件。
            val key = if (event.eventKey.isNotEmpty()) event.eventKey
                else "${event.taskId}:${event.type}"
            if (seen.add(key)) events.add(event)
        }
        return events
    }

    // ---- 路径 1：显式事件 ------------------------------------------------

    private fun walkExplicit(
        node: Any?, depth: Int, out: MutableList<TaskEvent>, deviceName: String,
        sourceId: String,
    ) {
        if (depth > MAX_DEPTH || node == null) return
        when (node) {
            is JSONObject -> {
                val eventType = explicitTypeOf(node)
                if (eventType != null) {
                    explicitEventFrom(node, eventType, deviceName, sourceId)?.let { out.add(it) }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    walkExplicit(node.opt(keys.next()), depth + 1, out, deviceName, sourceId)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkExplicit(node.opt(i), depth + 1, out, deviceName, sourceId)
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
        node: JSONObject, type: String, deviceName: String, sourceId: String,
    ): TaskEvent? {
        val taskId = taskIdOf(node).orEmpty()
        // 无 taskId 的显式事件无法定位到具体任务，不产生通知
        // （避免远端泛化的 error/状态报文触发无意义的失败通知）
        if (taskId.isEmpty()) return null
        val summary = summaryOf(node).orEmpty()
        val mapped = when (type) {
            "permission_request" -> TaskEvent.Type.PERMISSION_REQUEST
            "elicitation_request" -> TaskEvent.Type.ELICITATION_REQUEST
            "permission_resolved", "elicitation_resolved" -> TaskEvent.Type.RESOLVED
            // completed/error 常被历史任务快照一并携带，不能直接当作新事件。
            // 完成/失败只由 session/task 状态的非终态→终态边沿产生，避免重连、
            // 刷新或分页加载时把旧任务反复通知。
            "completed", "error" -> return null
            else -> return null // created/streaming/updated 等不需要通知
        }
        val interaction = firstString(node, "interactionId", "interaction_id", "requestId", "request_id")
            ?: node.optJSONObject("pendingInteraction")?.let {
                firstString(it, "interactionId", "interaction_id", "requestId", "request_id")
            }
        val version = interaction ?: firstString(node, "lastActivityAt", "last_activity_at", "timestamp")
        val eventKey = if (version.isNullOrEmpty()) "" else
            "$sourceId|$taskId|${mapped.name}|$version"
        return TaskEvent(mapped, taskId, summary.take(80), deviceName, eventKey)
    }

    // ---- 路径 2：会话差分 ------------------------------------------------

    private fun walkSessions(
        node: Any?,
        depth: Int,
        out: MutableList<TaskSessionTracker.SessionState>,
    ) {
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
    private fun sessionStateOf(node: JSONObject): TaskSessionTracker.SessionState? {
        val id = node.optString("sessionId", "")
        if (id.isEmpty()) return null
        val hasSummary = node.optJSONObject("pendingInteractionSummary") != null
        val hasEnded = node.has("sessionEnded")
        val hasPhase = node.optString("phase", "").isNotEmpty()
        if (!hasSummary && !hasEnded && !hasPhase) return null

        var permCount: Int? = null
        var userInputCount: Int? = null
        node.optJSONObject("pendingInteractionSummary")?.let { s ->
            if (s.has("permissionCount")) {
                permCount = s.optInt("permissionCount", 0)
            }
            if (s.has("userInputCount")) {
                userInputCount = s.optInt("userInputCount", 0)
            }
        }

        var interactionKind: String? = null
        var toolName: String? = null
        var description: String? = null
        val interactionId = node.optJSONObject("pendingInteraction")?.let { inter ->
            firstString(inter, "interactionId", "interaction_id")
        }
        val lastActivityAt = firstLong(node, "lastActivityAt", "last_activity_at")
        val createdAt = firstLong(node, "createdAt", "created_at")
        node.optJSONObject("pendingInteraction")?.let { inter ->
            interactionKind = inter.optString("kind", "").ifEmpty { null }
            toolName = inter.optString("toolName", "").ifEmpty { null }
            description = firstString(inter, "description", "summary")
        }

        return TaskSessionTracker.SessionState(
            sessionId = id,
            title = node.optString("title", "").ifEmpty { null },
            phase = node.optString("phase", "").ifEmpty { null },
            sessionEnded = if (node.has("sessionEnded")) node.optBoolean("sessionEnded") else null,
            permissionCount = permCount,
            userInputCount = userInputCount,
            interactionKind = interactionKind,
            toolName = toolName,
            description = description,
            interactionId = interactionId,
            lastActivityAt = lastActivityAt,
            createdAt = createdAt,
        )
    }

    // ---- 路径 3：后台执行器状态 ---------------------------------------------

    private class BackgroundTaskTracker {
        private data class SourceState(
            val works: MutableMap<String, String> = HashMap(),
            var baselineEstablished: Boolean = false,
            var frameStarted: Boolean = false,
            var frameSawState: Boolean = false,
        )

        private val sourceStates = HashMap<String, SourceState>()

        @Synchronized
        fun beginFrame(sourceId: String) {
            val source = sourceStates.getOrPut(sourceId) { SourceState() }
            source.frameStarted = true
            source.frameSawState = false
        }

        @Synchronized
        fun update(
            sourceId: String,
            taskId: String,
            executionId: String,
            status: String,
            title: String,
            deviceName: String,
        ): TaskEvent? {
            val source = sourceStates.getOrPut(sourceId) { SourceState() }
            val previous = source.works.put(executionId, status)
            source.frameSawState = true
            // 背景执行器可能在同一帧/同一节点的多个分支里重复出现（例如先 running、
            // 再 completed，或 walkBackgroundTasks 与 walkBackgroundWorks 同时命中）。
            // 首次基线帧只记录状态，绝不返回任何终态事件；否则应用重启/重连后的
            // 历史快照会把旧任务全部误报为“刚完成/失败”并批量弹出通知。
            if (!source.baselineEstablished) return null
            if (status !in TERMINAL_STATUSES || previous == status) return null
            val mapped = if (status in FAILED_STATUSES) {
                TaskEvent.Type.TASK_FAILED
            } else {
                TaskEvent.Type.TASK_COMPLETED
            }
            return TaskEvent(
                type = mapped,
                taskId = taskId,
                taskTitle = title.take(80),
                deviceName = deviceName,
                eventKey = "$sourceId|$taskId|${mapped.name}|$executionId",
            )
        }

        @Synchronized
        fun endFrame(sourceId: String) {
            val source = sourceStates[sourceId]
            if (source != null && source.frameStarted) {
                if (source.frameSawState) {
                    source.baselineEstablished = true
                }
                source.frameStarted = false
                source.frameSawState = false
            }
        }

        companion object {
            private val TERMINAL_STATUSES = setOf(
                "completed", "completedsuccess", "completedinterrupted",
                "exited", "exit", "finished", "stopped", "resultpending",
                "done", "success", "failed", "error", "cancelled", "canceled",
            )
            private val FAILED_STATUSES = setOf("failed", "error", "cancelled", "canceled")
        }
    }

    private fun walkBackgroundTasks(
        node: Any?,
        depth: Int,
        out: MutableList<TaskEvent>,
        deviceName: String,
        sourceId: String,
    ) {
        if (depth > MAX_DEPTH || node == null) return
        when (node) {
            is JSONObject -> {
                backgroundTaskStateOf(node)?.let { state ->
                    backgroundTaskTracker.update(
                        sourceId, state.taskId, state.executionId,
                        state.status, state.title, deviceName,
                    )?.let(out::add)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    walkBackgroundTasks(node.opt(keys.next()), depth + 1, out, deviceName, sourceId)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkBackgroundTasks(node.opt(i), depth + 1, out, deviceName, sourceId)
                }
            }
        }
    }

    private data class BackgroundTaskState(
        val taskId: String,
        val executionId: String,
        val status: String,
        val title: String,
    )

    private data class BackgroundWorkState(
        val taskId: String,
        val executionId: String,
        val status: String,
        val title: String,
    )

    /**
     * conversation topic 的 state.updated 会维护 backgroundWorks。
     * 这里的会话 ID 在 topic/frame 中，任务条目本身通常只携带 exec_ ID、
     * command/status/exitCode；必须沿当前遍历路径向下传 taskId。
     */
    private fun walkBackgroundWorks(
        node: Any?,
        depth: Int,
        inheritedTaskId: String?,
        out: MutableList<TaskEvent>,
        deviceName: String,
        sourceId: String,
    ) {
        if (depth > MAX_DEPTH || node == null) return
        when (node) {
            is JSONObject -> {
                val topicTaskId = node.optString("topic", "")
                    .takeIf { it.startsWith("conversation/") }
                    ?.removePrefix("conversation/")
                    ?.takeIf { it.isNotBlank() }
                val taskId = taskIdOf(node) ?: topicTaskId ?: inheritedTaskId
                node.optJSONObject("backgroundWorks")?.let { works ->
                    appendBackgroundWorks(works, taskId, out, deviceName, sourceId)
                }
                node.optJSONArray("backgroundWorks")?.let { works ->
                    appendBackgroundWorks(works, taskId, out, deviceName, sourceId)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    walkBackgroundWorks(
                        node.opt(keys.next()), depth + 1, taskId,
                        out, deviceName, sourceId,
                    )
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkBackgroundWorks(
                        node.opt(i), depth + 1, inheritedTaskId,
                        out, deviceName, sourceId,
                    )
                }
            }
        }
    }

    private fun appendBackgroundWorks(
        container: Any,
        taskId: String?,
        out: MutableList<TaskEvent>,
        deviceName: String,
        sourceId: String,
    ) {
        if (taskId.isNullOrBlank()) return
        when (container) {
            is JSONArray -> {
                for (i in 0 until container.length()) {
                    backgroundWorkStateOf(container.optJSONObject(i), taskId)?.let { state ->
                        emitBackgroundWork(state, out, deviceName, sourceId)
                    }
                }
            }
            is JSONObject -> {
                val keys = container.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = container.opt(key)
                    if (value is JSONObject) {
                        backgroundWorkStateOf(value, taskId, key)?.let { state ->
                            emitBackgroundWork(state, out, deviceName, sourceId)
                        }
                    }
                }
            }
        }
    }

    private fun backgroundWorkStateOf(
        work: JSONObject?,
        taskId: String,
        keyHint: String? = null,
    ): BackgroundWorkState? {
        if (work == null) return null
        val executionId = firstString(
            work, "executionId", "execId", "runId", "commandId", "workId", "id",
        ) ?: keyHint?.takeIf { it.startsWith("exec_") || it.contains("exec") }
        if (executionId.isNullOrBlank() ||
            !(executionId.startsWith("exec_") || executionId.contains("exec"))
        ) return null
        val rawStatus = firstString(work, "status", "state", "phase")?.lowercase()
        val exitCode = firstLong(work, "exitCode", "exit_code")
        val status = when {
            rawStatus != null -> rawStatus
            exitCode == null -> "running"
            exitCode == 0L -> "completed"
            else -> "failed"
        }
        val title = firstString(work, "title", "command", "description", "summary") ?: "后台任务"
        return BackgroundWorkState(taskId, executionId, status, title)
    }

    private fun emitBackgroundWork(
        state: BackgroundWorkState,
        out: MutableList<TaskEvent>,
        deviceName: String,
        sourceId: String,
    ) {
        backgroundTaskTracker.update(
            sourceId, state.taskId, state.executionId,
            state.status, state.title, deviceName,
        )?.let(out::add)
    }

    private fun backgroundTaskStateOf(node: JSONObject): BackgroundTaskState? {
        // 远端后台任务完成也可能只以 <task-notification> 文本进入消息流：
        // 该消息不是会话状态，但会带 exec_... 执行器 ID 与完成结果。
        notificationTextStateOf(node)?.let { return it }
        val status = firstString(
            node,
            "status", "state", "phase", "displayStatus", "resultStatus",
        )?.lowercase() ?: return null
        val executionId = firstString(
            node,
            "executionId", "execId", "runId", "commandId", "id",
        )?.takeIf { it.startsWith("exec_") || it.contains("exec") } ?: return null
        val taskId = taskIdOf(node) ?: return null
        val title = firstString(node, "title", "command", "description", "summary") ?: "后台任务"
        return BackgroundTaskState(taskId, executionId, status, title)
    }

    // ---- 路径 4：任务索引 ------------------------------------------------

    /**
     * 新版远端会同时发送任务索引。它与 session.upserted 的字段布局不同：
     * taskId 在 address/meta，状态在 activity/liveStatus/meta.status；若只解析
     * sessionId，完成/失败事件会直接漏掉。
     */
    private fun walkTaskIndex(
        node: Any?,
        depth: Int,
        out: MutableList<TaskSessionTracker.SessionState>,
    ) {
        if (depth > MAX_DEPTH || node == null) return
        when (node) {
            is JSONObject -> {
                if (node.optString("op") == "task.upserted") {
                    node.optJSONObject("task")?.let(::taskIndexStateOf)?.let(out::add)
                }
                node.optJSONObject("payload")?.let { payload ->
                    if (payload.optString("kind") == "snapshot") {
                        payload.optJSONObject("snapshot")?.optJSONArray("tasks")
                            ?.let { tasks -> appendTaskStates(tasks, out, indexed = true) }
                    }
                }
                node.optJSONObject("result")?.optJSONArray("tasks")
                    ?.let { tasks -> appendTaskStates(tasks, out, indexed = false) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    walkTaskIndex(node.opt(keys.next()), depth + 1, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkTaskIndex(node.opt(i), depth + 1, out)
                }
            }
        }
    }

    private fun appendTaskStates(
        tasks: JSONArray,
        out: MutableList<TaskSessionTracker.SessionState>,
        indexed: Boolean,
    ) {
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            val state = if (indexed) taskIndexStateOf(task) else flatTaskStateOf(task)
            if (state != null) out.add(state)
        }
    }

    private fun taskIndexStateOf(task: JSONObject): TaskSessionTracker.SessionState? {
        val membership = task.optJSONObject("membership")
        if (membership?.optBoolean("archived", false) == true) return null
        val address = task.optJSONObject("address")
        val meta = task.optJSONObject("meta")
        val id = firstString(address, "taskId") ?: firstString(meta, "taskId")
            ?: firstString(task, "taskId") ?: return null
        val activity = task.optJSONObject("activity")
        val phase = firstString(activity, "phase")
            ?: firstString(task, "liveStatus")
            ?: firstString(meta, "status")?.let(::phaseFromDisplayStatus)
        // task.upserted 与 session.upserted 的交互布局不同：计数在
        // activity.pendingInteractions（复数），详情在 meta.pendingInteraction。
        // 只读 phase 会漏掉 tasks-index 流里的审批/提问（列表页只弹一条的根因）。
        val pendingCounts = activity?.optJSONObject("pendingInteractions")
        val permCount = pendingCounts?.takeIf { it.has("permissionCount") }
            ?.optInt("permissionCount", 0)
        val inputCount = pendingCounts?.takeIf { it.has("userInputCount") }
            ?.optInt("userInputCount", 0)
        val pending = meta?.optJSONObject("pendingInteraction")
            ?: task.optJSONObject("pendingInteraction")
        val interactionId = pending?.let { firstString(it, "interactionId", "interaction_id") }
        val interactionKind = pending?.optString("kind", "")?.ifEmpty { null }
        val toolName = pending?.optString("toolName", "")?.ifEmpty { null }
        val description = pending?.let { firstString(it, "description", "summary") }
        val lastActivityAt = firstLong(activity, "lastActivityAt", "last_activity_at")
            ?: firstLong(meta, "updatedAt", "updated_at", "lastActivityAt")
            ?: firstLong(task, "updatedAt", "updated_at", "lastActivityAt")
        return TaskSessionTracker.SessionState(
            sessionId = id,
            title = firstString(meta, "title") ?: firstString(task, "title"),
            phase = phase,
            sessionEnded = null,
            permissionCount = permCount,
            userInputCount = inputCount,
            interactionKind = interactionKind,
            toolName = toolName,
            description = description,
            interactionId = interactionId,
            lastActivityAt = lastActivityAt,
            createdAt = firstLong(meta, "createdAt", "created_at")
                ?: firstLong(task, "createdAt", "created_at"),
        )
    }

    private fun flatTaskStateOf(task: JSONObject): TaskSessionTracker.SessionState? {
        val id = firstString(task, "taskId") ?: return null
        val activity = task.optJSONObject("activity")
        val meta = task.optJSONObject("meta")
        val pendingCounts = activity?.optJSONObject("pendingInteractions")
        val permCount = pendingCounts?.takeIf { it.has("permissionCount") }
            ?.optInt("permissionCount", 0)
        val inputCount = pendingCounts?.takeIf { it.has("userInputCount") }
            ?.optInt("userInputCount", 0)
        val pending = task.optJSONObject("pendingInteraction")
            ?: meta?.optJSONObject("pendingInteraction")
        return TaskSessionTracker.SessionState(
            sessionId = id,
            title = firstString(task, "title") ?: firstString(meta, "title"),
            phase = firstString(task, "displayStatus")?.let(::phaseFromDisplayStatus)
                ?: firstString(task, "liveStatus")
                ?: firstString(activity, "phase"),
            sessionEnded = null,
            permissionCount = permCount,
            userInputCount = inputCount,
            interactionKind = pending?.optString("kind", "")?.ifEmpty { null },
            toolName = pending?.optString("toolName", "")?.ifEmpty { null },
            description = pending?.let { firstString(it, "description", "summary") },
            interactionId = pending?.let { firstString(it, "interactionId", "interaction_id") },
            lastActivityAt = firstLong(task, "lastActivityAt", "last_activity_at")
                ?: firstLong(task, "updatedAt", "updated_at")
                ?: firstLong(activity, "lastActivityAt", "last_activity_at"),
            createdAt = firstLong(task, "createdAt", "created_at")
                ?: firstLong(meta, "createdAt", "created_at"),
        )
    }

    private fun notificationTextStateOf(node: JSONObject): BackgroundTaskState? {
        val text = firstString(node, "text", "content", "message", "body") ?: return null
        if (!text.contains("task-notification")) return null
        val executionId = Regex("exec_[A-Za-z0-9_-]+").find(text)?.value ?: return null
        val failed = Regex("失败|错误|error|failed", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val completed = Regex("完成|成功|completed|success", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        if (!failed && !completed) return null
        val taskId = taskIdOf(node) ?: return null
        val title = firstString(node, "title", "summary") ?: "后台任务"
        return BackgroundTaskState(
            taskId = taskId,
            executionId = executionId,
            status = if (failed) "failed" else "completed",
            title = title,
        )
    }

    private fun phaseFromDisplayStatus(status: String): String? = when (status) {
        "completed" -> "completedSuccess"
        "error" -> "error"
        "running" -> "running"
        else -> null
    }

    private fun firstString(node: JSONObject?, vararg keys: String): String? {
        if (node == null) return null
        for (key in keys) {
            val v = node.optString(key, "")
            if (v.isNotEmpty()) return v
        }
        return null
    }

    private fun firstLong(node: JSONObject?, vararg keys: String): Long? {
        if (node == null) return null
        for (key in keys) {
            if (!node.has(key)) continue
            when (val value = node.opt(key)) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return null
    }

    /** 显式事件的任务信息有时被包在 session/payload/data 内，不能只读当前节点。 */
    private fun taskIdOf(node: JSONObject): String? {
        firstString(node, "taskId", "task_id", "sessionId", "session_id")?.let { return it }
        for (key in arrayOf("session", "payload", "data", "task")) {
            node.optJSONObject(key)?.let { nested ->
                firstString(nested, "taskId", "task_id", "sessionId", "session_id")?.let { return it }
            }
        }
        return null
    }

    private fun summaryOf(node: JSONObject): String? {
        firstString(node, "title", "description", "kind", "toolName")?.let { return it }
        for (key in arrayOf("session", "payload", "data", "task")) {
            node.optJSONObject(key)?.let { nested ->
                firstString(nested, "title", "description", "kind", "toolName")?.let { return it }
            }
        }
        return null
    }

    fun logParsed(event: TaskEvent) {
        Log.d(TAG, "task event: type=${event.type} id=${event.taskId} title=${event.taskTitle}")
    }
}
