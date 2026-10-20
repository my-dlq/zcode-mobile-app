package ai.zcode.remote.ui.remote.event

import ai.zcode.remote.R
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.service.KeepAliveService
import ai.zcode.remote.ui.remote.RemoteControlActivity
import android.app.Notification
import android.app.NotificationManager
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 使用本机真实 WebSocket 驱动现有 Remote Activity，不调用 notify 或伪造 JS 桥回调。 */
@RunWith(AndroidJUnit4::class)
class FourTypeListeningTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val types = listOf("PERMISSION_REQUEST", "ELICITATION_REQUEST", "TASK_COMPLETED", "TASK_FAILED")

    @Test
    fun realWebSocketCapturesFourTypesAndRespectsReplaySwitchesAndForeground() {
        check(context.packageName.endsWith(".debug")) { "隔离监听测试只能运行在 debug 包" }
        val runId = "four-listen-${UUID.randomUUID()}"
        val settings = AppSettingsRepository.getInstance(context)
        val repository = ConnectionRepository.getInstance(context)
        val preferences = listOf(settings, repository).map { owner ->
            owner.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(owner) as SharedPreferences
        }
        val saved = preferences.map { it.all.toMap() }
        val manager = context.getSystemService(NotificationManager::class.java)
        val evidence = JSONArray()
        var activity: RemoteControlActivity? = null
        val fixture = SocketFixture()
        val connection = RemoteConnection(id = runId, name = "四类监听隔离验证", url = fixture.url)
        var stamp = System.currentTimeMillis()
        val allTasks = mutableSetOf<String>()
        fun enabled(index: Int, value: Boolean) = when (index) {
            0 -> settings.setNotifApprovalEnabled(value)
            1 -> settings.setNotifElicitationEnabled(value)
            2 -> settings.setNotifCompletedEnabled(value)
            else -> settings.setNotifFailedEnabled(value)
        }
        fun records(): List<Triple<String, String, String>> = TaskNotificationInbox(context).use { inbox ->
            inbox.readableDatabase.query("event_inbox", arrayOf("task_id", "event_type", "state"),
                "event_key LIKE ?", arrayOf("$runId|%"), null, null, "row_id ASC").use { cursor ->
                buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
            }
        }
        fun notifications() = manager.activeNotifications.filter { sbn ->
            val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            text.contains(runId)
        }
        fun waitFor(description: String, timeout: Long = 10_000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue(description, condition())
        }
        fun state(task: String, index: Int, active: Boolean, version: Int, taskIndex: Boolean): JSONObject {
            val permission = if (active && index == 0) 1 else 0
            val question = if (active && index == 1) 1 else 0
            val phase = if (active && index == 2) "completedSuccess" else if (active && index == 3) "error" else "running"
            val counts = JSONObject().put("permissionCount", permission).put("userInputCount", question)
            val interaction = JSONObject().put("interactionId", "$task-request-$version")
                .put("kind", if (index == 0) "toolPermission" else "userInput").put("description", task)
            stamp += 100
            return if (taskIndex) JSONObject()
                .put("address", JSONObject().put("taskId", task))
                .put("meta", JSONObject().put("title", task).put("pendingInteraction", interaction))
                .put("activity", JSONObject().put("phase", phase).put("pendingInteractions", counts).put("lastActivityAt", stamp))
            else JSONObject().put("sessionId", task).put("title", task).put("phase", phase)
                .put("pendingInteractionSummary", counts).put("pendingInteraction", interaction).put("lastActivityAt", stamp)
        }
        fun sendStates(states: List<JSONObject>, taskIndex: Boolean, wire: Boolean = false) {
            val payload = if (taskIndex) JSONObject().put("payload", JSONObject().put("kind", "snapshot")
                .put("snapshot", JSONObject().put("tasks", JSONArray(states))))
                else JSONObject().put("sessions", JSONArray(states))
            fixture.send(if (wire) JSONObject().put("wireVersion", 1).put("payload", JSONObject()
                .put("dataBase64", Base64.encodeToString(payload.toString().toByteArray(), Base64.NO_WRAP))).toString()
                else payload.toString())
        }
        fun wave(label: String, taskIndex: Boolean = false, disabled: Int? = null, master: Boolean = true,
                 reusedTasks: List<String>? = null, version: Int = 1): List<String> {
            val tasks = (reusedTasks ?: types.indices.map { "$runId-$label-$it" }).also { allTasks.addAll(it) }
            val before = records().size
            sendStates(tasks.mapIndexed { i, task -> state(task, i, false, version, taskIndex) }, taskIndex)
            val changes = tasks.mapIndexed { i, task -> state(task, i, true, version, taskIndex) }
            sendStates(changes, taskIndex, wire = taskIndex)
            waitFor("$label 四类事件均完成消费") { records().size >= before + 4 && records().filter { it.first in tasks }.all { it.third in listOf("DELIVERED", "DISCARDED") } }
            val actual = records().drop(before).filter { it.first in tasks }
            types.forEachIndexed { i, type ->
                val record = actual.single { it.first == tasks[i] }
                assertEquals(type, record.second)
                assertEquals(if (master && disabled != i) "DELIVERED" else "DISCARDED", record.third)
            }
            val times = notifications().associate { it.id to it.postTime }
            sendStates(changes, taskIndex, wire = taskIndex)
            SystemClock.sleep(300)
            assertEquals("重复快照不重新发布通知", times, notifications().associate { it.id to it.postTime })
            assertEquals(before + 4, records().size)
            evidence.put(JSONObject().put("case", label).put("format", if (taskIndex) "tasks-index/base64" else "sessions-index")
                .put("states", JSONObject().apply { actual.forEach { put(it.second, it.third) } }).put("replay", "unchanged"))
            return tasks
        }
        fun report(phase: String) {
            context.filesDir.resolve("four-type-listening.json").writeText(JSONObject()
                .put("phase", phase).put("run", runId).put("cases", evidence).toString(2))
        }
        try {
            settings.setNotificationEnabled(true)
            types.indices.forEach { enabled(it, true) }
            assertTrue("debug 包需有系统通知权限", manager.areNotificationsEnabled())
            repository.saveConnection(connection)
            report("starting-activity")
            // vivo 会冻结尚未显示窗口的 instrumentation 进程；用 shell 授权的启动
            // 通道打开真实 Activity，避免测试启动本身受后台拉起策略影响。
            val resumed = CountDownLatch(1)
            val callback = ActivityLifecycleCallback { candidate, stage ->
                if (candidate is RemoteControlActivity && stage == Stage.RESUMED) {
                    activity = candidate
                    resumed.countDown()
                }
            }
            val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
            instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(callback) }
            try {
                instrumentation.uiAutomation.executeShellCommand(
                    "am start -W -n ${context.packageName}/${RemoteControlActivity::class.java.name} " +
                        "--es ${RemoteControlActivity.EXTRA_URL} ${connection.url} " +
                        "--es ${RemoteControlActivity.EXTRA_NAME} ${connection.name}"
                ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).readBytes() }
                assertTrue("测试远程页进入前台", resumed.await(10, TimeUnit.SECONDS))
            } finally {
                instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback) }
            }
            assertTrue("真实页面 WebSocket 已建立：${fixture.requests}", fixture.connected.await(15, TimeUnit.SECONDS))
            val bridge = RemoteControlActivity::class.java.getDeclaredField("eventBridge").apply { isAccessible = true }.get(activity) as TaskEventBridge
            waitFor("原生桥收到 WebSocket UP") { bridge.lastConnectionState?.state == TaskEventBridge.ConnectionState.State.UP }
            report("connected")
            val firstTasks = wave("sessions")
            wave("sessions-repeat", reusedTasks = firstTasks, version = 2)
            wave("task-index", taskIndex = true)
            val titles = listOf(R.string.notif_approval_title, R.string.notif_elicitation_title, R.string.notif_completed_title, R.string.notif_failed_title).map { context.getString(it) }
            titles.forEach { title -> assertTrue("系统通知标题 $title", notifications().any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == title }) }
            for (i in types.indices) {
                enabled(i, false)
                wave("disabled-$i", disabled = i)
                enabled(i, true)
            }
            settings.setNotificationEnabled(false)
            wave("master-off", master = false)
            settings.setNotificationEnabled(true)

            // 使用实际 Activity 的会话轮询，验证四种事件对当前会话均抑制。
            val foregroundTask = "$runId-foreground".also { allTasks.add(it) }
            val webView = activity!!.findViewById<WebView>(R.id.webView)
            instrumentation.runOnMainSync { webView.evaluateJavascript("document.getElementById('session').setAttribute('data-session-id', '$foregroundTask')", null) }
            waitFor("原生识别前台会话") { RemoteControlActivity.isForegroundSession(foregroundTask) }
            for (i in types.indices) {
                sendStates(listOf(state(foregroundTask, i, false, i, false)), false)
                sendStates(listOf(state(foregroundTask, i, true, i, false)), false)
                waitFor("前台 $i 已抑制") { records().any { it.first == foregroundTask && it.second == types[i] && it.third == "DISCARDED" } }
            }
            evidence.put(JSONObject().put("case", "foreground-suppression").put("allFour", "DISCARDED"))
            report("before-background")
            instrumentation.uiAutomation.executeShellCommand("input keyevent 3").close()
            waitFor("Activity 已退出前台") { !RemoteControlActivity.isForegroundSession(foregroundTask) }
            report("background")
            wave("background")
            evidence.put(JSONObject().put("case", "transport").put("state", bridge.lastConnectionState?.state)
                .put("trafficAgeMs", SystemClock.elapsedRealtime() - bridge.lastTrafficAtElapsedMs))
            report("passed")
        } catch (failure: Throwable) {
            evidence.put(JSONObject().put("failure", failure.toString()))
            report("failed")
            throw failure
        } finally {
            // 只清理本次隔离任务；连接及全部偏好按原值恢复，不碰正式包数据。
            notifications().forEach { manager.cancel(it.id) }
            activity?.let { target -> instrumentation.runOnMainSync { target.finish() } }
            KeepAliveService.stop(context)
            fixture.close()
            TaskNotificationInbox(context).use { inbox -> inbox.writableDatabase.delete("event_inbox", "event_key LIKE ?", arrayOf("$runId|%")) }
            preferences.zip(saved).forEach { (prefs, values) ->
                prefs.edit().clear().apply {
                    values.forEach { (key, value) -> when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    } }
                }.commit()
            }
        }
    }

    /** 仅监听 Android 本机回环地址，帧来自本测试，页面走真实 WebSocket 接收。 */
    private class SocketFixture : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val clients = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<String>()
        val connected = CountDownLatch(1)
        val url = "http://127.0.0.1:${server.localPort}/"
        init {
            thread(isDaemon = true, name = "监听测试回环服务") {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) client@{
                        try {
                            val reader = socket.getInputStream().bufferedReader()
                            requests.add(reader.readLine() ?: return@client)
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            }
                            val output = socket.getOutputStream()
                            if (headers["upgrade"].equals("websocket", true)) {
                                val accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                                    .digest((headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()), Base64.NO_WRAP)
                                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                                output.flush()
                                clients.add(socket)
                                connected.countDown()
                            } else {
                                val html = """<!doctype html><html><body><h1>四类监听隔离验证</h1><div id="session">本机测试，不执行远端任务</div><script>window.fixtureSocket = new WebSocket('ws://127.0.0.1:${server.localPort}/stream');</script></body></html>""".toByteArray()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                output.write(html); output.flush(); socket.close()
                            }
                        } catch (failure: Exception) { requests.add(failure.toString()); socket.close() }
                    }
                }
            }
        }
        @Synchronized fun send(text: String) {
            val bytes = text.toByteArray()
            check(bytes.size < 65536)
            check(clients.isNotEmpty())
            clients.forEach { socket ->
                val output = socket.getOutputStream()
                output.write(0x81)
                if (bytes.size < 126) output.write(bytes.size) else {
                    output.write(126); output.write(bytes.size shr 8); output.write(bytes.size and 255)
                }
                output.write(bytes); output.flush()
            }
        }
        override fun close() { clients.forEach { runCatching { it.close() } }; server.close() }
    }
}
