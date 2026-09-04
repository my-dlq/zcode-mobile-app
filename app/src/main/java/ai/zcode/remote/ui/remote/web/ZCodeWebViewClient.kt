package ai.zcode.remote.ui.remote.web

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

class ZCodeWebViewClient(
    private val onPageStart: () -> Unit,
    private val onPageFinish: (url: String) -> Unit,
    private val onPageError: (errorCode: Int, description: String) -> Unit
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
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.let { injectAntiMisoperation(it) }
        // 详情页返回按钮同样在每次页面加载后注入：设置中心内是 SPA 导航，
        // 但长会话/切回工作区等操作可能触发整页加载，注入会随 DOM 重建丢失，
        // 仅依赖 openWorkspaceSettings 成功回调注入一次不够（实测用户进详情
        // 页时按钮缺失即由此引起）
        view?.let { injectDetailBackButton(it) }
        onPageFinish(url ?: "")
    }

    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
        val didCrash = detail?.didCrash() == true
        android.util.Log.e("ZCodeWeb", "onRenderProcessGone: didCrash=$didCrash")
        onPageError(-100, if (didCrash) "页面渲染进程异常，请点击重试" else "系统内存不足回收了页面，请点击重试")
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
                    // 导致页面顶部出现大片背景空白，且整体呈电脑端样式
                    try {
                        var m = document.querySelector('meta[name="viewport"]');
                        if (m) {
                            m.setAttribute('content',
                                'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no');
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
        val viewportContent = if (isDesktop) {
            "width=1280, initial-scale=0.32, user-scalable=yes"
        } else {
            "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
        }
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
               默认 14px/32px，二级模型菜单从供应商条目左侧展开，容易被底部
               输入框或屏幕边缘遮挡。只作用于 z-[60] 的模型选择弹层，不影响聊天
               消息、输入框和设置中心页面；menuitemradio 保留右侧选中标记空间。 */
            div[role="menu"].z-\\[60\\] {
                max-width: calc(100vw - 16px) !important;
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

            /* 任务会话底部"上下文容量"悬浮卡（Radix HoverCard）字体与模型菜单
               一致：label 为 text-ui-base 14px、数值为 text-ui-sm，统一 12px/18px。
               限定 data-slot="hover-card-content"，不影响聊天消息与输入框。 */
            div[data-slot="hover-card-content"],
            div[data-slot="hover-card-content"] * {
                font-size: 12px !important;
                line-height: 18px !important;
            }
            /* 弹层默认右缘贴死屏幕边缘（x=92 宽 320 @视口 412），整体左移 46px 后
               水平居中、左右留白均衡；transform 无需与浮层 wrapper 的 inline 定位耦合 */
            div[data-slot="hover-card-content"] {
                transform: translateX(-46px) !important;
            }

            /* 思考等级与权限控制使用 Radix Select listbox；
               统一字体大小与模型选择列表一致（主标题 12px/16px，副描述 10.5px/14px）；
               调小条目上下间隙并收窄左右宽度至 180~200px。 */
            div[role="listbox"].z-\\[60\\],
            div[data-slot="select-content"] {
                max-width: 200px !important;
                min-width: 170px !important;
                width: auto !important;
                max-height: calc(100vh - 16px) !important;
                overflow-x: hidden !important;
                overflow-y: auto !important;
                font-size: 12px !important;
                line-height: 16px !important;
                padding: 4px !important;
            }
            div[role="listbox"].z-\\[60\\] [role="option"],
            div[data-slot="select-content"] [data-slot="select-item"] {
                min-height: 32px !important;
                height: auto !important;
                padding: 3px 22px 3px 6px !important;
                font-size: 12px !important;
                line-height: 16px !important;
                box-sizing: border-box !important;
                margin-bottom: 1px !important;
            }
            div[role="listbox"].z-\\[60\\] [role="option"]:last-child,
            div[data-slot="select-content"] [data-slot="select-item"]:last-child {
                margin-bottom: 0 !important;
            }
            div[role="listbox"].z-\\[60\\] [role="option"] span[class*="truncate"],
            div[data-slot="select-content"] [data-slot="select-item"] span[class*="truncate"] {
                font-size: 12px !important;
                line-height: 16px !important;
            }
            div[role="listbox"].z-\\[60\\] [role="option"] span[class*="line-clamp"],
            div[data-slot="select-content"] [data-slot="select-item"] span[class*="line-clamp"] {
                font-size: 10.5px !important;
                line-height: 14px !important;
                margin-top: 0 !important;
                opacity: 0.75 !important;
            }

            /* 16. 每日 Token 趋势图日期标签由 Recharts 生成在 SVG 中，移动端
               默认显示“7月26日”且字号偏大，多个日期会挤在一起。JS 将其格式化
               为“7.26”，这里同步缩小坐标轴字号；只匹配 Recharts 日期 tick。 */
            svg text.recharts-cartesian-axis-tick-value {
                font-size: 10px !important;
            }

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

            /* 5. 优雅纯净的原生级按压反馈（轻柔透明度过渡，无任何突兀蓝色闪烁） */
            button:active, [role="button"]:active, [role="tab"]:active, a:active {
                opacity: 0.75 !important;
                transform: scale(0.96) !important;
                transition: transform 0.05s ease, opacity 0.05s ease !important;
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
               解决 Floating-UI 测量两阶段（第一帧在上方闪现、第二帧掉到下方）的翻转闪烁问题：
               - 开启 GPU 硬件加速并约束 transform 平滑渲染；
               - 抑制未就绪时的突变跳跃动画。 */
            [data-radix-popper-content-wrapper] {
                will-change: transform, opacity !important;
            }
            [data-radix-popper-content-wrapper] [data-slot="popover-content"],
            [data-radix-popper-content-wrapper] [data-slot="dropdown-menu-content"],
            [data-radix-popper-content-wrapper] [role="menu"],
            [data-radix-popper-content-wrapper] [role="dialog"] {
                transform-origin: top !important;
                backface-visibility: hidden !important;
                -webkit-backface-visibility: hidden !important;
            }
        """.trimIndent().replace("\n", " ").replace("\"", "\\\"")

        val js = """
            (function() {
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
                                        if (clickable.matches('button[role="switch"], [role="switch"], [data-slot*="collapsible"], [data-slot*="trigger"], [data-slot*="accordion"], [aria-expanded], details summary') ||
                                            clickable.closest('button[role="switch"], [role="switch"], [data-slot*="collapsible"], [data-slot*="trigger"], [data-slot*="accordion"], [aria-expanded], details summary')) return;
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

                // 5. 设置中心与表单输入框聚焦时自动平滑滚动居中（上探至键盘正上方，排除任务聊天输入框）
                document.addEventListener('focusin', function(e) {
                    var target = e.target;
                    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
                        if (!target.closest('.chat-composer-region, .chat-composer-input-surface')) {
                            setTimeout(function() {
                                try {
                                    target.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                } catch(err) {}
                            }, 300);
                        }
                    }
                }, true);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
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
