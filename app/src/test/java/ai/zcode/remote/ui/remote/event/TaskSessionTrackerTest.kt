package ai.zcode.remote.ui.remote.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSessionTrackerTest {

    private fun state(
        id: String,
        phase: String? = "running",
        permissionCount: Int = 0,
    ) = TaskSessionTracker.SessionState(
        sessionId = id,
        title = "任务 $id",
        phase = phase,
        sessionEnded = false,
        permissionCount = permissionCount,
        userInputCount = 0,
        interactionKind = null,
        toolName = null,
        description = "任务描述",
    )

    @Test
    fun `initial snapshot notifies current pending interactions but not history`() {
        val tracker = TaskSessionTracker()

        val events = tracker.update(
            "connection-a",
            listOf(
                state("pending", "running", permissionCount = 1),
                state("old-1", "completedSuccess"),
                state("old-2", "completedInterrupted"),
            ),
            "设备 A",
        )

        assertEquals(1, events.size)
        assertEquals(TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST, events.single().type)
        assertEquals("pending", events.single().taskId)
    }

    @Test
    fun `new completed session after baseline emits completion`() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("old", "running")), "设备 A")

        val after = System.currentTimeMillis() + 1000
        val events = tracker.update(
            "connection-a",
            listOf(state("new", "completedSuccess").copy(createdAt = after, lastActivityAt = after)),
            "设备 A",
        )

        assertEquals(1, events.count { it.type == TaskEventParser.TaskEvent.Type.TASK_COMPLETED })
        assertEquals("new", events.single { it.type == TaskEventParser.TaskEvent.Type.TASK_COMPLETED }.taskId)
    }

    @Test
    fun `running session transition emits completion once`() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("task", "running")), "设备 A")

        val first = tracker.update("connection-a", listOf(state("task", "completedSuccess")), "设备 A")
        val duplicate = tracker.update("connection-a", listOf(state("task", "completedSuccess")), "设备 A")

        assertEquals(1, first.count { it.type == TaskEventParser.TaskEvent.Type.TASK_COMPLETED })
        assertTrue(duplicate.isEmpty())
    }

    @Test
    fun `different connections keep independent baselines`() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("same-id", "running")), "设备 A")
        tracker.update("connection-b", listOf(state("other", "running")), "设备 B")

        val after = System.currentTimeMillis() + 1000
        val events = tracker.update(
            "connection-b",
            listOf(
                state("same-id", "completedSuccess").copy(
                    createdAt = after,
                    lastActivityAt = after,
                ),
            ),
            "设备 B",
        )

        assertEquals(1, events.count { it.type == TaskEventParser.TaskEvent.Type.TASK_COMPLETED })
        assertEquals("设备 B", events.single().deviceName)
    }

    @Test
    fun `permission request is detected after baseline`() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("task", "running")), "设备 A")

        val events = tracker.update("connection-a", listOf(state("task", "running", permissionCount = 1)), "设备 A")

        assertEquals(TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST, events.single().type)
    }

    @Test
    fun `second permission in one session receives a new event key`() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("task", "running")), "设备 A")

        val first = tracker.update(
            "connection-a",
            listOf(state("task", "running", permissionCount = 1).copy(
                interactionId = "interaction-1", lastActivityAt = 100L,
            )),
            "设备 A",
        ).single()
        tracker.update(
            "connection-a",
            listOf(state("task", "running", permissionCount = 0).copy(
                interactionId = null, lastActivityAt = 101L,
            )),
            "设备 A",
        )
        val second = tracker.update(
            "connection-a",
            listOf(state("task", "running", permissionCount = 1).copy(
                interactionId = "interaction-2", lastActivityAt = 200L,
            )),
            "设备 A",
        ).single()

        assertEquals(TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST, first.type)
        assertEquals(TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST, second.type)
        assertTrue(first.eventKey != second.eventKey)
    }

    @Test
    fun `partial update does not clear an existing permission`() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("task", "running", permissionCount = 1)), "设备 A")

        val events = tracker.update(
            "connection-a",
            listOf(state("task", "running").copy(permissionCount = null, userInputCount = null)),
            "设备 A",
        )

        assertTrue(events.none { it.type == TaskEventParser.TaskEvent.Type.RESOLVED })
    }

    @Test
    fun `session ended after baseline emits completion without phase`() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("task", "running")), "设备 A")

        val events = tracker.update(
            "connection-a",
            listOf(state("task", null).copy(sessionEnded = true)),
            "设备 A",
        )

        assertEquals(TaskEventParser.TaskEvent.Type.TASK_COMPLETED, events.single().type)
    }

    @Test
    fun `late running update cannot reopen a completed task`() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("task", "running")), "设备 A")
        tracker.update("connection-a", listOf(state("task", "completedSuccess")), "设备 A")

        val staleRunning = tracker.update(
            "connection-a",
            listOf(state("task", "running")),
            "设备 A",
        )
        val repeatedCompleted = tracker.update(
            "connection-a",
            listOf(state("task", "completedSuccess")),
            "设备 A",
        )

        assertTrue(staleRunning.isEmpty())
        assertTrue(repeatedCompleted.isEmpty())
    }

    @Test
    fun `resolved reuses latest request key and does not match older interaction`() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("task", "running")), "设备 A")

        val first = tracker.update(
            "connection-a",
            listOf(state("task", "running", permissionCount = 1).copy(
                interactionId = "interaction-1", lastActivityAt = 100L,
            )),
            "设备 A",
        ).single()
        val firstResolved = tracker.update(
            "connection-a",
            listOf(state("task", "running", permissionCount = 0).copy(
                interactionId = null, lastActivityAt = 101L,
            )),
            "设备 A",
        ).single()
        val second = tracker.update(
            "connection-a",
            listOf(state("task", "running", permissionCount = 1).copy(
                interactionId = "interaction-2", lastActivityAt = 200L,
            )),
            "设备 A",
        ).single()

       assertEquals(TaskEventParser.TaskEvent.Type.RESOLVED, firstResolved.type)
       assertEquals(first.eventKey, firstResolved.eventKey)
       assertTrue(first.eventKey != second.eventKey)
   }

    @Test
    fun `task reactivated after error emits a second failure with distinct key`() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("task", "running").copy(lastActivityAt = 100L)), "设备 A")
        val first = tracker.update(
            "connection-a",
            listOf(state("task", "error").copy(lastActivityAt = 200L)),
            "设备 A",
        ).single()
        // 失败后用户重新发起：running 带更新的活动时间戳
        tracker.update(
            "connection-a",
            listOf(state("task", "running").copy(lastActivityAt = 300L)),
            "设备 A",
        )
        val second = tracker.update(
            "connection-a",
            listOf(state("task", "error").copy(lastActivityAt = 400L)),
            "设备 A",
        ).single()

        assertEquals(TaskEventParser.TaskEvent.Type.TASK_FAILED, first.type)
        assertEquals(TaskEventParser.TaskEvent.Type.TASK_FAILED, second.type)
        assertTrue(first.eventKey != second.eventKey)
    }

    @Test
    fun `stale running frame after terminal cannot reopen task`() {
        val tracker = TaskSessionTracker()
        tracker.update("connection-a", listOf(state("task", "running").copy(lastActivityAt = 100L)), "设备 A")
        tracker.update(
            "connection-a",
            listOf(state("task", "error").copy(lastActivityAt = 200L)),
            "设备 A",
        )
        // 迟到的旧 running 帧（时间戳不更新）不能把终态写回运行中
        val stale = tracker.update(
            "connection-a",
            listOf(state("task", "running").copy(lastActivityAt = 200L)),
            "设备 A",
        )
        val repeated = tracker.update(
            "connection-a",
            listOf(state("task", "error").copy(lastActivityAt = 200L)),
            "设备 A",
        )
        assertTrue(stale.isEmpty())
        assertTrue(repeated.isEmpty())
    }
}
