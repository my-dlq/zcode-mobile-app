package ai.zcode.remote.ui.remote.event

import ai.zcode.remote.data.model.TaskReminderProfile
import ai.zcode.remote.data.model.TaskReminderTone
import ai.zcode.remote.data.model.TaskReminderType
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.ui.remote.event.TaskEventParser.TaskEvent
import ai.zcode.remote.ui.settings.NotificationTonePicker
import android.app.Notification
import android.app.NotificationManager
import android.media.RingtoneManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 用系统实际显示的通知验证四类事件各自的渠道和提醒方式。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class TaskReminderRoutingTest {
    @Test
    fun postsEachTaskTypeToItsOwnChannel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val settings = AppSettingsRepository.getInstance(context)
        val previousChannels = manager.notificationChannels.associateBy { it.id }
        val sharedId = "zcode_task_reminder_shared"
        val beforeMaster = settings.isNotificationEnabled()
        val beforeAlerts = settings.areTaskReminderAlertsEnabled()
        val beforeApproval = settings.isNotifApprovalEnabled()
        val beforeQuestion = settings.isNotifElicitationEnabled()
        val beforeCompleted = settings.isNotifCompletedEnabled()
        val beforeFailed = settings.isNotifFailedEnabled()
        val previousProfiles = TaskReminderType.entries.associateWith(settings::taskReminderProfile)
        val silentTypes = setOf(TaskReminderType.APPROVAL, TaskReminderType.FAILED)
        val source = "reminder-${UUID.randomUUID()}"
        val events = mutableListOf<TaskEvent>()

        fun postedNotification(type: TaskEvent.Type): Notification {
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
            var actual: Notification? = null
            while (actual == null && SystemClock.elapsedRealtime() < deadline) {
                actual = manager.activeNotifications.firstOrNull {
                    it.id == id && it.notification.extras.getString(TaskNotifier.EXTRA_SOURCE_ID) == source
                }?.notification
                if (actual == null) SystemClock.sleep(50)
            }
            return checkNotNull(actual) { "${type.name} 未投递到系统通知栏" }
        }

        try {
            assertTrue(manager.areNotificationsEnabled())
            settings.setNotificationEnabled(true)
            settings.setTaskReminderAlertsEnabled(true)
            settings.setNotifApprovalEnabled(true)
            settings.setNotifElicitationEnabled(true)
            settings.setNotifCompletedEnabled(true)
            settings.setNotifFailedEnabled(true)
            val silent = TaskReminderProfile(soundEnabled = false, vibrationEnabled = false)
            for (type in TaskReminderType.entries) {
                settings.setTaskReminderProfile(type, silent.takeIf { type in silentTypes })
            }

            val posted = listOf(
                TaskReminderType.APPROVAL to postedNotification(TaskEvent.Type.PERMISSION_REQUEST),
                TaskReminderType.QUESTION to postedNotification(TaskEvent.Type.ELICITATION_REQUEST),
                TaskReminderType.COMPLETED to postedNotification(TaskEvent.Type.TASK_COMPLETED),
                TaskReminderType.FAILED to postedNotification(TaskEvent.Type.TASK_FAILED),
            )
            assertEquals(4, posted.map { it.second.channelId }.toSet().size)
            assertTrue(posted.none { it.second.channelId == sharedId })
            assertEquals(previousChannels.containsKey(sharedId), manager.getNotificationChannel(sharedId) != null)
            val defaultTone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val cleanInstall = previousChannels.keys.none { it.startsWith("zcode_task_") }
            for ((type, notification) in posted) {
                val expectedAlert = if (type in silentTypes) Notification.GROUP_ALERT_SUMMARY else Notification.GROUP_ALERT_ALL
                assertEquals(type.name, expectedAlert, notification.groupAlertBehavior)
                val id = notification.channelId
                val channel = manager.getNotificationChannel(id)
                assertEquals(context.getString(TaskReminderChannels.label(type)), channel.name.toString())
                if (cleanInstall) {
                    assertEquals(defaultTone, channel.sound)
                    assertTrue(channel.shouldVibrate())
                }
                previousChannels[id]?.let { previous ->
                    assertEquals(previous.sound, channel.sound)
                    assertEquals(previous.shouldVibrate(), channel.shouldVibrate())
                    assertEquals(previous.importance, channel.importance)
                }
            }

            val chime = TaskReminderProfile(soundEnabled = true, vibrationEnabled = false, tone = TaskReminderTone.CHIME)
            settings.setTaskReminderProfile(TaskReminderType.APPROVAL, chime)
            settings.setTaskReminderProfile(TaskReminderType.QUESTION, TaskReminderProfile(soundEnabled = false, vibrationEnabled = true))
            val soundChannelId = postedNotification(TaskEvent.Type.PERMISSION_REQUEST).channelId
            val soundChannel = manager.getNotificationChannel(soundChannelId)
            val ringtone = TaskReminderTone.CHIME.uri(context, null)
            assertEquals(ringtone, soundChannel.sound)
            assertFalse(soundChannel.shouldVibrate())
            context.contentResolver.openInputStream(ringtone).use { assertTrue(checkNotNull(it).read() >= 0) }

            val vibrationChannel = manager.getNotificationChannel(postedNotification(TaskEvent.Type.ELICITATION_REQUEST).channelId)
            assertNull(vibrationChannel.sound)
            assertTrue(vibrationChannel.shouldVibrate())
            assertEquals(soundChannelId, postedNotification(TaskEvent.Type.PERMISSION_REQUEST).channelId)

            settings.setTaskReminderAlertsEnabled(false)
            val mutedApproval = postedNotification(TaskEvent.Type.PERMISSION_REQUEST)
            val mutedQuestion = postedNotification(TaskEvent.Type.ELICITATION_REQUEST)
            assertEquals(Notification.GROUP_ALERT_SUMMARY, mutedApproval.groupAlertBehavior)
            assertEquals(Notification.GROUP_ALERT_SUMMARY, mutedQuestion.groupAlertBehavior)
            assertEquals(soundChannelId, mutedApproval.channelId)
            assertEquals(vibrationChannel.id, mutedQuestion.channelId)
            settings.setTaskReminderAlertsEnabled(true)
            val restored = postedNotification(TaskEvent.Type.PERMISSION_REQUEST)
            assertEquals(Notification.GROUP_ALERT_ALL, restored.groupAlertBehavior)
            assertEquals(soundChannelId, restored.channelId)

            settings.setTaskReminderProfile(TaskReminderType.APPROVAL, null)
            assertEquals(posted.first().second.channelId, postedNotification(TaskEvent.Type.PERMISSION_REQUEST).channelId)

            NotificationTonePicker.systemTones(context).firstOrNull()?.let { tone ->
                val systemSound = chime.copy(tone = tone)
                settings.setTaskReminderProfile(TaskReminderType.APPROVAL, systemSound)
                assertEquals(systemSound, settings.taskReminderProfile(TaskReminderType.APPROVAL))
                val systemChannelId = postedNotification(TaskEvent.Type.PERMISSION_REQUEST).channelId
                val systemChannel = manager.getNotificationChannel(systemChannelId)
                val actualSound = checkNotNull(systemChannel.sound)
                val resolver = context.contentResolver
                assertEquals(resolver.uncanonicalize(tone.source) ?: tone.source, resolver.uncanonicalize(actualSound) ?: actualSound)
                assertEquals(systemChannelId, postedNotification(TaskEvent.Type.PERMISSION_REQUEST).channelId)
            }
        } finally {
            settings.setNotificationEnabled(beforeMaster)
            settings.setTaskReminderAlertsEnabled(beforeAlerts)
            settings.setNotifApprovalEnabled(beforeApproval)
            settings.setNotifElicitationEnabled(beforeQuestion)
            settings.setNotifCompletedEnabled(beforeCompleted)
            settings.setNotifFailedEnabled(beforeFailed)
            for ((type, profile) in previousProfiles) settings.setTaskReminderProfile(type, profile)
            TaskNotificationInbox(context).use { inbox ->
                for (event in events) {
                    manager.cancel(source, event.taskId.hashCode())
                    manager.cancel(event.eventKey.hashCode())
                    inbox.writableDatabase.delete("event_inbox", "event_key = ?", arrayOf(event.eventKey))
                }
            }
        }
    }
}
