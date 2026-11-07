package ai.zcode.remote.ui.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView
import ai.zcode.remote.R
import ai.zcode.remote.ui.remote.web.WorkspaceSidebarButtonScript
import ai.zcode.remote.ui.remote.web.WorkspaceSidebarViewport

/** 平板与横屏手机在远程页面内部显示侧栏按钮，侧栏状态仍由远程页面管理。 */
class WorkspaceSidebarController(
    private val context: Context,
    private val webView: WebView,
    private val isPageAvailable: () -> Boolean,
) {
    private val enabled: Boolean
        get() = WorkspaceSidebarViewport.isEligible(context.resources.configuration)
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var connectionId = ""
    private var desiredCollapsed = false
    private var restorePending = true
    private var restoreAttempts = 0
    private var generation = 0L
    private var queryToken = 0L
    private var queryBusy = false
    private var toggling = false
    private var disposed = false

    fun bindConnection(id: String) {
        removeButton()
        connectionId = id
        desiredCollapsed = id.isNotBlank() && preferences.getBoolean(id, false)
        resetPage()
    }

    fun onPageStarted() {
        resetPage()
        removeButton()
    }

    fun onPageFinished() {
        val currentGeneration = generation
        handler.postDelayed({
            if (currentGeneration == generation) probe()
        }, PAGE_READY_DELAY_MS)
    }

    fun onForegroundTick() = probe()

    fun onConfigurationChanged() {
        resetPage()
        removeButton()
    }

    fun onPageUnavailable() {
        resetPage()
        removeButton()
    }

    fun dispose() {
        removeButton()
        disposed = true
        generation++
        queryToken++
        handler.removeCallbacksAndMessages(null)
    }

    private fun resetPage() {
        generation++
        queryToken++
        queryBusy = false
        toggling = false
        restorePending = true
        restoreAttempts = 0
    }

    private fun probe() {
        if (disposed || toggling || queryBusy || !isPageAvailable()) return
        queryState { collapsed ->
            if (collapsed == null) {
                // 设置页、登录页及手机竖屏不继承上一次宽屏工作区的状态。
                restorePending = true
                restoreAttempts = 0
                return@queryState
            }
            if (restorePending) {
                if (collapsed == desiredCollapsed) {
                    restorePending = false
                } else if (restoreAttempts < MAX_RESTORE_ATTEMPTS) {
                    restoreAttempts++
                    toggleRemote(collapsed)
                }
            } else if (collapsed != desiredCollapsed) {
                // 外接键盘或远程命令面板也可能切换侧栏，跟随页面的真实状态保存。
                saveState(collapsed)
            }
        }
    }

    private fun toggleRemote(before: Boolean) {
        toggling = true
        val currentGeneration = generation
        val now = SystemClock.uptimeMillis()
        webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 0, KeyEvent.META_CTRL_ON))
        webView.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B, 0, KeyEvent.META_CTRL_ON))
        handler.postDelayed({
            if (currentGeneration != generation || disposed || !isPageAvailable()) return@postDelayed
            queryState { after ->
                toggling = false
                if (after == null || after == before) {
                    handler.postDelayed({ probe() }, RESTORE_RETRY_DELAY_MS)
                    return@queryState
                }
                restorePending = after != desiredCollapsed
            }
        }, TOGGLE_VERIFY_DELAY_MS)
    }

    private fun queryState(onResult: (Boolean?) -> Unit) {
        if (queryBusy || disposed || !isPageAvailable()) return
        queryBusy = true
        val token = ++queryToken
        val currentGeneration = generation
        val currentConnection = connectionId
        webView.evaluateJavascript(
            WorkspaceSidebarButtonScript.probe(
                enabled,
                context.getString(R.string.sidebar_collapse),
                context.getString(R.string.sidebar_expand),
            ),
        ) { value ->
            if (token != queryToken || currentGeneration != generation ||
                currentConnection != connectionId || disposed || !isPageAvailable()) return@evaluateJavascript
            queryBusy = false
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

    private fun removeButton() {
        if (!disposed && isPageAvailable()) {
            webView.evaluateJavascript(WorkspaceSidebarButtonScript.remove, null)
        }
    }

    companion object {
        private const val PREFS = "zcode_tablet_sidebar"
        private const val PAGE_READY_DELAY_MS = 500L
        private const val TOGGLE_VERIFY_DELAY_MS = 350L
        private const val RESTORE_RETRY_DELAY_MS = 500L
        private const val MAX_RESTORE_ATTEMPTS = 3
    }
}
