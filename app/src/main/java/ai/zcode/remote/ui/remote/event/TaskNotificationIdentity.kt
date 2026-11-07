package ai.zcode.remote.ui.remote.event

/** 来源 tag 使用完整字符串而非 hash；通知 ID 仍沿用 taskId 的 hash。 */
internal object TaskNotificationIdentity {
    fun sourceId(sourceId: String, eventKey: String): String =
        sourceId.ifBlank { sourceFromKey(eventKey) }

    /** legacy 是本地兜底键的标记，不是连接；旧记录不能因此被路由到虚构连接。 */
    private fun sourceFromKey(eventKey: String): String =
        if (eventKey.startsWith("legacy|")) "" else eventKey.substringBefore('|', "")

    /**
     * 旧无 tag 通知只能凭明确身份兼容；tag、extras、交互键提供的身份如有冲突，
     * 宁可留下旧横幅，也不能删除其他连接的通知。空身份不作为归属证据。
     */
    fun belongsToSource(tag: String?, extraSourceId: String, interactionKey: String, sourceId: String): Boolean {
        if (sourceId.isBlank()) return false
        val keySource = sourceFromKey(interactionKey)
        if (tag != null && tag != sourceId) return false
        if (extraSourceId.isNotBlank() && extraSourceId != sourceId) return false
        if (keySource.isNotBlank() && keySource != sourceId) return false
        return tag == sourceId || extraSourceId == sourceId || keySource == sourceId
    }

    /** 新版空 tag 仍可凭完整交互键配对，但不能把这一兼容扩展到旧 null tag 或连接清理。 */
    fun matchesInteraction(
        tag: String?,
        extraSourceId: String,
        interactionKey: String,
        sourceId: String,
        resolvedKey: String,
    ): Boolean {
        if (resolvedKey.isBlank() || interactionKey != resolvedKey) return false
        return belongsToSource(tag, extraSourceId, interactionKey, sourceId) ||
            (tag == "" && extraSourceId.isEmpty() && sourceId.isEmpty() &&
                sourceFromKey(interactionKey).isEmpty())
    }

    fun legacyFallbackKey(event: TaskEventParser.TaskEvent): String =
        "legacy|${event.deviceName}|${event.taskId}|${event.type}|${event.taskTitle}"

    fun stableKey(event: TaskEventParser.TaskEvent): String = event.eventKey.ifBlank {
        // 长度限定 source，避免 source 自带分隔符时制造另一连接的相同前缀。
        "legacy|${event.sourceId.length}:${event.sourceId}|${event.deviceName}|${event.taskId}|${event.type}|${event.taskTitle}"
    }
}
