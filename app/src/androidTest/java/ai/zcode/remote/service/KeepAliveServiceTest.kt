package ai.zcode.remote.service

import android.content.Context
import android.content.ContextWrapper
import android.webkit.WebView
import android.webkit.WebViewClient
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.ui.remote.RemoteControlActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 不启动系统服务、不使用真实仓库、不导航网络；所有 WebView 操作均在主线程。 */
@RunWith(AndroidJUnit4::class)
class KeepAliveServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferencesName = "keepalive-test-${UUID.randomUUID()}"
    private val storageContext = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int) =
            context.getSharedPreferences(preferencesName, mode)
    }
    private val repository = ConnectionRepository(storageContext)
    private lateinit var service: KeepAliveService
    private val webViews = mutableListOf<RecordingWebView>()
    private var fixtureInstalled = false
    private var savedExpected = ""
    private var savedOwner = false

    @Before
    fun setUp() = onMain {
        // 不替换正在运行的真实服务，也不改变真实 Activity 的所有权。
        assumeTrue(staticValue("currentService") == null)
        assumeTrue(!KeepAliveService.isRunning && !RemoteControlActivity.hasLiveInstance())
        savedExpected = staticValue("expectedSourceId") as String
        savedOwner = KeepAliveService.activityOwnsEventSource
        service = KeepAliveService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(service, storageContext)
        service.monitorRepository = repository
        service.monitorWebViewFactory = { RecordingWebView(it).also(webViews::add) }
        setStatic("currentService", service)
        setStatic("expectedSourceId", "")
        setStatic("activityOwnsEventSource", false)
        fixtureInstalled = true
    }

    @After
    fun tearDown() {
        if (fixtureInstalled) {
            instrumentation.waitForIdleSync()
            onMain {
                call("disposeEventSource")
                setStatic("currentService", null)
                setStatic("expectedSourceId", savedExpected)
                setStatic("activityOwnsEventSource", savedOwner)
            }
        }
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun refreshKeepsHealthySourceButRebuildUsesEditedUrlAndName() = onMain {
        val original = repository.saveConnection(connection())
        target(original)
        call("refreshEventSource")
        val first = webViews.single()
        val edited = repository.saveConnection(original.copy(name = "更新名称", sid = "new",
            url = "http://127.0.0.1/remote?mid=computer-a&sid=new"))
        assertEquals(original.loginSpace, edited.loginSpace)

        call("refreshEventSource")
        assertEquals(edited.url, value("monitorUrl"))
        assertEquals(edited.name, value("monitorName"))
        assertSame(first, value("monitorWebView"))
        assertEquals(listOf(original.url), first.loadedUrls)
        assertEquals(0, first.destroyCalls)

        call("restartHiddenMonitor")
        assertEquals(1, first.stopCalls)
        assertEquals(1, first.destroyCalls)
        assertEquals(listOf(edited.url), webViews.last().loadedUrls)
    }

    @Test
    fun directHiddenStartSynchronizesMetadataBeforeLoading() = onMain {
        val original = repository.saveConnection(connection())
        target(original)
        val edited = repository.saveConnection(original.copy(name = "新名称", sid = "new",
            url = "http://127.0.0.1/remote?mid=computer-a&sid=new"))
        call("startHiddenMonitor")
        assertEquals(listOf(edited.url), webViews.single().loadedUrls)
        assertEquals(edited.name, value("monitorName"))
    }

    @Test
    fun syncOnlyUpdatesExistingMatchingTargetAndNeverChangesExpectation() {
        lateinit var original: RemoteConnection
        lateinit var edited: RemoteConnection
        onMain {
            original = repository.saveConnection(connection())
            target(original)
            edited = repository.saveConnection(original.copy(name = "改名", sid = "new",
                url = "http://127.0.0.1/remote?mid=computer-a&sid=new"))
            setStatic("expectedSourceId", "pending-b")
            KeepAliveService.syncConnection(original.loginSpace.sourceId)
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals(edited.url, value("monitorUrl"))
            assertEquals(edited.name, value("monitorName"))
            assertEquals("pending-b", staticValue("expectedSourceId"))
            assertTrue(webViews.isEmpty())
            KeepAliveService.syncConnection("other-source")
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals(original.loginSpace.sourceId, value("monitorSourceId"))
            setStatic("currentService", null)
            KeepAliveService.syncConnection(original.loginSpace.sourceId)
            assertNull(staticValue("currentService"))
            assertEquals("pending-b", staticValue("expectedSourceId"))
            setStatic("currentService", service)
        }
    }

    @Test
    fun staleOrDeletedSourceCannotFollowBareConnectionIdToNewGeneration() = onMain {
        val original = repository.saveConnection(connection())
        target(original)
        call("startHiddenMonitor")
        val updated = repository.resetLogin(original.id)!!
        assertNotEquals(original.loginSpace, updated.loginSpace)
        call("refreshEventSource")
        assertEquals("", value("monitorSourceId"))
        assertNull(value("monitorWebView"))
        target(original)
        call("startHiddenMonitor")
        assertEquals(1, webViews.size)

        target(updated)
        call("startHiddenMonitor")
        assertEquals(2, webViews.size)
        repository.deleteConnection(updated.id)
        call("refreshEventSource")
        assertEquals("", value("monitorSourceId"))
        assertNull(value("monitorWebView"))
    }

    @Test
    fun activityOwnershipPreventsHiddenSourceCreation() = onMain {
        target(repository.saveConnection(connection()))
        setStatic("activityOwnsEventSource", true)
        call("refreshEventSource")
        call("startHiddenMonitor")
        assertTrue(webViews.isEmpty())
    }

    @Test
    fun forgettingOldTargetDoesNotClearNewPendingOwner() {
        lateinit var original: RemoteConnection
        onMain {
            original = repository.saveConnection(connection())
            target(original)
            setStatic("expectedSourceId", "pending-b")
            setStatic("activityOwnsEventSource", true)
            KeepAliveService.forgetConnection(original.id, original.url, original.loginSpace.sourceId)
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals("", value("monitorSourceId"))
            assertEquals("pending-b", staticValue("expectedSourceId"))
            assertTrue(KeepAliveService.activityOwnsEventSource)
        }
    }

    @Test
    fun forgettingWithoutServiceClearsOnlyMatchingExpectedGeneration() = onMain {
        val original = repository.saveConnection(connection())
        val updated = repository.resetLogin(original.id)!!
        setStatic("currentService", null)
        setStatic("expectedSourceId", updated.loginSpace.sourceId)
        KeepAliveService.forgetConnection(original.id, original.url, original.loginSpace.sourceId)
        assertEquals(updated.loginSpace.sourceId, staticValue("expectedSourceId"))
        KeepAliveService.forgetConnection(updated.id, updated.url, updated.loginSpace.sourceId)
        assertEquals("", staticValue("expectedSourceId"))
        assertNull(staticValue("currentService"))
        setStatic("currentService", service)
    }

    @Test
    fun deadRendererSkipsStopLoadingAndDelayedCallbackCannotDestroyReplacement() {
        lateinit var first: RecordingWebView
        lateinit var replacement: RecordingWebView
        onMain {
            target(repository.saveConnection(connection()))
            call("startHiddenMonitor")
            first = webViews.single()
            assertTrue(first.client!!.onRenderProcessGone(first, null))
            assertSame(first, value("deadMonitorWebView"))
            // 在排队的死亡清理前重建，验证旧回调的引用检查不误杀新源。
            call("restartHiddenMonitor")
            replacement = webViews.last()
            assertNotSame(first, replacement)
            assertEquals(0, first.stopCalls)
            assertEquals(1, first.destroyCalls)
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertSame(replacement, value("monitorWebView"))
            assertEquals(0, replacement.destroyCalls)
            // 已销毁实例的迟到回调也不能标记新源死亡。
            first.client!!.onRenderProcessGone(first, null)
            assertNull(value("deadMonitorWebView"))
        }
        instrumentation.waitForIdleSync()
        onMain { assertSame(replacement, value("monitorWebView")) }
    }

    private fun connection() = RemoteConnection(name = "测试设备", mid = "computer-a", sid = "old",
        url = "http://127.0.0.1/remote?mid=computer-a&sid=old")

    private fun target(connection: RemoteConnection) {
        setValue("monitorUrl", connection.url)
        setValue("monitorName", connection.name)
        setValue("monitorSourceId", connection.loginSpace.sourceId)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(name: String) = KeepAliveService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun value(name: String): Any? = field(name).get(service)
    private fun setValue(name: String, value: Any?) = field(name).set(service, value)
    private fun staticValue(name: String): Any? = field(name).get(null)
    private fun setStatic(name: String, value: Any?) = field(name).set(null, value)
    private fun call(name: String) = KeepAliveService::class.java.getDeclaredMethod(name).apply {
        isAccessible = true
    }.invoke(service)

    /** 截断首次导航，测试只记录地址，不连接 localhost 或任何外部服务器。 */
    private class RecordingWebView(context: Context) : WebView(context) {
        val loadedUrls = mutableListOf<String>()
        var client: WebViewClient? = null
        var stopCalls = 0
        var destroyCalls = 0
        override fun setWebViewClient(client: WebViewClient) {
            this.client = client
            super.setWebViewClient(client)
        }
        override fun loadUrl(url: String) { loadedUrls.add(url) }
        override fun stopLoading() { stopCalls++ }
        override fun destroy() {
            destroyCalls++
            super.destroy()
        }
    }
}
