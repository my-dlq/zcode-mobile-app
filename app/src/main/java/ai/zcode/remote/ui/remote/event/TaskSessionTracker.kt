package ai.zcode.remote.ui.remote.event

/**
 * 按远端连接维护会话状态基线。首次同步只建立历史基线，后续首次出现的新会话
 * 即使第一帧已经结束，也视为监听期间发生的完成/失败事件。
 */
internal class TaskSessionTracker {

    internal data class SessionState(
        val sessionId: String,
        val title: String?,
        val phase: String?,
        val sessionEnded: Boolean?,
        // 索引增量并不总是携带 pendingInteractionSummary。null 表示
        // "本次报文未更新该字段"，必须与上一份快照合并，不能误作 0。
        val permissionCount: Int?,
        val userInputCount: Int?,
        val interactionKind: String?,
        val toolName: String?,
        val description: String?,
        val interactionId: String? = null,
        val lastActivityAt: Long? = null,
        val createdAt: Long? = null,
    )

    private data class SourceState(
        val sessions: HashMap<String, SessionState> = HashMap(),
        val interactionGenerations: HashMap<String, Long> = HashMap(),
        val lastRequestKeyBySession: HashMap<String, String> = HashMap(),
        var baselineEstablished: Boolean = false,
        var baselineAtMillis: Long = 0L,
    )

    private val sources = HashMap<String, SourceState>()

    @Synchronized
    fun update(
        sourceId: String,
        incoming: List<SessionState>,
        deviceName: String,
    ): List<TaskEventParser.TaskEvent> {
        if (incoming.isEmpty()) return emptyList()

        val source = sources.getOrPut(sourceId) { SourceState() }
        val isInitialSnapshot = !source.baselineEstablished
        if (isInitialSnapshot) {
            source.baselineAtMillis = System.currentTimeMillis()
        }
        val events = ArrayList<TaskEventParser.TaskEvent>()

        for (patch in incoming) {
            val prev = source.sessions[patch.sessionId]
            // 终态后任务被重新激活（失败/完成后用户再次发起，复用同一 sessionId，
            // 索引条目从 error/completed 回到 running 且活动时间戳更新）时，
            // 必须放行让状态回到 running，否则第二次终态不会产生新事件。
            // 用 patch 的原始时间戳判断，避免 mergeWith 清空 lastActivityAt
            // 导致无法区分“重新激活”与“迟到的旧 running 帧”。
            val reactivated = prev?.phase in TERMINAL_PHASES && patch.phase == "running" &&
                patch.patchNewerThan(prev)
            // 同一 wire frame 里可能同时有 session 引用、局部 delta 与完整 upsert。
            // 按字段合并，既避免局部 delta 清空待处理数量，也让后面的完整状态覆盖前面。
            val next = if (reactivated) patch.copy(phase = "running") else patch.mergeWith(prev)
            source.sessions[next.sessionId] = next
            val prevPerm = prev?.permissionCount ?: 0
            val prevInput = prev?.userInputCount ?: 0
            val nextPerm = next.permissionCount ?: 0
            val nextInput = next.userInputCount ?: 0
            val interactionChanged = patch.interactionId != null &&
                prev?.interactionId != null && patch.interactionId != prev.interactionId

            // 首次同步时仍要通知当前待处理审批/提问；仅完成/失败态作为历史基线。
            if (nextPerm > 0 && (prevPerm == 0 || interactionChanged)) {
                val request = next.toEvent(
                    TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST, deviceName, sourceId,
                    nextGeneration(source, next.sessionId),
                )
                source.lastRequestKeyBySession[next.sessionId] = request.eventKey
                events.add(request)
            }
            if (nextInput > 0 && (prevInput == 0 || interactionChanged)) {
                val request = next.toEvent(
                    TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST, deviceName, sourceId,
                    nextGeneration(source, next.sessionId),
                )
                source.lastRequestKeyBySession[next.sessionId] = request.eventKey
                events.add(request)
            }

            // permission 与 elicitation 可能在同一帧直接切换。只有全部待处理交互
            // 都归零时才撤回通知，避免“新提问入队后立刻被旧审批 resolved 清掉”。
            if (prevPerm + prevInput > 0 && nextPerm + nextInput == 0) {
                val resolved = next.toEvent(TaskEventParser.TaskEvent.Type.RESOLVED, deviceName, sourceId)
                // RESOLVED 携带最近一次请求的 eventKey：交互归零后 interactionId /
                // lastActivityAt 会被清空，若重新生成 key 将无法与请求事件一一对应，
                // 客户端按 taskId 全局清理时会误删同一任务新入队的下一次审批。
                val pairedResolved = source.lastRequestKeyBySession[next.sessionId]
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { key -> resolved.copy(eventKey = key) }
                    ?: resolved
                events.add(pairedResolved)
            }

            // 有些索引更新只给 sessionEnded，不再重复给 completedSuccess；两种终态
            // 都应产生完成通知，但 error 必须优先走失败通知。
            val isDone = isSuccessfulTerminal(next)
            val wasDone = prev?.let(::isSuccessfulTerminal) == true
            val newlyTerminal = isDone && (
                (prev != null && !wasDone) ||
                    (prev == null && !isInitialSnapshot &&
                        occurredAfterBaseline(next, source))
                )
            if (!isInitialSnapshot && newlyTerminal) {
                events.add(next.toEvent(TaskEventParser.TaskEvent.Type.TASK_COMPLETED, deviceName, sourceId))
            }
            val newError = next.phase == ERROR_PHASE && (
                (prev != null && prev.phase != ERROR_PHASE) ||
                    (prev == null && !isInitialSnapshot &&
                        occurredAfterBaseline(next, source))
                )
            if (!isInitialSnapshot && newError) {
                events.add(next.toEvent(TaskEventParser.TaskEvent.Type.TASK_FAILED, deviceName, sourceId))
            }
        }

        source.baselineEstablished = true
        return events
    }

