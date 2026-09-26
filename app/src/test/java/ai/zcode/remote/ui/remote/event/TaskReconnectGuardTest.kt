package ai.zcode.remote.ui.remote.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskReconnectGuardTest {

    private fun state(
        id: String,
        phase: String? = "running",
        createdAt: Long? = null,
    ) = TaskSessionTracker.SessionState(
        sessionId = id,
        title = "任务 $id",
        phase = phase,
        sessionEnded = false,
        permissionCount = 0,
        userInputCount = 0,
        interactionKind = null,
        toolName = null,
        description = "任务描述",
        createdAt = createdAt,
    )

    @Test
    fun initialCompletedSnapshotDoesNotEmitHistoricalFailuresAfterReconnect() {
        val tracker = TaskSessionTracker()
        val first = tracker.update(
            "connection-a",
            listOf(state("old-failed", "error"), state("old-done", "completedSuccess")),
            "设备 A",
        )
        val reconnect = tracker.update(
            "connection-a",
            listOf(state("old-failed", "error"), state("old-done", "completedSuccess")),
            "设备 A",
        )
        assertEquals(0, first.count { it.type == TaskEventParser.TaskEvent.Type.TASK_FAILED })
        assertEquals(0, first.count { it.type == TaskEventParser.TaskEvent.Type.TASK_COMPLETED })
        assertTrue(reconnect.isEmpty())
    }

    @Test
    fun newTerminalSessionCreatedAfterBaselineStillNotifies() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("old", "running")), "设备 A")
        val baselineAt = System.currentTimeMillis()
        val events = tracker.update(
            "connection-a",
            listOf(state("new", "completedSuccess", createdAt = baselineAt + 1000)),
            "设备 A",
        )
        assertEquals(1, events.count { it.type == TaskEventParser.TaskEvent.Type.TASK_COMPLETED })
        assertEquals("new", events.single().taskId)
    }
}
