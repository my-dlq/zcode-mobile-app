package ai.zcode.remote.ui.remote.web

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.ui.remote.event.EventCaptureScript
import ai.zcode.remote.ui.remote.event.TaskEventBridge
import kotlin.math.roundToInt

class ZCodeWebViewClient(
    private val onPageStart: () -> Unit,
    private val onPageFinish: (url: String) -> Unit,
    private val onPageError: (errorCode: Int, description: String) -> Unit,
    private val onRenderProcessGone: () -> Unit,
    /** 当前页面缩放百分比（70~150），由设置页写入、此处读取以生成 viewport。 */
    private val pageZoomProvider: () -> Int = { 100 },
    /**
     * 「当前设备上的工作区和任务」页用哪套界面（远程原生 / Zmobile移动适配）。
     * 每次注入时读取，因此设置页改完返回即可生效（配合 onResume 重新注入）。
     */
    private val dashboardModeProvider: () -> AppSettingsRepository.DashboardMode =
        { AppSettingsRepository.DashboardMode.NATIVE },
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // 在页面最早执行阶段注入全套桌面端环境特征模拟（针对电脑端设置判定）
        view?.let { wv ->
            val emulateDesktopJs = """
                (function() {
                    try {
                        Object.defineProperty(navigator, 'userAgent', { get: function() { return 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'; } });
                        Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; } });
                    } catch(e) {}
                })();
            """.trimIndent()
            wv.evaluateJavascript(emulateDesktopJs, null)
        }
        onPageStart()
        // 尽早注入防误触样式
        view?.let { injectAntiMisoperation(it) }
        // document-start 的事件捕获脚本已在 RemoteControlActivity.setupEventCapture()
        // 中调用（loadUrl 之前），这里不再重复——onPageStarted 时页面已开始加载，
        // addDocumentStartJavaScript 对当前这次加载可能来不及生效
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.let { injectAntiMisoperation(it) }
        // 详情页返回按钮同样在每次页面加载后注入：设置中心内是 SPA 导航，
        // 但长会话/切回工作区等操作可能触发整页加载，注入会随 DOM 重建丢失，
        // 仅依赖 openWorkspaceSettings 成功回调注入一次不够（实测用户进详情
        // 页时按钮缺失即由此引起）
        view?.let { injectDetailBackButton(it) }
        // 任务事件捕获脚本：镜像 fetch/SSE/WS 流量回传原生（通知 + 会话健康）。
        // SPA 内部导航不触发 onPageFinished，但 window 重建（整页加载）后必须重注
        view?.let { injectEventCapture(it) }
        onPageFinish(url ?: "")
    }

    private fun injectEventCapture(view: WebView) {
        view.evaluateJavascript(EventCaptureScript.build(TaskEventBridge.BRIDGE_NAME), null)
    }

    @androidx.annotation.RequiresApi(26)
    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
        val didCrash = detail?.didCrash() == true
        android.util.Log.e("ZCodeWeb", "onRenderProcessGone: didCrash=$didCrash")
        onPageError(-100, if (didCrash) "页面渲染进程异常，正在重新连接" else "系统回收了页面，正在重新连接")
        onRenderProcessGone()
        return true
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            val code = error?.errorCode ?: -1
            val desc = error?.description?.toString() ?: "网络连接异常"
            onPageError(code, desc)
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame == true) {
            val code = errorResponse?.statusCode ?: -1
            val reason = errorResponse?.reasonPhrase.orEmpty()
            onPageError(code, "HTTP $code${if (reason.isBlank()) "" else " $reason"}")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase()

        // 仅处理 http / https，防止意外触发 tel:, mailto:, market: 等跳出
        if (scheme == "http" || scheme == "https") {
            // 在当前 WebView 中打开，不跳转外部系统浏览器
            return false
        }
        return true
    }

    /**
     * 触发打开/切换 ZCode 远程工作区的设置中心页面
     * 机制（基于 2026-08 对 v4 远端页面 DOM 的实测）：
     * 1. 手机 WebView 视口（<768px CSS 宽度）会命中远端移动端断点，页面退化为
     *    任务列表 dashboard，该布局下完全没有设置入口——必须先注入桌面 viewport
     *    （width=1280）强制桌面布局，侧边栏及其底部齿轮按钮才会出现；
     * 2. 设置入口是侧边栏底部 button[aria-label="设置"]（中文标签、Tailwind 工具类，
     *    页面不存在任何含 settings 字样的 class），用完整点击事件序列拉起；
     * 3. 设置中心是 SPA 全屏视图（URL 不变），唯一可靠的"已打开"标志是
     *    button[aria-label="返回工作区"]。旧版检测选择器混入 aside button:has(svg)
     *    在任意页面恒命中，导致首次执行即误判"已打开"直接返回 true、从未真正点击
     *    ——这就是"远程设置"弹不出但 Toast 报成功的历史根因；React Fiber 探测在
     *    当前版本页面同样失效（#root 无 __reactFiber key），已移除；
     * 4. evaluateJavascript 只能取同步返回值，而打开动作由 JS 内部异步轮询完成，
     *    故 JS 写结果标志位、Kotlin 侧二次轮询标志位后再回调 onDone，保证回调真实。
     * 5. 拉起成功后回切移动端视口（width=device-width）：设置中心自带移动端响应式，
     *    以 APP 端布局展示；若停留在 initial-scale=0.32 的桌面视口，h-screen/h-dvh
     *    会按放大的 vh（约 3 倍屏高）计算，产生顶部大片背景空白。
     * 穿透策略仅在极端情况（dashboard 且找不到任何设置入口）进入一个任务视图以获得
     * 侧边栏：优先选择"已完成"任务，只做导航查看，绝不触碰停止/取消类控件。
     */
    fun openWorkspaceSettings(webView: WebView, onDone: ((Boolean) -> Unit)? = null) {
        // 回切移动端视口时用的内容（含当前页面缩放），在此算好注入，避免 JS 侧写死
        val mobileViewportContent = viewportContent(webView, isDesktop = false)
        val js = """
            (function() {
                if (window.__zcodeSettingsOpening) return;
                window.__zcodeSettingsOpening = true;
                window.__zcodeSettingsOpened = false;

                // 关键前置：小视口会触发远端移动端布局（无任何设置入口），先切桌面视口
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        (document.head || document.documentElement).appendChild(meta);
                    }
                    meta.setAttribute('content', 'width=1280, initial-scale=0.32, user-scalable=yes');
                    window.dispatchEvent(new Event('resize'));
                } catch (e) {}

                function settingsOpenedNow() {
                    return !!document.querySelector(
                        'button[aria-label="返回工作区"], button[aria-label="Back to workspace"]');
                }

                function isVisible(el) {
                    if (!el) return false;
                    var rect = el.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) return false;
                    var p = el;
                    while (p && p !== document.body) {
                        var s = getComputedStyle(p);
                        if (s.display === 'none' || s.visibility === 'hidden') return false;
                        p = p.parentElement;
                    }
                    return true;
                }

                function fireFullClick(elem) {
                    if (!elem) return;
                    try { elem.focus(); } catch (e) {}
                    var opts = { bubbles: true, cancelable: true, view: window };
                    try { elem.dispatchEvent(new PointerEvent('pointerdown', opts)); } catch (e) {}
                    try { elem.dispatchEvent(new MouseEvent('mousedown', opts)); } catch (e) {}
                    try { elem.dispatchEvent(new PointerEvent('pointerup', opts)); } catch (e) {}
                    try { elem.dispatchEvent(new MouseEvent('mouseup', opts)); } catch (e) {}
                    try { elem.dispatchEvent(new MouseEvent('click', opts)); } catch (e) {}
                    try { elem.click(); } catch (e) {}
                }

                function findSettingEntry() {
                    // 1. v4 页面真实入口：侧边栏底部齿轮（aria-label 为中文"设置"）
                    var btn = document.querySelector(
                        'button[aria-label="设置"], button[aria-label="Settings"]');
                    if (btn && isVisible(btn)) return btn;
                    // 2. 模糊兜底：title/aria-label 含"设置/Settings"字样
                    btn = document.querySelector(
                        '[aria-label*="设置"], [title*="设置"], [aria-label*="Settings"], [title*="Settings"]');
                    if (btn && isVisible(btn)) return btn;
                    // 3. 几何兜底：底部靠左且含 SVG 的 button。实测设置齿轮位于侧边栏内 x≈216，
                    //    旧版 left<120 够不着，放宽至 340 覆盖整个侧边栏宽度。
                    //    ⚠️ 必须限定在侧边栏 aside 容器内查找：注入桌面视口后 SPA 尚未重排时，
                    //    整个页面里"底部靠左 + 含 SVG"的按钮会命中对话框输入区的「添加上下文」
                    //    (＋) 按钮（任务会话页左下角），对它 fireFullClick 会弹出附加菜单并反复
                    //    弹出/收起，settingsOpenedNow() 永不成立导致 tick 无限循环、无法进入设置
                    //    中心。设置齿轮是 aside 侧边栏子元素，而 ＋ 按钮不在任何 aside 内——
                    //    限定容器后重排前的早期 tick 会返回 null 安全等待，重排后由精确查找命中。
                    var candidates = [];
                    var allAsides = document.querySelectorAll('aside');
                    for (var j = 0; j < allAsides.length; j++) {
                        var asideBtns = allAsides[j].querySelectorAll('button');
                        for (var i = 0; i < asideBtns.length; i++) {
                            var el = asideBtns[i];
                            if (!isVisible(el) || !el.querySelector('svg')) continue;
                            var rect = el.getBoundingClientRect();
                            if (rect.width >= 16 && rect.height >= 16 &&
                                rect.left >= 0 && rect.left < 340 &&
                                rect.top >= window.innerHeight - 220) {
                                candidates.push(el);
                            }
                        }
                    }
                    candidates.sort(function(a, b) {
                        return b.getBoundingClientRect().bottom - a.getBoundingClientRect().bottom;
                    });
                    return candidates[0] || null;
                }

                function isDashboardListPage() {
                    var text = document.body ? (document.body.innerText || '') : '';
                    return text.indexOf('当前设备上的工作区和任务') !== -1 ||
                           text.indexOf('个工作区') !== -1;
                }

                // 仅当处于 dashboard 列表页且找不到设置入口时穿透进入任务视图（纯导航查看）
                function findTaskCard() {
                    // 优先选"已完成"任务，尽量避开正在运行的会话界面
                    var badgeNames = ['已完成', '运行中'];
                    for (var i = 0; i < badgeNames.length; i++) {
                        var nodes = document.querySelectorAll('span, div');
                        for (var j = 0; j < nodes.length; j++) {
                            if ((nodes[j].innerText || '').trim() === badgeNames[i]) {
                                var p = nodes[j].parentElement;
                                while (p && p !== document.body) {
                                    if (p.offsetHeight >= 30 && p.offsetHeight <= 130) return p;
                                    p = p.parentElement;
                                }
                            }
                        }
                    }
                    return null;
                }

                function switchToMobileViewport() {
                    // 回切移动端视口：设置视图自带响应式（h-screen/h-dvh 等），
                    // 若停留在 initial-scale=0.32 的桌面视口，vh 会被放大到约 3 倍屏高，
                    // 导致页面顶部出现大片背景空白，且整体呈电脑端样式。
                    // 内容由 Kotlin 侧按当前页面缩放生成，回切后缩放设置同样生效
                    try {
                        var m = document.querySelector('meta[name="viewport"]');
                        if (m) {
                            m.setAttribute('content', '$mobileViewportContent');
                            window.dispatchEvent(new Event('resize'));
                        }
                    } catch (e) {}
                }

                var attempts = 0;

                function tick() {
                    attempts++;
                    if (settingsOpenedNow()) {
                        // 延迟回切，等待设置视图过渡动画完成；回切后二次确认仍在设置中心
                        setTimeout(function() {
                            switchToMobileViewport();
                            setTimeout(function() {
                                window.__zcodeSettingsOpened = settingsOpenedNow();
                                window.__zcodeSettingsOpening = false;
                            }, 600);
                        }, 400);
                        return;
                    }
                    var entry = findSettingEntry();
                    if (entry) {
                        fireFullClick(entry);
                    } else if (isDashboardListPage()) {
                        var card = findTaskCard();
                        if (card) fireFullClick(card);
                    }
                    if (!window.__zcodeSettingsOpened) {
                        if (attempts < 16) {
                            setTimeout(tick, 500);
                        } else {
                            window.__zcodeSettingsOpening = false;
                        }
                    }
                }

                setTimeout(tick, 350);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)

        // evaluateJavascript 回调只能拿到同步返回值；打开动作由 JS 内部异步轮询完成，
        // 因此对结果标志位做二次轮询，拿到真实结果后才回调 onDone（约 1s 后开始，最多等 12s）
        var resultChecks = 0
        fun pollResult() {
            webView.evaluateJavascript("(window.__zcodeSettingsOpened === true)") { result ->
                when {
                    result == "true" -> {
                        // 回切移动端视口的 reflow 可能使早前注入的 <style> 失效或丢失，
                        // 成功后补注一次防误触/设置页自适应样式，避免卡片文字单字竖排
                        injectAntiMisoperation(webView)
                        // 设置中心详情页（如插件/MCP/技能的条目详情）没有返回列表的按钮，
                        // 注入右上角悬浮返回按钮：侦测到面包屑两级导航时显示，点击返回列表
                        injectDetailBackButton(webView)
                        onDone?.invoke(true)
                    }
                    resultChecks++ < 24 -> webView.postDelayed({ pollResult() }, 500)
                    else -> onDone?.invoke(false)
                }
            }
        }
        webView.postDelayed({ pollResult() }, 1000)
    }

    /**
     * 动态切换桌面宽屏渲染模式与移动端自适应模式
     */
    fun setDesktopViewport(webView: WebView, isDesktop: Boolean) {
        applyViewportContent(webView, viewportContent(webView, isDesktop))
    }

    /**
     * 按当前「页面缩放」设置应用移动端视口（供页面加载完成/从设置页返回时调用）。
     * 100% 时与原生移动端行为完全一致。
     */
    fun applyPageZoom(webView: WebView) {
        applyViewportContent(webView, viewportContent(webView, isDesktop = false))
    }

    /**
     * 生成 viewport meta 内容。
     *
     * 缩放实现说明：远端页面从 #root 到会话区是连续 5 层 `height:100dvh + overflow:hidden`
     * 的硬裁切链，中间没有可滚动祖先——因此 transform:scale / CSS zoom 会把溢出内容
     * 直接裁掉且无滚动补偿（100dvh 不随缩放变化），不可用。这里改用改布局视口的办法：
     *   width = 屏幕CSS宽 / Z、initial-scale = Z
     * 布局视口高度随之变成 屏幕高/Z，100dvh × Z 正好铺满，不裁切；同时真正改变布局
     * 视口宽度会触发远端响应式重排，等价于浏览器缩放（非捏合式纯放大）。
     * 桌面模式（进设置中心临时态）忽略缩放，沿用原有 width=1280/initial-scale=0.32。
     */
    private fun viewportContent(webView: WebView, isDesktop: Boolean): String {
        if (isDesktop) {
            return "width=1280, initial-scale=0.32, user-scalable=yes"
        }
        // 缩放范围用仓库常量，避免与设置页/菜单两处写死不一致
        val zoomPercent = pageZoomProvider().coerceIn(
            AppSettingsRepository.PAGE_ZOOM_MIN, AppSettingsRepository.PAGE_ZOOM_MAX
        )
        if (zoomPercent == 100) {
            return "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
        }
        // 屏幕 CSS 宽度：原生像素宽 / density，Kotlin 侧算好不依赖 JS 测量
        val density = webView.resources.displayMetrics.density
        val screenCssWidth = if (density > 0f && webView.width > 0) {
            (webView.width / density).roundToInt()
        } else {
            // WebView 尚未测量完成时退化为屏幕宽（density 已含在 displayMetrics 里）
            (webView.resources.displayMetrics.widthPixels / density).roundToInt()
        }
        val zoom = zoomPercent / 100f
        val layoutWidth = (screenCssWidth / zoom).roundToInt().coerceAtLeast(1)
        return "width=$layoutWidth, initial-scale=$zoom, maximum-scale=$zoom, user-scalable=no"
    }

    private fun applyViewportContent(webView: WebView, viewportContent: String) {
        val js = """
            (function() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                meta.setAttribute('content', '$viewportContent');
                window.dispatchEvent(new Event('resize'));
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 注入防误触 CSS 与 FastTouch 零延迟触控增强引擎
     * 解决模型设置、供应商列表、下拉菜单等在移动端触摸误判为滑动导致点击不响应的问题
     */
    private fun injectAntiMisoperation(webView: WebView) {
        val css = """
            /* 1. 禁用点击高亮与长按呼出：全局 user-select:none 让非文本区不可选中
               （原生长按拦截已移除——它曾把输入框的长按粘贴菜单一并吞掉），
               输入框/xterm/Monaco 及编辑器内部后代在下方豁免名单中恢复 text。
               ⚠️ user-select 非 true 继承属性但 auto 会参考父级，豁免必须覆盖
               编辑器内部后代（如 Lexical 的子节点），否则光标与选区会失效 */
            * {
                -webkit-touch-callout: none !important;
                -webkit-tap-highlight-color: transparent !important;
                -webkit-focus-ring-color: transparent !important;
                outline: none !important;
                -webkit-user-select: none !important;
                user-select: none !important;
            }
            input, textarea, select, [contenteditable="true"], .monaco-editor, .xterm,
            [contenteditable="true"] *, .monaco-editor *, .xterm *,
            [contenteditable=""] , [contenteditable="plaintext-only"], [contenteditable="plaintext-only"] *,
            .history-message, .history-message * {
                -webkit-user-select: text !important;
                user-select: text !important;
                -webkit-touch-callout: default !important;
            }
            html, body {
                overscroll-behavior-y: contain !important;
                -webkit-tap-highlight-color: transparent !important;
                -webkit-text-size-adjust: 100% !important;
                text-size-adjust: 100% !important;
            }

            /* 2. 任务会话模型选择器紧凑化：远端 Radix 两级 menu 在移动视口下
               默认 14px/32px。只作用于 z-[60] 的模型选择弹层，不影响聊天
               消息、输入框和设置中心页面；menuitemradio 保留右侧选中标记空间。
               一级供应商菜单保持 w-max（不写死宽度），避免把列宽压窄导致二级
               子菜单被挤到屏幕左侧外。 */
            div[role="menu"].z-\\[60\\] {
                max-height: calc(100vh - 16px) !important;
                overflow-x: hidden !important;
                overflow-y: auto !important;
                font-size: 12px !important;
                line-height: 18px !important;
            }
            div[role="menu"].z-\\[60\\] [role="menuitem"],
            div[role="menu"].z-\\[60\\] [role="menuitemradio"] {
                min-height: 28px !important;
                height: 28px !important;
                padding: 3px 6px !important;
                font-size: 12px !important;
                line-height: 18px !important;
            }
            div[role="menu"].z-\\[60\\] [role="menuitemradio"] {
                padding-right: 28px !important;
            }
            div[role="menu"].z-\\[60\\] [role="menuitem"] *,
            div[role="menu"].z-\\[60\\] [role="menuitemradio"] * {
                font-size: 12px !important;
                line-height: 18px !important;
            }
            /* 二级模型子菜单：源码是 w-max（按内容扩展），但移动端视口窄，模型名
               （如 deepseek-v4.1-flash）显示不全。这里给足宽度（按可用空间撑开
               且不超出视口）。子菜单的水平位置由 Radix 用 popper wrapper 的
               transform:translate(...) 定位，默认从触发器左侧弹出、实测 left=-52
               溢出左屏——CSS margin 无效（bounding rect 含 margin、transform 覆盖），
               故水平夹取交给注入 JS（见 stabilizeModelTrigger 旁的 clampModelSubmenu）。
               data-slot=dropdown-menu-sub-content 精确命中子菜单，不影响一级供应商列。 */
            div[data-slot="dropdown-menu-sub-content"] {
                min-width: 200px !important;
                width: max-content !important;
                max-width: calc(100vw - 24px) !important;
            }
            div[data-slot="dropdown-menu-sub-content"] .truncate {
                /* 模型名允许用满子菜单宽度，不提前省略号截断 */
                max-width: none !important;
            }

            /* 任务会话底部"上下文容量"悬浮卡（Radix HoverCard）字体与模型菜单
               一致：label 为 text-ui-base 14px、数值为 text-ui-sm，统一 12px/18px。
               限定 data-slot="hover-card-content"，不影响聊天消息与输入框。
               ⚠️ 不要在这里加 transform 偏移——卡片由外层 popper wrapper 的
               translate 定位，固定偏移只适配单一视口/卡宽（曾写死 translateX(-46px)
               实测反而把卡片推出屏幕左侧）。水平居中改由 JS 动态计算，见下方第 8 节。 */
            div[data-slot="hover-card-content"],
            div[data-slot="hover-card-content"] * {
                font-size: 12px !important;
                line-height: 18px !important;
            }

            /* 思考等级与权限控制实际渲染为 Radix DropdownMenu（role="menu" +
               data-slot="dropdown-menu-radio-item"），并非 Select listbox；
               条目是「图标 + 标题 + 副描述」两行结构，默认用宽松布局
               （min-h-13 items-start gap-3 py-2），且标题 text-ui-base=14px、
               副描述 text-ui-sm=12px——而模型选择器菜单被上面 373~388 行压到
               12px/18px、min-h-8(32px)、gap-2、px-2 py-1，两者观感差异明显。
               这里把权限/思考条目对齐到与模型菜单一致的紧凑规格：
               - 布局：py-2→py-1.5、gap-3→gap-2、min-h-13→min-h-11，去掉 items-start；
               - 字号：标题与模型条目一致 12px/18px，副描述 11px/15px 保留层级；
               用 :has(.flex-col) 精确命中带副描述的两行条目，不影响模型菜单
               自身的单行条目。宽度沿用菜单自身 w-64(256px)，避免再挤压换行。*/
            /* 计划模式是 dropdown-menu-checkbox-item(role=menuitemcheckbox)，
               变更前确认/自动编辑/完全访问是 dropdown-menu-radio-item(role=menuitemradio)，
               思考等级是 dropdown-menu-item(role=menuitem)。统一按 role + :has(.flex-col)
               命中带副描述的两行条目，避免按具体 data-slot 漏掉某一种形态。*/
            div[role="menu"].z-\\[60\\] [role="menuitemcheckbox"]:has(.flex-col),
            div[role="menu"].z-\\[60\\] [role="menuitemradio"]:has(.flex-col),
            div[role="menu"].z-\\[60\\] [role="menuitem"]:has(.flex-col) {
                min-height: 40px !important;
                height: auto !important;
                padding-top: 6px !important;
                padding-bottom: 6px !important;
                gap: 8px !important;
                align-items: center !important;
                box-sizing: border-box !important;
                margin-bottom: 0 !important;
                font-size: 12px !important;
                line-height: 18px !important;
            }
            div[role="menu"].z-\\[60\\] [role="menuitemcheckbox"]:has(.flex-col) > svg,
            div[role="menu"].z-\\[60\\] [role="menuitemradio"]:has(.flex-col) > svg,
            div[role="menu"].z-\\[60\\] [role="menuitem"]:has(.flex-col) > svg {
                width: 16px !important;
                height: 16px !important;
                margin-top: 0 !important;
            }
            div[role="menu"].z-\\[60\\] [role="menuitemcheckbox"]:has(.flex-col) .flex-col > span:first-child,
            div[role="menu"].z-\\[60\\] [role="menuitemradio"]:has(.flex-col) .flex-col > span:first-child,
            div[role="menu"].z-\\[60\\] [role="menuitem"]:has(.flex-col) .flex-col > span:first-child {
                font-size: 12px !important;
                line-height: 18px !important;
            }
            div[role="menu"].z-\\[60\\] [role="menuitemcheckbox"]:has(.flex-col) .flex-col > span.text-ui-sm,
            div[role="menu"].z-\\[60\\] [role="menuitemradio"]:has(.flex-col) .flex-col > span.text-ui-sm,
            div[role="menu"].z-\\[60\\] [role="menuitem"]:has(.flex-col) .flex-col > span.text-ui-sm {
                font-size: 11px !important;
                line-height: 15px !important;
                margin-top: 0 !important;
                opacity: 0.75 !important;
            }
            /* Radix Select listbox 形态（部分页面）同样对齐 12px/18px */
            div[role="listbox"].z-\\[60\\] [role="option"],
            div[data-slot="select-content"] [data-slot="select-item"] {
                font-size: 12px !important;
                line-height: 18px !important;
                min-height: 32px !important;
                height: auto !important;
                padding: 4px 22px 4px 8px !important;
                box-sizing: border-box !important;
            }
            div[role="listbox"].z-\\[60\\] [role="option"] span[class*="truncate"],
            div[data-slot="select-content"] [data-slot="select-item"] span[class*="truncate"] {
                font-size: 12px !important;
                line-height: 18px !important;
            }
            div[role="listbox"].z-\\[60\\] [role="option"] span[class*="line-clamp"],
            div[data-slot="select-content"] [data-slot="select-item"] span[class*="line-clamp"] {
                font-size: 11px !important;
                line-height: 15px !important;
                margin-top: 0 !important;
                opacity: 0.75 !important;
            }

            /* 底部「+」添加上下文弹层是 Radix Popover（data-slot="popover-content"），
               默认 14px/21px、图标 16px、条目 h-8(32px)，明显大于模型列表菜单
               (12px/18px)。此处整体缩小对齐模型菜单视觉规格：字号 12px/18px、
               图标 14px、条目高 28px。限定 popover-content，不影响聊天消息与输入框。
               ⚠️ **不要给弹层写死 max-width/width**（曾写死 max-width:300px）：远端本就用
               `w-(--radix-popover-trigger-width)` 让弹层宽度跟随触发器，Radix 实测把
               `--radix-popover-trigger-width` 置为输入框宽度(366.5px)、并把弹层定位在
               left=16，**左右边缘天然与输入框对齐**。写死 300px 会把右边缘截短 66px
               （实测 left=16/right=316 vs 输入框 right=382.5），正是"+ 列表没和输入框
               对齐"的根因。宽度与水平位置一律交回远端 + JS 兜底（见 alignComposerPopover）。*/
            div[data-slot="popover-content"] {
                font-size: 12px !important;
                line-height: 18px !important;
            }
            div[data-slot="popover-content"] [data-slot*="item"],
            div[data-slot="popover-content"] [role="menuitem"],
            div[data-slot="popover-content"] [role="option"] {
                min-height: 28px !important;
                height: 28px !important;
                padding-top: 0 !important;
                padding-bottom: 0 !important;
                font-size: 12px !important;
                line-height: 18px !important;
            }
            div[data-slot="popover-content"] [data-slot*="item"] *,
            div[data-slot="popover-content"] [role="menuitem"] *,
            div[data-slot="popover-content"] [role="option"] * {
                font-size: 12px !important;
                line-height: 18px !important;
            }
            div[data-slot="popover-content"] [data-slot*="item"] svg,
            div[data-slot="popover-content"] [role="menuitem"] svg,
            div[data-slot="popover-content"] [role="option"] svg {
                width: 14px !important;
                height: 14px !important;
            }

            /* 任务会话顶部标题在窄屏被源码主动收窄：
               h1[data-testid="workspace-title"] 带 @max-[560px]:max-w-[30vw] 与
               @max-[420px]:max-w-[22vw]，手机视口 412px 命中后者 → 标题仅 22vw≈91px，
               长标题（如"安装安卓15模拟器测试项目"）只剩"安装安卓15…"，右侧却白白
               空出 200 多像素。这里改为"预留右侧按钮空间"的显式上限：
               max-width: calc(100vw - 130px) —— 130px = 右侧"更多"按钮(28) + 面板
               切换按钮(28) + 两侧内边距与间距，实测三种视口(412/380/360)下「更多」与
               面板切换按钮都完整可见且保持 22px 间隔。
               ⚠️ 不要用 max-width:none：标题会贪心吃满整行，把「更多」按钮挤到面板
               切换按钮旁(实测间隔仅 8px)，视觉上"顶没"收起按钮。
               ⚠️ 不要给 h1 加 flex-grow、也不要用 min()/fit-content() 包 max-content
               （在 max-width 里属无效值会被丢弃，回落到源码 22vw 上限）。 */
            h1[data-testid="workspace-title"] {
                max-width: calc(100vw - 130px) !important;
            }

            /* 16. 每日 Token 趋势图日期标签由 Recharts 生成在 SVG 中，移动端
               默认显示“7月26日”且字号偏大，多个日期会挤在一起。JS 将其格式化
               为“7.26”，这里同步缩小坐标轴字号；只匹配 Recharts 日期 tick。 */
            svg text.recharts-cartesian-axis-tick-value {
                font-size: 10px !important;
            }

            /* 模型触发按钮「管理模型」过渡态：由注入 JS 缓存最后一个有效模型标签，
               目录刷新空窗（按钮短暂只显示「管理模型」）时直接写回缓存标签。这里无需
               额外 CSS——文本由 JS 直接维护，避免 font-size:0 之类的折中把真实模型名
               一并隐藏造成布局抖动。*/

            /* 3. 消除所有按钮与交互元素的 300ms 点击延迟与双击拦截 */
            button, [role="button"], [role="tab"], a, select, input, [tabindex],
            div[class*="provider"], div[class*="Provider"],
            div[class*="item"], div[class*="tab"], div[class*="Tab"] {
                touch-action: manipulation !important;
                cursor: pointer !important;
                -webkit-tap-highlight-color: transparent !important;
            }

            /* 3. 🛡️ 穿透图标内的子元素（绿色状态圆点、SVG、Path 等），确保点击事件直达外层 Button。
               ⚠️ 排除列表条目行内控件：子智能体/MCP/命令等页面的条目行是
               div[role="button"]，若裸用 [role="button"] > * 会把行内右侧的
               开关按钮（button[role="switch"]，恰好是行直接子级）也置为
               pointer-events:none——触摸直接穿透开关落到行上，行 onClick
               触发跳转编辑页、开关永远点不动（"点开关进编辑模块"根因）。
               故对行内按钮类控件恢复可命中；开关由下方独立保护规则兜底 */
            button > *, [role="button"] > *:not(button):not(a):not(select):not(input):not([role="switch"]),
            [role="tab"] > *,
            div[class*="provider"] button *, div[class*="Provider"] button *,
            svg, svg *, span[class*="badge"], span[class*="dot"], span[class*="status"] {
                pointer-events: none !important;
            }

            /* 3b. 🛡️ Switch 开关可点击性兜底：任何穿透规则都不得波及开关自身及其后代。
               列表条目的开关一旦 pointer-events:none 即无法切换且点击穿透到行触发
               编辑跳转；此规则置于穿透规则之后，以 !important 最高优先级覆盖 */
            button[role="switch"], button[role="switch"] *,
            [role="switch"], [role="switch"] * {
                pointer-events: auto !important;
            }

            /* 4. 📐 放大主界面侧边栏小图标的触摸热区与间距。
               作用域严格限定 workspace-sidebar / nav：宽泛的 button:has(svg)
               会命中设置中心各页面行内的操作按钮组（如模型列表的
               测试/编辑/删除），把行内按钮撑到 44×44 后挤压模型名输入框、
               顶偏 MCP 条目开关造成溢出——行内表单控件保持原生尺寸即可 */
            aside[class*="workspace-sidebar"] button:not([class*="rounded-xl"]),
            aside[class*="workspace-sidebar"] a:not([class*="rounded-xl"]),
            nav button:not([class*="rounded-xl"]),
            nav a:not([class*="rounded-xl"]) {
                min-width: 44px !important;
                min-height: 44px !important;
                margin: 4px 0 !important;
                display: inline-flex !important;
                align-items: center !important;
                justify-content: center !important;
                position: relative !important;
                -webkit-tap-highlight-color: transparent !important;
            }

            /* 5. 优雅纯净的原生级按压反馈（轻柔透明度过渡，无任何突兀蓝色闪烁）
               ⚠️ 2026-09-26 排除两种"整行/整张卡片"大按钮（2026-09-26 模拟器实测）：
                 - 工作区卡片主体 button（点它=展开/收起任务列表）：:active scale(0.96)
                   会让整张卡片连同标题字体一起瞬间缩小 4%，字体抖动；
                 - 任务项 button[data-testid^="task-item-"]：同样是整行按钮，按下时
                   整行内容（标题/徽章）缩放抖动。
               这两条按钮的 :active 不缩放、不变淡，保留默认视觉。其它小按钮（+ 添加、
               chevron、设置等）保持 scale(0.96) 反馈。 */
            button:active, [role="button"]:active, [role="tab"]:active, a:active {
                opacity: 0.75 !important;
                transform: scale(0.96) !important;
                transition: transform 0.05s ease, opacity 0.05s ease !important;
            }
            /* 排除规则（优先级需高于上面那条，:not 不增加特异性故用独立选择器） */
            ul.mt-3.space-y-2 > li.rounded-lg.border.border-card-border.bg-card
                > div > button.flex.min-w-0.flex-1.items-center.gap-2:active,
            button[data-testid^="task-item-"]:active {
                opacity: 1 !important;
                transform: none !important;
                transition: none !important;
            }

            /* 6. 确保设置面板在移动端可自由上下滑动，且不截断横向内容 */
            main, [role="main"], div[class*="settings-content"], div[class*="SettingsContent"] {
                -webkit-overflow-scrolling: touch !important;
            }

            /* ========================================================
               🌟 设置页面移动端自适应卡片流排版
               ======================================================== */
            
            /* 1. 左侧主导航栏：保持左侧紧凑垂直排列（仅图标，宽度 50px），绝不强行跑到顶部。
               作用域必须限定 workspace-sidebar 特征（主界面侧边栏专属 class）：
               裸 aside/nav 会误伤设置中心左栏与模型设置供应商列（同为 aside，
               原生 56/64px 设计），导致内容挤压、横向溢出滚动条 */
            aside[class*="workspace-sidebar"], div[class*="sidebar"], div[class*="settings-sidebar"] {
                width: 50px !important;
                min-width: 50px !important;
                max-width: 50px !important;
                flex-shrink: 0 !important;
                display: flex !important;
                flex-direction: column !important;
                padding: 8px 3px !important;
                align-items: center !important;
            }

            /* 2. 隐藏主界面侧边栏中多余的文字，仅展示精致图标与高亮指示。
               :not(:has(img)) 保护含 <img> logo 的 span——模型设置的供应商
               logo 是 data:image/svg+xml 图片，无 svg 子级，曾被此规则
               整体隐藏造成"第一个供应商图标缺失" */
            aside[class*="workspace-sidebar"] button > span:not(:has(svg)):not(:has(img)),
            aside[class*="workspace-sidebar"] a > span:not(:has(svg)):not(:has(img)),
            div[class*="sidebar"] button > span:not(:has(svg)):not(:has(img)),
            div[class*="sidebar"] a > span:not(:has(svg)):not(:has(img)),
            nav button > span:not(:has(svg)):not(:has(img)),
            nav a > span:not(:has(svg)):not(:has(img)) {
                display: none !important;
            }

            /* 3. 设置卡片重构为纵向表单流：标题/说明 → 输入框 → 保存按钮。
               卡片容器限定 border-t+py-3 双特征降低误伤；两列 grid 行
               (grid-cols-[minmax...]) 与其右列包裹层用 display:contents
               打散，使保存按钮与输入框提升为同层级 flex item 后用 order 排序。
               注意：
               a) grid 选择器必须限定 minmax 前缀——设置中心根布局是
                  grid-cols-[64px_minmax(0,1fr)]，宽泛匹配会连带打散根布局
                  导致左侧图标栏崩坏；
               b) 提升后的元素是卡片的 DOM 孙级，order 规则必须用完整
                  后代路径（> 直接子级路径匹配不到，实测踩坑） */
            div[class*="border-t"][class*="py-3"] {
                display: flex !important;
                flex-direction: column !important;
            }
            div[class*="border-t"] > div[class*="grid-cols-[minmax"],
            div[class*="border-t"] > div[class*="grid-cols-[minmax"] > div:last-child {
                display: contents !important;
            }
            /* 默认次序：标题/说明最先(order 1)，其余内容与输入行随后(order 2)，
               保存按钮最后(order 3)。开关/下拉类卡片无输入行，不受影响 */
            div[class*="border-t"][class*="py-3"] > * { order: 2; width: 100% !important; min-width: 0 !important; }
            div[class*="border-t"] div[class*="grid-cols-[minmax"] > div.min-w-0 {
                order: 1 !important;
                white-space: normal !important;
                word-break: break-word !important;
            }
            div[class*="border-t"] div[class*="grid-cols-[minmax"] > div:last-child > *:not(button) {
                order: 2 !important;
                width: 100% !important;
                min-width: 0 !important;
            }
            div[class*="border-t"] div[class*="grid-cols-[minmax"] > div:last-child > button:not([role="switch"]) {
                order: 3 !important;
                align-self: flex-start !important;
                margin-top: 4px !important;
            }
            /* Switch 开关仅排序与对齐。
               ⚠️ 禁止在此声明任何宽高尺寸：远端原生开关为
               data-[size=default]:w-[32px]/h-[18px]，配套圆球位移
               translate-x-[calc(100%-2px)]（相对球自身宽度≈14px），
               与原生轨道严丝合缝；若强制拉大外框（如 44×24），位移仍按
               原生计算，圆球就会停在轨道中间——"打开后圆球不在右侧"
               的根因，此坑已踩过两次，勿回填尺寸 */
            div[class*="border-t"] div[class*="grid-cols-[minmax"] > div:last-child > button[role="switch"] {
                order: 2 !important;
                align-self: flex-start !important;
                margin-top: 4px !important;
            }

            /* 3b. 键盘快捷键设置页表格行豁免（2026-09-26 模拟器 412px 实测根因）：
               表格行 div 同时带 grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_80px_72px]
               与 border-t border-border py-3，被规则 3「设置卡片纵向表单流」的
               display:flex !important + flex-direction:column 命中——grid 列定义
               在 flex 布局下完全失效，4 列（命令/键帽/作用域/删除）塌陷成单列竖排，
               与保持 grid 的 4 列表头严重错位（表头无 border-t 不被规则 3 命中）。
               此处按 data-testid 精确定位快捷键行恢复 grid，让列定义重新生效，
               并清掉规则 3 强加给子元素的 order/width:100%（flex 排序在 grid 下
               无意义且 width:100% 会把每个单元格撑满整行）。
               列宽依据实测内容分配（412px 视口容器 321px、行 padding 左右各 14px，
               可用 293px；各列内容自然宽：命令 135 / 键帽 111 / 作用域 37 / 删除 18）：
               命令列 1fr 吃剩余空间、键帽列固定 112px（实测最宽内容 111px 上限）、
               作用域/删除收窄——与桌面端 4 列横排布局保持一致，仅按窄屏等比收紧。
               ⚠️ 键帽列用固定像素而非 auto：表头与行是两个独立 grid，列宽各按自身
               内容计算；若键帽列 auto，表头按「按键绑定」文字算（48px）、行按真实
               键帽算（111px），两个 grid 各算各的导致表头标题与键帽错位，固定值
               才能让两个 grid 逐列严格对齐。 */
            /* ⚠️ 特异性必须压过规则 3 的 div[class*="border-t"][class*="py-3"]（0,2,1）。
               裸 div[data-testid^=...] 只有 (0,1,1) 会被覆盖——因此叠加 border-t/py-3
               两个 class 选择器提到 (0,3,1)，display:grid 才能真正生效（实测裸 testid
               选择器写入样式表但 computed 仍被规则 3 压成 flex）。 */
            div[data-testid^="settings-shortcut-row-"][class*="border-t"][class*="py-3"] {
                display: grid !important;
                grid-template-columns: minmax(0, 1fr) 112px 44px 32px !important;
                align-items: center !important;
                column-gap: 6px !important;
            }
            /* 同理子元素选择器也要压过规则 3 的 > *{width:100%}（0,2,2），提到 (0,3,2) */
            div[data-testid^="settings-shortcut-row-"][class*="border-t"][class*="py-3"] > * {
                order: initial !important;
                width: auto !important;
                min-width: 0 !important;
            }
            /* 表头列宽与行用同一套固定值，逐列严格对齐 */
            div[data-testid="settings-shortcuts-section"] .grid[class*="grid-cols"] {
                grid-template-columns: minmax(0, 1fr) 112px 44px 32px !important;
                column-gap: 6px !important;
            }

            /* 4. 下拉框、输入框自适应满宽，去除 PC 端 260px/320px 导致的挤压 */
            button[class*="w-[260px]"], div[class*="w-[260px]"],
            button[class*="w-[320px]"], div[class*="w-[320px]"],
            div[class*="border-t"] input, div[class*="border-t"] textarea {
                width: 100% !important;
                max-width: 100% !important;
                min-width: 0 !important;
            }

            /* 6. Switch 开关仅保留对齐属性。
               远端新版开关原生为 data-[size=default]:w-[32px]/h-[18px]，圆球位移是
               translate-x-[calc(100%-2px)]（相对自身宽度=14px），与原生轨道严丝合缝；
               若强制放大外框为 44×24，位移仍按原生计算，圆球会停在轨道中间偏左，
               即"打开后圆球不在右侧"问题的根因。故不再声明任何尺寸 */
            button[role="switch"] {
                align-self: flex-start !important;
                margin-top: 4px !important;
            }

            /* 保持卡片内部内边距紧凑美观 */
            div[class*="border-t"] {
                padding: 12px 14px !important;
            }

            /* 7. 设置中心左栏豁免 sidebar 50px 强制：原生设计为 grid-cols-[64px]
               图标栏，强制 50px 后内部 nav(px-2 内边距 + 40px 按钮 ≈ 56px)横向溢出，
            	overflow-x:auto 产生左右滚动条。恢复原生尺寸并禁横向滚动 */
            aside[class*="min-w-0"], aside[class*="min-w-0"] nav,
            aside[class*="min-w-0"] div[class*="sidebar"],
            aside[class*="min-w-0"] nav[class*="overflow-y-auto"] {
                width: auto !important;
                min-width: 0 !important;
                max-width: none !important;
                padding: 0 !important;
            }
aside[class*="min-w-0"] nav {
    overflow-x: hidden !important;
}

            /* 8. 设置中心列表条目防溢出兜底：子智能体/MCP 服务器/命令等页面的
               条目是 cursor-default 的 grid 行，窄屏下长名称会把行内右侧的
               开关挤出容器（实测 left=434/2656 超出视口）。约束条目不超容器宽，
               名称区域允许内部截断 */
            div[class*="grid"][class*="cursor-default"] {
                min-width: 0 !important;
                max-width: 100% !important;
            }
            main div[class*="grid"][class*="cursor-default"] * {
                max-width: 100% !important;
            }

            /* 9. 模型列表行重排：名称优先独占整行，操作按钮换行到下方。
               原生一行塞 [模型名 + 上下文徽章 + 测试/编辑/删除] 三段，
               窄屏下名称输入框被挤到仅 ~93px 几乎不可见。特征限定
               bg-input+divide-y 容器（模型列表专属），名称区 flex-basis
               100% 独占首行后按钮自动 wrap 到第二行 */
            div[class*="bg-input"][class*="divide-y"] > div > div[class*="items-center"] {
                flex-wrap: wrap !important;
                row-gap: 6px !important;
            }
            div[class*="bg-input"][class*="divide-y"] > div > div[class*="items-center"] > div[class*="flex-1"] {
                flex: 1 1 100% !important;
            }

            /* 10. 模型设置供应商列压缩列修复：移动端断点（max-md）下供应商条目被
               远端压成 32px 图标列（名称 span 走 max-md:sr-only 隐藏），条目内
               16px 拖拽按钮（aria-label="拖拽调整供应商顺序"）占满中心热区——
               点击条目任意位置都命中拖拽按钮，不触发条目 onClick 的供应商切换
               （"点供应商不能切换"根因，2026-08-23 实测）。
               ⚠️ 初版方案是恢复条目 min-width:130px+名称显示，但容器 aside 仅
               56px，强制 min-width 会让条目溢出容器被左栏图标和内容区遮挡，
               造成供应商列变形（截图显示竖条文字+图标被挡）——已回退。
               正确方案：保持 32px 图标列原貌，仅把拖拽按钮 pointer-events:none，
               让点击穿透到条目本体（role=button 的 div），触发供应商切换——
               实测 CDP 受信任点击可正常切换且不破坏布局。
               作用域限定 aside 内 aria-label 为供应商名的 [role="button"]，
               拖拽按钮 aria-label 含"拖拽"故只命中拖拽按钮 */
            aside div[role="button"][aria-label] button[aria-label*="拖拽"] {
                pointer-events: none !important;
            }

            /* 11. 设置中心左侧导航栏缩窄 + 图标居中 + 火箭禁用。
               原生结构：返回工作区(40px) + 14 个导航按钮(40×40 + 4px margin-bottom)
               + 3 个分组标题(space-y-4 间距 16px) + 底部账号/帮助 —— 总高超出 851
               出现滑动条，且分组间 16px gap 与组内 4px gap 混排导致图标间距不等。
               统一压缩为 32×32 + 2px 间距 + 隐藏分组标题 + 侧边栏收窄。
               ⚠️ 设置中心导航按钮全是 rounded-xl，规则 4 的 :not([class*="rounded-xl"])
               排除不会命中它们——必须用此专属规则统一压缩（含 rounded-xl）。
               关键：分组容器（space-y-4 / space-y-1）用 display:contents 打散——
               容器自身不产生布局（border-t/padding/margin 全部失效），按钮直接
               成为同级 flex item，间距完全统一 34px、图标垂直居中 8px 偏移。
               初版用 margin-top 压缩仍残留 16px gap（space-y-4 的容差），
               实测 display:contents 才能彻底统一。
               作用域限定 aside nav button[aria-label]（设置中心左栏导航按钮），
               避免误伤主界面侧边栏（那是 aside[class*="workspace-sidebar"]）。
               侧边栏收窄：64px → 44px（比 40px 图标稍宽一点，左右各 2px 留白），
               图标居中（nav padding 0 2px，44-40=4/2=2），选中/Hover/Tooltip 保留。
               网格布局：grid-cols-[64px] → 44px，主内容随侧边栏缩窄向左扩展。
               左箭头对齐：返回工作区按钮容器（px-2 pb-3 pt-3）改为 padding 12px 2px，
               与菜单图标一致（实测 center=22 对齐）。
               小火箭（引导）：pointer-events:none + opacity:0.4，点击无效（用户要求） */
            aside[class*="min-w-0"] {
                width: 44px !important;
                min-width: 44px !important;
                max-width: 44px !important;
            }
            /* 网格布局：第一列 64px → 44px，主内容向左扩展 */
            div[class*="grid-cols-[64px_minmax(0,1fr)]"] {
                grid-template-columns: 44px minmax(0, 1fr) !important;
            }
            aside[class*="min-w-0"] nav {
                width: 44px !important;
                padding: 0 2px 12px !important;
                margin: 0 !important;
            }
            aside nav button[aria-label][class*="rounded-xl"] {
                width: 40px !important;
                height: 40px !important;
                min-width: 40px !important;
                min-height: 40px !important;
                margin: 0 !important;
                padding: 0 !important;
            }
            /* 隐藏设置中心导航里的分组标题（基础设置/Agent 能力/数据与统计） */
            aside nav div[class*="text-ui-sm"][class*="max-lg:sr-only"] {
                display: none !important;
            }
            /* 分组容器（space-y-4 / space-y-1）display:contents 打散布局 */
            aside nav div[class*="space-y-4"],
            aside nav div[class*="space-y-1"] {
                display: contents !important;
            }
            /* 左箭头（返回工作区）与菜单图标对齐：容器 padding 12px 2px + margin-left 0 */
            aside div[class*="px-2 pb-3 pt-3"] {
                padding: 12px 2px !important;
            }
            aside div button[aria-label="返回工作区"] {
                width: 40px !important;
                height: 40px !important;
                min-width: 40px !important;
                min-height: 40px !important;
                margin: 4px 0 2px !important;
                padding: 0 !important;
            }
            /* 小火箭图标（引导）点击无效 */
            aside nav button[aria-label="引导"] {
                pointer-events: none !important;
                opacity: 0.4 !important;
            }

            /* 12. 模型设置供应商编辑头部：名称+编辑按钮第一行；
               已启用/禁用/删除 第二行（删除在禁用右侧，三者同行）。
               原生：名称 + 编辑按钮 + 已启用标签 + 禁用按钮 挤在
               flex items-start justify-between gap-3 同一行（名称被截断到仅
               ~15px"W.."）；而删除按钮是头部的第二个独立子块
               （div.flex items-center gap-2 内一个 ghost icon 按钮），
               在 column 布局下独占一行。
               调整：头部改 flex-wrap 行布局，把两个子容器 display:contents
               打散，让名称/编辑/已启用/禁用/删除 都成为头部同一级 flex 项，
               用 order 分组：名称(order0, flex-basis calc(100%-30px)) +
               编辑(order1) 第一行；已启用(order2) + 禁用(order3) +
               删除(order4) 第二行，删除在禁用右侧。
               ⚠️ 作用域必须限定 div[class*="flex items-start justify-between gap-3"]
               （仅对话式供应商头部；连接方式套餐类头部是 flex-wrap items-center
               justify-between，结构不同不命中）。子容器打散用
               > div:first-child, > div:last-child——裸 > div 特异性 (0,1,1)
               会被旧版 > div:first-child {display:flex}（(0,2,1)）覆盖，
               导致 child1 不参与 order 分组、删除仍会被挤出同排。
               实测（CDP）Woker-Public 卡片：已启用/禁用/删除同排 y=254，
               禁用 x=241 < 删除 x=341。 */
            div[class*="flex items-start justify-between gap-3"]:not(.mt-4):has(span[class*="rounded-full"]) {
                flex-direction: row !important;
                flex-wrap: wrap !important;
                align-items: center !important;
                gap: 6px !important;
            }
            div[class*="flex items-start justify-between gap-3"]:not(.mt-4):has(span[class*="rounded-full"]) > div:first-child,
            div[class*="flex items-start justify-between gap-3"]:not(.mt-4):has(span[class*="rounded-full"]) > div:last-child {
                display: contents !important;
            }
            div[class*="flex items-start justify-between gap-3"]:not(.mt-4):has(span[class*="rounded-full"]) > div:first-child > div:first-child {
                flex: 0 0 calc(100% - 30px) !important;
                min-width: 0 !important;
                order: 0 !important;
                overflow: visible !important;
                text-overflow: clip !important;
                white-space: normal !important;
            }
            div[class*="flex items-start justify-between gap-3"]:not(.mt-4):has(span[class*="rounded-full"]) > div:first-child > button:first-of-type {
                flex-shrink: 0 !important;
                order: 1 !important;
            }
            div[class*="flex items-start justify-between gap-3"]:not(.mt-4):has(span[class*="rounded-full"]) > div:first-child > span[class*="rounded-full"] {
                order: 2 !important;
            }
            div[class*="flex items-start justify-between gap-3"]:not(.mt-4):has(span[class*="rounded-full"]) > div:first-child > button:last-of-type {
                order: 3 !important;
            }
            div[class*="flex items-start justify-between gap-3"]:not(.mt-4):has(span[class*="rounded-full"]) > div:last-child > button {
                order: 4 !important;
            }

            /* 13. 使用统计「Token 活动」点阵：移动断点下 cell 自带大 padding
               （12px 14px），box-sizing:border-box 时最小尺寸被 padding+border
               顶到 29.5×25.5——排查时 computed width 恒为 29.5238px
               （= 14×2 + 0.76×2，与 width/height 声明无关，压不掉），
               52 列点阵互相重叠渲染成横向长条。
               修复：清掉 cell padding 恢复 aspect-square 方点；点阵容器
               （div.grid.w-full.gap-x-0.5，52 列）给足最小宽度并自身横向滚动，
               窄屏下左右滑动查看完整一年。 */
            div.grid[class*="gap-x-0.5"] > div > div {
                padding: 0 !important;
            }
            div.grid[class*="gap-x-0.5"] {
                min-width: 640px !important;
            }
            /* 滚动放在点阵与月份标签（兄弟 grid）的共同父级（无类名 div，:has 定位），
               两者整体滑动保持列对齐。⚠️ 不能给 grid 自身 overflow-x:auto +
               min-width:640——容器会被撑到 640px 撞破父卡片（内容不溢出容器，
               永远不产生滚动条），右侧最近两个月的蓝色活动点被屏幕裁掉，
               用户会误以为没有数据。 */
            div:has(> div.grid[class*="gap-x-0.5"]) {
                overflow-x: auto !important;
            }

            /* 14. 模型设置中模型名称与参数展示紧凑化适配。
               模型列表中的模型标识（如 claude-3-5-sonnet-20241022）在手机屏幕下容易过长溢出，
               将其字号进一步调小（10px）并紧凑排列，同时将窗口大小与能力徽章右侧紧贴。 */
            input[data-testid*="model-provider-model-input"],
            div[class*="rounded-xl"] input[data-slot="input"].font-mono,
            input[data-slot="input"].font-mono {
                height: 28px !important;
                font-size: 10px !important;
                line-height: 14px !important;
                padding-left: 5px !important;
                letter-spacing: -0.3px !important;
            }
            div.relative:has(span[data-model-input-capability]) input.font-mono {
                padding-right: 48px !important;
            }
            div.relative:not(:has(span[data-model-input-capability])):has(span[aria-label*="窗口"]) input.font-mono,
            div.relative:not(:has(span[data-model-input-capability])):has(span[title*="窗口"]) input.font-mono,
            div.relative:not(:has(span[data-model-input-capability])):has(span[class*="tabular-nums"]) input.font-mono {
                padding-right: 28px !important;
            }
            span:has(> [data-model-input-capability]),
            span:has(> [aria-label*="窗口"]) {
                right: 3px !important;
                gap: 1.5px !important;
            }
            [data-model-input-capability],
            span[aria-label*="窗口"],
            span[title*="窗口"],
            div.relative span.tabular-nums {
                font-size: 8px !important;
                padding: 0 2px !important;
                height: 14px !important;
                line-height: 12px !important;
            }

            /* 15. 二级详情与新建/编辑表单小屏幕紧凑化适配。
               设置中心二级表单（新建子智能体、新建 MCP、新建命令等）在手机窄屏下的输入控件与滚动优化：
               - 输入框、下拉框、多行文本域字号统一优化为 13px，高度适度紧凑，防止在窄屏下臃肿；
               - 表单卡片容器在窄屏下留出底部安全内边距，确保滚动与软键盘弹出时能完整露出底部保存/取消按钮。 */
            div[class*="rounded-xl"][class*="border-border"][class*="bg-background"] input[data-slot="input"]:not(.font-mono),
            div[class*="rounded-xl"][class*="border-border"][class*="bg-background"] textarea,
            div[class*="rounded-xl"][class*="border-border"][class*="bg-background"] button[role="combobox"] {
                font-size: 13px !important;
            }
            div[class*="rounded-xl"][class*="border-border"][class*="bg-background"] {
                padding-bottom: 24px !important;
            }

            /* 16. 设置中心与表单长列表底部键盘安全滚动留白（限定只在设置中心生效，绝不影响任务会话）。
               当编辑底部的模型名称或表单项时，软键盘弹出占用下半屏，
               充足的 padding-bottom 允许列表自由向上滚动，避免被软键盘遮挡。 */
            div:has(> aside) main,
            main:has(div[class*="provider"]),
            div:has(> nav[aria-label="设置路径"]) {
                padding-bottom: min(45dvh, 320px) !important;
                scroll-padding-bottom: min(45dvh, 320px) !important;
            }

            /* 17. 子智能体与任务消息气泡在窄屏及侧滑栏中的宽度与溢出适配。
               - 子任务侧滑抽屉（side pane）在手机屏幕上全宽铺满，避免右侧抽屉左侧留白挤压内容；
               - 限制消息气泡与任务信息卡片的最大宽度为 100%，增加文本自动折行，防止因右对齐 (items-end) 时长路径向左侧突刺被屏幕边缘截断。 */
            div[class*="w-[min(88vw,28rem)]"],
            div[class*="border-l"][class*="shadow-2xl"][class*="translate-x-"] {
                width: 100% !important;
                max-width: 100% !important;
                left: 0 !important;
                right: 0 !important;
            }
            div[class*="group/user-row"] {
                width: 100% !important;
                max-width: 100% !important;
                align-items: stretch !important;
            }
            div[class*="group/user-row"] > div[class*="rounded-xl"],
            div[class*="user-row"] > div[class*="rounded-xl"],
            div[class*="history-message"] div[class*="rounded-xl"][class*="border-border"] {
                max-width: 100% !important;
                width: 100% !important;
                box-sizing: border-box !important;
                word-break: break-word !important;
                overflow-wrap: anywhere !important;
            }

            /* 18. Radix UI / Floating-UI 下拉菜单与 Popover 在移动端的防闪烁与平滑展开优化。
               根因（实测确认，与注入字号无关）：Radix Select/DropdownMenu 的 popper
               会先以 transform:translate(0,-200%) 挂到屏幕外做测量，下一帧再瞬移到
               锚点位置——屏幕外那一帧在 WebView 里被渲染出来，表现为菜单"先闪一下再
               归位"的抖动。处理：
               - 屏幕外测量帧（style 含 -200%）时把内容设为不可见，消除位移帧的可见性；
               - 关掉 popper 自身的 transition/animation，避免 transform 平滑插值把
                 瞬移过程渲染成滑动；
               - 保留 GPU 合成与背面剔除，减少重绘闪烁。 */
            [data-radix-popper-content-wrapper] {
                will-change: transform, opacity !important;
            }
            /* 屏幕外测量帧隐藏内容（Select/DropdownMenu/Popover 通用） */
            [data-radix-popper-content-wrapper][style*="-200%"] [data-slot="select-content"],
            [data-radix-popper-content-wrapper][style*="-200%"] [data-slot="dropdown-menu-content"],
            [data-radix-popper-content-wrapper][style*="-200%"] [data-slot="popover-content"],
            [data-radix-popper-content-wrapper][style*="-200%"] [role="menu"],
            [data-radix-popper-content-wrapper][style*="-200%"] [role="listbox"],
            [data-radix-popper-content-wrapper][style*="-200%"] [role="dialog"] {
                visibility: hidden !important;
            }
            /* 关闭浮层位移/缩放动画，瞬移直接到位，避免中间插值帧造成的滑动感 */
            [data-radix-popper-content-wrapper] [data-slot="select-content"],
            [data-radix-popper-content-wrapper] [data-slot="dropdown-menu-content"],
            [data-radix-popper-content-wrapper] [data-slot="popover-content"],
            [data-radix-popper-content-wrapper] [role="menu"],
            [data-radix-popper-content-wrapper] [role="listbox"],
            [data-radix-popper-content-wrapper] [role="dialog"] {
                transition: none !important;
                animation: none !important;
                transform-origin: top !important;
                backface-visibility: hidden;
                -webkit-backface-visibility: hidden;
            }

            /* 19. 隐藏任务列表页顶部说明横幅，让任务列表上移。
               目标文案：「本次连接可以查看当前设备上已打开的项目、任务和会话；二维码失效后
               需要回到桌面端重新连接。」
               DOM 定位（真机 992e8e14，2026-09-26 实测）：
               - 该横幅是滚动容器 `div.min-h-0.flex-1.overflow-y-auto` 的**首子元素**，
                 完整类名组合 `rounded-lg border border-card-border bg-card p-3
                 text-ui-base/relaxed text-foreground-subtle`；
               - 紧随其后的兄弟是「当前设备上的工作区和任务」标题
                 `div.mt-4.flex.items-start.justify-between`，再往下才是任务列表 `ul`。
               选择器用「滚动容器 > 该横幅」结构锚点，且该 class 组合全页仅此一处
               （实测 count=1），不误伤其它卡片。隐藏后标题/列表随文档流自然上移
               约 71px（横幅高度），无需额外位移。
               ⚠️ 不叠加 `:has(+ div.mt-4)` 兄弟锚点：一旦标题改版或缺省，横幅反而
                  会意外复现；class 组合本身已唯一，足够精确。
               ⚠️ 本规则放在主样式串（两种模式都生效）：用户要求「远程原生」模式
                  下也去掉这条提示，故不放进只在适配模式挂载的 dashboardCss。
                  注：编号 21 的顶部留白收紧依赖本规则隐藏后的结构，两条需同时生效
                  ——21 只在适配模式挂载，原生模式下标题上方留白略多，属预期。 */
            div.min-h-0.flex-1.overflow-y-auto
                > div.rounded-lg.border.border-card-border.bg-card.text-foreground-subtle:first-child {
                display: none !important;
            }

            /* 20. 任务列表页 header 整条隐藏（2026-09-26 需求，替代原"精简化"）：
               原生 App 已在 layout 中渲染「ZMobile LOGO + 名称 + 调色板按钮」顶部栏，
               网页内 header（标题 + 副标题 + 调色板按钮）整条冗余，直接 display:none。
               DOM 结构（真机 992e8e14 & 模拟器 emulator-5554 实测）：
                 header.shrink-0.border-b.bg-header.px-4.py-3     ← 整页唯一 <header>
                   └ div.flex.min-w-0.items-start.justify-between
                       ├ div.min-w-0（标题 + 副标题）
                       └ button[aria-label="选择主题"]（调色板，原生按钮转发点击到这里）
               ⚠️ 网页调色板按钮的 click 处理逻辑保留（远端 React 监听仍挂在该 button
                  上），只是按钮随 header 一起 display:none。原生按钮通过 JS 找到它
                  并派发完整 pointer event 序列触发菜单。 */
            header.shrink-0.border-b.bg-header {
                display: none !important;
            }

            /* 22. 设置面板左侧栏顶部空白收紧（2026-09-26 需求）。
               左侧栏 DOM（模拟器 emulator-5554 实测）：
                 [data-testid="settings-page"] > aside.min-w-0 > div.flex.h-full.flex-col
                   ├ div.h-12 [app-region:drag]           ← 顶部 48px 拖拽区（桌面端窗口
                   │                                         拖动把手，移动端无意义）
                   ├ div.px-2.pb-3.pt-3                   ← 「返回工作区」按钮容器（70px）
                   └ nav.flex-1.overflow-y-auto.px-2.pb-3 ← 图标列（y=118）
               实测 nav 内容高度 ~561px < 可视 748px，本无需滚动；左侧竖线其实是
               aside 的 border-r，并非真滚动条。但顶部 118px 空白确实浪费：
               拖拽区 48px 在移动端纯占位（无窗口可拖），返回区上下 padding 12px
               也偏松。
               处理：拖拽区 48→8px、返回区上下 padding 12→6px，整列上移约 52px。
               选择器要点：
               - `[app-region:drag]` 在 DOM 里是**类名**（class="h-12 [app-region:drag]"），
                 不是属性——属性选择器命中不到，必须用**类选择器**；
               - ⚠️ **最大坑**：CSS 选择器里 Tailwind 自定义类的冒号必须用 `\\:`
                 转义（单反斜杠在浏览器里被当伪类前缀，整条规则被丢弃——实测注入的
                 stylesheet 里 height 规则完全缺失，只剩 padding 规则）。又因为
                 本注入器 `.replace("\n", " ")` 会去掉换行，CSS 里写 `\\:` 即可
                 （Kotlin 三引号字符串里写 `\\:`）；
               - 更稳的兜底是结构选择器「`div.flex.h-full.flex-col` 的首子 div」，
                 不依赖那条 Tailwind 自定义类名是否变化；
               - 用 `[data-testid="settings-page"] aside.min-w-0` 限定在设置面板
                 左侧栏内，避免误伤其它 h-12 / px-2 元素。 */
            [data-testid="settings-page"] aside.min-w-0 .h-12.app-region\\:drag,
            [data-testid="settings-page"] aside.min-w-0 > div.flex.h-full.flex-col > div:first-child {
                height: 8px !important;
            }
            [data-testid="settings-page"] aside.min-w-0 .px-2.pb-3.pt-3 {
                padding-top: 6px !important;
                padding-bottom: 6px !important;
            }

            /* 23. 模型列表行修复（2026-09-26 真机 992e8e14 实测）：
               问题 A：模型行按钮（测试/编辑/删除/switch）点击无响应。
               根因：模型行容器 `[data-model-provider-model-id]` 是 dnd-kit 的
               sortable 拖拽项（`role="button"` + `cursor-grab` + `touch-pan-y`），
               未激活拖拽时其**所有后代 computed pointer-events 全为 none**
               （行容器自身 auto，但 space-y-2/flex gap-2/button 等子孙全部 none），
               导致按钮 hit-test 直接落到行容器上、click 不触发。
               实测：`document.elementFromPoint(按钮中心)` 返回 DIV 而非 BUTTON，
               而 switch（`role="switch"`）的 pointer-events 却是 auto ——dnd-kit
               似乎对 switch 网开一面，其它按钮一律屏蔽。
               处理：强制按钮与 switch 的 pointer-events 恢复为 auto。
               ⚠️ 不能给整个 `[data-model-provider-model-id]` 设 auto——那会让
                  行容器本身也吞掉所有 touch，破坏 dnd-kit 拖拽排序手势。
                  只精准放行按钮/switch 这种**真正需要点击**的元素。

               问题 B：模型名称被截断成 `d...`（中列仅 207px，单行拥挤）。
               处理：把行内容器从「单行 flex」改为「两行 flex-wrap」，
               让名称+徽章区与按钮区各自独占一行：
                 - `div.space-y-2.px-3.py-2 > div.flex.items-center.gap-2`
                   原：flex-nowrap，名称+徽章+按钮挤一行；
                   改：flex-wrap，名称区 `flex-basis: 100%` 独占一行，
                       按钮区（含 switch 的 `div.flex.items-center.gap-2`）
                       另起一行右对齐。 */
            [data-model-provider-model-id] button,
            [data-model-provider-model-id] [role="switch"] {
                pointer-events: auto !important;
            }
            [data-model-provider-model-id] > div.space-y-2.px-3.py-2
                > div.flex.items-center.gap-2 {
                flex-wrap: wrap !important;
                row-gap: 6px !important;
            }
            [data-model-provider-model-id] > div.space-y-2.px-3.py-2
                > div.flex.items-center.gap-2
                > div.flex.min-w-0.flex-1.items-center.gap-2 {
                flex-basis: 100% !important;
                min-width: 0 !important;
            }
            [data-model-provider-model-id] > div.space-y-2.px-3.py-2
                > div.flex.items-center.gap-2
                > div.flex.items-center.gap-2:not(.min-w-0) {
                margin-left: auto !important;
            }

            /* 26. 模型名称字号调小（2026-09-26 模拟器 emulator-5554 实测）。
               需求：让长模型名（如 `deepseek-v4-flash-vision-exp`，28 字符）尽可能
               完整显示。
               实测：模型名容器可用宽 173px（zone 206px - 徽章 33px），
               名称 `deepseek-v4-flash-vision-exp` 在各字号下宽：
                 14px→235 / 13px→218 / 12px→202 / 11px→185 / 10px→168。
               仅 10px 能完整显示。
               处理：`span[data-testid^="model-provider-model-input-"]` 字号
               14px→10px，行高 1.4 保持可读。
               ⚠️ 该 span 是 `truncate`，截断宽度由 flex 父容器 min-w-0 决定，
                  所以 `getBoundingClientRect().width` 一直是 173——判断是否能
                  完整显示要用「创建临时 span 测量自然宽度」而非 getBounding。 */
            [data-model-provider-model-id] span[data-testid^="model-provider-model-input-"] {
                font-size: 10px !important;
                line-height: 1.4 !important;
            }

            /* 24. 设置面板左侧栏右侧空白回收（2026-09-26 真机 992e8e14 实测）。
               根因：`[data-testid="settings-page"]` 用 `grid-cols-[68px_minmax(0,1fr)]`
               布局，第一列固定 68px；但 aside 内容（图标列）实测只有 44px，
               于是 68-44=24px 留在 aside 右侧成为永久空白带，把中列起点推到
               x=68，浪费宝贵的横向空间（真机屏宽 412px，中列仅 344px）。
               处理：把 grid 第一列从 68px 缩到 44px，中列从 x=44 开始，
               中列宽 344→368（多 24px）。类选择器用 `[data-testid="settings-page"]`
               而非 class——`grid-cols-[68px_minmax(0,1fr)]` 这种带方括号的
               Tailwind 任意值类写到 CSS 选择器里需要复杂转义，用 testid 更稳。
               ⚠️ Tailwind 的 `lg:grid-cols-[268px_minmax(0,1fr)]` 是 lg 断点的，
                  移动端不命中，我们改的是基础 grid-template-columns，对移动端
                  生效；桌面端 WebView 不会触发（项目只在移动端注入）。 */
            [data-testid="settings-page"] {
                grid-template-columns: 44px minmax(0, 1fr) !important;
            }

            /* 28. 设置中心侧边栏按钮的 hover tooltip 屏蔽（2026-09-26 需求，模拟器实测）。
               现象：在设置中心点侧边栏图标（外观/记忆/MCP/命令/...），手指按下瞬间
               会先弹出按钮的 aria-label 文字气泡（如「记忆」白底气泡）再切到对应
               模块，视觉上"闪一下"。
               根因：远端按钮是 `button[data-slot="tooltip-trigger"][aria-label]`，
               配对的视觉 tooltip 容器是挂在 body 末尾 popper wrapper 里的
               `div[data-slot="tooltip-content"][data-side="right"][data-state="delayed-open"]`。
               Radix Tooltip 内部状态触发，与 aria-describedby 无关——移除
               aria-describedby 不能阻止弹出（已实测）。
               处理：CSS 全局 `display:none` 隐藏所有 tooltip-content，
               JS 白名单（编号 16）放行工作区气泡（含「最近活动」/路径分隔符「:\\」「:/」），
               不影响其它依赖 tooltip 的功能。 */
            div[data-slot="tooltip-content"] {
                display: none !important;
            }
        """.trimIndent().replace("\n", " ").replace("\"", "\\\"")

        // 「当前设备上的工作区和任务」页（dashboard）专属的窄屏适配样式。
        // 单独成串是为了能按用户设置（AppSettingsRepository.getDashboardMode）决定是否注入：
        // 选「远程原生」时不注入（该页保持远端原貌），选「Zmobile移动适配」时注入（下方 19/21/25/27）。
        // ⚠️ 只包含「改变该页布局」的规则。触控增强（点击热区、FastTouch、穿透修复）不在其中——
        //    那些是 App 全局的可用性保障，任何模式下都要生效。
        val dashboardCss = """
            /* 主工作区任务列表标题与操作图标栏排版保护：
               确保 3 个图标（全部折叠/设置/刷新）作为整体紧凑排列，向左排列，间距适当，不被打散或分散拉伸 */
            div.mt-4[class*="justify-between"] > div.flex.shrink-0,
            div.mt-4[class*="justify-between"] > div:last-child {
                display: flex !important;
                flex: 0 0 auto !important;
                align-items: center !important;
                justify-content: flex-start !important;
                gap: 6px !important;
                width: auto !important;
            }
            div.mt-4[class*="justify-between"] > div.flex.shrink-0 button {
                order: initial !important;
                flex: 0 0 auto !important;
            }

            /* 21. 收紧任务列表页滚动容器顶部空白（2026-09-26 需求）。
               间距来源（真机 992e8e14 / 模拟器 emulator-5554 实测）：
               - header 底 y=37；
               - 滚动容器 `div.min-h-0.flex-1.overflow-y-auto.px-3.py-3` 的 pt=12px → 49；
               - 首可见子元素 `div.mt-4`（「当前设备上的工作区和任务」标题行）mt=16px → 65。
               即标题离 header 底 28px 的空白，主要来自这两层 padding/margin。
               处理：scroller 上 padding 12px→4px，标题行上 margin 16px→8px，
               标题离 header 底 28px→12px；下 padding 12px→8px 保持上下节奏。
               ⚠️ 不能用 `:first-child`——编号 19 隐藏的 banner 虽然 display:none
                  但仍是 firstElementChild，`:first-child` 是结构伪类不会跳过它。
                  因此用「banner 的相邻兄弟」选择器，精确命中标题行。 */
            div.min-h-0.flex-1.overflow-y-auto.px-3.py-3 {
                padding-top: 4px !important;
                padding-bottom: 8px !important;
            }
            div.min-h-0.flex-1.overflow-y-auto.px-3.py-3
                > div.rounded-lg.border.border-card-border.bg-card.text-foreground-subtle:first-child
                + .mt-4 {
                margin-top: 8px !important;
            }

            /* 25. 任务列表工作区卡片精简（2026-09-26 需求，两行布局）：
               第一行：项目标题 + 更新于 + 本地标签
               第二行：项目路径 + N 个任务（任务数量字号与路径一致）
               - 隐藏左侧文件夹/对话图标（span.size-8）；
               - 「更新于 xx 分」从独立一行挪到标题行右侧（与标题同行）；
               - 「本地」标签在标题行最右；
               - 「N 个任务」从原第三行挪到路径行右侧，字号/颜色与路径一致。
               卡片 DOM（真机 992e8e14 实测）：
                 li.rounded-lg.border.border-card-border.bg-card
                 └ div.flex.min-w-0.items-center.gap-2.px-3.py-3
                    ├ button.flex.min-w-0.flex-1.items-center.gap-2
                    │   ├ span.flex.size-8（图标，隐藏对象）
                    │   ├ span.min-w-0.flex-1
                    │   │   ├ span.flex.items-center.gap-2（标题行：标题+本地）
                    │   │   ├ span.mt-1.block.truncate.font-mono（路径，14px）
                    │   │   └ span.mt-1.block.text-ui-base.text-foreground-subtle（更新于）
                    │   └ span.flex.shrink-0.items-center.gap-2.text-ui-base（N 个任务+chevron）
                    └ button.group/button（+ 添加按钮，right≈24+32）
               做法：li 设 position:relative 作锚点；「更新于」「本地」absolute 到第一行
               右侧（top≈12-14，与标题同基线，right=68/108 避开 + 按钮）；
               「N 个任务」absolute 到第二行右侧（top=38，与路径同基线）；
               标题行 padding-right 200px（给「更新于」「本地」让位）；
               路径 padding-right 130px（给「N 个任务」让位）；
               li min-height 62px 保底（absolute 元素脱离文档流）。
               ⚠️ 「N 个任务」的字号（默认 text-ui-base=14px）和颜色（默认
                  text-foreground-subtle≈60% 透明度）都要改成与路径一致——
                  路径是 14px / oklab(0.87 0 0 / 0.3)（30% 弱色），否则
                  视觉上「N 个任务」会比路径更显眼。 */
            ul.mt-3.space-y-2 > li.rounded-lg.border.border-card-border.bg-card {
                position: relative !important;
                min-height: 62px !important;
            }
            ul.mt-3.space-y-2 > li.rounded-lg.border.border-card-border.bg-card
                span.size-8.shrink-0 {
                display: none !important;
            }
            /* 第一行右侧：更新于 + 本地（与标题同行，垂直居中对齐） */
            ul.mt-3.space-y-2 > li.rounded-lg.border.border-card-border.bg-card
                span.mt-1.block.text-ui-base.text-foreground-subtle:last-child {
                position: absolute !important;
                top: 14px !important;
                right: 108px !important;
                margin-top: 0 !important;
                font-size: 10px !important;
                line-height: 1.4 !important;
                white-space: nowrap !important;
                z-index: 1 !important;
            }
            /* 「本地」：top 14（不是 12），让徽章与标题/时间垂直居中对齐。
               实测标题 cy=130 / 时间 cy=131，badge 原 top=12 cy=127 偏上 3px；
               top=14 后 badge cy=129，三者基线一致。字号缩到 10px 与「更新于」一致。 */
            ul.mt-3.space-y-2 > li.rounded-lg.border.border-card-border.bg-card
                span.rounded-full.border.border-border.bg-surface {
                position: absolute !important;
                top: 14px !important;
                right: 68px !important;
                z-index: 1 !important;
                font-size: 10px !important;
                padding-left: 4px !important;
                padding-right: 4px !important;
            }
            /* 标题：默认 text-ui-base=14px，与「更新于」13px 不一致；改 12px 统一*/
            ul.mt-3.space-y-2 > li.rounded-lg.border.border-card-border.bg-card
                span.truncate.text-ui-base.font-medium {
                font-size: 12px !important;
            }
            /* 路径：长路径（如 D:\oss-install-windows-flow-assias-aiagent 38 字符）
               默认 14px 会被截断；缩到 9px 让长路径完整显示（实测 9px 自然宽 ~232
               ≤ 可用 233）。**⚠️ 测量陷阱**：path 是 truncate，截断宽度由 flex
               父容器 min-w-0 决定，getBoundingClientRect().width 一直是 323，
               不能用来判断是否能完整显示——必须创建临时 span 测自然宽度。 */
            ul.mt-3.space-y-2 > li.rounded-lg.border.border-card-border.bg-card
                span.mt-1.block.truncate.font-mono {
                padding-right: 90px !important;
                font-size: 9px !important;
            }
            /* 第二行右侧：N 个任务。
               ⚠️ top 取值要与路径行垂直中心对齐（2026-09-26 CDP 实测）：
               路径 cy = 卡片 top + 41.5；本元素含 chevron 整体高 16，
               故 top = 41.5 - 16/2 = 33.5（取 33），cy 落在 41.5 与路径同行中心。
               旧值 top=38 会让 cy 偏下 5.2px，与路径明显不在同一水平线。
               ⚠️ **不要在这里写死 color**（2026-09-27 修复）：原值 `oklab(0.87 0 0 / 0.3)`
               是照抄**深色主题**下路径的实测值，浅色主题下 0.87 的浅灰落在白色卡片上
               几乎不可见（用户截图反馈"白色下看不清"）。该元素远端自带
               `text-foreground-subtle` 类，实测浅色=oklab(0.269 0 0/0.6)、
               深色=oklab(0.87 0 0/0.6)，**两种主题都自适应**——删掉写死的 color
               让它走远端主题变量即可，字体大小仍由本规则统一。 */
            ul.mt-3.space-y-2 > li.rounded-lg.border.border-card-border.bg-card
                span.flex.shrink-0.items-center.gap-2.text-ui-base {
                position: absolute !important;
                top: 33px !important;
                right: 68px !important;
                font-size: 9px !important;
                line-height: 1 !important;
            }
            /* 标题行/路径：让出右侧空间给「更新于」「本地」「N 个任务」。
               ⚠️ 用 :has() 区分有/无「更新于」：
                 - 有「更新于」（旧工作区）：标题行 130px（让「更新于」+「本地」），
                   路径 90px（让「N 个任务」）；
                 - 无「更新于」（新工作区，如刚扫二维码添加的）：只让「本地」，
                   标题行 56px、路径 84px。
               ⚠️ 数值依据（2026-09-26 页面缩放功能实测补充）：右侧保留元素与
               路径元素右缘相对卡片右缘都是**恒定偏移**（+ 按钮与各 padding 都是
               固定像素），不随卡片宽度变化——实测路径右缘 = 卡片右缘 − 52px，
               「N 个任务」左缘 = 卡片右缘 − 126px，故路径所需 padding 恒为 74px。
               原「无更新于」分支写 50px，在 100% 视口下仅剩 22px 余量，一旦页面
               放大（≥110%，如「页面缩放」调到 120%）立即出现路径文字与「N 个任务」
               重叠（实测 110% 余量 −15px、150% 余量 −25px）。故提到 84px 留出
               10px 安全余量；标题行同理 50→56px（其所需 47px，原值仅 3px 余量）。 */
            ul.mt-3.space-y-2 > li.rounded-lg.border.border-card-border.bg-card:has(span.mt-1.block.text-ui-base.text-foreground-subtle:last-child)
                span.flex.min-w-0.items-center.gap-2 {
                padding-right: 130px !important;
            }
            ul.mt-3.space-y-2 > li.rounded-lg.border.border-card-border.bg-card:not(:has(span.mt-1.block.text-ui-base.text-foreground-subtle:last-child))
                span.flex.min-w-0.items-center.gap-2 {
                padding-right: 56px !important;
            }
            ul.mt-3.space-y-2 > li.rounded-lg.border.border-card-border.bg-card:not(:has(span.mt-1.block.text-ui-base.text-foreground-subtle:last-child))
                span.mt-1.block.truncate.font-mono {
                padding-right: 84px !important;
            }

            /* 27. 任务项标题截断修复（2026-09-26 模拟器 emulator-5554 实测）。
               嵌套在工作区卡片 ul.border-t 内的任务项，⚠️ **真结构**是
               `li > button[data-testid^="task-item-"] > span.min-w-0.flex-1 > span.block.truncate.text-ui-base`
               ——**`data-testid` 挂在 button 上，不是 li 上**！第一次写
               `li[data-testid^="task-item-"]` 选择器根本没命中（li 上没有这个属性）。
               标题默认 14px，长标题「安装安卓15模拟器测试项目拟器测试项目多少度111」
               （28 字符）需要 319px 但仅 259px 可用，被截断为「…测试项…」。
               量化：14px=319 / 13px=296 / 12px=274，仍差；只有 12px + 腾出更多空间才够。
               处理：
                 - item padding px-2.5=10px → 6px（左右各 -4，+8px 可用）；
                 - item gap 8 → 6（两个 gap 各 -2，+4px）；
                 - 状态点 size-4=16 → 12（-4px）；
                 - 状态徽章（已完成/运行中）字号 10 → 9px、padding 缩（约 +8px）；
                 - 标题字号 14 → 12px。
               综合可用宽 259→约 285，12px 长标题 274 ≤ 285 完整显示。 */
            button[data-testid^="task-item-"] {
                padding-left: 6px !important;
                padding-right: 6px !important;
                gap: 6px !important;
            }
            button[data-testid^="task-item-"] > span.relative.flex.size-4 {
                width: 12px !important;
                height: 12px !important;
            }
            button[data-testid^="task-item-"] > span.min-w-0.flex-1
                > span.block.truncate.text-ui-base {
                font-size: 12px !important;
                line-height: 1.4 !important;
            }
            button[data-testid^="task-item-"] > span.inline-flex.shrink-0.rounded-full {
                font-size: 9px !important;
                padding-left: 4px !important;
                padding-right: 4px !important;
            }

        """.trimIndent().replace("\n", " ").replace("\"", "\\\"")

        // 「当前设备上的工作区和任务」页是否使用 Zmobile 移动适配布局。
        // 由 AppSettingsRepository.getDashboardMode() 决定，用户可在原生设置页切换；
        // 切换后 RemoteControlActivity.onResume 会重新注入，无需重载页面。
        val dashboardAdaptive =
            dashboardModeProvider() == AppSettingsRepository.DashboardMode.ADAPTIVE

        val js = """
            (function() {
                // 每次注入都把最新模式写到 window 上：长期存活的 MutationObserver
                // （如调色板按钮注入）读它而非闭包常量，切换设置后无需重载页面即生效。
                window.__zcodeDashboardAdaptive = $dashboardAdaptive;

                // 1. 安全注入触控优化样式
                function applyStyle() {
                    var target = document.head || document.documentElement || document.body;
                    if (!target) return false;
                    var style = document.getElementById('zcode-mobile-fast-touch-style');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'zcode-mobile-fast-touch-style';
                        target.appendChild(style);
                    }
                    style.innerHTML = "$css";
                    // dashboard（「当前设备上的工作区和任务」页）专属适配样式：
                    // 按用户设置决定是否挂载。用一个独立 style 元素承载，切换模式时
                    // 只需增删该元素，不必重新计算主样式（幂等，SPA 内切换也安全）。
                    var dash = document.getElementById('zcode-dashboard-adaptive-style');
                    if (window.__zcodeDashboardAdaptive) {
                        if (!dash) {
                            dash = document.createElement('style');
                            dash.id = 'zcode-dashboard-adaptive-style';
                            target.appendChild(dash);
                        }
                        dash.innerHTML = "$dashboardCss";
                    } else if (dash) {
                        dash.remove();
                    }
                    return true;
                }
                if (!applyStyle()) {
                    document.addEventListener('DOMContentLoaded', applyStyle);
                }

                // 2. 拦截全局意外的右键菜单（保留文本输入框与聊天消息内容）
                // ⚠️ Android WebView 长按文本会派发 contextmenu，preventDefault 会
                // 抑制后续的选择复制菜单弹出——.history-message（任务会话的消息
                // 内容，含用户提问与模型输出）必须放行，否则文本可选但无法复制
                window.addEventListener('contextmenu', function(e) {
                    var t = e.target;
                    var tag = t.tagName ? t.tagName.toLowerCase() : '';
                    if (tag !== 'input' && tag !== 'textarea' && !t.isContentEditable &&
                        !(t.closest && t.closest('.history-message'))) {
                        e.preventDefault();
                    }
                }, { passive: false });

                // 3. 🌟 FastTouch 零延迟触摸点击代理引擎（自适应视口缩放）
                if (!window._zcodeFastTouchInstalled) {
                    window._zcodeFastTouchInstalled = true;

                    // 3.0 开关组件的触摸事件隔离：列表条目（子智能体/MCP/命令）挂有
                    // dnd-kit 拖拽传感器，触摸时传感器在 pointerdown/touchstart 即激活
                    // 拖拽模式并吞掉后续原生 click（桌面鼠标有位移阈值故不受影响），
                    // 导致开关永远点不动。在传播最早点（window capture）阻断落在开关上
                    // 的 pointer/touch 事件向外层拖拽监听传播；stopPropagation 不影响
                    // 浏览器从触摸序列合成受信任 click，开关得以正常切换。
                    // 注意圆球 span 自带 data-state，须用 closest 反查祖先链上的 switch
                    function isolateSwitchPointerEvents(type) {
                        window.addEventListener(type, function(e) {
                            var t = e.target;
                            if (t && t.closest && t.closest('button[role="switch"], [role="switch"]')) {
                                e.stopPropagation();
                            }
                        }, { capture: true, passive: true });
                    }
                    isolateSwitchPointerEvents('pointerdown');
                    isolateSwitchPointerEvents('pointerup');
                    isolateSwitchPointerEvents('pointercancel');
                    isolateSwitchPointerEvents('touchstart');
                    isolateSwitchPointerEvents('touchend');
                    isolateSwitchPointerEvents('touchcancel');

                    var touchStartX = 0;
                    var touchStartY = 0;
                    var touchStartTime = 0;
                    var touchTarget = null;
                    // 记录最近一次原生 click 到达 document 的时刻（capture 阶段），
                    // 用于避免与浏览器原生合成 click 双重触发：
                    // 开关类组件被连续 click 两次等于切回原状态（表现为"点了没反应"），
                    // 对话框类按钮则表现为"闪一下就关闭"
                    var lastNativeClickAt = 0;
                    document.addEventListener('click', function() {
                        lastNativeClickAt = Date.now();
                    }, true);

                    document.addEventListener('touchstart', function(e) {
                        if (e.touches.length === 1) {
                            var t = e.touches[0];
                            touchStartX = t.clientX;
                            touchStartY = t.clientY;
                            touchStartTime = Date.now();
                            touchTarget = e.target;
                        }
                    }, { passive: true, capture: true });

                    document.addEventListener('touchend', function(e) {
                        if (e.changedTouches.length === 1 && touchTarget) {
                            var t = e.changedTouches[0];
                            var dx = Math.abs(t.clientX - touchStartX);
                            var dy = Math.abs(t.clientY - touchStartY);
                            var duration = Date.now() - touchStartTime;

                            // 考虑到桌面 Viewport 缩放比例，位移容差放宽至 35px（对应手机屏幕实际 10px 生理微抖动）
                            if (dx < 35 && dy < 35 && duration < 450) {
                                // 编辑器、终端与 Diff 差异视图内部由 Monaco / 浏览器原生处理触控，FastTouch 跳过合成避免干扰
                                if (touchTarget.closest('.monaco-editor, .xterm, [class*="diff"], [data-slot="diff"], pre, code')) return;

                                // 向上寻找所有可能的可交互节点
                                var clickable = touchTarget.closest('button, [role="button"], [role="tab"], a, select, [tabindex], [data-state], [data-slot], div[class*="item"], div[class*="provider"]') || touchTarget;

                                if (clickable) {
                                    // 延迟触发前先检查原生 click 是否已经派发：
                                    // 浏览器在 touchend 后会立即合成原生 click，若已发生则跳过手动合成，
                                    // 否则 toggle 开关会切换两次回到原状态、对话框会开了又关、可折叠卡片会展开后瞬间收起
                                    setTimeout(function() {
                                        if (Date.now() - lastNativeClickAt < 80) return;
                                        // 具有状态切换特性的组件（开关、可折叠面板、手风琴、下拉触发器等）必须完全由浏览器原生受信任 click 处理：
                                        // 手动合成 untrusted click 会与稍后到达的原生 click 构成“双击”，导致展开马上收回（闪烁一下）
                                        // ⚠️ `[data-zcode-inject="1"]`：App 注入的 dashboard 调色板按钮（规则 17）。
                                        //    它转发给 zcodeTriggerThemeToggle() 打开 Radix DropdownMenu——
                                        //    与原生 trigger 同为 **toggle 语义**，被点偶数次就开→关（表现为"闪一下就消失"）。
                                        //    实测（模拟器）原生 click 到达可能晚至 ~190ms，超过下方 80ms 守卫，
                                        //    FastTouch 会额外合成 1~3 次 click，把菜单反复开关。故必须整体跳过。
                                        if (clickable.matches('button[role="switch"], [role="switch"], [data-slot*="collapsible"], [data-slot*="trigger"], [data-slot*="accordion"], [aria-expanded], [data-zcode-inject="1"], details summary') ||
                                            clickable.closest('button[role="switch"], [role="switch"], [data-slot*="collapsible"], [data-slot*="trigger"], [data-slot*="accordion"], [aria-expanded], [data-zcode-inject="1"], details summary')) return;
                                        try {
                                            clickable.click();
                                        } catch(err) {}
                                    }, 10);
                                }
                            }
                        }
                    }, { passive: true, capture: true });
                }

                // 4. 每日 Token 趋势图的 Recharts 日期标签使用“7月26日”格式，
                // 移动端空间有限时会互相重叠。格式化为“7.26”节省横向空间，
                // 使用 requestAnimationFrame 防抖，避免在大型 DOM 渲染时频繁阻塞主线程
                var trendRAF = null;
                function formatTrendDates() {
                    trendRAF = null;
                    var labels = document.querySelectorAll('svg text.recharts-cartesian-axis-tick-value');
                    for (var i = 0; i < labels.length; i++) {
                        var text = (labels[i].textContent || '').trim();
                        var match = text.match(/^(\d+)月(\d+)日$/);
                        if (match) {
                            var formatted = match[1] + '.' + ('0' + match[2]).slice(-2);
                            if (labels[i].textContent !== formatted) labels[i].textContent = formatted;
                        }
                    }
                }
                formatTrendDates();
                new MutationObserver(function() {
                    if (!trendRAF) trendRAF = requestAnimationFrame(formatTrendDates);
                }).observe(document.body, { childList: true, subtree: true });

                // 6. 模型触发按钮「管理模型」闪现兜底。
                // 根因（Web 端 V4ComposerToolbar/modelTriggerDisplay）：选思考级别会触发一次
                // 模型目录(modelSelectGroups)刷新，目录短暂为空时 resolveV4ModelTriggerDisplay
                // 找不到选中项 → 回退显示「管理模型」占位符，刷新完成又恢复，表现为按钮文本
                // 闪一下「管理模型」。这里缓存最后一个"有效"模型标签（含斜杠且不含管理模型），
                // 当按钮过渡态只显示「管理模型」时用缓存标签覆盖可见文本，刷新恢复后交由页面
                // 自身渲染；按钮本就显示真实模型名时同步更新缓存。
                var zcodeLastModelLabel = null;
                function stabilizeModelTrigger() {
                    var btn = document.querySelector('button[data-testid="chat-model-select-trigger"]');
                    if (!btn) return;
                    var text = (btn.textContent || '').trim();
                    var hasManage = text.indexOf('管理模型') !== -1;
                    var residual = text.replace(/管理模型/g, '').trim();
                    if (hasManage && residual.length === 0) {
                        // 过渡空窗：只剩占位符 → 用缓存的有效标签覆盖可见文本。
                        // ⚠️ 只能改「已存在的文本节点」(nodeValue)，**绝不能**对容器赋
                        // textContent：远端 RollingToolbarLabel 把 provider 前缀与模型名
                        // 渲染成两个独立子 span，并用 AnimatePresence 以 label 为 key
                        // 整体替换这棵子树。赋 textContent 会先摧毁该子树，之后 React
                        // 渲染新模型名时只能写进**已脱离文档的孤儿节点**，可见标签于是
                        // 永久卡在旧模型——实测现象：切模型后子菜单勾选已变、输入框下方
                        // 标签不变，且控制台无任何报错，极易误判为"模型没选上"。
                        if (zcodeLastModelLabel) {
                            var tnodes = [];
                            (function collect(n) {
                                for (var i = 0; i < n.childNodes.length; i++) {
                                    var c = n.childNodes[i];
                                    if (c.nodeType === 3) { if ((c.nodeValue || '').trim()) tnodes.push(c); }
                                    else if (c.nodeType === 1) collect(c);
                                }
                            })(btn);
                            // 仅当当前是「单一占位文本节点」时改写；双 span（前缀+模型名）
                            // 结构下不改，宁可留着占位符也不破坏 React 的 DOM 结构。
                            if (tnodes.length === 1 && tnodes[0].nodeValue.trim() === '管理模型') {
                                tnodes[0].nodeValue = zcodeLastModelLabel;
                            }
                        }
                    } else if (!hasManage && text.length > 0) {
                        // 正常态：记录有效标签（含 provider/model 结构）
                        zcodeLastModelLabel = text;
                    }
                    // hasManage && residual>0（占位+模型名混排）视为恢复中，不动
                }
                stabilizeModelTrigger();
                var modelTrigRAF = null;
                new MutationObserver(function() {
                    if (!modelTrigRAF) modelTrigRAF = requestAnimationFrame(function(){ modelTrigRAF = null; stabilizeModelTrigger(); });
                }).observe(document.body, { childList: true, subtree: true, characterData: true });

                // 7. 模型二级子菜单水平夹取。
                // Radix 子菜单由 popper wrapper 的 transform:translate(x,y) 定位，默认从
                // 供应商触发器左侧弹出，移动端实测 left=-52 溢出左屏（CSS margin 无效——
                // getBoundingClientRect 含 margin 且位置被 transform 决定）。这里在子菜单
                // 打开/尺寸变化后，把其 popper wrapper 的 translateX 夹取到 [8, vw-w-8]
                // 区间：左移出屏则右移至贴左 8px，右移出屏则左移至贴右 8px，保证模型名
                // 完整可见且不超屏。供应商一级列不动，实现"二级列表右移一点点"的效果。
                function clampModelSubmenu() {
                    var sub = document.querySelector('div[data-slot="dropdown-menu-sub-content"]');
                    if (!sub) return;
                    var wrapper = sub.closest('[data-radix-popper-content-wrapper]');
                    if (!wrapper) return;
                    var rect = sub.getBoundingClientRect();
                    if (rect.width === 0) return;
                    var vw = window.innerWidth;
                    var GUTTER = 8;
                    var overflowLeft = rect.left < GUTTER;
                    var overflowRight = rect.right > vw - GUTTER;
                    if (!overflowLeft && !overflowRight) return;
                    // 解析现有 transform: translate(x, y)
                    var style = wrapper.style.transform || '';
                    var m = style.match(/translate\((-?[\d.]+)px[,\s]+(-?[\d.]+)px\)/);
                    if (!m) return;
                    var curX = parseFloat(m[1]), curY = m[2];
                    var delta = 0;
                    if (overflowLeft) delta = GUTTER - rect.left;
                    else if (overflowRight) delta = (vw - GUTTER) - rect.right;
                    var newX = curX + delta;
                    wrapper.style.transform = 'translate(' + newX + 'px, ' + curY + 'px)';
                }
                clampModelSubmenu();
                var submenuRAF = null;
                new MutationObserver(function() {
                    if (!submenuRAF) submenuRAF = requestAnimationFrame(function(){ submenuRAF = null; clampModelSubmenu(); });
                }).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['style'] });

                // 8. 上下文容量悬浮卡按视口动态水平居中。
                // Radix HoverCard 默认以"触发器"为基准定位（实测 data-side="right" /
                // align="start"），而触发器在输入框工具栏最左侧（left≈4），卡片
                // (w-72/w-80=320px) 于是整体偏右（实测卡中心 216 vs 视口中心 206）。
                // 这里不用写死偏移（视口/卡宽变化即失效），而是每次卡片出现/尺寸变化时
                // 读取其真实宽度，按 (vw - w) / 2 动态算出左边界，改写 popper wrapper 的
                // translateX，使卡片正中央对齐视口正中；两侧不足时夹取到 8px 内边距。
                // ⚠️ 页面同时存在多个 hover-card（消息预览等），必须逐个遍历、只处理
                // 容量卡——否则 querySelector 会命中第一个（消息预览卡）而漏掉容量卡。
                // 容量卡特征：文本含「上下文容量」/「平均缓存命中率」。
                function isContextCard(el) {
                    var t = (el.textContent || '');
                    return t.indexOf('上下文容量') !== -1 || t.indexOf('平均缓存命中率') !== -1;
                }
                function centerContextCard() {
                    var cards = document.querySelectorAll('div[data-slot="hover-card-content"]');
                    for (var i = 0; i < cards.length; i++) {
                        var card = cards[i];
                        if (!isContextCard(card)) continue;
                        var wrapper = card.closest('[data-radix-popper-content-wrapper]');
                        if (!wrapper) continue;
                        var rect = card.getBoundingClientRect();
                        if (rect.width === 0) continue;
                        var vw = window.innerWidth;
                        var GUTTER = 8;
                        // 目标左边界：视口居中；宽度超出可用空间时退回 8px 内边距
                        var targetLeft = Math.max(GUTTER, Math.min((vw - rect.width) / 2, vw - rect.width - GUTTER));
                        var delta = targetLeft - rect.left;
                        if (Math.abs(delta) < 0.5) continue;
                        var style = wrapper.style.transform || '';
                        var m = style.match(/translate\((-?[\d.]+)px[,\s]+(-?[\d.]+)px\)/);
                        if (!m) continue;
                        var newX = parseFloat(m[1]) + delta;
                        wrapper.style.transform = 'translate(' + newX + 'px, ' + m[2] + 'px)';
                    }
                }
                centerContextCard();
                var ctxCardRAF = null;
                new MutationObserver(function() {
                    if (!ctxCardRAF) ctxCardRAF = requestAnimationFrame(function(){ ctxCardRAF = null; centerContextCard(); });
                }).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['style'] });
                window.addEventListener('resize', function() { centerContextCard(); });

                // 9.「+」添加上下文弹层与输入框左右对齐。
                // 远端该弹层是 Radix Popover，宽度声明为 w-(--radix-popover-trigger-width)
                // 并由 Radix 按触发器定位，理论宽度已等于输入框；但注入 CSS 曾写死
                // max-width 截短右边缘（已移除），且视口/键盘/横竖屏变化时 Radix 的
                // 测量可能滞后于输入框。这里每次弹层出现或尺寸变化时**实测输入框的
                // 左右边缘**，把弹层的宽度与水平位置夹取到与之对齐，不依赖远端实现细节。
                // ⚠️ 只处理带「+」特征（含 附件 且含 插件/添加上下文）的 popover，
                // 避免误伤页面其它 popover（如消息操作、设置项）。
                function isComposerPopover(el) {
                    var t = el.textContent || '';
                    return t.indexOf('附件') !== -1 && (t.indexOf('插件') !== -1 || t.indexOf('添加上下文') !== -1);
                }
                function alignComposerPopover() {
                    // 先查弹层（多数时候不存在，尽早返回，避免流式输出时每帧做多余查询）
                    var pops = document.querySelectorAll('div[data-slot="popover-content"]');
                    if (pops.length === 0) return;
                    var composer = document.querySelector('.chat-composer-region, .chat-composer-input-surface');
                    if (!composer) return;
                    var cr = composer.getBoundingClientRect();
                    if (cr.width === 0) return;
                    var vw = window.innerWidth;
                    var GUTTER = 8;
                    // 目标区间与输入框一致；输入框贴边时留 8px 兜底
                    var targetLeft = Math.max(GUTTER, cr.left);
                    var targetRight = Math.min(vw - GUTTER, cr.right);
                    var targetWidth = targetRight - targetLeft;
                    if (targetWidth <= 0) return;
                    for (var i = 0; i < pops.length; i++) {
                        var pop = pops[i];
                        if (!isComposerPopover(pop)) continue;
                        var wrapper = pop.closest('[data-radix-popper-content-wrapper]');
                        if (!wrapper) continue;
                        // 先定宽（覆盖任何写死宽度），再实测位置算水平位移
                        var curW = pop.getBoundingClientRect().width;
                        if (Math.abs(curW - targetWidth) > 0.5) {
                            pop.style.setProperty('width', targetWidth + 'px', 'important');
                            pop.style.setProperty('max-width', targetWidth + 'px', 'important');
                        }
                        var rect = pop.getBoundingClientRect();
                        var delta = targetLeft - rect.left;
                        if (Math.abs(delta) < 0.5) continue;   // 已对齐，避免写回触发观察器自激
                        var style = wrapper.style.transform || '';
                        var m = style.match(/translate\((-?[\d.]+)px[,\s]+(-?[\d.]+)px\)/);
                        if (!m) continue;
                        wrapper.style.transform = 'translate(' + (parseFloat(m[1]) + delta) + 'px, ' + m[2] + 'px)';
                    }
                }
                alignComposerPopover();
                var composerPopRAF = null;
                new MutationObserver(function() {
                    if (!composerPopRAF) composerPopRAF = requestAnimationFrame(function(){ composerPopRAF = null; alignComposerPopover(); });
                }).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
                window.addEventListener('resize', function() { alignComposerPopover(); });

                // 10. 侧边抽屉（审查/终端）打开时，在会话标题行最右端补一枚「返回」按钮。
                // 背景：窄屏下抽屉打开时 header 右侧的侧边面板切换按钮会消失（线上版
                // !isSidePaneOpen 才渲染），用户缺少可见的收起/返回入口；抽屉关闭时
                // 远端 header 自带的左上角"返回任务首页"已够用。
                // 因此按钮**仅在抽屉打开时创建**，关闭时移除——不给正常会话页加多余按钮。
                // 判定：遮罩 [data-mobile-side-pane-overlay="true"] 是否处于打开态。
                // ⚠️ 不能用 computed opacity 判定：抽屉开合是 Tailwind class 切换
                // （opacity-100 ↔ pointer-events-none opacity-0）+ 200ms opacity 过渡，
                // 过渡期间 computed opacity 仍是旧值（实测点击打开后 20ms 仍为 0、
                // 120ms 才 0.26），而同步逻辑在 class 变更后一帧就执行，读到的 opacity
                // 必然 <0.1 → 误判"未打开"并放弃创建，且不会重试——这正是"按钮时有时无
                // 甚至根本不出现"的根因。改用**与 class 同步翻转**的 aria-hidden /
                // pointer-events-none 判据（实测打开态 aria-hidden="false" 且无
                // pointer-events-none，关闭态 aria-hidden="true" 且带 pointer-events-none）。
                // 位置：追加到标题行右侧按钮组（h1.closest('header') > div > 第2个子元素）末尾，
                // 随 header 布局自然排在最后，不覆盖/不挤压既有按钮。
                // 点击**只在页面内收起抽屉**，不调用原生返回键分层（见下方 click 处理器注释：
                // 走原生分层会在抽屉已收起时落到"返回任务首页"层，把用户误带回会话列表）。
                (function() {
                    if (window.__zcodeTitleBackInstalled) return;
                    window.__zcodeTitleBackInstalled = true;
                    function isSidePaneActive() {
                        var overlay = document.querySelector('[data-mobile-side-pane-overlay="true"]');
                        if (!overlay) return false;
                        // 只看与 class 同步翻转的信号，不看过渡中的 computed opacity（见上方注释）
                        if (overlay.getAttribute('aria-hidden') === 'true') return false;
                        var cls = overlay.getAttribute('class') || '';
                        if (cls.indexOf('pointer-events-none') >= 0) return false;
                        return true;
                    }
                    function syncTitleBackButton() {
                        var existing = document.getElementById('__zcode_title_back');
                        // 仅抽屉打开时显示；其余情况（含非会话页）一律移除
                        if (!isSidePaneActive()) {
                            if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
                            return;
                        }
                        var h1 = document.querySelector('h1[data-testid="workspace-title"]');
                        if (!h1) {
                            if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
                            return;
                        }
                        var header = h1.closest('header');
                        if (!header) return;
                        var outer = header.querySelector(':scope > div');
                        if (!outer) return;
                        var rightGroup = outer.children[1];
                        if (!rightGroup) return;
                        // 已在正确位置则不重复创建
                        if (existing && existing.parentNode === rightGroup) return;
                        if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
                        var el = document.createElement('button');
                        el.id = '__zcode_title_back';
                        el.setAttribute('aria-label', '返回');
                        el.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5"/><path d="M12 19l-7-7 7-7"/></svg>';
                        el.style.cssText = 'flex:0 0 auto;display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:8px;border:0;background:transparent;color:inherit;cursor:pointer;padding:0;-webkit-tap-highlight-color:transparent;';
                        el.addEventListener('click', function(ev) {
                            ev.preventDefault(); ev.stopPropagation();
                            // 本按钮只在抽屉打开时存在，语义严格等于"收起抽屉"，因此**只在页面内
                            // 收起、绝不交回原生返回键分层**。
                            // 原因：原生返回分层里抽屉收起只占第 1A 层，且该层命中后无条件 return；
                            // 一旦抽屉已收起（连点两下/收起动画期间再点），1A 不再命中，分层会继续
                            // 落到第 5 层"返回任务首页"，把用户直接带回会话列表——这正是实测的 bug。
                            // 收起失败时什么都不做（用户仍可用系统返回键），保证不会误跳列表。
                            function paneStillActive() {
                                var ov = document.querySelector('[data-mobile-side-pane-overlay="true"]');
                                if (!ov) return false;
                                if (ov.getAttribute('aria-hidden') === 'true') return false;
                                var c = ov.getAttribute('class') || '';
                                return c.indexOf('pointer-events-none') < 0;
                            }
                            function closePane() {
                                if (!paneStillActive()) return;   // 已收起：直接结束，不触发任何返回
                                var attempts = 0;
                                (function tryClose() {
                                    if (!paneStillActive()) return;
                                    attempts++;
                                    var ov = document.querySelector('[data-mobile-side-pane-overlay="true"]');
                                    if (!ov) return;
                                    var target = ov.querySelector('button') || ov;
                                    try { target.click(); } catch (err) {}
                                    // 收起过渡约 200ms；仍激活则重试，最多 4 次。
                                    // 连点/动画期间再点都不会误跳列表：每次进入都先判 paneStillActive，
                                    // 已收起即直接返回，全程不调用原生返回键。
                                    if (attempts < 4) setTimeout(tryClose, 260);
                                })();
                            }
                            closePane();
                        }, true);
                        rightGroup.appendChild(el);
                    }
                    syncTitleBackButton();
                    var titleBackRAF = null;
                    new MutationObserver(function() {
                        if (!titleBackRAF) titleBackRAF = requestAnimationFrame(function(){ titleBackRAF = null; syncTitleBackButton(); });
                    }).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class', 'aria-hidden'] });
                })();

                // 11. 任务标题占位兜底。
                // 现象：从任务列表点进一个已存在的任务会话时，顶部标题先显示「新建任务」
                // 占位文案，通常几百毫秒后自行纠正；但实测有概率**永久停在占位文案**
                // （远端在"列表侧 meta 已存在但标题为空"时会跳过异步快照兜底，见
                // useWorkspaceActiveTaskState / useActiveTaskSnapshotMeta）。用户观感即
                // "进入任务后标题变成了新建任务"。
                // ⚠️ 这是远端固有竞态，**不是本 App 注入引起的**：A/B 实测把注入的标题
                // CSS 全部移除后，占位仍每轮出现（3/3，585~832ms）。App 侧只能做显示兜底。
                // 做法：任务列表渲染时缓存 sid→真实标题；会话页用 [data-session-id] 定位
                // 当前任务，h1 文本命中占位文案时用缓存标题替换。
                // ⚠️ 只改**已存在的文本节点** nodeValue，绝不对容器赋 textContent——
                // 远端 h1 由 React 渲染（内部是 <span class="min-w-0 truncate">），赋
                // textContent 会摧毁其子树、让 React 后续更新写进孤儿节点（同
                // stabilizeModelTrigger 踩过的坑）。结构不符时宁可不动。
                var zcodeTaskTitleCache = {};
                var ZCODE_TITLE_PLACEHOLDERS = ['新建任务', '新任务', 'New session', '新建会话'];
                function isPlaceholderTitle(t) {
                    if (!t) return false;
                    for (var i = 0; i < ZCODE_TITLE_PLACEHOLDERS.length; i++) {
                        if (t === ZCODE_TITLE_PLACEHOLDERS[i]) return true;
                    }
                    return false;
                }
                function cacheTaskTitles() {
                    var rows = document.querySelectorAll('[data-testid^="task-item-"]');
                    for (var i = 0; i < rows.length; i++) {
                        var tid = rows[i].getAttribute('data-testid') || '';
                        if (tid.indexOf('task-item-') !== 0) continue;
                        var sid = tid.slice('task-item-'.length);
                        if (!sid) continue;
                        // 行内第一个嵌套 span 是任务标题（更深的 span 是耗时/状态文案）
                        var titleEl = rows[i].querySelector('span span');
                        var t = titleEl ? (titleEl.textContent || '').trim() : '';
                        if (t && !isPlaceholderTitle(t)) zcodeTaskTitleCache[sid] = t;
                    }
                }
                function resolveActiveTaskTitle() {
                    var els = document.querySelectorAll('[data-session-id]');
                    for (var i = 0; i < els.length; i++) {
                        var sid = els[i].getAttribute('data-session-id') || '';
                        if (sid && zcodeTaskTitleCache[sid]) return zcodeTaskTitleCache[sid];
                    }
                    return null;
                }
                function fixTaskTitle() {
                    var h1 = document.querySelector('h1[data-testid="workspace-title"]');
                    if (!h1) return;
                    var span = h1.querySelector('span');
                    if (!span) return;
                    var node = span.firstChild;
                    if (!node || node.nodeType !== 3) return;    // 结构不符：不动
                    var cur = (node.nodeValue || '').trim();
                    if (!isPlaceholderTitle(cur)) return;        // 非占位：不动
                    var real = resolveActiveTaskTitle();
                    if (!real || real === cur) return;
                    node.nodeValue = real;
                }
                cacheTaskTitles();
                fixTaskTitle();
                var titleFixRAF = null;
                new MutationObserver(function() {
                    if (!titleFixRAF) titleFixRAF = requestAnimationFrame(function() {
                        titleFixRAF = null;
                        cacheTaskTitles();
                        fixTaskTitle();
                    });
                }).observe(document.body, { childList: true, subtree: true, characterData: true });

                // 12. 标题行「工作区路径」按钮首次点击只闪一下气泡（不打开）→ 根治。
                // 现象：进入任务会话后**第一次**点击标题行最左侧的文件夹按钮
                // （button[data-testid="workspace-path"]），只闪一下提示气泡、工作区信息
                // 面板并不打开，需再点一次才生效。
                // 实测（真机 992e8e14，2026-09-26）：
                //   - 首次点击后浮层数为 0（面板未打开）；事件序列正常，仅 1 次受信任 click；
                //   - 逐帧看：先以「折叠态」内容闪出（40 字符/288x93/opacity=1），随即淡出；
                //     第二次点击才是展开态（126 字符，含完整路径与「最近活动」）；
                //   - 程序化 btn.click() 首次即成功 → 与触摸序列（pointerdown→focus→click）相关；
                //   - A/B：移除全部注入 CSS 后现象依旧 → **远端固有行为**，非本 App 注入引起。
                // 机制：该按钮被远端 ControlHintTooltip 包裹（open 受 workspaceContextOpen 控制）。
                // 触摸时 **focus 先于 click ~30ms**（实测 focus@4753 / click@4784），focus 先以
                // open=false 打开气泡（折叠态），随后 Radix 在触摸下的 onOpenChange(false)
                // 覆盖掉 onClick 设置的 open=true，于是"闪一下即关"。
                // ⚠️ 早期版本用"补点击"兜底（若未打开则再 click 一次），实测虽能打开但**仍有
                //    双段闪烁**（先闪折叠态→关闭→再打开完整态），治标不治本。
                // 根治：触摸把 open 从 false 翻成 true 的指令是 React 通过 **focusin 委托**
                // 下发的（focus 不冒泡、focusin 冒泡），在**面板尚未打开时**拦截该按钮的
                // focusin，让折叠态根本不出现，首次点击即直接展开完整态（实测 0 帧折叠态、
                // 直接 len=126）。
                // ⚠️ **必须让位"已打开要关闭"的点击**：第二次点击的 focusin 负责把 open 翻回
                //    false，若一律拦截会导致面板关不掉（实测全拦截后第 2 次点击面板仍 open）。
                //    因此只在「面板未打开」时拦截，已打开时放行。
                (function() {
                    if (window.__zcodeWorkspaceBtnFix) return;
                    window.__zcodeWorkspaceBtnFix = true;
                    // 展开态判据：气泡内容含「最近活动」或路径分隔符（折叠态只有工作区名，
                    // 实测折叠 40 字符 vs 展开 126 字符，可靠区分）
                    function workspacePanelOpen() {
                        var w = document.querySelector('[data-radix-popper-content-wrapper]');
                        if (!w) return false;
                        var inner = w.firstElementChild;
                        if (!inner || inner.getAttribute('data-slot') !== 'tooltip-content') return false;
                        var t = inner.textContent || '';
                        return t.indexOf('最近活动') !== -1 || t.indexOf(':\\') !== -1 || t.indexOf(':/') !== -1;
                    }
                    // 用 focusin（React 委托通道）而非 focus：React 在根节点挂 focusin 监听，
                    // focus 本身不冒泡、拦不到；focusin 冒泡，capture 阶段可拦。
                    document.addEventListener('focusin', function(e) {
                        var b = e.target && e.target.closest
                            ? e.target.closest('button[data-testid="workspace-path"]') : null;
                        if (!b) return;
                        if (workspacePanelOpen()) return;   // 已打开：放行，让第 2 次点击能关闭
                        e.stopImmediatePropagation();
                        e.stopPropagation();
                        e.preventDefault();
                    }, true);
                })();

                // 13. 「+」添加上下文弹层不应唤起输入法。
                // 现象（真机 992e8e14，2026-09-26）：点「+」弹出列表时输入法跟着弹出；
                // 在列表里选中一个条目后输入法又弹一次。用户诉求：只有**直接点输入框**
                // 才弹输入法，弹层开合一律不弹。
                // 实测机制：输入框是 contenteditable 的 DIV（`.chat-composer-region` 内）。
                // 键盘打开时视口高度 860→524；此时点「+」，document.activeElement 仍是
                // 那个 contenteditable（`DIV ce=true`）、视口维持 524 —— 即**焦点一直留在
                // 输入框上**，输入法因此保持/重新弹出；而从干净状态（焦点在 BODY）点「+」
                // 则不会弹（实测 vh=860）。所以修复点就是：弹层开合期间不让输入框保持焦点。
                // 做法（保守，不干扰正常输入）：
                //   ① 点「+」时收起输入框焦点；② 弹层打开期间若有间接聚焦输入框则再收起；
                //   ③ 在弹层内选中条目后同样收起。
                // ⚠️ 只处理 `.chat-composer-region / .chat-composer-input-surface` 内的
                //    contenteditable，且**不拦截用户直接点输入框**（用最近一次真实指针目标
                //    判定），避免影响正常打字。
                (function() {
                    if (window.__zcodeComposerImeFix) return;
                    window.__zcodeComposerImeFix = true;
                    var lastPointerTarget = null;
                    document.addEventListener('pointerdown', function(e) { lastPointerTarget = e.target; }, true);
                    document.addEventListener('touchstart', function(e) { lastPointerTarget = e.target; }, true);
                    function composerEditable() {
                        var ae = document.activeElement;
                        if (!ae || !ae.isContentEditable) return null;
                        return ae.closest('.chat-composer-region, .chat-composer-input-surface') ? ae : null;
                    }
                    function blurComposer() {
                        var ed = composerEditable();
                        if (!ed) return;
                        try { ed.blur(); } catch (err) {}
                    }
                    function composerPopoverOpen() {
                        var pops = document.querySelectorAll('div[data-slot="popover-content"]');
                        for (var i = 0; i < pops.length; i++) {
                            var t = pops[i].textContent || '';
                            if (t.indexOf('附件') !== -1) return true;
                        }
                        return false;
                    }
                    // ① 点「+」：收起焦点；Radix 可能在稍后再移动焦点，故延迟补一次
                    document.addEventListener('click', function(ev) {
                        var btn = ev.target && ev.target.closest
                            ? ev.target.closest('button[data-testid="chat-attachment-button"]') : null;
                        if (!btn) return;
                        blurComposer();
                        setTimeout(blurComposer, 120);
                        setTimeout(blurComposer, 320);
                    }, true);
                    // ② 弹层打开期间：仅当焦点不是用户直接点输入框带来的，才收起
                    document.addEventListener('focusin', function(e) {
                        var t = e.target;
                        if (!t || !t.isContentEditable) return;
                        if (!t.closest('.chat-composer-region, .chat-composer-input-surface')) return;
                        if (!composerPopoverOpen()) return;          // 弹层没开：不管
                        if (lastPointerTarget && (lastPointerTarget === t || t.contains(lastPointerTarget))) return;  // 用户直接点：放行
                        try { t.blur(); } catch (err) {}
                    }, true);
                    // ③ 在弹层内选中条目后收起（选中动作不应唤起输入法）
                    document.addEventListener('click', function(ev) {
                        var t = ev.target;
                        if (!t || !t.closest) return;
                        if (!t.closest('div[data-slot="popover-content"]')) return;
                        if (!t.closest('[role="menuitem"],[role="option"],[data-slot*="item"],button')) return;
                        setTimeout(blurComposer, 150);
                    }, true);
                })();

                // 14. （已删除）原"任务列表页 header 标题改写"逻辑——2026-09-26 起网页
                // header 整条 display:none（CSS 规则 20），标题改写与 MutationObserver
                // 持续纠偏失去意义，移除以省一份运行时开销。原生顶部栏的标题/LOGO/主题
                // 按钮由 App 自行渲染（activity_remote_control.xml@layoutNativeTopBar）。

                // 15. dashboard 调色板按钮的 JS 桥：window.zcodeTriggerThemeToggle()。
                // 流程：找到网页里**原生**的调色板 trigger（data-slot="dropdown-menu-trigger"）
                // → 派发完整 pointer 事件序列打开 Radix DropdownMenu → 把菜单位置修正到
                // 注入按钮下方。**菜单打开后不再自动切换主题**，由用户自己点选
                // （2026-09-27 用户反馈"闪一下就消失了"）。
                // ⚠️ 历史沿革：该函数原为「点一下自动切到下一个主题」——当时按钮在原生顶栏、
                //    只是个图标没有配套下拉菜单。2026-09-26 按钮移到 dashboard 后，用户期望
                //    像正常按钮那样弹出菜单自己选，自动切换会让菜单只闪现约 190ms 即关闭
                //    （实测时间线：352ms 出现、544ms 消失、主题被改成下一项）。故移除自动切换。
                // ⚠️ 两条硬约束：
                //   ① 必须用 fireFullClick（完整 pointer 序列），trigger.click() 这种
                //      synthetic untrusted click 无法触发 Radix 打开菜单（实测）；
                //   ② 菜单是 Radix 动态挂载到 body 末尾的 z-[60] div，实测约 875~1205ms
                //      才出现，等待挂载必须用 MutationObserver（见下方）。
                (function() {
                    if (window.zcodeTriggerThemeToggle) return;
                    function fireFullClick(el) {
                        if (!el) return false;
                        var r = el.getBoundingClientRect();
                        var x = r.left + r.width / 2, y = r.top + r.height / 2;
                        ['pointerover','pointerenter','mouseover','mouseenter',
                         'pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(type) {
                            var isPointer = type.indexOf('pointer') === 0;
                            var Ctor = isPointer ? PointerEvent : MouseEvent;
                            try {
                                el.dispatchEvent(new Ctor(type, {
                                    bubbles: true, cancelable: true, view: window,
                                    button: 0, buttons: type.indexOf('down') >= 0 ? 1 : 0,
                                    clientX: x, clientY: y,
                                    pointerType: 'mouse', isPrimary: true
                                }));
                            } catch (e) { /* PointerEvent 老内核可能不支持 */ }
                        });
                        return true;
                    }
                    /**
                     * 把主题菜单移到注入按钮下方。
                     * 根因：Radix 以原生 trigger 的 rect 为锚点定位，而该 trigger 在被规则 4
                     * 隐藏的 header 里，rect 全 0（实测 --radix-popper-anchor-width: 0px、
                     * transform: translate(0px, 1.9px)），菜单因此跑到屏幕左上角。
                     * 做法：菜单挂载后用 MutationObserver 抓到 wrapper，按其自身尺寸把
                     * transform 改写成「注入按钮右对齐、下方 8px」，并同步修正
                     * transform-origin 让展开动画从右上角开始（与原生观感一致）。
                     * ⚠️ 只改 transform、不改 left/top——Radix 内部用 transform 做定位，
                     * 覆盖 left/top 会被其后续写入覆盖掉。
                     */
                    function alignThemeMenu() {
                        var anchor = document.querySelector(
                            'button[aria-label="选择主题"][data-zcode-inject="1"]');
                        if (!anchor) return;
                        // 只挑「主题菜单」那个 wrapper：内部含 role=menuitemradio。
                        // 页面上可能同时有其它 popper wrapper（tooltip/popover），
                        // 直接 querySelector 取第一个会误改别的浮层。
                        var items = document.querySelectorAll('[role="menuitemradio"]');
                        if (!items.length) return;
                        var wrapper = items[0].closest('[data-radix-popper-content-wrapper]');
                        if (!wrapper) return;
                        var a = anchor.getBoundingClientRect();
                        var w = wrapper.getBoundingClientRect();
                        // 尺寸尚未测量出来时先跳过（Radix 首帧可能还是 0），等下次 mutation 再试
                        if (w.width <= 0) return;
                        // 右对齐按钮、垂直贴按钮下方 8px；越界时夹回视口内
                        var left = Math.max(8, Math.min(a.right - w.width, window.innerWidth - w.width - 8));
                        var top = a.bottom + 8;
                        var desired = 'translate(' + Math.round(left) + 'px, ' + Math.round(top) + 'px)';
                        // ⚠️ 自激防护：本函数写在 style 上会再次触发观察器，
                        // 值已就位时直接返回，否则会陷入无限循环
                        if (wrapper.style.transform === desired) return;
                        wrapper.style.setProperty('transform', desired, 'important');
                        wrapper.style.setProperty('--radix-popper-transform-origin', '100% 0px', 'important');
                    }

                    window.zcodeTriggerThemeToggle = function() {
                        // ⚠️ 必须点**原生** trigger（data-slot="dropdown-menu-trigger"），
                        // 不能用 aria-label 全局 querySelector：页面里 aria-label="选择主题"
                        // 有两个，且规则 17 注入的那个也在其中，点到它会递归调用本函数。
                        // 原生 trigger 位于被规则 4 隐藏的 header 里（rect 全 0），
                        // Radix 以它为锚点会把菜单定位到左上角——位置由 alignThemeMenu() 修正。
                        var trigger = document.querySelector(
                            'button[data-slot="dropdown-menu-trigger"][aria-label="选择主题"]')
                            || document.querySelector('button[aria-label="选择主题"]');
                        if (!trigger) return 'no-trigger';
                        fireFullClick(trigger);
                        // 菜单由 Radix 动态挂载（实测约 875~1205ms 后才出现），固定帧数/短延时
                        // 都会错过挂载时机——用 MutationObserver 等 [role=menuitemradio] 出现。
                        // ⚠️ 挂载后不能只纠偏固定几帧就收手：实测 Radix 会在稍后**再次回写**
                        //    transform（真机上出现过纠偏完成后又被写回 (0,2) 左上角的竞态）。
                        //    改为「等菜单挂载 → 只观察该 wrapper 自身的 style 变化 → 位置被
                        //    改写就立即纠正」。alignThemeMenu 内部有「值已就位则 return」的
                        //    自激防护，故观察自身写入不会死循环。
                        // ⚠️ 只观察 wrapper 自身而非整个 body：观察 body 子树会被页面动画的
                        //    style 变化频繁触发，每次都跑 querySelector + getBoundingClientRect，
                        //    开销不必要。
                        var mountObs = new MutationObserver(function() {
                            var items = document.querySelectorAll('[role="menuitemradio"]');
                            if (!items.length) return;
                            var wrapper = items[0].closest('[data-radix-popper-content-wrapper]');
                            if (!wrapper) return;
                            mountObs.disconnect();      // 已找到 wrapper，停止等挂载
                            alignThemeMenu();
                            var styleObs = new MutationObserver(function() {
                                // 菜单已关闭则停止纠偏
                                if (!wrapper.isConnected) {
                                    styleObs.disconnect();
                                    releaseBodyPointerLock();
                                    return;
                                }
                                alignThemeMenu();
                            });
                            styleObs.observe(wrapper, { attributes: true, attributeFilter: ['style'] });
                            // 兜底：用户长时间不选时也要能清理，避免长期观察
                            setTimeout(function() { styleObs.disconnect(); releaseBodyPointerLock(); }, 30000);
                        });
                        mountObs.observe(document.body, { childList: true, subtree: true });
                        setTimeout(function() { mountObs.disconnect(); }, 5000);
                        return 'ok';
                    };

                    /**
                     * 释放 Radix 残留在 body 上的 pointer-events 锁。
                     *
                     * 根因（2026-09-27 实测）：Radix 打开 DropdownMenu 时会给
                     * `document.body` 写入内联样式 `pointer-events: none`（阻止浮层
                     * 外的交互），正常关闭时移除。但若菜单以非标准路径关闭（程序化点击
                     * 菜单项、快速开关等），清理逻辑可能不执行，锁就**永久残留**——
                     * 后果是整个页面不可点击：`elementFromPoint` 全部穿透到 <html>，
                     * 手指触摸落不到按钮上（实测按钮 pointerEvents 计算值为 none），
                     * 表现为「点了菜单闪一下就没了」且之后再点也无效。
                     *
                     * ⚠️ 只在**没有浮层**时才清理：菜单/弹层打开期间 body 的锁是
                     * Radix 有意为之，提前移除会让浮层外点击穿透、行为异常。
                     */
                    function releaseBodyPointerLock() {
                        // 仍有任何 popper 浮层（菜单/下拉/弹层）存在时不清理
                        if (document.querySelector('[data-radix-popper-content-wrapper]')) return;
                        var inline = document.body.style.pointerEvents;
                        if (inline === 'none') {
                            document.body.style.pointerEvents = '';
                        }
                    }
                })();

                // 16. tooltip 白名单（与 CSS 规则 28 配套，2026-09-26 模拟器实测）。
                // CSS 已全局 display:none 所有 div[data-slot="tooltip-content"]——这
                // 会同时屏蔽工作区气泡（dashboard 上点 zcode-mobile-app 等工作区名时
                // 弹出的「最近活动 / 路径」气泡，也是 tooltip-content slot）。这里用
                // MutationObserver 监听 tooltip-content 出现，内容含「最近活动」标题
                // 或路径分隔符「:\\」「:/」时清空 display 恢复显示。
                // ⚠️ 不能依赖"按 trigger 位置反查"——tooltip 通过 popper wrapper 挂到
                // body 末尾，DOM 上不在 trigger 旁边，只能靠内容特征区分。
                (function() {
                    if (window.__zcodeTooltipWhitelist) return;
                    window.__zcodeTooltipWhitelist = true;
                    function restore(el) {
                        var t = el.textContent || '';
                        if (t.indexOf('最近活动') !== -1 ||
                            t.indexOf(':\\') !== -1 ||
                            t.indexOf(':/') !== -1) {
                            // 用 setProperty('display','block','important') 而非
                            // setProperty('display','','important')——空值不一定能
                            // 覆盖外部 CSS 的 display:none !important（实测无效）。
                            el.style.setProperty('display', 'block', 'important');
                        }
                    }
                    function scan() {
                        var tips = document.querySelectorAll('div[data-slot="tooltip-content"]');
                        for (var i = 0; i < tips.length; i++) restore(tips[i]);
                    }
                    new MutationObserver(scan).observe(document.body, { childList: true, subtree: true });
                    scan();
                })();

                // 17. dashboard 标题行注入「选择主题」按钮（2026-09-26 需求）。
                // 用户反馈原生 App 顶部栏的调色板按钮位置不合适，要求移到 dashboard
                // 的「当前设备上的工作区和任务」标题行右侧——与「折叠全部工作区 /
                // 整理任务 / 刷新工作区和任务」三个按钮同一行。
                // 实现：找到按钮组容器 `div.mt-4.flex.items-start > div.flex.shrink-0.items-center`
                // （精确特征是内部含 `button[aria-label="刷新工作区和任务"]`），在其末尾
                // append 一个 class/SVG 完全复用现有按钮的 <button aria-label="选择主题">。
                // 点击调规则 15 的 zcodeTriggerThemeToggle() 打开主题菜单（由用户自己选）。
                // ⚠️ SPA 重渲染会重建按钮组（如切换任务/工作区后），需 MutationObserver
                //    持续监听并补注入；observer 回调里发现按钮已存在则跳过，避免重复。
                // ⚠️ 两种模式都注入：网页 header 被规则 20 整条隐藏（含它自带的调色板
                //    按钮），所以无论选「远程原生」还是「移动适配」，都需要把这枚按钮
                //    补到标题行按钮组里，否则用户在该页无法切换主题。
                (function() {
                    if (window.__zcodeThemeBtnInject) return;
                    window.__zcodeThemeBtnInject = true;
                    var BTN_CLASS = 'group/button inline-flex shrink-0 items-center justify-center ' +
                        'rounded-md border border-transparent bg-clip-padding text-ui-base/relaxed ' +
                        'whitespace-nowrap transition-colors outline-none select-none ' +
                        'disabled:pointer-events-none disabled:opacity-50 ' +
                        'aria-invalid:border-destructive aria-invalid:ring-2 aria-invalid:ring-destructive/20 ' +
                        'dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 ' +
                        '[&_svg]:pointer-events-none [&_svg]:shrink-0 text-foreground ' +
                        'hover:bg-hover hover:text-foreground aria-expanded:bg-hover aria-expanded:text-foreground ' +
                        'size-6 [&_svg:not([class*="size-"])]:size-3';
                    var PALETTE_SVG =
                        '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" ' +
                        'fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" ' +
                        'stroke-linejoin="round" class="lucide lucide-palette size-3.5" aria-hidden="true">' +
                        '<path d="M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z"></path>' +
                        '<circle cx="13.5" cy="6.5" r=".5" fill="currentColor"></circle>' +
                        '<circle cx="17.5" cy="10.5" r=".5" fill="currentColor"></circle>' +
                        '<circle cx="6.5" cy="12.5" r=".5" fill="currentColor"></circle>' +
                        '<circle cx="8.5" cy="7.5" r=".5" fill="currentColor"></circle></svg>';
                    function findRowGroup() {
                        // 通过「刷新工作区和任务」按钮反查按钮组容器
                        var refresh = document.querySelector('button[aria-label="刷新工作区和任务"]');
                        return refresh ? refresh.parentElement : null;
                    }
                    function inject() {
                        var grp = findRowGroup();
                        if (!grp) return false;
                        // 兜底：清理 Radix 残留在 body 上的 pointer-events 锁。
                        // 该锁若残留会让**整页不可点击**（触摸穿透到 <html>），
                        // 连本按钮都点不到，因此必须在每次注入时顺手检查。
                        // 仍有浮层时不清理（锁是 Radix 有意为之）。
                        if (!document.querySelector('[data-radix-popper-wrapper], [data-radix-popper-content-wrapper]')) {
                            if (document.body.style.pointerEvents === 'none') {
                                document.body.style.pointerEvents = '';
                            }
                        }
                        // 已注入过且仍连着 DOM 则跳过
                        if (grp.querySelector('button[aria-label="选择主题"][data-zcode-inject="1"]')) return true;
                        // 旧逻辑可能注入到别处（header 已隐藏），先清掉
                        var orphans = document.querySelectorAll('button[aria-label="选择主题"][data-zcode-inject="1"]');
                        for (var k = 0; k < orphans.length; k++) orphans[k].remove();
                        var btn = document.createElement('button');
                        btn.setAttribute('type', 'button');
                        btn.setAttribute('aria-label', '选择主题');
                        btn.setAttribute('data-zcode-inject', '1');
                        btn.setAttribute('data-slot', 'button');
                        btn.setAttribute('data-variant', 'ghost');
                        btn.setAttribute('data-size', 'icon-sm');
                        btn.className = BTN_CLASS;
                        // ⚠️ 显式恢复可命中：CSS 规则 3 的 `button > *` 穿透规则本意是让
                        // 图标内元素不拦截点击，但它作用范围广；实测本按钮的
                        // computed pointer-events 曾为 none（触摸落不到按钮上），
                        // 这里按最高优先级把按钮自身钉回 auto。
                        btn.style.setProperty('pointer-events', 'auto', 'important');
                        btn.innerHTML = PALETTE_SVG;
                        btn.addEventListener('click', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            if (window.zcodeTriggerThemeToggle) window.zcodeTriggerThemeToggle();
                        });
                        grp.appendChild(btn);
                        return true;
                    }
                    inject();
                    new MutationObserver(function() { inject(); })
                        .observe(document.body, { childList: true, subtree: true });
                })();

                // 5. （已删除，2026-09-26）原"输入框聚焦自动滚动居中"规则——
                // 在模型设置供应商详情页点 Base URL/API Key 输入框时，
                // scrollIntoView({block:'center'}) 会把整个供应商模块往上推 45px+，
                // 用户反馈"模块不要上移"。键盘避让已由原生 setupKeyboardInsets
                // （动态调整 WebView bottomMargin）统一处理，网页层不再 scroll。

                // 18. 模态框打开时屏蔽自动 focus（2026-09-26 需求，模拟器实测）。
                // 在模型设置点「编辑模型配置」（铅笔图标）→ 编辑模态框（[role="dialog"]）
                // 弹出瞬间，远端 React 会调 HTMLElement.focus() 把焦点设到第一个可交互
                // 元素。两种情况：
                // ① 若第一个可交互元素是 INPUT/TEXTAREA/contenteditable（实测是上下文
                //    窗口大小 input type=text value="500000"）→ 真机上立即弹出系统输入法。
                // ② 若第一个可交互元素是 button[data-slot="popover-trigger"]（如模态框
                //    里"智能配置"旁的「?」按钮 aria-label="智能配置说明"）→ Radix
                //    Popover 的 trigger 在 focus 时会自动展开 popover（实测弹出
                //    「根据模型 ID、Base URL 和 API 格式...」说明气泡），用户没点
                //    「?」却被迫看到气泡。
                // 处理：hook HTMLElement.prototype.focus——若调用方目标在 [role="dialog"]
                // 内是 INPUT/TEXTAREA/contenteditable 或 button[data-slot="popover-trigger"]，
                // 且**没有对应的用户指针行为**（500ms 内 pointerdown/touchstart/mousedown
                // 落在该元素或 dialog 内），**直接 return 不调原 focus**。focus() 无返回
                // 值，React 不知道被拦。
                // 用户在模态框里**主动点输入框/按钮**仍能正常 focus + 触发对应行为——
                // pointerdown 的 target 会被记录，hook 命中"有对应指针行为"分支直接放行。
                // ⚠️ 不用 blur 方案——blur 会触发 focusout/blur 事件，React 的 autoFocus
                //    状态机可能感知到焦点丢失后再次 focus，形成"拦了又被重设"的死循环。
                //    hook focus 直接拒绝调用，对 React 来说"focus 调用没产生任何事件"，
                //    状态机以为已经聚焦成功（虽然实际没），但不会重试。
                (function() {
                    if (window.__zcodeModalAutofocusFix) return;
                    window.__zcodeModalAutofocusFix = true;
                    var lastPointerTarget = null;
                    var lastPointerTime = 0;
                    function recordPointer(e) {
                        lastPointerTarget = e.target;
                        lastPointerTime = Date.now();
                    }
                    document.addEventListener('pointerdown', recordPointer, true);
                    document.addEventListener('touchstart', recordPointer, true);
                    document.addEventListener('mousedown', recordPointer, true);

                    var origFocus = HTMLElement.prototype.focus;
                    HTMLElement.prototype.focus = function(options) {
                        var ae = this;
                        var tag = ae.tagName;
                        var isInput = tag === 'INPUT' || tag === 'TEXTAREA' || ae.isContentEditable;
                        var isPopoverTrigger = tag === 'BUTTON' && ae.getAttribute &&
                            ae.getAttribute('data-slot') === 'popover-trigger';
                        if (isInput || isPopoverTrigger) {
                            var dlg = ae.closest && ae.closest('[role="dialog"]');
                            if (dlg) {
                                // 用户手势：500ms 内有 pointerdown 落在 ae 内或 dialog 内
                                var isUserGesture = false;
                                if (lastPointerTarget && (Date.now() - lastPointerTime) < 500) {
                                    if (lastPointerTarget === ae || ae.contains(lastPointerTarget) ||
                                        (lastPointerTarget.closest && lastPointerTarget.closest('[role="dialog"]') === dlg)) {
                                        isUserGesture = true;
                                    }
                                }
                                if (!isUserGesture) {
                                    // 程序化 auto focus → 拒绝，不调原 focus
                                    return;
                                }
                            }
                        }
                        return origFocus.call(this, options);
                    };
                })();
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    /**
     * 按当前设置重新注入页面适配样式（供「设置页返回」等场景调用，无需重载页面）。
     * 主要用途：用户在原生设置页切换「工作区与任务页样式」后，立即让远程页生效。
     */
    fun reapplyPageAdaptation(webView: WebView) {
        injectAntiMisoperation(webView)
    }

    /**
     * 注入设置中心详情页的悬浮返回按钮。
     * 实测（2026-08-23 基于 v4 设置中心验证）：插件/MCP/技能等模块的条目详情页
     * 顶部只有只读面包屑（nav 带 pointer-events-none，父级还是 [app-region:drag]
     * 窗口拖拽区），没有任何可点击的返回入口，进入详情后无法回到列表模块。
     * 方案：MutationObserver 侦测"设置路径"面包屑（nav[aria-label="设置路径"]，
     * 含两个层级如 插件 > Commit Commands）出现，出现时在右上角？帮助图标左侧
     * 显示一枚固定定位的返回按钮，点击时触发面包屑第一级按钮（如"插件"）的
     * 原生 click —— 实测该按钮带真实点击处理，点击即返回列表。
     * 面包屑一级按钮是唯一可靠的返回入口（history.back 对 SPA 内部导航不生效），
     * 因此按钮的点击逻辑直接复用面包屑一级按钮，不自己实现路由。
     * 注意：必须用 aria-label="设置路径" 精确定位，遍历所有 nav 匹配"含2层且非
     * 侧边栏"太宽松，会误命中对话问题导航等其它 nav 导致点击错按钮。
     */
    private fun injectDetailBackButton(webView: WebView) {
        val js = """
            (function() {
                if (window.__zcodeDetailBackInjected) return;
                window.__zcodeDetailBackInjected = true;

                var backBtn = document.createElement('button');
                backBtn.id = '__zcode_detail_back';
                backBtn.setAttribute('aria-label', '返回列表');
                backBtn.style.cssText = [
                    'position: fixed',
                    'display: none',
                    'align-items: center',
                    'justify-content: center',
                    'width: 32px',
                    'height: 32px',
                    'top: 6px',
                    'right: 44px',
                    'border-radius: 8px',
                    'border: 1px solid rgba(255,255,255,0.15)',
                    'background: rgba(60,64,70,0.75)',
                    'color: #e5e5e5',
                    'cursor: pointer',
                    'z-index: 99999',
                    '-webkit-tap-highlight-color: transparent'
                ].join(';');
                backBtn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5"/><path d="M12 19l-7-7 7-7"/></svg>';
                document.body.appendChild(backBtn);

                // 触发面包屑第一级按钮的原生 click（远端真实返回入口）。
                // 面包屑的唯一可靠标识是 nav[aria-label="设置路径"]（如"插件 > Commit
                // Commands"）；遍历所有 nav 匹配"含2层且非侧边栏"太宽松，会误命中其他
                // 导航（如对话问题导航）导致点击错按钮，必须用 aria-label 精确定位
                function goBack() {
                    var crumb = document.querySelector('nav[aria-label="设置路径"]') ||
                                document.querySelector('nav[aria-label*="路径"]');
                    if (crumb) {
                        var items = Array.from(crumb.querySelectorAll('button'));
                        if (items.length > 0 && items[0].getBoundingClientRect().width > 0) {
                            items[0].click();
                            return true;
                        }
                    }
                    return false;
                }
                backBtn.addEventListener('click', goBack);

                var checkRAF = null;
                function check() {
                    checkRAF = null;
                    // 详情页/二级新建页判定：设置路径面包屑存在且含父级按钮与当前层级
                    var crumb = document.querySelector('nav[aria-label="设置路径"]') ||
                                document.querySelector('nav[aria-label*="路径"]');
                    var inDetail = false;
                    if (crumb) {
                        var btns = crumb.querySelectorAll('button');
                        var cur = crumb.querySelector('[aria-current="page"]');
                        var items = crumb.querySelectorAll('li');
                        inDetail = (btns.length > 0 && cur !== null) || items.length >= 2 || (crumb.innerText || '').split('\n').length >= 2;
                    }
                    backBtn.style.display = inDetail ? 'inline-flex' : 'none';
                }
                check();
                new MutationObserver(function() {
                    if (!checkRAF) checkRAF = requestAnimationFrame(check);
                }).observe(document.body, { childList: true, subtree: true });

                // 5. 使用统计「Token 活动」点阵：初始滚动到最右端（最新活动记录）。
                // 规则 13 让点阵在窄屏下于共同父级内横向滚动，但初始停在左端——
                // 历史月份全是灰点，蓝色活动点集中在右端最近两个月，用户会误以为
                // 没有数据。点阵出现时把滚动父级滚到最右；data 标记防重入，之后
                // 不干预用户手动滑动，切换 每日/每周/累计 导致点阵重建时重新定位。
                var heatmapRAF = null;
                function fixHeatmapScroll() {
                    heatmapRAF = null;
                    var grids = document.querySelectorAll('div.grid[class*="gap-x-0.5"]');
                    for (var i = 0; i < grids.length; i++) {
                        var p = grids[i].parentElement;
                        if (!p) continue;
                        if (p.getAttribute('data-heatmap-end') !== '1' && p.scrollWidth > p.clientWidth + 10) {
                            p.scrollLeft = p.scrollWidth;
                            p.setAttribute('data-heatmap-end', '1');
                        }
                    }
                }
                fixHeatmapScroll();
                new MutationObserver(function() {
                    if (!heatmapRAF) heatmapRAF = requestAnimationFrame(fixHeatmapScroll);
                }).observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }
}