    private fun SessionState.mergeWith(previous: SessionState?): SessionState {
        // 同一任务的任务索引、会话索引会异步到达。终态已确认后，迟到的 running
        // 不能把它写回运行中；否则下一份 completed 会被误判为一次新完成并重复通知。
        // 但任务失败/完成后用户重新发起会复用同一 sessionId（同一索引条目从
        // error 回到 running），若一律压回终态，第二次失败时 prev 仍是 error，
        // 状态机不产生新的 TASK_FAILED —— 表现为“同一任务第二次失败不通知”。
        // 判别依据：新帧携带更新的 lastActivityAt（updatedAt）时视为重新激活，
        // 允许状态回到 running；时间戳相同或缺失的迟到帧仍被压回终态去重。
        val mergedPhase = when {
            previous?.phase in TERMINAL_PHASES && phase !in TERMINAL_PHASES -> previous?.phase
            phase != null -> phase
            else -> previous?.phase
        }
        val mergedPermissionCount = permissionCount ?: previous?.permissionCount
        val mergedUserInputCount = userInputCount ?: previous?.userInputCount
        val hasPendingInteraction = (mergedPermissionCount ?: 0) +
            (mergedUserInputCount ?: 0) > 0
        return copy(
            title = title ?: previous?.title,
            phase = mergedPhase,
            sessionEnded = if (previous?.sessionEnded == true && sessionEnded == false) true
                else sessionEnded ?: previous?.sessionEnded,
            permissionCount = mergedPermissionCount,
            userInputCount = mergedUserInputCount,
            interactionKind = if (hasPendingInteraction) interactionKind ?: previous?.interactionKind else null,
            toolName = if (hasPendingInteraction) toolName ?: previous?.toolName else null,
            description = if (hasPendingInteraction) description ?: previous?.description else null,
            // 收到明确的 0 待处理数时，上一次交互已经结束；不能把旧 interactionId
            // 带入下一次审批，否则缺少 ID 的新交互会与旧事件键冲突。
            interactionId = if (hasPendingInteraction) interactionId ?: previous?.interactionId else null,
            // lastActivityAt 是任务的活动时间（updatedAt/lastActivityAt），
            // 始终保留最新值：它既作为审批无 interactionId 时的兜底版本，
            // 也用于判断终态后任务是否被重新激活（error→running 新一轮执行）。
            // 交互归零时只清 interactionId，不清 lastActivityAt——审批的
            // RESOLVED 事件键由 lastRequestKeyBySession 配对，不受影响。
            lastActivityAt = lastActivityAt ?: previous?.lastActivityAt,
            createdAt = createdAt ?: previous?.createdAt,
        )
    }

    /** 当前 patch 是否比上一份快照更新（依据活动时间戳，缺失时视为不更新）。 */
    private fun SessionState.patchNewerThan(previous: SessionState?): Boolean {
        val prevStamp = previous?.lastActivityAt ?: previous?.createdAt ?: return false
        val patchStamp = lastActivityAt ?: createdAt ?: return false
        return patchStamp > prevStamp
    }

    private fun isSuccessfulTerminal(state: SessionState): Boolean =
        state.phase in DONE_PHASES ||
            (state.sessionEnded == true && state.phase != ERROR_PHASE)

    private fun nextGeneration(source: SourceState, sessionId: String): Long {
        val next = (source.interactionGenerations[sessionId] ?: 0L) + 1L
        source.interactionGenerations[sessionId] = next
        return next
    }

    private fun occurredAfterBaseline(
        state: SessionState,
        source: SourceState,
    ): Boolean {
        val stamp = state.createdAt ?: state.lastActivityAt ?: return false
        return stamp > source.baselineAtMillis
    }

    private fun SessionState.toEvent(
        type: TaskEventParser.TaskEvent.Type,
        deviceName: String,
        sourceId: String,
        generation: Long = 0L,
    ): TaskEventParser.TaskEvent {
        val eventTitle = when (type) {
            TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST,
            TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST -> description ?: title ?: ""
            else -> title ?: ""
        }
        val version = interactionId ?: lastActivityAt?.toString() ?:
            generation.takeIf { it > 0 }?.toString() ?:
            "${permissionCount ?: 0}:${userInputCount ?: 0}:${title.orEmpty()}"
        val eventKey = "$sourceId|$sessionId|${type.name}|$version"
        return TaskEventParser.TaskEvent(type, sessionId, eventTitle.take(80), deviceName, eventKey)
    }

    private companion object {
        val DONE_PHASES = setOf("completedSuccess", "completedInterrupted")
        const val ERROR_PHASE = "error"
        val TERMINAL_PHASES = DONE_PHASES + ERROR_PHASE
    }
}
