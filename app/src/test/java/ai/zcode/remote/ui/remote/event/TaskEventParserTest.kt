package ai.zcode.remote.ui.remote.event

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskEventParserTest {

    @Test
    fun `explicit and session completion in one frame are merged`() {
        val sourceId = "parser-merge"
        TaskEventParser.parse(
            """{
                "sessionId":"task-1",
                "title":"任务",
                "phase":"running",
                "pendingInteractionSummary":{"permissionCount":0,"userInputCount":0}
            }""".trimIndent(),
            "设备",
            sourceId,
        )

        val events = TaskEventParser.parse(
            """{
                "event":"completed",
                "taskId":"task-1",
                "title":"任务",
                "session":{"sessionId":"task-1","title":"任务","phase":"completedSuccess","pendingInteractionSummary":{"permissionCount":0,"userInputCount":0}}
            }""".trimIndent(),
            "设备",
            sourceId,
        )

        assertEquals(1, events.count { it.type == TaskEventParser.TaskEvent.Type.TASK_COMPLETED })
    }

    @Test
    fun `partial session delta preserves pending interaction state`() {
        val sourceId = "parser-partial"
        TaskEventParser.parse(
            """{"sessionId":"task-1","phase":"running","pendingInteractionSummary":{"permissionCount":1,"userInputCount":0}}""",
            "设备",
            sourceId,
        )

        val events = TaskEventParser.parse(
            """{"sessionId":"task-1","phase":"running"}""",
            "设备",
            sourceId,
        )

        assertEquals(0, events.count { it.type == TaskEventParser.TaskEvent.Type.RESOLVED })
    }

    @Test
    fun `nested explicit event finds session id`() {
        val events = TaskEventParser.parse(
            """{"event":"permission_request","payload":{"sessionId":"task-nested","description":"请确认"}}""",
            "设备",
            "parser-nested",
        )

        assertEquals(TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST, events.single().type)
        assertEquals("task-nested", events.single().taskId)
    }

    @Test
    fun `task index upsert emits completion after running baseline`() {
        val sourceId = "parser-task-index"
        TaskEventParser.parse(
            """{"op":"task.upserted","task":{"address":{"taskId":"task-index"},"meta":{"title":"索引任务"},"activity":{"phase":"running"}}}""",
            "设备",
            sourceId,
        )

        val events = TaskEventParser.parse(
            """{"op":"task.upserted","task":{"address":{"taskId":"task-index"},"meta":{"title":"索引任务"},"activity":{"phase":"completedSuccess"}}}""",
            "设备",
            sourceId,
        )

        assertEquals(TaskEventParser.TaskEvent.Type.TASK_COMPLETED, events.single().type)
        assertEquals("task-index", events.single().taskId)
    }

    @Test
    fun `explicit completed event alone does not notify historical task`() {
        val events = TaskEventParser.parse(
            """{"event":"completed","taskId":"old-task","title":"历史任务"}""",
            "设备",
            "parser-explicit-terminal",
        )

        assertEquals(0, events.size)
    }

    @Test
    fun `background execution completion emits a notification event once`() {
        val sourceId = "parser-background-exec"
        TaskEventParser.parse(
            """{"taskId":"task-bg","executionId":"exec_53ab78b1-5c8c-4a2c-a6a4-6aff52549a4f","title":"Run 60-second background wait task","status":"running"}""",
            "设备", sourceId,
        )
        val body = """
            {
              "taskId":"task-bg",
              "executionId":"exec_53ab78b1-5c8c-4a2c-a6a4-6aff52549a4f",
              "title":"Run 60-second background wait task",
              "status":"completed"
            }
        """.trimIndent()

        val first = TaskEventParser.parse(body, "设备", sourceId)
        val duplicate = TaskEventParser.parse(body, "设备", sourceId)

        assertEquals(TaskEventParser.TaskEvent.Type.TASK_COMPLETED, first.single().type)
        assertEquals("task-bg", first.single().taskId)
        assertEquals(0, duplicate.size)
    }

    @Test
    fun `task notification text with execution id emits completion`() {
        TaskEventParser.parse(
            """{"taskId":"task-bg-text","executionId":"exec_613c282c-91bb-43dd-8f3b-dcd851860df6","status":"running"}""",
            "设备", "parser-background-text",
        )
        val events = TaskEventParser.parse(
            """{"taskId":"task-bg-text","text":"<task-notification>exec_613c282c-91bb-43dd-8f3b-dcd851860df6 已完成</task-notification>"}""",
            "设备",
            "parser-background-text",
        )

        assertEquals(TaskEventParser.TaskEvent.Type.TASK_COMPLETED, events.single().type)
        assertEquals("task-bg-text", events.single().taskId)
    }

    @Test
    fun `background works state update emits completion at terminal edge`() {
        val sourceId = "parser-background-works"
        val running = """
            {"topic":"conversation/sess-bg","payload":{"deltas":[{"op":"state.updated","patch":{"backgroundWorks":[{"workId":"exec_work-1","command":"sleep 60","status":"running"}]}}]}}
        """.trimIndent()
        val completed = """
            {"topic":"conversation/sess-bg","payload":{"deltas":[{"op":"state.updated","patch":{"backgroundWorks":[{"workId":"exec_work-1","command":"sleep 60","status":"resultPending"}]}}]}}
        """.trimIndent()

        TaskEventParser.parse(running, "设备", sourceId)
        val events = TaskEventParser.parse(completed, "设备", sourceId)

        assertEquals(TaskEventParser.TaskEvent.Type.TASK_COMPLETED, events.single().type)
        assertEquals("sess-bg", events.single().taskId)
    }

    @Test
    fun `initial background works snapshot only establishes baseline`() {
        val events = TaskEventParser.parse(
            """{"topic":"conversation/sess-bg-history","payload":{"deltas":[{"op":"state.updated","patch":{"backgroundWorks":[{"workId":"exec_history-1","command":"sleep 60","status":"resultPending"}]}}]}}""",
            "设备",
            "parser-background-history",
        )

       assertEquals(0, events.size)
   }

    @Test
    fun `task index pending interaction emits elicitation request after running baseline`() {
        val sourceId = "parser-task-index-input"
        TaskEventParser.parse(
            """{"op":"task.upserted","task":{"address":{"taskId":"task-input"},"meta":{"title":"提问任务"},"activity":{"phase":"running"}}}""",
            "设备", sourceId,
        )

        val events = TaskEventParser.parse(
            """{"op":"task.upserted","task":{"address":{"taskId":"task-input"},"meta":{"title":"提问任务","pendingInteraction":{"interactionId":"perm_q-1","kind":"userInput","toolName":"AskUserQuestion","description":"请确认"}},"activity":{"phase":"running","pendingInteractions":{"permissionCount":0,"userInputCount":1}}}}""",
            "设备", sourceId,
        )

        assertEquals(TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST, events.single().type)
        assertEquals("task-input", events.single().taskId)
    }

    @Test
    fun `task index pending interactions notify multiple task sessions independently`() {
        val sourceId = "parser-task-index-two"
        TaskEventParser.parse(
            """{"payload":{"deltas":[{"op":"task.upserted","task":{"address":{"taskId":"task-a"},"activity":{"phase":"running"}}},{"op":"task.upserted","task":{"address":{"taskId":"task-b"},"activity":{"phase":"running"}}}]}}""",
            "设备", sourceId,
        )

        val events = TaskEventParser.parse(
            """{"payload":{"deltas":[{"op":"task.upserted","task":{"address":{"taskId":"task-a"},"meta":{"pendingInteraction":{"interactionId":"perm_a-1","kind":"userInput","toolName":"AskUserQuestion"}},"activity":{"phase":"running","pendingInteractions":{"permissionCount":0,"userInputCount":1}}}},{"op":"task.upserted","task":{"address":{"taskId":"task-b"},"meta":{"pendingInteraction":{"interactionId":"perm_b-1","kind":"userInput","toolName":"AskUserQuestion"}},"activity":{"phase":"running","pendingInteractions":{"permissionCount":0,"userInputCount":1}}}}]}}""",
            "设备", sourceId,
        )

        assertEquals(2, events.count { it.type == TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST })
        assertEquals(
            setOf("task-a", "task-b"),
            events.map { it.taskId }.toSet(),
        )
    }

    @Test
    fun `task index flat tasks pending permission emits request after running baseline`() {
        val sourceId = "parser-task-index-flat"
        TaskEventParser.parse(
            """{"result":{"tasks":[{"taskId":"flat-task","activity":{"phase":"running","pendingInteractions":{"permissionCount":0,"userInputCount":0}}}]}}""",
            "设备", sourceId,
        )

        val events = TaskEventParser.parse(
            """{"result":{"tasks":[{"taskId":"flat-task","meta":{"pendingInteraction":{"interactionId":"perm_flat-1","kind":"toolPermission","toolName":"Shell"}},"activity":{"phase":"running","pendingInteractions":{"permissionCount":1,"userInputCount":0}}}]}}""",
            "设备", sourceId,
        )

        assertEquals(TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST, events.single().type)
        assertEquals("flat-task", events.single().taskId)
    }
}
