package ai.zcode.remote.ui.remote.event

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 仅发布独立来源的测试通知；不调用消费信箱、不保存连接、不启动通知目标页面。 */
@RunWith(AndroidJUnit4::class)
class NotificationSourceIsolationDeviceTest {
    @Test
    fun sameTaskNotificationsRemainIndependentAcrossSourcesAndGenerations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        assumeTrue(NotificationManagerCompat.from(context).areNotificationsEnabled())
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val suffix = UUID.randomUUID().toString()
        val sourceA = "device-notification-fixture-a-$suffix"
        val sourceB = "device-notification-fixture-b-$suffix"
        val nextGeneration = "$sourceA:login:1"
        val taskId = "device-notification-task-$suffix"
        val id = taskId.hashCode()
        val post = TaskNotifier::class.java.getDeclaredMethod(
            "post", TaskNotificationInbox.Record::class.java, Context::class.java,
        ).apply { isAccessible = true }
        fun publish(source: String, version: Int) {
            val event = TaskEventParser.TaskEvent(
                TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST,
                taskId, "多连接隔离验证（测试）", "通知测试", "$source|$taskId|approval:$version", source,
            )
            assertEquals("DELIVERED", post.invoke(TaskNotifier, TaskNotificationInbox.Record(-1L, event), context).toString())
        }
        fun active(source: String) = manager.activeNotifications.singleOrNull { it.tag == source && it.id == id }
        fun awaitNotifications() {
            val end = android.os.SystemClock.elapsedRealtime() + 5_000L
            while (android.os.SystemClock.elapsedRealtime() < end) {
                if (active(sourceA) != null && active(sourceB) != null) return
                Thread.sleep(50)
            }
            fail("两个测试来源的同任务通知未同时出现")
        }
        val key = TaskNotifier::class.java.getDeclaredField("EXTRA_INTERACTION_KEY").apply { isAccessible = true }.get(null) as String
        try {
            publish(sourceA, 1)
            publish(sourceB, 1)
            awaitNotifications()
            assertNotNull(active(sourceA))
            assertNotNull(active(sourceB))
            publish(sourceA, 2)
            val end = android.os.SystemClock.elapsedRealtime() + 5_000L
            while (android.os.SystemClock.elapsedRealtime() < end &&
                active(sourceA)?.notification?.extras?.getString(key) != "$sourceA|$taskId|approval:2") {
                Thread.sleep(50)
            }
            assertEquals("$sourceA|$taskId|approval:2", active(sourceA)?.notification?.extras?.getString(key))
            assertEquals("$sourceB|$taskId|approval:1", active(sourceB)?.notification?.extras?.getString(key))
            publish(nextGeneration, 1)
            val generationEnd = android.os.SystemClock.elapsedRealtime() + 5_000L
            while (active(nextGeneration) == null && android.os.SystemClock.elapsedRealtime() < generationEnd) Thread.sleep(50)
            assertNotNull(active(nextGeneration))
            assertNotNull(active(sourceA))
            assertNotNull(active(sourceB))
        } finally {
            for (source in listOf(sourceA, sourceB, nextGeneration)) manager.cancel(source, id)
        }
    }
}
