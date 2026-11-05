package ai.zcode.remote.ui.remote.event

import ai.zcode.remote.R
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.data.model.UpdateInfo
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.service.KeepAliveService
import ai.zcode.remote.ui.remote.RemoteControlActivity
import ai.zcode.remote.ui.main.MainActivity
import ai.zcode.remote.ui.main.UpdateDialog
import ai.zcode.remote.ui.settings.SettingsActivity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** 仅使用独立 debug 包和夹具，测试后恢复连接偏好。 */
@RunWith(AndroidJUnit4::class)
class AppRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun withRepository(action: (ConnectionRepository) -> Unit) {
        check(context.packageName.endsWith(".debug"))
        val repo = ConnectionRepository.getInstance(context)
        val prefs = repo.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(repo) as SharedPreferences
        val original = prefs.all.toMap()
        val active = repo.javaClass.getDeclaredField("activeConnectionId").apply { isAccessible = true }
        val originalActive = active.get(repo)
        prefs.edit().clear().commit()
        try { action(repo) } finally {
            val editor = prefs.edit().clear()
            for ((key, value) in original) when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            editor.commit()
            active.set(repo, originalActive)
        }
    }

    private fun connection(id: String) = RemoteConnection(id = id, name = "办公室", url = "http://127.0.0.1/remote?mid=$id&sid=$id")
    private fun event(connection: RemoteConnection, explicitSource: Boolean = true) = TaskEventParser.TaskEvent(
        TaskEventParser.TaskEvent.Type.TASK_COMPLETED, "shared-task", "任务", connection.name,
        "${connection.id}|shared-task|TASK_COMPLETED|123", if (explicitSource) connection.id else "",
    )

    private fun launchIntent(event: TaskEventParser.TaskEvent): Intent = TaskNotifier.javaClass
        .getDeclaredMethod("buildLaunchIntent", Context::class.java, TaskEventParser.TaskEvent::class.java)
        .apply { isAccessible = true }.invoke(TaskNotifier, context, event) as Intent

    @Test fun duplicateNamesAndRenamesKeepTheExactNotificationTarget() = withRepository { repo ->
        val a = connection("a")
        val b = connection("b")
        repo.saveConnection(a)
        repo.saveConnection(b)
        val oldEvent = event(b)
        assertEquals(b.url, launchIntent(oldEvent).getStringExtra("extra_url"))
        repo.saveConnection(b.copy(name = "改名后"))
        assertEquals(b.url, launchIntent(oldEvent).getStringExtra("extra_url"))
        assertEquals(b.url, launchIntent(event(b, explicitSource = false)).getStringExtra("extra_url"))
        assertFalse(launchIntent(event(a)).filterEquals(launchIntent(oldEvent)))
    }

    @Test fun ambiguousLegacyNameReturnsHomeInsteadOfGuessing() = withRepository { repo ->
        repo.saveConnection(connection("a"))
        repo.saveConnection(connection("b"))
        val intent = launchIntent(event(connection("b")).copy(sourceId = "", eventKey = ""))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNull(intent.getStringExtra("extra_url"))
    }

    @Test fun deletionClearsRestoreStateAndReAddingDoesNotReviveOldIdentity() = withRepository { repo ->
        val old = connection("old")
        repo.saveConnection(old)
        repo.updateLastConnected(old.id)
        repo.updateLastTaskId(old.id, "task")
        repo.deleteConnection(old.id)
        val reopened = ConnectionRepository(context)
        assertNull(reopened.getLastActiveUrl())
        assertNull(reopened.getLastActiveName())
        assertEquals("", reopened.getLastActiveTaskId())
        assertTrue(reopened.isSourceDeleted(old.id))
        assertTrue(reopened.isSourceDeleted(old.url))
        repo.saveConnection(old.copy(id = "replacement"))
        assertFalse(repo.isSourceDeleted(old.url))
        assertFalse(repo.isSourceDeleted("replacement"))
        assertTrue(repo.isSourceDeleted(old.id))
        assertNull(launchIntent(event(old)).getStringExtra("extra_url"))
    }

    @Test fun deletingInactiveConnectionKeepsTheActiveRestoreTarget() = withRepository { repo ->
        val a = connection("a")
        val b = connection("b")
        repo.saveConnection(a)
        repo.saveConnection(b)
        repo.updateLastConnected(b.id)
        repo.deleteConnection(a.id)
        assertEquals(b.url, repo.getLastActiveUrl())
    }

    @Test fun sourceIdentitySurvivesInboxReopenAndSourceDiscardIsIsolated() {
        val name = "source-${UUID.randomUUID()}.db"
        val a = event(connection("a"))
        val b = event(connection("b"))
        try {
            TaskNotificationInbox(context, name).use { inbox ->
                assertTrue(inbox.enqueue(a))
                assertTrue(inbox.enqueue(b))
            }
            TaskNotificationInbox(context, name).use { inbox ->
                inbox.discardSource("a")
                assertFalse(inbox.enqueue(a))
                val next = inbox.claimNext()!!
                assertEquals("b", next.event.sourceId)
                inbox.markDelivered(next.rowId)
                assertNull(inbox.claimNext())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun inboxV1MigrationPreservesDeliveredDedupAndLegacyRouting() = withRepository { repo ->
        val name = "migration-${UUID.randomUUID()}.db"
        val b = connection("b")
        repo.saveConnection(connection("a"))
        repo.saveConnection(b)
        val delivered = event(connection("a"))
        val pending = event(b)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE event_inbox (row_id INTEGER PRIMARY KEY AUTOINCREMENT, event_key TEXT NOT NULL UNIQUE, task_id TEXT NOT NULL, event_type TEXT NOT NULL, task_title TEXT NOT NULL, device_name TEXT NOT NULL, state TEXT NOT NULL, lease_until INTEGER NOT NULL, next_attempt_at INTEGER NOT NULL, created_at INTEGER NOT NULL)")
                for ((event, state) in listOf(delivered to "DELIVERED", pending to "PENDING")) {
                    db.execSQL("INSERT INTO event_inbox (event_key, task_id, event_type, task_title, device_name, state, lease_until, next_attempt_at, created_at) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?)",
                        arrayOf(event.eventKey, event.taskId, event.type.name, event.taskTitle, event.deviceName, state, System.currentTimeMillis()))
                }
                db.version = 1
            }
            TaskNotificationInbox(context, name).use { inbox ->
                assertFalse(inbox.enqueue(delivered))
                val migrated = inbox.claimNext()!!
                assertEquals("", migrated.event.sourceId)
                assertEquals(b.url, launchIntent(migrated.event).getStringExtra("extra_url"))
                inbox.markDelivered(migrated.rowId)
                assertFalse(inbox.enqueue(pending))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun updateDialogSurvivesActualActivityRecreation() {
        check(context.packageName.endsWith(".debug"))
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val resumed = AtomicReference<SettingsActivity>()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is SettingsActivity && stage == Stage.RESUMED) resumed.set(activity)
        }
        instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(callback) }
        var host: SettingsActivity? = null
        try {
            val initial = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            host = initial
            instrumentation.runOnMainSync {
                UpdateDialog.newInstance(UpdateInfo("v0.3.3", "0.3.3", "https://example.invalid/update.apk", 10, "## 更新说明\n\n- **修复通知**"))
                    .showNow(initial.supportFragmentManager, "restored-update")
                initial.recreate()
            }
            val end = SystemClock.elapsedRealtime() + 10_000
            while ((resumed.get() == null || resumed.get() === initial) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
            val restoredHost = resumed.get()
            assertNotNull(restoredHost)
            assertNotSame(initial, restoredHost)
            host = restoredHost
            instrumentation.runOnMainSync {
                val dialog = restoredHost.supportFragmentManager.findFragmentByTag("restored-update") as UpdateDialog
                assertTrue(dialog.requireDialog().findViewById<TextView>(R.id.tvUpdateTitle).text.contains("0.3.3"))
                val notes = dialog.requireDialog().findViewById<TextView>(R.id.tvReleaseNotes).text
                assertTrue(notes is android.text.Spanned)
                assertTrue(notes.contains("修复通知"))
                assertFalse(notes.contains("**"))
                dialog.dismissNow()
            }
        } finally {
            instrumentation.runOnMainSync {
                lifecycle.removeLifecycleCallback(callback)
                host?.finish()
            }
        }
    }

    @Test fun legacyUpdateDialogWithoutArgumentsClosesSafely() {
        val host = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        try {
            instrumentation.runOnMainSync {
                val dialog = UpdateDialog()
                dialog.showNow(host.supportFragmentManager, "legacy-update")
                dialog.dismissNow()
            }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }

    @Test fun deletingActiveConnectionStopsPageMonitorAndLateServiceRestart() = withRepository { repo ->
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val settings = AppSettingsRepository.getInstance(context)
        val originalKeepAlive = settings.isKeepAliveEnabled()
        val oldActivity = AtomicReference<RemoteControlActivity>()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is RemoteControlActivity && stage == Stage.RESUMED) oldActivity.set(activity)
        }
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val connection = RemoteConnection(id = "delete-${UUID.randomUUID()}", name = "删除回归", url = "http://127.0.0.1:${server.localPort}/")
        thread(isDaemon = true, name = "删除连接回归夹具") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) { /* 消费请求头 */ }
                    requests.incrementAndGet()
                    val body = "<!doctype html><title>本地夹具</title><main>删除连接回归</main>".toByteArray()
                    runCatching {
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                    }
                }
            }
        }
        fun await(message: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
            assertTrue(message, condition())
        }
        try {
            settings.setKeepAliveEnabled(false)
            repo.saveConnection(connection)
            instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(callback) }
            instrumentation.startActivitySync(Intent(context, RemoteControlActivity::class.java)
                .putExtra(RemoteControlActivity.EXTRA_URL, connection.url)
                .putExtra(RemoteControlActivity.EXTRA_NAME, connection.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("远程页与本地夹具已加载") { oldActivity.get() != null && requests.get() > 0 && KeepAliveService.isRunning }
            val pending = event(connection)
            TaskNotificationInbox(context).use { assertTrue(it.enqueue(pending)) }
            instrumentation.runOnMainSync {
                repo.deleteConnection(connection.id)
                RemoteControlActivity.closeConnection(connection.id, connection.url)
                KeepAliveService.forgetConnection(connection.id, connection.url)
                TaskNotifier.discardConnection(context, connection.id, connection.url)
            }
            await("删除后页面和监听服务都已停止") { !RemoteControlActivity.hasLiveInstance() && !KeepAliveService.isRunning }
            assertNull(repo.getLastActiveUrl())
            TaskNotificationInbox(context).use { assertFalse(it.enqueue(pending)) }
            // 模拟删除前已经排队、删除后才送达的服务启动命令。
            instrumentation.runOnMainSync {
                val staleStart = Intent(context, KeepAliveService::class.java)
                    .putExtra("extra_keepalive_url", connection.url)
                    .putExtra("extra_keepalive_source_id", connection.id)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(staleStart)
                else context.startService(staleStart)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            await("迟到启动命令不能恢复已删除的监听") { !KeepAliveService.isRunning }
        } finally {
            server.close()
            instrumentation.runOnMainSync {
                lifecycle.removeLifecycleCallback(callback)
                oldActivity.get()?.finish()
                KeepAliveService.stop(context)
            }
            settings.setKeepAliveEnabled(originalKeepAlive)
        }
    }
}
