
package ai.zcode.remote.ui.remote

import ai.zcode.remote.ui.BaseActivity

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.PopupWindow
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ScriptHandler
import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.data.model.ConnectionLoginSpace
import ai.zcode.remote.ui.remote.web.ConnectionWebProfile
import ai.zcode.remote.ui.remote.web.TaskListVisibilityScript
import ai.zcode.remote.utils.UrlParser
import ai.zcode.remote.databinding.ActivityRemoteControlBinding
import ai.zcode.remote.databinding.PopupRemoteMenuBinding
import ai.zcode.remote.ui.about.AboutActivity
import ai.zcode.remote.ui.remote.event.EventCaptureScript
import ai.zcode.remote.ui.remote.event.ClaimCampaignDialogController
import ai.zcode.remote.ui.remote.event.ClaimCampaignNotifier
import ai.zcode.remote.ui.remote.event.TaskEventBridge
import ai.zcode.remote.ui.remote.event.TaskNotifier
import ai.zcode.remote.ui.remote.web.ZCodeWebChromeClient
import ai.zcode.remote.ui.remote.web.ZCodeWebViewClient
import ai.zcode.remote.ui.settings.SettingsActivity
import ai.zcode.remote.utils.ImmersiveHelper
import ai.zcode.remote.utils.ToastUtils
import android.os.Handler
import android.os.Looper

class RemoteControlActivity : BaseActivity() {

    // 远程页是全屏页：石墨灰需用全屏变体（无 ActionBar 背景窗口）
    override fun graphiteThemeRes(): Int = R.style.Theme_ZCodeRemote_Fullscreen_Graphite

    private lateinit var binding: ActivityRemoteControlBinding
    private lateinit var appSettings: AppSettingsRepository
    private var targetUrl: String = ""
    /** 设备名缺省值，onCreate 中赋为 strings 资源里的默认文案，之后可能被 WebView 标题覆盖。 */
    private var deviceName: String = ""
    private var pendingTaskId: String = ""
    private var pageLoading = true
    private var pendingSessionJump: Runnable? = null
    private var connectionId: String = ""
    private var loginSpace: ConnectionLoginSpace? = null
    private var webViewReady = false
    private var webViewDisposed = false
    private val rendererRecovery = RendererRecoveryState()
    private var rendererRecoveryScheduled = false
    private val rendererRecoveryRunnable = Runnable {
        rendererRecoveryScheduled = false
        performRendererRecovery()
    }
    private var pendingSettingsOpen: Runnable? = null
    private var loginNoticeIsolated = false
    private var loginNoticeEligible = false
    private var loginNoticeResumed = false
    private var loginNoticeProbePending = false
    private var loginNoticePageGeneration = 0L
    private var loginNoticeTimer = LoginNoticeTimer()
    private val hideLoginNotice = Runnable { binding.tvLoginNotice.visibility = View.GONE }
    private var isFullscreen = true
    private var isKeepScreenOn = true
    private var lastBackPressTime = 0L
    /** Activity 是否对用户可见（onStart/onStop 维护；后台不可见时渲染进程被回收需立即交接）。 */
    @Volatile
    private var activityVisible = false
    /** 事件源是否已交接给保活服务（避免交接后 onDestroy 再次触发重复交接）。 */
    @Volatile
    private var eventSourceHandedOver = false
    /** 事件桥建立时刻（elapsedRealtime）；用于跳过页面刚加载尚未收到首次心跳的宽限期。 */
    @Volatile
    private var eventBridgeCreatedAtElapsedMs = 0L
    private var mainMenuPopup: PopupWindow? = null

