
package ai.zcode.remote.ui.remote

import androidx.appcompat.app.AppCompatActivity

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.databinding.ActivityRemoteControlBinding
import ai.zcode.remote.ui.remote.event.EventCaptureScript
import ai.zcode.remote.ui.remote.event.TaskEventBridge
import ai.zcode.remote.ui.remote.event.TaskNotifier
import ai.zcode.remote.ui.remote.web.ZCodeWebChromeClient
import ai.zcode.remote.ui.remote.web.ZCodeWebViewClient
import ai.zcode.remote.utils.ImmersiveHelper
import ai.zcode.remote.utils.ToastUtils
import android.os.Handler
import android.os.Looper

class RemoteControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteControlBinding
    private lateinit var appSettings: AppSettingsRepository
    private var targetUrl: String = ""
    private var deviceName: String = "ZCode 远程工作区"
    private var pendingTaskId: String = ""
    private var connectionId: String = ""
    private var isFullscreen = true
    private var isKeepScreenOn = true
    private var isDesktopMode = false
    private var lastBackPressTime = 0L

    private lateinit var customWebViewClient: ZCodeWebViewClient
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var eventBridge: TaskEventBridge
    private val handler = Handler(Looper.getMainLooper())

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
        deviceName = intent.getStringExtra(EXTRA_NAME) ?: "ZCode 远程工作区"
        pendingTaskId = intent.getStringExtra(EXTRA_TASK_ID) ?: ""
        isSettingsModeRequested = intent.getBooleanExtra(EXTRA_SETTINGS_MODE, false)

        if (targetUrl.isBlank()) {
            ToastUtils.show(this, "无效的远程连接 URL")
            finish()
            return
        }

        // 反查 connectionId：用于 onPause 时保存当前任务会话到对应连接
        val repo = ConnectionRepository.getInstance(this)
        connectionId = repo.findByUrl(targetUrl)?.id ?: ""
        // 不管从哪条路径进入（列表点击/通知点击/深链接/自动恢复），
        // 都更新 lastActive 指向当前连接——确保下次恢复/通知跳转到正确的连接
        if (connectionId.isNotEmpty()) {
            repo.updateLastConnected(connectionId)
        }
        android.util.Log.d("ZCodeEvent", "connectionId for $deviceName: ${connectionId.ifEmpty { "(not found)" }}")

        // 单连接监听：一次只保留一个远程页实例（新打开连接时关闭旧页面，
        // 旧 WebView 随之销毁，事件监听切换到当前连接）
        current?.takeIf { it !== this }?.finish()
        current = this

        // 打开任一连接即自动启动保活前台服务：
        // 防止进程被系统冻结（Android 12+ cached-app freeze / MIUI 更甚），
        // 冻结后所有 WebSocket、重连、事件接收全部停摆——通知收不到的致命原因
        ai.zcode.remote.service.KeepAliveService.start(this)

        setupImmersiveAndScreen()
        setupKeyboardInsets()
        setupWebView()
        setupFloatingControl()
        setupBackPressHandler()

        if (isSettingsModeRequested) {
            isDesktopMode = true
            val settings = binding.webView.settings
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
        }

        setupEventCapture()
        loadUrl(targetUrl)
    }

    /** 注册任务事件桥并在每次页面加载后注入捕获脚本（SPA 导航可能重建 window）。 */
    private fun setupEventCapture() {
        ensureNotificationPermission()
        eventBridge = TaskEventBridge(
            deviceName = deviceName,
            onEvent = { event ->
                runOnUiThread { TaskNotifier.notify(this, event) }
            },
            onSessionState = { up ->
                runOnUiThread { updateSessionHealth(up) }
            },
            wsUrlCallback = { url ->
                // WS 打开时仅记录日志：事件监听完全依赖页面 WebView 自身连接
                // （页面带完整鉴权），原生不做独立 WS（裸连缺桥会话必然被踢）
                android.util.Log.d("ZCodeEvent", "onWsUrl: connId=$connectionId url=$url")
            }
        )
        binding.webView.addJavascriptInterface(eventBridge, TaskEventBridge.BRIDGE_NAME)
        // document-start 注入必须在 loadUrl 之前调用一次——onPageStarted 回调时页面
        // 已开始加载，脚本可能赶不上页面引导阶段建立的 WebSocket 连接
        try {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                binding.webView,
                EventCaptureScript.build(TaskEventBridge.BRIDGE_NAME),
                setOf("https://zcode.z.ai")
            )
        } catch (e: Exception) {
            android.util.Log.w("ZCodeWeb", "document-start inject failed: ${e.message}")
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

    private fun updateSessionHealth(up: Boolean) {
        // 用户不需要连接状态提示框，始终保持隐藏
        binding.sessionHealth.root.visibility = View.GONE
    }

    private fun setupImmersiveAndScreen() {
        if (isFullscreen) {
            ImmersiveHelper.enterImmersiveFullscreen(this)
        } else {
            ImmersiveHelper.exitImmersiveFullscreen(this)
        }
        ImmersiveHelper.setKeepScreenOn(this, isKeepScreenOn)
    }

    private fun setupKeyboardInsets() {
        // 沉浸式全屏下监听软键盘高度并抬高 WebView，使输入框打字时自动上探至软键盘正上方
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val keyboardHeight = if (imeHeight > 0) (imeHeight - navHeight).coerceAtLeast(0) else 0

            val webViewLp = binding.webView.layoutParams as? android.widget.FrameLayout.LayoutParams
            if (webViewLp != null && webViewLp.bottomMargin != keyboardHeight) {
                webViewLp.bottomMargin = keyboardHeight
                binding.webView.layoutParams = webViewLp
            }
            insets
        }
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
            onPageStart = {
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutErrorOverlay.visibility = View.GONE
            },
            onPageFinish = { _ ->
                binding.progressBar.visibility = View.GONE
                // 通知点击带来的会话跳转：页面加载完成后注入 JS 定位并点击任务条目
                if (pendingTaskId.isNotEmpty()) {
                    val tid = pendingTaskId
                    pendingTaskId = "" // 只跳一次，SPA 内部导航不再重复
                    binding.webView.postDelayed({
                        binding.webView.evaluateJavascript(
                            ai.zcode.remote.ui.remote.event.SessionJumpScript.build(tid), null
                        )
                    }, 1500) // 等任务列表渲染完成
                }
                if (isSettingsModeRequested && binding.layoutErrorOverlay.visibility != View.VISIBLE) {
                    binding.webView.postDelayed({
                        if (!isSettingsModeRequested || binding.layoutErrorOverlay.visibility == View.VISIBLE) return@postDelayed
                        customWebViewClient.openWorkspaceSettings(binding.webView) { opened ->
                            isSettingsModeRequested = false
                            if (opened) {
                                isDesktopMode = false
                                ToastUtils.show(this@RemoteControlActivity, "已进入设置中心")
                            } else {
                                customWebViewClient.setDesktopViewport(binding.webView, false)
                                ToastUtils.show(this@RemoteControlActivity, "进入设置中心失败，请稍后重试")
                            }
                        }
                    }, 500)
                }
            },
            onPageError = { _, description ->
                binding.progressBar.visibility = View.GONE
                binding.layoutErrorOverlay.visibility = View.VISIBLE
                binding.tvErrorDetail.text = description
            }
        )
        webView.webViewClient = customWebViewClient

        // 配置 WebChromeClient
        webView.webChromeClient = ZCodeWebChromeClient(
            allowedHost = Uri.parse(targetUrl).host,
            onProgressUpdate = { progress ->
                binding.progressBar.progress = progress
                if (progress >= 100) {
                    binding.progressBar.visibility = View.GONE
                } else {
                    binding.progressBar.visibility = View.VISIBLE
                }
            },
            onTitleReceived = { title ->
                if (deviceName == "ZCode 远程工作区" && title.isNotBlank()) {
                    deviceName = title
                }
            },
            onOpenFileChooser = { callback, params ->
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
        // 成功后再回切移动端视口以 APP 端布局展示设置中心，因此同步桌面模式状态
        dialog.onOpenSettingsListener = {
            customWebViewClient.openWorkspaceSettings(binding.webView) { success ->
                if (success) {
                    isDesktopMode = false
                    ToastUtils.show(this@RemoteControlActivity, "已进入设置中心")
                } else {
                    customWebViewClient.setDesktopViewport(binding.webView, false)
                    ToastUtils.show(this@RemoteControlActivity, "进入设置中心失败，请稍后重试")
                }
            }
        }

        dialog.onRefreshListener = {
            binding.webView.reload()
            ToastUtils.show(this, "正在重新连接...")
        }

        dialog.onToggleFullscreenListener = {
            isFullscreen = !isFullscreen
            appSettings.setFullscreenEnabled(isFullscreen)
            if (isFullscreen) {
                ImmersiveHelper.enterImmersiveFullscreen(this)
                ToastUtils.show(this, "已进入沉浸全屏")
            } else {
                ImmersiveHelper.exitImmersiveFullscreen(this)
                ToastUtils.show(this, "已退出沉浸全屏")
            }
        }

        dialog.onCopyUrlListener = {
            val currentUrl = binding.webView.url ?: targetUrl
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("ZCode URL", currentUrl)
            clipboard?.setPrimaryClip(clip)
            ToastUtils.show(this, getString(R.string.toast_copied))
        }

        dialog.onClearCacheListener = {
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
                binding.webView.evaluateJavascript(
                    """(function() {
                        function isVisible(el) {
                            if (!el) return false;
                            var st = window.getComputedStyle(el);
                            if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity) === 0 || st.pointerEvents === 'none') {
                                return false;
                            }
                            var r = el.getBoundingClientRect();
                            return r.width > 0 && r.height > 0;
                        }

                        // 1. 优先关闭/收起处于打开状态的代码审查、终端等侧边栏面板（最高优先级）
                        // 1A. 移动端侧滑面板全屏遮罩（处于激活状态时，直接 click 遮罩内的关闭触发器）
                        var overlay = document.querySelector('[data-mobile-side-pane-overlay="true"]');
                        if (overlay) {
                            var ost = window.getComputedStyle(overlay);
                            if (ost.pointerEvents !== 'none' && parseFloat(ost.opacity) > 0.1) {
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
                        var settingsBack = Array.from(document.querySelectorAll('button[aria-label*="返回工作区"], button[aria-label*="工作区"], aside button:has(svg), nav button:has(svg)')).find(function(b) {
                            if (!isVisible(b)) return false;
                            var aria = (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.textContent || '');
                            var r = b.getBoundingClientRect();
                            return /返回工作区|Back to Workspace/i.test(aria) || (r.top < 60 && r.left < 50 && b.closest('aside, nav'));
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
                        var hasHomeElements = !!document.querySelector('[data-testid^="task-item-"], button[aria-expanded]');
                        
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
        })
    }

    private fun applyDesktopMode(enable: Boolean) {
        val settings = binding.webView.settings
        if (enable) {
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
        } else {
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 15; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
        }
        customWebViewClient.setDesktopViewport(binding.webView, enable)
        binding.webView.reload()
    }

    private fun loadUrl(url: String) {
        binding.webView.loadUrl(url)
    }

    /** singleTop 复用栈顶实例时：用新 Intent 的 extra 切换到新连接/会话。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newUrl = intent.getStringExtra(EXTRA_URL) ?: return
        val newName = intent.getStringExtra(EXTRA_NAME) ?: deviceName
        val newTaskId = intent.getStringExtra(EXTRA_TASK_ID) ?: ""
        // 保存当前页面的 taskId 到旧连接（切换前）
        saveCurrentTaskId()
        // 更新连接信息并重新加载
        targetUrl = newUrl
        deviceName = newName
        pendingTaskId = newTaskId
        val repo = ConnectionRepository.getInstance(this)
        connectionId = repo.findByUrl(targetUrl)?.id ?: ""
        if (connectionId.isNotEmpty()) {
            repo.updateLastConnected(connectionId)
        }
        // 重新加载页面
        loadUrl(targetUrl)
    }

    override fun onResume() {
        super.onResume()
        // 不调 webView.onResume()：与 onPause 对应，保持 WebView 持续活跃，
        // 让切后台时 JS 的 WebSocket 仍能收消息（审批/完成事件镜像到系统通知）
        if (isFullscreen) {
            ImmersiveHelper.enterImmersiveFullscreen(this)
        } else {
            ImmersiveHelper.exitImmersiveFullscreen(this)
        }
        ai.zcode.remote.ui.main.MainActivity.markVisiblePage("remote")
        // 页面恢复到前台可见：记录可见状态并持续跟踪当前会话 ID。
        // 若用户正停留在该会话页（正在对话），审批/提问弹层已在页面上，
        // 系统通知跳过（见 TaskNotifier.notify 的前台会话判断）。
        // 周期刷新以跟随 SPA 页面内切换会话（页面内导航不触发 onPageFinished）
        foregroundSessionVisible.set(true)
        refreshForegroundSessionId()
        handler.postDelayed(foregroundSessionTick, FOREGROUND_SESSION_TICK_MS)
    }

    override fun onPause() {
        super.onPause()
        // 不调 webView.onPause()：暂停会冻结 JS 定时器与 WS 回调，
        // 导致切后台后电脑端发审批 APP 收不到。让 WebView 在后台保持活跃，
        // 事件经 TaskEventBridge → TaskNotifier 触发系统通知。
        // 页面离开前台（被列表页覆盖/切后台/关闭）：不再抑制系统通知
        foregroundSessionVisible.set(false)
        handler.removeCallbacks(foregroundSessionTick)
        // 切出时记录当前任务会话：通过 JS 从页面 DOM 提取 task-item 的
        // data-testid（选中态/展开态），持久化到对应连接，下次切回时恢复
        saveCurrentTaskId()
    }

    /** 周期刷新前台会话 ID 的 Runnable（跟随页面内会话切换）。 */
    private val foregroundSessionTick = object : Runnable {
        override fun run() {
            if (!foregroundSessionVisible.get()) return
            refreshForegroundSessionId()
            handler.postDelayed(this, FOREGROUND_SESSION_TICK_MS)
        }
    }

    /** 提取当前前台会话 ID 并更新全局（供 TaskNotifier 判断是否抑制通知）。 */
    private fun refreshForegroundSessionId() {
        if (connectionId.isEmpty()) return
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
            val taskId = result?.trim('"')?.trim() ?: ""
            foregroundSessionId.set(taskId)
        }
    }

    /** 从页面 DOM 提取当前所在的任务会话 ID 并保存到连接仓库。 */
    private fun saveCurrentTaskId() {
        if (connectionId.isEmpty()) return
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
            val taskId = result?.trim('"')?.trim() ?: ""
            if (taskId.isNotEmpty()) {
                ConnectionRepository.getInstance(this).updateLastTaskId(connectionId, taskId)
            }
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        handler.removeCallbacks(foregroundSessionTick)
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        binding.webView.destroy()
        super.onDestroy()
    }

    /** 用户返回到连接列表：不销毁本页——把 MainActivity 启动到栈顶
     *  （MainActivity 已改 standard，不再清栈），本页留在后台，
     *  WebView 及页面 WS 继续存活：审批/完成事件继续镜像到系统通知。
     *  保持连接状态：activeConnectionId 不清除，方便用户快速切回。 */
    private fun exitToDeviceList() {
        lastBackPressTime = 0L
        try {
            startActivity(
                Intent(this, ai.zcode.remote.ui.main.MainActivity::class.java)
                    .putExtra(ai.zcode.remote.ui.main.MainActivity.EXTRA_FROM_REMOTE, true)
            )
        } catch (e: Exception) {
            finish()
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_TASK_ID = "extra_task_id"
        private const val EXTRA_SETTINGS_MODE = "extra_settings_mode"

        /** 前台会话 ID 的刷新周期（ms）：跟随 SPA 页面内会话切换。 */
        private const val FOREGROUND_SESSION_TICK_MS = 2000L

        /** 当前存活的 RemoteControlActivity 实例（单连接监听：最多一个）。 */
        @Volatile
        private var current: RemoteControlActivity? = null

        /** 是否已有远程页存活（供 MainActivity 切回判断/自动恢复去重）。 */
        fun hasLiveInstance(): Boolean = current != null

        /** 远程页是否在前台可见（Activity onResume/onPause 维护）。 */
        private val foregroundSessionVisible = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 前台页面当前显示的会话 ID（从 [data-session-id] 提取，列表页为空）。 */
        private val foregroundSessionId = java.util.concurrent.atomic.AtomicReference("")

        /**
         * 用户是否正停留在该任务会话页（页面前台可见且显示此会话）。
         * 此时审批/提问弹层已在页面上，TaskNotifier 跳过系统通知。
         */
        fun isForegroundSession(taskId: String): Boolean =
            foregroundSessionVisible.get() && foregroundSessionId.get() == taskId

        fun start(
            context: Context,
            url: String,
            name: String = "",
            startInSettingsMode: Boolean = false,
            taskId: String = "",
        ) {
            val intent = Intent(context, RemoteControlActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_SETTINGS_MODE, startInSettingsMode)
                if (taskId.isNotEmpty()) putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startActivity(intent)
        }
    }
}
