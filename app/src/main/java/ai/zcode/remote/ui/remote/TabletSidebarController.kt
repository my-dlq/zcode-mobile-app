package ai.zcode.remote.ui.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.widget.ImageButton
import ai.zcode.remote.R
import ai.zcode.remote.utils.ToastUtils

/** 平板侧栏按钮复用远程页面的 Ctrl+B；布局宽度和侧栏内容仍由远程页面管理。 */
class TabletSidebarController(
    private val context: Context,
    private val webView: WebView,
    private val topBar: View,
    private val topBarButton: ImageButton,
    private val overlayButton: ImageButton,
    private val isPageAvailable: () -> Boolean,
) {
    private val enabled = context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SW_DP
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var connectionId = ""
    private var desiredCollapsed = false
    private var actualCollapsed: Boolean? = null
    private var restorePending = true
    private var restoreAttempts = 0
    private var generation = 0L
    private var queryToken = 0L
    private var queryBusy = false
    private var toggling = false
    private var disposed = false

    init {
        topBarButton.setOnClickListener { toggleByUser() }
        overlayButton.setOnClickListener { toggleByUser() }
        updateButtons()
    }

    fun bindConnection(id: String) {
        connectionId = id
        desiredCollapsed = id.isNotBlank() && preferences.getBoolean(id, false)
        resetPage()
    }

    fun onPageStarted() = resetPage()

    fun onPageFinished() {
        if (!enabled) return
        val currentGeneration = generation
        handler.postDelayed({
            if (currentGeneration == generation) probe()
        }, PAGE_READY_DELAY_MS)
    }

    fun onForegroundTick() = probe()

    fun onTopBarVisibilityChanged() = updateButtons()

    fun onPageUnavailable() = resetPage()

    fun dispose() {
        disposed = true
        generation++
        queryToken++
        handler.removeCallbacksAndMessages(null)
        actualCollapsed = null
        updateButtons()
    }

    private fun resetPage() {
        generation++
        queryToken++
        queryBusy = false
        toggling = false
        restorePending = true
        restoreAttempts = 0
        actualCollapsed = null
        updateButtons()
    }

    private fun probe() {
        if (!enabled || disposed || toggling || queryBusy || !isPageAvailable()) return
        queryState { collapsed ->
            actualCollapsed = collapsed
            updateButtons()
            if (collapsed == null) {
                // 设置页、登录页及远程移动布局不应继承上一次宽屏工作区的状态。
                restorePending = true
                restoreAttempts = 0
                return@queryState
            }
            if (restorePending) {
                if (collapsed == desiredCollapsed) {
                    restorePending = false
                } else if (restoreAttempts < MAX_RESTORE_ATTEMPTS) {
                    restoreAttempts++
                    toggleRemote(collapsed, restoring = true)
                }
            } else if (collapsed != desiredCollapsed) {
                // 外接键盘或远程命令面板也可能切换侧栏，跟随页面的真实状态保存。
                saveState(collapsed)
            }
        }
    }

    private fun toggleByUser() {
        if (!enabled || disposed || toggling || !isPageAvailable()) return
        queryState { before ->
            if (before == null) {
                actualCollapsed = null
                updateButtons()
                return@queryState
            }
            actualCollapsed = before
            toggleRemote(before, restoring = false)
        }
    }

    private fun toggleRemote(before: Boolean, restoring: Boolean) {
        toggling = true
        updateButtons()
        val currentGeneration = generation
        val now = SystemClock.uptimeMillis()
        webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 0, KeyEvent.META_CTRL_ON))
        webView.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B, 0, KeyEvent.META_CTRL_ON))
        handler.postDelayed({
            if (currentGeneration != generation || disposed || !isPageAvailable()) return@postDelayed
            queryState { after ->
                toggling = false
                actualCollapsed = after
                updateButtons()
                if (after == null || after == before) {
                    if (restoring) {
                        handler.postDelayed({ probe() }, RESTORE_RETRY_DELAY_MS)
                    } else {
                        ToastUtils.show(context, context.getString(R.string.sidebar_toggle_failed))
                    }
                    return@queryState
                }
                if (restoring) {
                    restorePending = after != desiredCollapsed
                } else {
                    restorePending = false
                    saveState(after)
                }
            }
        }, TOGGLE_VERIFY_DELAY_MS)
    }

    private fun queryState(onResult: (Boolean?) -> Unit) {
        if (queryBusy || disposed || !isPageAvailable()) return
        queryBusy = true
        updateButtons()
        val token = ++queryToken
        val currentGeneration = generation
        val currentConnection = connectionId
        webView.evaluateJavascript(PROBE_JS) { value ->
            if (token != queryToken || currentGeneration != generation ||
                currentConnection != connectionId || disposed || !isPageAvailable()) return@evaluateJavascript
            queryBusy = false
            updateButtons()
            onResult(when (value?.trim('"')) {
                "closed" -> true
                "open" -> false
                else -> null
            })
        }
    }

    private fun saveState(collapsed: Boolean) {
        desiredCollapsed = collapsed
        if (connectionId.isNotBlank()) preferences.edit().putBoolean(connectionId, collapsed).apply()
    }

    private fun updateButtons() {
        val shown = enabled && !disposed && actualCollapsed != null && isPageAvailable()
        topBarButton.visibility = if (shown && topBar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        overlayButton.visibility = if (shown && topBar.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        val label = if (actualCollapsed == true) R.string.sidebar_expand else R.string.sidebar_collapse
        val icon = if (actualCollapsed == true) R.drawable.ic_sidebar_expand else R.drawable.ic_sidebar_collapse
        for (button in arrayOf(topBarButton, overlayButton)) {
            button.contentDescription = context.getString(label)
            button.setImageResource(icon)
            button.isEnabled = !toggling && !queryBusy
        }
    }

    companion object {
        private const val PREFS = "zcode_tablet_sidebar"
        private const val TABLET_MIN_SW_DP = 600
        private const val PAGE_READY_DELAY_MS = 500L
        private const val TOGGLE_VERIFY_DELAY_MS = 350L
        private const val RESTORE_RETRY_DELAY_MS = 500L
        private const val MAX_RESTORE_ATTEMPTS = 3

        // 官方 v4 宽屏工作区会保留此 panel，并用 aside 的 aria-hidden 表示收起状态。
        // 只读 DOM，不读取任务或账号；768px 与远程页面的 max-md 分界保持一致。
        private val PROBE_JS = """
            (function() {
                if (!window.matchMedia('(min-width: 768px)').matches) return 'unavailable';
                var settings = document.querySelector('[data-testid="settings-page"]');
                if (settings && settings.getClientRects().length > 0 &&
                    window.getComputedStyle(settings).visibility !== 'hidden') return 'unavailable';
                var shell = document.querySelector('[data-workspace-shell="true"]');
                var panel = shell && shell.querySelector('[data-workspace-sidebar-panel="true"]');
                var aside = panel && panel.querySelector('aside');
                if (!aside || shell.getBoundingClientRect().width <= 0) return 'unavailable';
                return aside.getAttribute('aria-hidden') === 'true' ||
                    panel.getBoundingClientRect().width < 2 ? 'closed' : 'open';
            })()
        """.trimIndent()
    }
}