    private lateinit var customWebViewClient: ZCodeWebViewClient
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var eventBridge: TaskEventBridge
    private var eventBridgeAlive = false
    private var eventCaptureScript: ScriptHandler? = null
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private val handler = Handler(Looper.getMainLooper())
    private val claimDialogs by lazy {
        ClaimCampaignDialogController(this) claim@ { key, callback ->
            if (!requireWebPage()) {
                callback(false)
                return@claim
            }
            val quoted = org.json.JSONObject.quote(key)
            binding.webView.evaluateJavascript(
                "!!(window.__zcodeClaims && window.__zcodeClaims.claim($quoted))"
            ) { value -> if (isWebPageAvailable()) callback(value == "true") }
        }
    }
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallbackRegistered = false
    private var networkAvailable = true
    private var networkLossObserved = false
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    private var lastReconnectAtElapsedMs = 0L
    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        if (!requireWebPage() || !networkAvailable) return@Runnable
        reconnectAttempt++
        lastReconnectAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("ZCodeWeb", "reload for reconnect: attempt=$reconnectAttempt")
        binding.layoutErrorOverlay.visibility = View.GONE
        loadUrl(targetUrl)
    }



    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val intent = result.data
            val resultUris: Array<Uri>? = when {
                intent?.clipData != null -> {
                    val count = intent.clipData!!.itemCount
                    Array(count) { i -> intent.clipData!!.getItemAt(i).uri }
                }
                intent?.data != null -> arrayOf(intent.data!!)
                else -> null
            }
            filePathCallback?.onReceiveValue(resultUris)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private var isSettingsModeRequested: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettingsRepository.getInstance(this)
        isFullscreen = appSettings.isFullscreenEnabled()

        targetUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        deviceName = intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.default_device_name)
        pendingTaskId = intent.getStringExtra(EXTRA_TASK_ID) ?: ""
        ClaimCampaignNotifier.acknowledgeIntent(this, intent)
        isSettingsModeRequested = intent.getBooleanExtra(EXTRA_SETTINGS_MODE, false)

        // 反查 connectionId：用于 onPause 时保存当前任务会话到对应连接
        val repo = ConnectionRepository.getInstance(this)
        val connection = resolveConnection(intent)
        if (connection == null) {
            finish()
            return
        }
        connectionId = connection.id
        loginSpace = connection.loginSpace
        targetUrl = connection.url
        deviceName = connection.name
        // 不管从哪条路径进入（列表点击/通知点击/深链接/自动恢复），
        // 都更新 lastActive 指向当前连接——确保下次恢复/通知跳转到正确的连接
        if (connectionId.isNotEmpty()) {
            repo.updateLastConnected(connectionId)
        }
        android.util.Log.d("ZCodeEvent", "connectionId for $deviceName: ${connectionId.ifEmpty { "(not found)" }}")

        val profileMode = runCatching { ConnectionWebProfile.bind(binding.webView, connection.loginSpace) }
            .getOrElse {
                ToastUtils.show(this, getString(R.string.login_space_failed))
                eventSourceHandedOver = true
                if (!hasLiveInstance()) {
                    ai.zcode.remote.service.KeepAliveService.releaseEventSource(
                        this, targetUrl, deviceName, connection.loginSpace.sourceId,
                    )
                }
                finish()
                return
            }

        // 单连接监听：一次只保留一个远程页实例（新打开连接时关闭旧页面，
        // 旧 WebView 随之销毁，事件监听切换到当前连接）。
        // 先取得事件源所有权再加载本页：让保活服务销毁可能残留的隐藏事件源，
        // 确保本 Activity 是唯一连接远端的控制端，避免“连接被占用”。
        ai.zcode.remote.service.KeepAliveService.acquireEventSource(
            this,
            url = targetUrl,
            name = deviceName,
            sourceId = connection.loginSpace.sourceId,
        )
        current?.takeIf { it !== this }?.let { closeConnection(it.connectionId, it.targetUrl) }
        current = this
        setupLoginNotice(connection, profileMode, savedInstanceState)

        setupImmersiveAndScreen()
        setupKeyboardInsets()
        setupNativeTopBar()
        setupWebView()
        webViewReady = true
        setupFloatingControl()
        setupBackPressHandler()

        if (isSettingsModeRequested) {
            val settings = binding.webView.settings
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
        }

        setupEventCapture()
        registerNetworkCallback()
        loadUrl(targetUrl)
        ClaimCampaignNotifier.campaignFromIntent(intent)?.let { claimDialogs.receive(listOf(it), fromNotification = true) }
    }

    /** 外部入口也必须落到已保存连接，不能意外使用默认登录空间。 */
    private fun resolveConnection(incoming: Intent): RemoteConnection? {
        val repository = ConnectionRepository.getInstance(this)
        val url = incoming.getStringExtra(EXTRA_URL).orEmpty()
        val sourceId = incoming.getStringExtra(EXTRA_SOURCE_ID).orEmpty()
            .ifBlank { ClaimCampaignNotifier.sourceFromIntent(incoming) }
            .ifBlank {
                incoming.data?.takeIf { it.scheme == "zcode-notification" }
                    ?.pathSegments?.firstOrNull().orEmpty()
            }
        if (sourceId.isNotBlank()) {
            // 来源身份已包含登录代数；URL 是可编辑的快照，不能用旧地址否决有效通知。
            if (repository.isSourceDeleted(sourceId)) return null
            return repository.findBySourceId(sourceId)
        }
        if (repository.isSourceDeleted(url)) return null
        repository.findByUrl(url)?.let { return it }
        val parsed = UrlParser.parse(url)
        if (!UrlParser.isValidRemoteConnection(parsed)) return null
        return repository.saveConnection(RemoteConnection(
            name = incoming.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { parsed.resolveSuggestedName(this) },
            url = parsed.originalUrl, mid = parsed.mid, sid = parsed.sid,
        ))
    }

    private fun setupLoginNotice(connection: RemoteConnection, mode: ConnectionWebProfile.Mode, savedState: Bundle?) {
        loginNoticeIsolated = mode == ConnectionWebProfile.Mode.ISOLATED
        loginNoticeEligible = loginNoticeIsolated &&
            !ConnectionWebProfile.hasShownIntroduction(this, connection.loginSpace)
        // 重建只能延续同一登录空间的剩余时间，跨连接 recreate 不能带走旧横幅。
        savedState?.takeIf {
            it.getString("login_notice_source") == connection.loginSpace.sourceId &&
                it.getInt("login_notice_process") == android.os.Process.myPid()
        }?.let {
            loginNoticeTimer = LoginNoticeTimer(it.getLong("login_notice_deadline", 0L))
        }
        binding.tvLoginNotice.visibility = if (loginNoticeIsolated) View.GONE else View.VISIBLE
        binding.tvLoginNotice.setText(if (loginNoticeIsolated) R.string.login_space_first_visit else R.string.login_space_unsupported)
        binding.tvLoginNotice.setOnClickListener {
            ConnectionLoginDialog.show(this, connection.id)
            if (loginNoticeIsolated) {
                loginNoticeTimer.dismiss()
                handler.removeCallbacks(hideLoginNotice)
                binding.tvLoginNotice.visibility = View.GONE
            }
        }
    }

    private fun refreshLoginNotice() {
        if (!loginNoticeIsolated || !loginNoticeResumed || !isWebPageAvailable()) return
        if (pageLoading || binding.layoutErrorOverlay.visibility == View.VISIBLE) {
            binding.tvLoginNotice.visibility = View.GONE
            return
        }
        val remaining = loginNoticeTimer.remainingMillis(android.os.SystemClock.elapsedRealtime())
        if (!loginNoticeEligible && remaining == 0L) {
            binding.tvLoginNotice.visibility = View.GONE
            return
        }
        if (loginNoticeProbePending) return
        val space = loginSpace ?: return
        val pageGeneration = loginNoticePageGeneration
        val pageUrl = binding.webView.url ?: return
        // OAuth 页面不参与判定；只检查当前连接同源页面中的真实任务列表。
        if (Uri.parse(pageUrl).let { page ->
                val target = Uri.parse(targetUrl)
                page.scheme != target.scheme || page.host != target.host || page.port != target.port
            }) {
            binding.tvLoginNotice.visibility = View.GONE
            return
        }
        loginNoticeProbePending = true
        binding.webView.evaluateJavascript(TaskListVisibilityScript.js) { visible ->
            if (pageGeneration != loginNoticePageGeneration) return@evaluateJavascript
            loginNoticeProbePending = false
            if (!loginNoticeResumed || !isWebPageAvailable() || loginSpace != space ||
                binding.webView.url != pageUrl || pageLoading ||
                binding.layoutErrorOverlay.visibility == View.VISIBLE) return@evaluateJavascript
            if (visible != "true") {
                binding.tvLoginNotice.visibility = View.GONE
                return@evaluateJavascript
            }
            if (loginNoticeEligible) {
                loginNoticeEligible = false
                if (ConnectionWebProfile.hasShownIntroduction(this, space)) return@evaluateJavascript
                ConnectionWebProfile.markIntroductionShown(this, space)
                loginNoticeTimer.start(android.os.SystemClock.elapsedRealtime())
            }
            val duration = loginNoticeTimer.remainingMillis(android.os.SystemClock.elapsedRealtime())
            handler.removeCallbacks(hideLoginNotice)
            binding.tvLoginNotice.visibility = if (duration > 0) View.VISIBLE else View.GONE
            if (duration > 0) handler.postDelayed(hideLoginNotice, duration)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("login_notice_source", loginSpace?.sourceId)
        outState.putInt("login_notice_process", android.os.Process.myPid())
        outState.putLong("login_notice_deadline", loginNoticeTimer.deadlineElapsedMs)
        super.onSaveInstanceState(outState)
    }

    /** 连接归属不随 renderer 消失；死 view 仍需靠此判断是否有权重建同一登录空间。 */
    private fun ownsCurrentLoginSpace(): Boolean = !eventSourceHandedOver &&
        current === this && !isFinishing && !isDestroyed &&
        loginSpace?.let { ConnectionRepository.getInstance(this).isLoginSpaceCurrent(it) } == true

    private fun isWebPageAvailable(): Boolean = ownsCurrentLoginSpace() &&
        webViewReady && !webViewDisposed && !rendererRecovery.pending

    /** 网页操作发现 renderer 已死时只转恢复；不把 load/reload 投递给已销毁的 view。 */
    private fun recoverRendererIfNeeded(): Boolean {
        if (!rendererRecovery.pending) return false
        if (!ownsCurrentLoginSpace()) {
            if (!isFinishing && !isDestroyed) finish()
            return true
        }
        if (rendererRecovery.requested) return true
        if (!activityVisible) {
            handler.removeCallbacks(rendererRecoveryRunnable)
            rendererRecoveryScheduled = false
            performRendererRecovery()
        } else if (!rendererRecoveryScheduled) {
            rendererRecoveryScheduled = true
            handler.postDelayed(rendererRecoveryRunnable, RENDERER_RECOVERY_DELAY_MS)
        }
        return true
    }

    private fun performRendererRecovery() {
        when (rendererRecovery.nextAction(ownsCurrentLoginSpace(), activityVisible)) {
            RendererRecoveryState.Action.RECREATE -> {
                android.util.Log.i("ZCodeWeb", "recreate activity after renderer process exit")
                // 保留当前 Intent 与 onSaveInstanceState 的同 source/PID 横幅计时，不改 Profile。
                recreate()
            }
            RendererRecoveryState.Action.HANDOVER -> handoverEventSourceToKeepAlive("renderer-gone-background")
            RendererRecoveryState.Action.NONE -> Unit
        }
    }

    private fun requireWebPage(): Boolean {
        if (recoverRendererIfNeeded()) return false
        return isWebPageAvailable()
    }

    /** 注册任务事件桥并在每次页面加载后注入捕获脚本（SPA 导航可能重建 window）。 */
    private fun setupEventCapture() {
        if (!requireWebPage()) return
        ensureNotificationPermission()
        if (::eventBridge.isInitialized) {
            eventBridge.dispose()
            binding.webView.removeJavascriptInterface(TaskEventBridge.BRIDGE_NAME)
        }
        eventCaptureScript?.let {
            try { it.remove() } catch (e: Exception) {
                android.util.Log.w("ZCodeWeb", "document-start script removal failed", e)
            }
            eventCaptureScript = null
        }
        val sourceId = loginSpace?.sourceId ?: return
        eventBridge = TaskEventBridge(
            sourceId = sourceId,
            deviceName = deviceName,
            defaultTaskName = getString(R.string.task_unnamed_background),
            onConnectionState = { state ->
                runOnUiThread { if (isWebPageAvailable()) handleConnectionState(state) }
            },
            onEvent = { event ->
                runOnUiThread { if (isWebPageAvailable()) TaskNotifier.notify(this, event) }
            },
            onClaimCampaigns = { body ->
                if (!isWebPageAvailable()) return@TaskEventBridge
                val campaigns = ClaimCampaignNotifier.receive(this, sourceId, deviceName, body)
                runOnUiThread {
                    if (sourceId == loginSpace?.sourceId && isWebPageAvailable()) {
                        claimDialogs.receive(campaigns)
                    }
                }
            },
            onClaimCampaignClicked = { key ->
                ClaimCampaignNotifier.acknowledge(this, sourceId, key)
            },
            onClaimCampaignStatus = { key, message, finished ->
                runOnUiThread {
                    if (sourceId == loginSpace?.sourceId && isWebPageAvailable()) {
                        claimDialogs.status(key, message, finished)
                    }
                }
            }
        )
        eventBridgeAlive = true
        eventBridgeCreatedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        binding.webView.addJavascriptInterface(eventBridge, TaskEventBridge.BRIDGE_NAME)
        android.util.Log.i("ZCodeEvent", "event bridge registered: source=$sourceId")
        // document-start 脚本按当前连接 origin 注册，对后续每次整页加载都会生效。
        try {
            val origin = Uri.parse(targetUrl).let { uri ->
                if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) targetUrl
                else "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            }
            eventCaptureScript = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                binding.webView,
                EventCaptureScript.build(TaskEventBridge.BRIDGE_NAME),
                setOf(origin)
            )
        } catch (e: Exception) {
            android.util.Log.w("ZCodeWeb", "document-start inject failed", e)
        }
    }

    /** Android 13+ 通知需要运行时授权；拒绝后不再重复打扰（系统会记住选择）。 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9001)
    }

    private fun setupImmersiveAndScreen() {
        if (isFullscreen) {
            ImmersiveHelper.enterImmersiveFullscreen(this)
        } else {
            ImmersiveHelper.exitImmersiveFullscreen(this)
        }
        ImmersiveHelper.setKeepScreenOn(this, isKeepScreenOn)
        applyTopBarVisibility()
    }

    /**
     * 按「显示顶部栏」设置切换 App 顶栏（LOGO + 菜单）的显隐。
     * 与全屏设置相互独立，可任意组合；隐藏后网页内容自然上移占满。
     * 隐藏时同步收起已展开的菜单弹层，避免浮层悬挂在无锚点的位置。
     */
    private fun applyTopBarVisibility() {
        val visible = appSettings.isRemoteTopBarVisible()
        val topBar = binding.layoutNativeTopBar
        val divider = binding.topBarDivider
        val target = if (visible) View.VISIBLE else View.GONE
        if (topBar.visibility != target) topBar.visibility = target
        if (divider.visibility != target) divider.visibility = target
        if (!visible) {
            mainMenuPopup?.dismiss()
            mainMenuPopup = null
        }
    }

    private fun setupKeyboardInsets() {
        // 沉浸式全屏下监听软键盘高度并抬高 WebView，使输入框打字时自动上探至软键盘正上方
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val keyboardHeight = if (imeHeight > 0) (imeHeight - navHeight).coerceAtLeast(0) else 0

            val webViewLp = if (webViewDisposed) null else
                binding.webView.layoutParams as? android.widget.FrameLayout.LayoutParams
            if (webViewLp != null && webViewLp.bottomMargin != keyboardHeight) {
                webViewLp.bottomMargin = keyboardHeight
                binding.webView.layoutParams = webViewLp
            }

            // 状态栏透明沉浸：原生顶部栏不被状态栏遮挡，paddingTop 动态 = 状态栏高
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val topBar = binding.layoutNativeTopBar
            if (topBar.paddingTop != statusBarHeight) {
                topBar.setPadding(topBar.paddingLeft, statusBarHeight, topBar.paddingRight, topBar.paddingBottom)
            }
            insets
        }
    }

    /** 原生顶部栏：调色板按钮（当前已隐藏）→ 通过 JS 打开网页内主题菜单。 */
    private fun setupNativeTopBar() {
        binding.btnTopBarTheme.setOnClickListener {
            if (!requireWebPage()) return@setOnClickListener
            // 调网页内 zcodeTriggerThemeToggle()（由 ZCodeWebViewClient 在 onPageFinished 注入）
            binding.webView.evaluateJavascript(
                "(window.zcodeTriggerThemeToggle && window.zcodeTriggerThemeToggle()) || 'not-ready'",
                null
            )
        }
        binding.btnTopBarMenu.setOnClickListener { showMainMenu() }
    }

    /**
     * 顶栏菜单：缩放快捷方式（缩小/放大）+「设置」「关于」。
     * 远程页已在某个连接中，不提供扫描/地址连接（会切换连接，属列表页职责）。
     * 用独立的 popup_remote_menu.xml，与主界面菜单互不影响。
     */
    private fun showMainMenu() {
        mainMenuPopup?.dismiss()

        val menuBinding = PopupRemoteMenuBinding.inflate(layoutInflater)
        val popupWidth = resources.getDimensionPixelSize(R.dimen.main_menu_width)
        val popup = PopupWindow(
            menuBinding.root,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = resources.displayMetrics.density * 12f
            isOutsideTouchable = true
            setBackgroundDrawable(ContextCompat.getDrawable(this@RemoteControlActivity, R.drawable.bg_menu_popup))
            setOnDismissListener {
                if (mainMenuPopup === this) mainMenuPopup = null
            }
        }

        // 缩放快捷方式：与设置页滑杆共用同一存储与生效链路（写 prefs → applyPageZoom）。
        // 菜单保持打开以便连续微调，故不调 popup.dismiss()；到边界时按钮置灰。
        // 当前百分比不在菜单里显示，改为点击后屏幕中央短暂提示（showZoomHint）。
        fun renderZoomRow() {
            val zoom = appSettings.getPageZoom()
            menuBinding.menuItemZoomOut.isEnabled =
                zoom > AppSettingsRepository.PAGE_ZOOM_MIN
            menuBinding.menuItemZoomIn.isEnabled =
                zoom < AppSettingsRepository.PAGE_ZOOM_MAX
            menuBinding.menuItemZoomOut.alpha = if (menuBinding.menuItemZoomOut.isEnabled) 1f else 0.4f
            menuBinding.menuItemZoomIn.alpha = if (menuBinding.menuItemZoomIn.isEnabled) 1f else 0.4f
        }

        fun stepZoom(delta: Int) {
            val next = (appSettings.getPageZoom() + delta)
                .coerceIn(AppSettingsRepository.PAGE_ZOOM_MIN, AppSettingsRepository.PAGE_ZOOM_MAX)
            if (next == appSettings.getPageZoom()) return
            appSettings.setPageZoom(next)
            if (requireWebPage()) customWebViewClient.applyPageZoom(binding.webView)
            renderZoomRow()
            showZoomHint(next)
        }

        renderZoomRow()
        menuBinding.menuItemLogin.setOnClickListener {
            popup.dismiss()
            ConnectionLoginDialog.show(this, connectionId)
        }
        menuBinding.menuItemZoomOut.setOnClickListener { stepZoom(-STEP) }
        menuBinding.menuItemZoomIn.setOnClickListener { stepZoom(STEP) }
        menuBinding.menuItemSettings.setOnClickListener {
            popup.dismiss()
            SettingsActivity.start(this)
        }
        menuBinding.menuItemAbout.setOnClickListener {
            popup.dismiss()
            AboutActivity.start(this)
        }

        mainMenuPopup = popup
        popup.showAsDropDown(binding.btnTopBarMenu, -popupWidth + binding.btnTopBarMenu.width, 8)
    }

    /**
     * 屏幕中央短暂显示当前页面缩放（2 秒后淡出）。
     * 连续调整时先移除上一次的隐藏任务，新值直接替换旧值显示——
     * 计时从最后一次调整重新开始，不会出现新旧提示叠加或提前消失。
     */
    private fun showZoomHint(zoomPercent: Int) {
        val hint = binding.tvZoomHint
        handler.removeCallbacks(zoomHintHide)
        hint.animate().cancel()
        hint.text = getString(R.string.menu_zoom_value, zoomPercent)
        hint.alpha = 1f
        hint.visibility = View.VISIBLE
        handler.postDelayed(zoomHintHide, ZOOM_HINT_DURATION_MS)
    }

    /** 缩放提示的淡出任务：2 秒到点后淡出并置 GONE。 */
    private val zoomHintHide = Runnable {
        binding.tvZoomHint.animate()
            .alpha(0f)
            .setDuration(ZOOM_HINT_FADE_MS)
            .withEndAction {
                // 淡出期间可能已被新的调整重新显示，仅在仍未显示时才隐藏
                if (binding.tvZoomHint.alpha == 0f) {
                    binding.tvZoomHint.visibility = View.GONE
                }
            }
            .start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        // 开启现代 Web 能力
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // 提升长 DOM/Diff 列表光栅化与多进程渲染稳定性
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            settings.offscreenPreRaster = true
        }

        // 禁用默认多点缩放引起的抖动
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        // 视口自适应
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // User Agent 设定为 Windows Chrome，保障高级设置与工作区全部可用
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        // 不在原生层拦截长按。旧方案（isLongClickable=false +
        // setOnLongClickListener{true}）会把 WebView 的长按事件全部消费掉，
        // 连任务会话输入框的长按粘贴菜单也一并失效。防误触改由注入 CSS 承担：
        // 全局 user-select:none 让非文本区不可选中（长按不弹选择菜单），
        // 输入框/xterm/Monaco 在豁免名单中保持 user-select:text，粘贴正常。

        // 配置 WebViewClient
        customWebViewClient = ZCodeWebViewClient(
            appContext = applicationContext,
            onPageStart = pageStart@ {
                if (!isWebPageAvailable()) return@pageStart
                pageLoading = true
                loginNoticePageGeneration++
                loginNoticeProbePending = false
                if (loginNoticeIsolated) binding.tvLoginNotice.visibility = View.GONE
                pendingSessionJump?.let { handler.removeCallbacks(it) }
                pendingSessionJump = null
                handler.removeCallbacks(reconnectRunnable)
                reconnectScheduled = false
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutErrorOverlay.visibility = View.GONE
            },
            onPageFinish = pageFinish@ { _ ->
                if (!isWebPageAvailable()) return@pageFinish
                pageLoading = false
                handler.removeCallbacks(reconnectRunnable)
                reconnectScheduled = false
                binding.progressBar.visibility = View.GONE
                refreshLoginNotice()
                // 整页加载后按当前设置重注 viewport 缩放（SPA 内部导航不改 meta，
                // 无需额外轮询；设置页改完缩放返回走 onResume 那条路径）
                customWebViewClient.applyPageZoom(binding.webView)
                // 通知点击带来的会话跳转：页面加载完成后注入 JS 定位并点击任务条目
                jumpToPendingSession(1500)
                if (isSettingsModeRequested && binding.layoutErrorOverlay.visibility != View.VISIBLE) {
                    pendingSettingsOpen?.let { handler.removeCallbacks(it) }
                    val openSettings = Runnable {
                        pendingSettingsOpen = null
                        if (!isWebPageAvailable() || !isSettingsModeRequested || binding.layoutErrorOverlay.visibility == View.VISIBLE) return@Runnable
                        customWebViewClient.openWorkspaceSettings(binding.webView) { opened ->
                            if (!isWebPageAvailable()) return@openWorkspaceSettings
                            isSettingsModeRequested = false
                            if (opened) {
                                ToastUtils.show(this@RemoteControlActivity, getString(R.string.toast_entered_settings_center))
                            } else {
                                customWebViewClient.setDesktopViewport(binding.webView, false)
                                ToastUtils.show(this@RemoteControlActivity, getString(R.string.toast_enter_settings_failed))
                            }
                        }
                    }
                    pendingSettingsOpen = openSettings
                    handler.postDelayed(openSettings, 500)
                }
            },
            onPageError = pageError@ { _, description ->
                if (!isWebPageAvailable()) return@pageError
                binding.progressBar.visibility = View.GONE
                binding.layoutErrorOverlay.visibility = View.VISIBLE
                binding.tvErrorDetail.text = description
                if (networkAvailable) scheduleReconnect("page-error")
            },
            onRenderProcessGone = {
                // 必须在回调内同步失效并销毁，不能让迟到回调/按钮/重连再触碰死 view。
                if (rendererRecovery.markGone()) {
                    disposeWebView()
                    recoverRendererIfNeeded()
                }
            },
            pageZoomProvider = { appSettings.getPageZoom() },
            dashboardModeProvider = { appSettings.getDashboardMode() },
        )
        webView.webViewClient = customWebViewClient

        // 配置 WebChromeClient
        webView.webChromeClient = ZCodeWebChromeClient(
            allowedHost = Uri.parse(targetUrl).host,
            onProgressUpdate = progressUpdate@ { progress ->
                if (!isWebPageAvailable()) return@progressUpdate
                binding.progressBar.progress = progress
                if (progress >= 100) {
                    binding.progressBar.visibility = View.GONE
                } else {
                    binding.progressBar.visibility = View.VISIBLE
                }
            },
            onTitleReceived = titleReceived@ { title ->
                if (!isWebPageAvailable()) return@titleReceived
                // 仅当设备名仍是缺省值时才用 WebView 标题覆盖；用户显式命名过的连接不被标题替换
                if (deviceName == getString(R.string.default_device_name) && title.isNotBlank()) {
                    deviceName = title
                }
            },
            onOpenFileChooser = fileChooser@ { callback, params ->
                if (!isWebPageAvailable()) return@fileChooser false
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                try {
                    val intent = params.createIntent()
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }
        )

        binding.btnRetry.setOnClickListener {
            if (!requireWebPage()) return@setOnClickListener
            binding.layoutErrorOverlay.visibility = View.GONE
            loadUrl(targetUrl)
        }
    }

    private fun setupFloatingControl() {
        binding.floatingControlView.onClickAction = {
            showControlMenuDialog()
        }
    }

    private fun showControlMenuDialog() {
        val dialog = FloatingControlDialog.newInstance(
            deviceName = deviceName,
            isFullscreen = isFullscreen
        )

        // 打开设置中心：openWorkspaceSettings 内部会先切桌面视口拉起齿轮按钮，
        // 成功后再回切移动端视口以 APP 端布局展示设置中心
        dialog.onOpenSettingsListener = openSettings@ {
            if (!requireWebPage()) return@openSettings
            customWebViewClient.openWorkspaceSettings(binding.webView) { success ->
                if (!isWebPageAvailable()) return@openWorkspaceSettings
                if (success) {
                    ToastUtils.show(this@RemoteControlActivity, getString(R.string.toast_entered_settings_center))
                } else {
                    customWebViewClient.setDesktopViewport(binding.webView, false)
                    ToastUtils.show(this@RemoteControlActivity, getString(R.string.toast_enter_settings_failed))
                }
            }
        }

        dialog.onRefreshListener = refresh@ {
            if (!requireWebPage()) return@refresh
            binding.webView.reload()
            ToastUtils.show(this, getString(R.string.toast_reconnecting))
        }

        dialog.onToggleFullscreenListener = {
            isFullscreen = !isFullscreen
            appSettings.setFullscreenEnabled(isFullscreen)
            if (isFullscreen) {
                ImmersiveHelper.enterImmersiveFullscreen(this)
                ToastUtils.show(this, getString(R.string.toast_entered_immersive))
            } else {
                ImmersiveHelper.exitImmersiveFullscreen(this)
                ToastUtils.show(this, getString(R.string.toast_exited_immersive))
            }
        }

        dialog.onCopyUrlListener = {
            val currentUrl = if (isWebPageAvailable()) binding.webView.url ?: targetUrl else targetUrl
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("ZCode URL", currentUrl)
            clipboard?.setPrimaryClip(clip)
            ToastUtils.show(this, getString(R.string.toast_copied))
        }

        dialog.onClearCacheListener = clearCache@ {
            if (!requireWebPage()) return@clearCache
            binding.webView.clearCache(true)
            binding.webView.reload()
            ToastUtils.show(this, getString(R.string.toast_cache_cleared))
        }

        dialog.onBackHomeListener = {
            finish()
        }

        dialog.show(supportFragmentManager, "FloatingControlDialog")
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dispatchPageBack()
            }
        })
    }

    /**
     * 执行一次"返回"：先向 WebView 注入探测脚本判断页面状态，再按返回值决定
     * 页面内处理 / 网页回退 / 退出到设备列表。
     * 系统返回键与页面内注入的「返回」按钮共用此逻辑，保证两者行为一致。
     */
    private fun dispatchPageBack() {
        if (!isWebPageAvailable()) {
            // 恢复中的旧页面不能探测 JS，但返回仍应能离开，不能困在等待恢复的空白页。
            if (rendererRecovery.pending) handoverEventSourceToKeepAlive("renderer-gone-back")
            exitToDeviceList()
            return
        }
        binding.webView.evaluateJavascript(
            """(function() {
                function isVisible(el) {
                    if (!el) return false;
                    var st = window.getComputedStyle(el);
                    if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity) === 0 || st.pointerEvents === 'none') {
                        return false;
                    }
                    var r = el.getBoundingClientRect();
                    if (r.width <= 0 || r.height <= 0 || r.bottom <= 0 || r.right <= 0 ||
                        r.top >= window.innerHeight || r.left >= window.innerWidth) return false;
                    for (var p = el; p && p.nodeType === 1; p = p.parentElement) {
                        var ps = window.getComputedStyle(p);
                        if (p.hidden || p.getAttribute('aria-hidden') === 'true' || p.hasAttribute('inert') ||
                            ps.display === 'none' || ps.visibility === 'hidden' || parseFloat(ps.opacity) === 0) return false;
                    }
                    return true;
                }

                // 2026-10-20 真机：长按正文进入原生选词后，返回不能直接离开会话。
                // 清除 DOM 选区会同步结束 WebView 的复制工具栏；光标（折叠选区）
                // 不在此消费返回，保留正常键盘和页面导航。
                var selection = window.getSelection();
                if (selection && !selection.isCollapsed) {
                    selection.removeAllRanges();
                    return 'true';
                }

                // 0. 菜单/弹层可能叠在会话、侧边面板或设置对话框之上，先交回原生
                // Escape 关闭最上层。2026-10-20 真机复现：漏掉模型菜单时会直接点
                // 「返回任务首页」，丢失当前会话位置。只检测打开且可见的浮层，
                // 不把已关闭的过渡节点、常驻面板或设置页当成需要消解的菜单。
                var openPopup = Array.from(document.querySelectorAll(
                    'div[role="menu"][data-state="open"], div[role="listbox"][data-state="open"], ' +
                    'div[data-slot="popover-content"][data-state="open"], ' +
                    'div[data-slot="hover-card-content"][data-state="open"], ' +
                    'div[role="dialog"][data-state="open"]'
                )).find(isVisible);
                if (openPopup) return 'dismiss-popup';

                // 2026-10-20 真机：小火箭引导用页面内状态切步骤，不写浏览历史。
                // 第一屏没有左上角返回，history.back() 会无效；仅在引导页内复用
                // 页头的上一步/退出按钮，保留弹层优先级及普通会话的返回规则。
                var onboarding = document.querySelector('[data-testid="onboarding-page"]');
                if (onboarding && isVisible(onboarding)) {
                    var guideExit = onboarding.querySelector('header button[aria-label="退出引导"]');
                    if (guideExit && isVisible(guideExit)) {
                        var guideBack = Array.from(guideExit.closest('header').querySelectorAll('button')).find(function(b) {
                            return b !== guideExit && isVisible(b) && /^(返回|Back)$/i.test((b.textContent || '').trim());
                        });
                        (guideBack || guideExit).click();
                        return 'true';
                    }
                }

                // 1. 优先关闭/收起处于打开状态的代码审查、终端等侧边栏面板
                // 1A. 移动端侧滑面板全屏遮罩（处于打开态时，直接 click 遮罩内的关闭触发器）
                // ⚠️ 判据必须用与 class 同步翻转的 aria-hidden / pointer-events-none，**不能**用
                // computed opacity：遮罩开合是 Tailwind class 切换 + 200ms opacity 过渡，过渡期间
                // computed opacity 仍是旧值（实测打开后 20ms 为 0、120ms 才 0.26）。若用 opacity
                // 判定，在"刚打开抽屉就按返回"时会漏判 1A，继续向下分层落到第 5 层"返回任务首页"，
                // 把用户直接带回会话列表——与页面内返回按钮同源的 bug。
                var overlay = document.querySelector('[data-mobile-side-pane-overlay="true"]');
                if (overlay) {
                    var ocls = overlay.getAttribute('class') || '';
                    var overlayOpen = overlay.getAttribute('aria-hidden') !== 'true' &&
                                      ocls.indexOf('pointer-events-none') < 0;
                    if (overlayOpen) {
                        var overlayBtn = overlay.querySelector('button') || overlay;
                        overlayBtn.click();
                        return 'true';
                    }
                }

                // 1B. 右上角收起按钮（带 panel-right-close 图标或 selected 状态）
                var panelCloseBtn = Array.from(document.querySelectorAll('button')).find(function(b) {
                    if (!isVisible(b)) return false;
                    var r = b.getBoundingClientRect();
                    var isTopRight = r.top >= 0 && r.top < 100 && r.left > 200;
                    var hasCloseIcon = b.querySelector('svg.lucide-panel-right-close, path[d*="m8 9 3 3-3 3"]') !== null;
                    var isSelected = (b.className || '').indexOf('selected') >= 0 || (b.getAttribute('data-state') === 'active' || b.getAttribute('data-state') === 'open');
                    return isTopRight && (hasCloseIcon || (b.querySelector('path[d*="M15 3v18"]') && isSelected));
                });
                if (panelCloseBtn) {
                    panelCloseBtn.click();
                    return 'true';
                }

                // 1C. 侧边栏内部的独立关闭按钮
                var sidePaneClose = document.querySelector('[data-mobile-side-pane="true"] button[aria-label*="关闭"], [data-mobile-side-pane="true"] button:has(svg.lucide-x)');
                if (sidePaneClose && isVisible(sidePaneClose)) {
                    sidePaneClose.click();
                    return 'true';
                }

                // 2. 检查是否有处于打开状态的真正模态对话框 (Dialog) 的关闭按钮
                var openDialogClose = document.querySelector('div[role="dialog"][data-state="open"] button[aria-label*="关闭"], div[role="dialog"][data-state="open"] button[aria-label*="Close"], div[role="dialog"] button.close');
                if (openDialogClose && isVisible(openDialogClose)) {
                    openDialogClose.click();
                    return 'true';
                }

                // 3. 检查设置中心二级详情页的悬浮返回按钮
                var detailBack = document.getElementById('__zcode_detail_back');
                if (detailBack && isVisible(detailBack)) {
                    detailBack.click();
                    return 'true';
                }

                // 4. 检查设置中心的“返回工作区”按钮
                // 2026-10-20 真机：首页左上角的“切换设备”也在 aside 内，旧几何
                // 兜底会把它当返回键。新版只在设置容器内找返回；旧版只兼容明确语义。
                var settingsPage = document.querySelector('[data-testid="settings-page"]');
                var settingsScope = settingsPage && isVisible(settingsPage) ? settingsPage : document;
                var settingsBack = Array.from(settingsScope.querySelectorAll('button[data-testid="settings-back-button"], button[aria-label*="返回工作区"], button[aria-label="Back to Workspace"], button[aria-label="Back to workspace"]')).find(function(b) {
                    if (!isVisible(b)) return false;
                    var aria = (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.textContent || '');
                    return b.getAttribute('data-testid') === 'settings-back-button' || /返回工作区|Back to Workspace/i.test(aria);
                });
                if (settingsBack && isVisible(settingsBack)) {
                    settingsBack.click();
                    return 'true';
                }

                // 5. 任务会话返回上一页任务列表：
                // 全局通用查找左上角返回按钮（适配所有版本、所有类名、所有远程地址）
                var allTopInteractives = Array.from(document.querySelectorAll('button, a, div[role="button"], span[role="button"]'));
                var headerBackBtn = allTopInteractives.find(function(b) {
                    if (b.id === '__zcode_detail_back') return false;
                    if (b.id === '__zcode_title_back') return false;
                    if (b.getAttribute('aria-label') === '切换设备') return false;
                    if (!isVisible(b)) return false;

                    var r = b.getBoundingClientRect();
                    // 必须位于页面顶部左上角区域（top < 70 且 left < 80 且宽度在合理范围 16~120px）
                    if (r.top < 0 || r.top > 70 || r.left < 0 || r.left > 80 || r.width <= 0 || r.width > 120) {
                        return false;
                    }

                    // 特征 A: 包含向左箭头 SVG
                    var hasArrowLeft = b.querySelector('svg.lucide-arrow-left, svg.lucide-chevron-left, path[d*="m12 19"], path[d*="19-7-7"], path[d*="19 12H5"], path[d*="12H5"], path[d*="M15 19"], path[d*="15 18"], path[d*="M10 19"], path[d*="M19 12"], path[d*="15 6-6 6"]') !== null;
                    if (hasArrowLeft) return true;

                    // 特征 B: aria-label / title / text 包含返回或首页
                    var label = (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.textContent || '');
                    if (/返回|首页|任务列表|Back|Home|chevron-left|arrow-left/i.test(label)) return true;

                    // 特征 C: 如果在顶部 Header 容器中，且是最左边的第一个按钮
                    var isInsideHeader = !!b.closest('header, [class*="header"], [class*="Header"], [class*="topbar"], [class*="top-bar"], [class*="h-11"], [class*="h-12"], [class*="h-10"]');
                    if (isInsideHeader && r.left < 40) return true;

                    // 特征 D: 纯几何兜底：左上角 40x40 范围内尺寸 <= 40 的小图标按钮
                    return r.top < 50 && r.left < 50 && r.width <= 40 && r.height <= 40;
                });

                if (headerBackBtn && isVisible(headerBackBtn)) {
                    headerBackBtn.click();
                    return 'true';
                }

                // 6. 任务会话状态判定与兜底
                var isChatSession = !!document.querySelector('textarea, .history-message, [data-slot="chat-input"], [data-testid*="chat"], [data-slot="terminal"], [data-slot="diff-editor"]');
                if (isChatSession) {
                    if (window.history && window.history.length > 1) {
                        window.history.back();
                        return 'true';
                    }
                }

                // 7. 检查是否处于根页面（工作区和任务列表首页）
                var bodyText = document.body.innerText || '';
                var hasHomeKeywords = /当前设备上的工作区和任务|已连接到当前桌面窗口|工作区和任务|Workspaces and Tasks|Connected to desktop/i.test(bodyText);
                var hasHomeElements = Array.from(document.querySelectorAll('[data-testid="task-new-button"], [data-testid^="task-item-"], button[data-testid="virtual-project-toggle"]')).some(isVisible);

                if (!isChatSession && (hasHomeKeywords || hasHomeElements)) {
                    return 'finish';
                }

                // 8. 通用 history 兜底
                if (window.history && window.history.length > 1) {
                    window.history.back();
                    return 'true';
                }

                return 'false';
            })()""".trimIndent()
        ) { handled ->
            if (!isWebPageAvailable()) return@evaluateJavascript
            if (handled == "\"dismiss-popup\"") {
                // 用浏览器受信任键盘事件交给远端 Radix 的浮层栈处理；不合成 click、
                // 不重复点击触发器，避免开关/子菜单双触发。此次返回只关闭浮层，
                // 不继续执行页面回退。新版表格预览的 dialog-close 只有关闭文字，
                // 没有 aria-label，同样由 Escape 处理，保留嵌套弹层的关闭顺序。
                binding.webView.requestFocus()
                binding.webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                binding.webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
                lastBackPressTime = 0L
                return@evaluateJavascript
            }
            val pageHandled = handled == "true" || handled == "\"true\""
            if (pageHandled) {
                lastBackPressTime = 0L
                return@evaluateJavascript
            }
            if (handled == "\"finish\"") {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    lastBackPressTime = 0L
                    exitToDeviceList()
                } else {
                    lastBackPressTime = now
                    ToastUtils.show(this@RemoteControlActivity, getString(R.string.press_again_to_exit))
                }
                return@evaluateJavascript
            }

            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
                lastBackPressTime = 0L
                return@evaluateJavascript
            }

            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 2000) {
                exitToDeviceList()
            } else {
                lastBackPressTime = now
                ToastUtils.show(this@RemoteControlActivity, getString(R.string.press_again_to_exit))
            }
        }
    }

    /**
     * 加载远端页时刻意把 `Accept-Language` 钉为中文，避免 APP 切到英文后系统默认
     * Accept-Language 变为 en 导致远端 SPA 跟着变英文——注入脚本中大量 aria-label
     * 选择器按中文匹配（"设置"/"拖拽"/"返回工作区"等），远端变英文会让这些注入失效。
     * 与 `ZCodeWebViewClient` 在 onPageStarted 注入的 `navigator.language` 锁定互补：
     * 一个管 HTTP 请求头，一个管 JS 侧 navigator 读取。
     */
    private fun loadUrl(url: String) {
        if (!requireWebPage()) return
        pageLoading = true
        pendingSessionJump?.let { handler.removeCallbacks(it) }
        pendingSessionJump = null
        val headers = mapOf("Accept-Language" to "zh-CN,zh;q=0.9")
        binding.webView.loadUrl(url, headers)
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        networkAvailable = isNetworkAvailable()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    val wasUnavailable = !networkAvailable || networkLossObserved
                    networkAvailable = true
                    if (wasUnavailable) {
                        networkLossObserved = false
                        android.util.Log.i("ZCodeWeb", "network available: ${network.networkHandle}")
                        scheduleReconnect("network-available")
                    }
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    networkLossObserved = true
                    networkAvailable = isNetworkAvailable()
                    if (networkAvailable) {
                        android.util.Log.i("ZCodeWeb", "default network switched: ${network.networkHandle}")
                        scheduleReconnect("network-switched")
                    } else {
                        handler.removeCallbacks(reconnectRunnable)
                        reconnectScheduled = false
                        android.util.Log.w("ZCodeWeb", "network lost: ${network.networkHandle}")
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                handler.post {
                    val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (validated && !networkAvailable) {
                        networkAvailable = true
                        scheduleReconnect("network-validated")
                    }
                }
            }
        }
        networkCallback = callback
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallbackRegistered = true
            android.util.Log.i("ZCodeWeb", "network callback registered")
        } catch (e: Exception) {
            android.util.Log.e("ZCodeWeb", "network callback registration failed", e)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            android.util.Log.w("ZCodeWeb", "network callback unregister failed", e)
        }
        networkCallbackRegistered = false
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun scheduleReconnect(reason: String, minimumDelayMs: Long = 0L) {
        if (!requireWebPage()) return
        if (!networkAvailable || reconnectScheduled || isFinishing ||
            (Build.VERSION.SDK_INT >= 17 && isDestroyed)
        ) return
        val now = android.os.SystemClock.elapsedRealtime()
        val intervalRemaining = (MIN_RECONNECT_INTERVAL_MS - (now - lastReconnectAtElapsedMs))
            .coerceAtLeast(0L)
        reconnectScheduled = true
        val delay = maxOf(
            ReconnectPolicy.delayMs(reconnectAttempt),
            minimumDelayMs,
            intervalRemaining,
        )
        android.util.Log.i("ZCodeWeb", "reconnect scheduled: reason=$reason delayMs=$delay attempt=$reconnectAttempt")
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun cancelReconnect(reason: String) {
        if (reconnectScheduled) {
            handler.removeCallbacks(reconnectRunnable)
            reconnectScheduled = false
            android.util.Log.i("ZCodeWeb", "reconnect cancelled: reason=$reason")
        }
    }

    private fun handleConnectionState(state: TaskEventBridge.ConnectionState) {
        when (state.state) {
            TaskEventBridge.ConnectionState.State.UP -> {
                cancelReconnect("transport-up")
                reconnectAttempt = 0
                android.util.Log.i("ZCodeWeb", "transport recovered: ${state.transport}")
            }
            TaskEventBridge.ConnectionState.State.DOWN -> {
                if (networkAvailable) {
                    scheduleReconnect("transport-down", TRANSPORT_SELF_RECOVERY_GRACE_MS)
                }
            }
            TaskEventBridge.ConnectionState.State.DEGRADED -> {
                android.util.Log.d("ZCodeWeb", "transport degraded: ${state.transport} ${state.reason}")
            }
        }
    }

    /** singleTop 复用栈顶实例时：用新 Intent 的 extra 切换到新连接/会话。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = resolveConnection(intent) ?: return
        // 通知携带的地址可能早于用户编辑，重建及恢复都应使用当前仓库快照。
        intent.putExtra(EXTRA_URL, next.url)
        intent.putExtra(EXTRA_NAME, next.name)
        intent.putExtra(EXTRA_SOURCE_ID, next.loginSpace.sourceId)
        setIntent(intent)
        if (loginSpace != next.loginSpace) {
            // WebView 的 Profile 只能在首次使用前设置；跨空间通过新实例完整重建。
            saveCurrentTaskId()
            eventSourceHandedOver = true
            eventBridgeAlive = false
            if (::eventBridge.isInitialized) eventBridge.dispose()
            claimDialogs.clear()
            handler.removeCallbacksAndMessages(null)
            foregroundSessionVisible.set(false)
            foregroundSessionId.set("")
            disposeWebView()
            recreate()
            return
        }
        ClaimCampaignNotifier.acknowledgeIntent(this, intent)
        val newUrl = next.url
        val newName = next.name
        val newTaskId = intent.getStringExtra(EXTRA_TASK_ID) ?: ""
        if (recoverRendererIfNeeded()) return
        val campaign = ClaimCampaignNotifier.campaignFromIntent(intent)
        if (isWebPageAvailable() && campaign != null &&
            binding.layoutErrorOverlay.visibility != View.VISIBLE) {
            // 同登录空间的旧地址通知仍复用当前网页，不因可编辑 URL 变化丢失草稿。
            targetUrl = newUrl
            deviceName = newName
            claimDialogs.receive(listOf(campaign), fromNotification = true)
            return
        }
        claimDialogs.clear()
        if (isWebPageAvailable() && newTaskId.isNotEmpty() &&
            binding.layoutErrorOverlay.visibility != View.VISIBLE
        ) {
            // 2026-10-20：通知原先无条件重载，v4 会重新配对并丢失现有 WS。
            // 同连接直接在现有页面导航；正在加载时由 onPageFinish 接续最新目标。
            isSettingsModeRequested = false
            targetUrl = newUrl
            deviceName = newName
            pendingTaskId = newTaskId
            binding.webView.evaluateJavascript("window.__zcodeJumpGen = (window.__zcodeJumpGen || 0) + 1", null)
            jumpToPendingSession()
            return
        }
        // 保存当前页面的 taskId 到旧连接（切换前）
        saveCurrentTaskId()
        // 更新连接信息并重新加载
        targetUrl = newUrl
        deviceName = newName
        pendingTaskId = newTaskId
        isSettingsModeRequested = intent.getBooleanExtra(EXTRA_SETTINGS_MODE, false)
        val repo = ConnectionRepository.getInstance(this)
        connectionId = next.id
        if (connectionId.isNotEmpty()) {
            repo.updateLastConnected(connectionId)
        }
        // singleTop 切换连接时同步事件源目标：本页仍是唯一控制端，服务只更新接管目标，
        // 不创建隐藏监听；本页销毁后才按新 URL 接管。
        ai.zcode.remote.service.KeepAliveService.acquireEventSource(
            this,
            url = targetUrl,
            name = deviceName,
            sourceId = next.loginSpace.sourceId,
        )
        setupEventCapture()
        // 重新加载页面
        loadUrl(targetUrl)
        claimDialogs.setVisible(foregroundSessionVisible.get())
        campaign?.let { claimDialogs.receive(listOf(it), fromNotification = true) }
    }

    /** 原生延迟和页面内跳转都只保留最新目标，整页重载后仍可继续未完成的跳转。 */
    private fun jumpToPendingSession(delayMs: Long = 0) {
        pendingSessionJump?.let { handler.removeCallbacks(it) }
        pendingSessionJump = null
        if (!requireWebPage() || pendingTaskId.isEmpty() || pageLoading) return
        val tid = pendingTaskId
        val jump = Runnable {
            pendingSessionJump = null
            if (!isWebPageAvailable() || pageLoading || pendingTaskId != tid) return@Runnable
            binding.webView.evaluateJavascript(
                ai.zcode.remote.ui.remote.event.SessionJumpScript.build(tid), null,
            )
        }
        pendingSessionJump = jump
        handler.postDelayed(jump, delayMs)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // renderer 待恢复必须先于 ready/ownership 退出分支，否则死 view 会误触 finish。
        if (recoverRendererIfNeeded()) return
        if (!webViewReady) return
        if (!ownsCurrentLoginSpace()) {
            finish()
            return
        }
        // 不调 webView.onResume()：与 onPause 对应，保持 WebView 持续活跃，
        // 让切后台时 JS 的 WebSocket 仍能收消息（审批/完成事件镜像到系统通知）
        // 从「显示与全屏」设置页返回时，全屏与顶部栏开关可能刚改过：
        // 重新读一次设置再应用（该函数内部会读取最新值）。
        isFullscreen = appSettings.isFullscreenEnabled()
        setupImmersiveAndScreen()
        // 页面恢复到前台可见：记录可见状态并持续跟踪当前会话 ID。
        // 若用户正停留在该会话页（正在对话），审批/提问弹层已在页面上，
        // 系统通知跳过（见 TaskNotifier.notify 的前台会话判断）。
        // 周期刷新以跟随 SPA 页面内切换会话（页面内导航不触发 onPageFinished）
        foregroundSessionVisible.set(true)
        loginNoticeResumed = true
        refreshLoginNotice()
        claimDialogs.setVisible(true)
        refreshForegroundSessionId()
        handler.postDelayed(foregroundSessionTick, FOREGROUND_SESSION_TICK_MS)

        // 从原生设置页返回时按最新「页面缩放」重注视口（onCreate 时页面尚未加载，
        // 真正生效靠 onPageFinish；这里只覆盖"改完设置返回"的场景）
        val currentUrl = binding.webView.url
        if (!currentUrl.isNullOrEmpty() && currentUrl != "about:blank") {
            customWebViewClient.applyPageZoom(binding.webView)
            // 「工作区与任务页样式」可能刚在设置页改过：重注适配样式，无需重载页面。
            // 幂等——注入脚本按当前设置增删 dashboard 样式元素与调色板按钮。
            customWebViewClient.reapplyPageAdaptation(binding.webView)
        }
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
    }

    override fun onStop() {
        super.onStop()
        activityVisible = false
        if (rendererRecovery.pending) recoverRendererIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        loginNoticeResumed = false
        loginNoticePageGeneration++
        loginNoticeProbePending = false
        handler.removeCallbacks(hideLoginNotice)
        if (loginNoticeIsolated) binding.tvLoginNotice.visibility = View.GONE
        // 页面离开前台时不停止 WebView 事件监听；仅暂停前台会话抑制与 UI 轮询。
        foregroundSessionVisible.set(false)
        claimDialogs.setVisible(false)
        // 立即清空旧会话 ID：若事件解析器在 onPause 之前已捕获“前台会话”，
        // 会沿用旧 ID 把切后台后的审批/提问也抑制掉，表现为几次通知后彻底失联。
        foregroundSessionId.set("")
        handler.removeCallbacks(foregroundSessionTick)
        // 切出时记录当前任务会话：通过 JS 从页面 DOM 提取 task-item 的
        // data-testid（选中态/展开态），持久化到对应连接，下次切回时恢复
        saveCurrentTaskId()
    }

    /** 周期刷新前台会话 ID 的 Runnable（跟随页面内会话切换）。 */
    private val foregroundSessionTick = object : Runnable {
        override fun run() {
            if (!foregroundSessionVisible.get() || !isWebPageAvailable()) return
            refreshForegroundSessionId()
            refreshLoginNotice()
            handler.postDelayed(this, FOREGROUND_SESSION_TICK_MS)
        }
    }

    /** 提取当前前台会话 ID 并更新全局（供 TaskNotifier 判断是否抑制通知）。 */
    private fun refreshForegroundSessionId() {
        if (!isWebPageAvailable()) return
        if (Build.VERSION.SDK_INT >= 17 && isDestroyed) return
        binding.webView.evaluateJavascript("""
            (function() {
                var pane = document.querySelector('[data-session-id]');
                if (pane) {
                    var sid = pane.getAttribute('data-session-id');
                    if (sid && sid.length > 0) return sid;
                }
                return '';
            })()
        """.trimIndent()) { result ->
            if (!isWebPageAvailable()) return@evaluateJavascript
            val taskId = result?.trim('"')?.trim() ?: ""
            foregroundSessionId.set(taskId)
            if (taskId.isNotBlank() && taskId == pendingTaskId) pendingTaskId = ""
            if (!isFinishing && !isDestroyed) claimDialogs.setInConversation(taskId.isNotBlank())
        }
    }

    /** 从页面 DOM 提取当前所在的任务会话 ID 并保存到连接仓库。 */
    private fun saveCurrentTaskId() {
        if (!isWebPageAvailable()) return
        val savedSpace = loginSpace ?: return
        binding.webView.evaluateJavascript("""
            (function() {
                // 优先：会话页的 data-session-id（v4-session-pane 元素上）
                var pane = document.querySelector('[data-session-id]');
                if (pane) {
                    var sid = pane.getAttribute('data-session-id');
                    if (sid && sid.length > 0) return sid;
                }
                // 其次：task-item 列表中的选中态
                var items = document.querySelectorAll('[data-testid^="task-item-"]');
                for (var i = 0; i < items.length; i++) {
                    var el = items[i];
                    if (el.getAttribute('aria-current') === 'true' ||
                        el.getAttribute('data-state') === 'active' ||
                        el.getAttribute('data-state') === 'selected' ||
                        (el.className || '').match(/active|selected|current/)) {
                        var tid = el.getAttribute('data-testid') || '';
                        if (tid.indexOf('task-item-') === 0) return tid.substring(10);
                    }
                }
                // fallback：URL hash 中的 sessionId
                var m = (location.hash || '').match(/sess_[a-f0-9-]+/);
                if (m) return m[0];
                return '';
            })()
        """.trimIndent()) { result ->
            if (webViewDisposed || rendererRecovery.pending) return@evaluateJavascript
            val taskId = result?.trim('"')?.trim() ?: ""
            if (taskId.isNotEmpty() && ConnectionRepository.getInstance(this).isLoginSpaceCurrent(savedSpace)) {
                ConnectionRepository.getInstance(this).updateLastTaskId(savedSpace.connectionId, taskId)
            }
        }
    }

    override fun onDestroy() {
        pendingSessionJump?.let { handler.removeCallbacks(it) }
        pendingSessionJump = null
        claimDialogs.clear()
        val ownedEventSource = current === this
        if (ownedEventSource) current = null
        // 主页面销毁后交还事件源所有权：保活服务随即用隐藏 WebView 接管同一远程 URL，
        // 填补 Activity WebView 销毁后的监听空窗（服务内部保证与主页面不并存）。
        // 已删除或交接的页面不能重新启动监听；旧页面销毁也不能覆盖新连接的目标。
        if (ownedEventSource && !eventSourceHandedOver && targetUrl.isNotBlank() &&
            loginSpace?.let { ConnectionRepository.getInstance(this).isLoginSpaceCurrent(it) } == true) {
            ai.zcode.remote.service.KeepAliveService.releaseEventSource(
                this, url = targetUrl, name = deviceName, sourceId = loginSpace!!.sourceId,
            )
        }
        handler.removeCallbacks(foregroundSessionTick)
        handler.removeCallbacks(reconnectRunnable)
        unregisterNetworkCallback()
        eventCaptureScript?.let {
            try { it.remove() } catch (e: Exception) {
                android.util.Log.w("ZCodeWeb", "document-start script removal failed", e)
            }
        }
        eventCaptureScript = null
        if (::eventBridge.isInitialized) eventBridge.dispose()
        eventBridgeAlive = false
        handler.removeCallbacksAndMessages(null)
        mainMenuPopup?.dismiss()
        mainMenuPopup = null
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        disposeWebView()
        super.onDestroy()
    }

    private fun disposeWebView() {
        if (webViewDisposed) return
        webViewDisposed = true
        webViewReady = false
        if (::customWebViewClient.isInitialized) customWebViewClient.invalidate()
        pendingSessionJump?.let { handler.removeCallbacks(it) }
        pendingSessionJump = null
        pendingSettingsOpen?.let { handler.removeCallbacks(it) }
        pendingSettingsOpen = null
        cancelReconnect("webview-disposed")
        handler.removeCallbacks(foregroundSessionTick)
        handler.removeCallbacks(hideLoginNotice)
        loginNoticePageGeneration++
        loginNoticeProbePending = false
        binding.tvLoginNotice.visibility = View.GONE
        foregroundSessionVisible.set(false)
        foregroundSessionId.set("")
        claimDialogs.clear()
        if (::eventBridge.isInitialized) eventBridge.dispose()
        eventBridgeAlive = false
        // renderer 已死时 ScriptHandler.remove 也可能调用 WebView；destroy 会一并释放脚本。
        eventCaptureScript = null
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        if (!rendererRecovery.pending) binding.webView.stopLoading()
        (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
    }

    /**
     * 将事件源所有权交还给保活服务并销毁本页。用于后台渲染进程被系统回收、
     * 页面 JS 心跳/流量长时间停止等“Activity 活着但事件源已死”的场景：
     * 保活服务随即用隐藏 WebView 接管同一远程 URL，避免通知监听空窗。
     */
    private fun handoverEventSourceToKeepAlive(reason: String) {
        if (eventSourceHandedOver || isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) {
            return
        }
        eventSourceHandedOver = true
        val url = targetUrl
        val name = deviceName
        val sourceId = loginSpace?.sourceId ?: return
        android.util.Log.w("ZCodeWeb", "handover event source to keep-alive: reason=$reason")
        // 先把自己从“存活实例”摘除：finish() 是异步的，若不先摘除，
        // 保活服务在 onStartCommand 里看到 hasLiveInstance() 仍为 true，
        // 会认为主页面还在托管事件源，从而跳过/销毁刚创建的隐藏监听，
        // 交接后依然处于“Activity 活着但事件源已死”的空窗。
        if (current === this) current = null
        disposeWebView()
        // 旧 WebView 已停止并销毁，再允许服务建立连接，避免交接时两个控制端互相踢线。
        ai.zcode.remote.service.KeepAliveService.releaseEventSource(
            this, url = url, name = name, sourceId = sourceId,
        )
        finish()
    }

    /** 用户返回到连接列表：不销毁本页——把 MainActivity 启动到栈顶
     *  （MainActivity 已改 standard，不再清栈），本页留在后台，
     *  WebView 及页面 WS 继续存活：审批/完成事件继续镜像到系统通知。
     *  保持连接状态：activeConnectionId 不清除，方便用户快速切回。 */
    private fun exitToDeviceList() {
        lastBackPressTime = 0L
        try {
            ai.zcode.remote.ui.main.MainActivity.start(this)
        } catch (e: Exception) {
            finish()
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_SOURCE_ID = "extra_login_source_id"
        private const val EXTRA_SETTINGS_MODE = "extra_settings_mode"

        private const val FOREGROUND_SESSION_TICK_MS = 2000L
        private const val MIN_RECONNECT_INTERVAL_MS = 4_000L
        private const val TRANSPORT_SELF_RECOVERY_GRACE_MS = 4_000L
        private const val RENDERER_RECOVERY_DELAY_MS = 3_000L



        /** 顶栏菜单「缩小/放大页面」每次调整的步进（%），与设置页滑杆的 1% 微调互补。 */
        private const val STEP = 5

        /** 缩放提示显示时长（ms）与淡出动画时长（ms）。 */
        private const val ZOOM_HINT_DURATION_MS = 2000L
        private const val ZOOM_HINT_FADE_MS = 200L

        /** 当前存活的 RemoteControlActivity 实例（单连接监听：最多一个）。 */
        @Volatile
        private var current: RemoteControlActivity? = null

        /** 是否已有远程页存活（供 MainActivity 切回判断/自动恢复去重）。 */
        fun hasLiveInstance(): Boolean = current != null

        /** 删除连接时关闭它的页面，禁止 onDestroy 再把已删除目标交回服务。 */
        fun closeConnection(id: String, url: String) {
            val activity = current ?: return
            if (activity.connectionId != id && !(activity.connectionId.isBlank() && activity.targetUrl == url)) return
            activity.eventSourceHandedOver = true
            current = null
            foregroundSessionVisible.set(false)
            foregroundSessionId.set("")
            activity.handler.removeCallbacksAndMessages(null)
            if (activity::eventBridge.isInitialized) activity.eventBridge.dispose()
            activity.eventBridgeAlive = false
            activity.disposeWebView()
            activity.finish()
        }

        /**
         * 主页面事件源健康自检（由 KeepAliveService 周期巡检调用）。
         * 页面注入的捕获脚本每 10s 上报一次心跳；若主 Activity 存在但其事件桥的
         * 心跳/流量超过 staleAfterElapsedMs 未更新，说明 WebView 渲染进程已被
         * 系统回收或页面 JS 已停（vivo 后台激进回收的典型表现），此时 Activity
         * 虽“活着”但已收不到任何远端事件。前台可见时交给 recreate/重连逻辑自行
         * 恢复；后台不可见时直接把事件源交接给保活服务的隐藏监听，避免通知空窗。
         *
         * @return true 表示已执行交接（调用方应停止当前分支并让服务接管）。
         */
        fun requestEventSourceHandoverIfStale(staleAfterElapsedMs: Long): Boolean {
            val activity = current ?: return false
            if (!activity.eventBridgeAlive || !activity::eventBridge.isInitialized) return false
            if (activity.activityVisible) return false
            if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return false
            val now = android.os.SystemClock.elapsedRealtime()
            // 事件桥刚建立（页面加载/重连初期）时可能尚未收到首次心跳，
            // 必须跳过，避免把正常加载中的页面误判为“事件源已死”。
            val bridgeAge = now - activity.eventBridgeCreatedAtElapsedMs
            if (bridgeAge < staleAfterElapsedMs) return false
            val lastBeat = activity.eventBridge.lastHeartbeatAtElapsedMs
            val lastTraffic = activity.eventBridge.lastTrafficAtElapsedMs
            val beatFresh = lastBeat > 0L && now - lastBeat < staleAfterElapsedMs
            val trafficFresh = lastTraffic > 0L && now - lastTraffic < staleAfterElapsedMs
            if (beatFresh || trafficFresh) return false
            activity.handoverEventSourceToKeepAlive(
                "heartbeat-stale-beat=" + (now - lastBeat) + "ms-traffic=" + (now - lastTraffic) + "ms"
            )
            return true
        }

        /** 远程页是否在前台可见（Activity onResume/onPause 维护）。 */
        private val foregroundSessionVisible = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 前台页面当前显示的会话 ID（从 [data-session-id] 提取，列表页为空）。 */
        private val foregroundSessionId = java.util.concurrent.atomic.AtomicReference("")

        /**
         * 用户是否正停留在该任务会话页（页面前台可见且显示此会话）。
         * 此时审批/提问弹层已在页面上，TaskNotifier 跳过系统通知。
         */
        fun isForegroundSession(taskId: String, sourceId: String = ""): Boolean =
            foregroundSessionVisible.get() && foregroundSessionId.get() == taskId &&
                (sourceId.isBlank() || current?.loginSpace?.sourceId == sourceId || current?.targetUrl == sourceId)

        fun start(
            context: Context,
            url: String,
            name: String = "",
            startInSettingsMode: Boolean = false,
            taskId: String = "",
            connectionId: String = "",
        ) {
            val repository = ConnectionRepository.getInstance(context)
            val connection = if (connectionId.isNotBlank()) repository.findById(connectionId) else repository.findByUrl(url)
            if (connectionId.isNotBlank() && connection == null) return
            val intent = Intent(context, RemoteControlActivity::class.java).apply {
                putExtra(EXTRA_URL, connection?.url ?: url)
                putExtra(EXTRA_NAME, connection?.name ?: name)
                putExtra(EXTRA_SETTINGS_MODE, startInSettingsMode)
                if (taskId.isNotEmpty()) putExtra(EXTRA_TASK_ID, taskId)
                connection?.let {
                    putExtra(EXTRA_SOURCE_ID, it.loginSpace.sourceId)
                }
            }
            context.startActivity(intent)
        }
    }
}
