package ai.zcode.remote.ui.remote.event

import ai.zcode.remote.data.model.TaskReminderMode
import ai.zcode.remote.data.model.TaskReminderType
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.ui.remote.event.TaskEventParser.TaskEvent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 验证覆盖升级后仍沿用旧渠道，并用系统实际显示的通知核对各类路由。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class TaskReminderRoutingTest {
    @Test
    fun postsToLegacySharedAndCustomChannels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val settings = AppSettingsRepository.getInstance(context)
        val legacyId = "zcode_task_approvals"
        val createdLegacy = manager.getNotificationChannel(legacyId) == null
        val beforeModes = TaskReminderType.entries.associateWith(settings::getTaskReminderMode)
        val beforeMaster = settings.isNotificationEnabled()
        val beforeApproval = settings.isNotifApprovalEnabled()
        val beforeQuestion = settings.isNotifElicitationEnabled()
        val beforeCompleted = settings.isNotifCompletedEnabled()
        val beforeFailed = settings.isNotifFailedEnabled()
        val source = "reminder-${UUID.randomUUID()}"
        val events = mutableListOf<TaskEvent>()

        fun verifyPost(type: TaskEvent.Type, expectedChannel: String) {
            val task = "$source-${events.size}"
            val event = TaskEvent(type, task, "通知提醒回归", "", "$source|$task|$type", sourceId = source)
            events += event
            TaskNotifier.notify(context, event)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            val id = if (type == TaskEvent.Type.PERMISSION_REQUEST || type == TaskEvent.Type.ELICITATION_REQUEST) {
                task.hashCode()
            } else {
                event.eventKey.hashCode()
            }
            var actual: String? = null
            while (actual == null && SystemClock.elapsedRealtime() < deadline) {
                actual = manager.activeNotifications.firstOrNull { it.id == id }?.notification?.channelId
                if (actual == null) SystemClock.sleep(50)
            }
            assertEquals(type.name, expectedChannel, actual)
        }

        try {
            assertTrue(manager.areNotificationsEnabled())
            if (createdLegacy) manager.createNotificationChannel(
                NotificationChannel(legacyId, "旧版审批", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            val legacy = manager.getNotificationChannel(legacyId)
            settings.setNotificationEnabled(true)
            settings.setNotifApprovalEnabled(true)
            settings.setNotifElicitationEnabled(true)
            settings.setNotifCompletedEnabled(true)
            settings.setNotifFailedEnabled(true)
            settings.setTaskReminderMode(TaskReminderType.APPROVAL, null)
            settings.setTaskReminderMode(TaskReminderType.QUESTION, TaskReminderMode.CUSTOM)
            settings.setTaskReminderMode(TaskReminderType.COMPLETED, TaskReminderMode.SHARED)
            settings.setTaskReminderMode(TaskReminderType.FAILED, TaskReminderMode.SHARED)

            verifyPost(TaskEvent.Type.PERMISSION_REQUEST, legacyId)
            verifyPost(TaskEvent.Type.ELICITATION_REQUEST, "zcode_task_reminder_question")
            verifyPost(TaskEvent.Type.TASK_COMPLETED, "zcode_task_reminder_shared")
            verifyPost(TaskEvent.Type.TASK_FAILED, "zcode_task_reminder_shared")
            val shared = manager.getNotificationChannel("zcode_task_reminder_shared")
            assertNotNull(shared.sound)
            assertTrue(shared.shouldVibrate())

            settings.setTaskReminderMode(TaskReminderType.APPROVAL, TaskReminderMode.CUSTOM)
            verifyPost(TaskEvent.Type.PERMISSION_REQUEST, "zcode_task_reminder_approval")
            settings.setTaskReminderMode(TaskReminderType.APPROVAL, TaskReminderMode.SHARED)
            verifyPost(TaskEvent.Type.PERMISSION_REQUEST, "zcode_task_reminder_shared")
            val currentLegacy = manager.getNotificationChannel(legacyId)
            assertEquals(legacy.sound, currentLegacy.sound)
            assertEquals(legacy.shouldVibrate(), currentLegacy.shouldVibrate())
            assertEquals(legacy.importance, currentLegacy.importance)
        } finally {
            for ((type, mode) in beforeModes) settings.setTaskReminderMode(type, mode)
            settings.setNotificationEnabled(beforeMaster)
            settings.setNotifApprovalEnabled(beforeApproval)
            settings.setNotifElicitationEnabled(beforeQuestion)
            settings.setNotifCompletedEnabled(beforeCompleted)
            settings.setNotifFailedEnabled(beforeFailed)
            TaskNotificationInbox(context).use { inbox ->
                for (event in events) {
                    manager.cancel(source, event.taskId.hashCode())
                    manager.cancel(event.eventKey.hashCode())
                    inbox.writableDatabase.delete("event_inbox", "event_key = ?", arrayOf(event.eventKey))
                }
            }
            if (createdLegacy) manager.deleteNotificationChannel(legacyId)
        }
    }
}
