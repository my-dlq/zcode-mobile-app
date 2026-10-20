package ai.zcode.remote.ui.remote.event

import ai.zcode.remote.R
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.service.KeepAliveService
import ai.zcode.remote.ui.remote.RemoteControlActivity
import android.app.NotificationManager
import android.content.SharedPreferences
import android.os.SystemClock
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** 点击真实系统通知回到真实 Remote Activity，页面用本机夹具避免操作官方任务。 */
@RunWith(AndroidJUnit4::class)
class NotificationNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8)
    }

    private fun await(description: String, timeout: Long = 12_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(80)
        assertTrue(description, condition())
    }

    @Test
    fun notificationTapReusesLivePageAndNavigatesFromHomeConversationAndSettings() {
        check(context.packageName.endsWith(".debug")) { "只能在独立 debug 包运行" }
        val run = "nav-${UUID.randomUUID()}"
        val repository = ConnectionRepository.getInstance(context)
        val settings = AppSettingsRepository.getInstance(context)
        val prefs = listOf(repository, settings).map {
            it.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(it) as SharedPreferences
        }
        val saved = prefs.map { it.all.toMap() }
        val manager = context.getSystemService(NotificationManager::class.java)
        val requests = AtomicInteger()
        val evidence = JSONArray()
        var activity: RemoteControlActivity? = null
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { candidate, stage ->
            if (candidate is RemoteControlActivity && stage == Stage.RESUMED) activity = candidate
        }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val connection = RemoteConnection(id = run, name = run, url = "http://127.0.0.1:${server.localPort}/")
        thread(isDaemon = true, name = "通知导航测试回环服务") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val first = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { /* 消费请求头 */ }
                    if (first.startsWith("GET / ")) requests.incrementAndGet()
                    val html = """<!doctype html><html><body><main id="root"></main><script>
                        window.instance = Math.random().toString(); window.draft = '未发送的草稿';
                        window.home = function() {
                            root.innerHTML = '<aside><button data-testid="task-new-button">新任务</button><button data-testid="virtual-project-toggle" aria-expanded="false" onclick="expand(this)">项目</button><ul id="tasks"></ul></aside>';
                        };
                        window.expand = function(b) { b.setAttribute('aria-expanded','true'); tasks.innerHTML = ['a','b','c','d'].map(id => '<li data-testid="task-item-'+id+'" onclick="chat(\''+id+'\')">会话'+id+'</li>').join(''); };
                        window.chat = function(id) { root.innerHTML = '<div data-session-id="'+id+'"><button aria-label="返回任务首页" onclick="home()">返回</button><textarea>'+window.draft+'</textarea></div>'; };
                        window.settingsPage = function() { root.insertAdjacentHTML('beforeend','<section data-testid="settings-page"><button data-testid="settings-back-button" aria-label="返回工作区" onclick="this.parentElement.remove()">返回工作区</button></section>'); };
                        window.onboarding = function() { root.insertAdjacentHTML('beforeend','<section data-testid="onboarding-page"><button aria-label="退出引导" onclick="this.parentElement.remove()">退出</button></section>'); };
                        home();
                    </script></body></html>""".toByteArray()
                    val output = it.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(html); output.flush()
                }
            }
        }
        fun web() = activity!!.findViewById<WebView>(R.id.webView)
        fun eval(js: String): String {
            val latch = CountDownLatch(1)
            var value = ""
            instrumentation.runOnMainSync { web().evaluateJavascript(js) { value = it; latch.countDown() } }
            assertTrue("脚本回调", latch.await(5, TimeUnit.SECONDS))
            return value
        }
        fun publish(task: String, type: TaskEventParser.TaskEvent.Type): android.app.Notification {
            val key = "$run|$task|${type.name}|${UUID.randomUUID()}"
            TaskNotifier.notify(context, TaskEventParser.TaskEvent(type, task, "通知跳转-$task", run, key))
            val id = if (type == TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST || type == TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST) task.hashCode() else key.hashCode()
            await("通知已发布") { manager.activeNotifications.any { it.id == id } }
            return manager.activeNotifications.single { it.id == id }.notification
        }
        fun tap(task: String, notification: android.app.Notification) {
            if (task == "a" && evidence.length() == 0 &&
                InstrumentationRegistry.getArguments().getString("externalTap") == "true"
            ) {
                // 系统通知的敏感视图可能不向测试的辅助功能树提供文字，外部通过
                // 当次截图进行真实 adb 点击；其余场景发送原通知 PendingIntent。
                context.filesDir.resolve("navigation-await-tap").writeText(run)
                await("外部已点击通知", 120_000) { context.filesDir.resolve("navigation-tap-done").exists() }
                context.filesDir.resolve("navigation-tap-done").delete()
                context.filesDir.resolve("navigation-await-tap").delete()
            } else notification.contentIntent.send()
        }
        try {
            settings.setNotificationEnabled(true)
            settings.setNotifApprovalEnabled(true); settings.setNotifElicitationEnabled(true)
            settings.setNotifCompletedEnabled(true); settings.setNotifFailedEnabled(true)
            assertTrue(manager.areNotificationsEnabled())
            repository.saveConnection(connection)
            instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(callback) }
            shell("am start -W -n ${context.packageName}/${RemoteControlActivity::class.java.name} --es extra_url ${connection.url} --es extra_name $run")
            await("真实远程页面已加载") { activity != null && eval("typeof window.home") == "\"function\"" }
            val original = activity
            val document = eval("window.instance")
            val bridgeField = RemoteControlActivity::class.java.getDeclaredField("eventBridge").apply { isAccessible = true }
            val originalBridge = bridgeField.get(activity)
            val cases = listOf("home" to "a", "conversation" to "b", "settings" to "c", "onboarding" to "d")
            val types = listOf(TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST, TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST,
                TaskEventParser.TaskEvent.Type.TASK_COMPLETED, TaskEventParser.TaskEvent.Type.TASK_FAILED)
            cases.forEachIndexed { index, (page, target) ->
                if (page == "settings") eval("settingsPage()")
                if (page == "onboarding") eval("onboarding()")
                val notification = publish(target, types[index])
                tap(target, notification)
                await("$page 通知到达指定会话 $target") { eval("document.querySelector('[data-session-id]')?.getAttribute('data-session-id')") == "\"$target\"" }
                assertSame("同连接不重建 Activity", original, activity)
                assertEquals("同连接不能重新加载或重新配对", document, eval("window.instance"))
                assertSame("保留原有事件监听桥", originalBridge, bridgeField.get(activity))
                assertEquals("未发送草稿仍保留", "\"未发送的草稿\"", eval("window.draft"))
                assertEquals("覆盖层已经关闭", "0", eval("document.querySelectorAll('[data-testid=settings-page],[data-testid=onboarding-page]').length"))
                evidence.put(JSONObject().put("from", page).put("type", types[index].name).put("target", target).put("sameDocument", true))
            }
            assertEquals("四次点击均复用页面，不额外请求首页", 1, requests.get())
            // 无可复用 Activity 时仍保留冷启动加载并跳转，验证通知本身携带目标。
            val notification = publish("a", TaskEventParser.TaskEvent.Type.TASK_COMPLETED)
            instrumentation.runOnMainSync { activity!!.finish() }
            await("旧页面已销毁") { original!!.isDestroyed }
            tap("a", notification)
            await("冷启动进入通知目标") { activity !== original && eval("document.querySelector('[data-session-id]')?.getAttribute('data-session-id')") == "\"a\"" }
            assertNotNull(notification.contentIntent)
            evidence.put(JSONObject().put("from", "cold-start").put("target", "a"))
            context.filesDir.resolve("notification-navigation.json").writeText(JSONObject().put("phase", "passed").put("cases", evidence).toString(2))
        } finally {
            shell("cmd statusbar collapse")
            context.filesDir.resolve("navigation-await-tap").delete()
            context.filesDir.resolve("navigation-tap-done").delete()
            instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback); activity?.finish() }
            KeepAliveService.stop(context)
            server.close()
            manager.activeNotifications.filter { it.notification.extras.getCharSequence("android.text").toString().contains(run) }.forEach { manager.cancel(it.id) }
            TaskNotificationInbox(context).use { it.writableDatabase.delete("event_inbox", "event_key LIKE ?", arrayOf("$run|%")) }
            prefs.zip(saved).forEach { (pref, values) -> pref.edit().clear().apply {
                values.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value); is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value); is Long -> putLong(key, value)
                    is Float -> putFloat(key, value); is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
            }.commit() }
        }
    }
}
