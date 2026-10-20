package ai.zcode.remote.ui.remote.event

import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.zcode.remote.data.repository.AppSettingsRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 真实 SQLite 与系统通知回归；仅在独立 debug 包中创建隔离测试交互。 */
@RunWith(AndroidJUnit4::class)
class TaskNotificationPairingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun request(key: String, task: String = "pairing-fixture") = TaskEventParser.TaskEvent(
        TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST, task, "通知配对回归", "", key,
    )

    @Test
    fun resolvedBeforeRequestSurvivesDatabaseReopen() {
        val name = "pairing-${UUID.randomUUID()}.db"
        val event = request("fixture|task|PERMISSION_REQUEST|a")
        try {
            TaskNotificationInbox(context, name).use { inbox ->
                inbox.discardPendingInteraction(event.copy(type = TaskEventParser.TaskEvent.Type.RESOLVED))
                assertFalse(inbox.enqueue(event))
            }
            TaskNotificationInbox(context, name).use { inbox ->
                assertFalse(inbox.enqueue(event))
                assertNull(inbox.claimNext())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun resolvedProcessingALeavesPendingBAndDeliveredHistoryIntact() {
        val name = "pairing-${UUID.randomUUID()}.db"
        val a = request("fixture|task|PERMISSION_REQUEST|a")
        val b = request("fixture|task|PERMISSION_REQUEST|b")
        try {
            TaskNotificationInbox(context, name).use { inbox ->
                assertTrue(inbox.enqueue(a))
                val claimed = inbox.claimNext()!!
                assertTrue(inbox.isProcessing(claimed.rowId))
                assertTrue(inbox.enqueue(b))
                inbox.discardPendingInteraction(a.copy(type = TaskEventParser.TaskEvent.Type.RESOLVED))
                assertFalse(inbox.isProcessing(claimed.rowId))
                val next = inbox.claimNext()!!
                assertEquals(b.eventKey, next.event.eventKey)
                inbox.markDelivered(next.rowId)
                inbox.discardPendingInteraction(b.copy(type = TaskEventParser.TaskEvent.Type.RESOLVED))
                assertFalse(inbox.enqueue(b))
                assertNull(inbox.claimNext())
            }
            TaskNotificationInbox(context, name).use { inbox ->
                assertFalse(inbox.enqueue(a))
                assertFalse(inbox.enqueue(b))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun postedBIsNotCanceledByResolvedAAndMatchingCooldownIsPreserved() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val settings = AppSettingsRepository.getInstance(context)
        val previousEnabled = settings.isNotificationEnabled()
        val previousApproval = settings.isNotifApprovalEnabled()
        val prefix = "pairing-${UUID.randomUUID()}"
        val task = "$prefix-task"
        val a = request("$prefix|$task|PERMISSION_REQUEST|a", task)
        val b = request("$prefix|$task|PERMISSION_REQUEST|b", task)
        fun activeKey() = manager.activeNotifications.firstOrNull { it.id == task.hashCode() }
            ?.notification?.extras?.getString(TaskNotifier.EXTRA_INTERACTION_KEY)
        fun awaitKey(key: String) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (activeKey() != key && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertEquals(key, activeKey())
        }
        try {
            settings.setNotificationEnabled(true)
            settings.setNotifApprovalEnabled(true)
            assertTrue("独立 debug 包需授予通知权限", manager.areNotificationsEnabled())
            TaskNotifier.notify(context, a)
            awaitKey(a.eventKey)
            TaskNotifier.notify(context, b)
            awaitKey(b.eventKey)
            TaskNotifier.notify(context, a.copy(type = TaskEventParser.TaskEvent.Type.RESOLVED))
            assertEquals(b.eventKey, activeKey())
            TaskNotifier.notify(context, b.copy(type = TaskEventParser.TaskEvent.Type.RESOLVED))
            assertEquals("保留原有快照抖动冷却", b.eventKey, activeKey())
            SystemClock.sleep(30_100)
            TaskNotifier.notify(context, b.copy(type = TaskEventParser.TaskEvent.Type.RESOLVED))
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (activeKey() != null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertNull(activeKey())
        } finally {
            manager.cancel(task.hashCode())
            settings.setNotificationEnabled(previousEnabled)
            settings.setNotifApprovalEnabled(previousApproval)
            TaskNotificationInbox(context).use { inbox ->
                for (event in listOf(a, b)) inbox.writableDatabase.delete(
                    "event_inbox", "event_key = ?", arrayOf(event.eventKey),
                )
            }
        }
    }
}
