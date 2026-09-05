package ai.zcode.remote.ui.remote.event

/**
 * 会话跳转注入脚本：在远程页面加载完成后，通过 data-testid 匹配 taskId
 * 对应的任务条目并点击进入会话。远端页面的任务条目以
 * `data-testid="task-item-<taskId>"` 标记，脚本先精确查找；找不到时
 * 逐级展开工作区分组（aria-expanded）再重试，最多尝试 20 秒。
 *
 * 参考同类远程客户端的 session_jump 实现，改为面向 Android WebView
 * evaluateJavascript 的原生注入。
 */
object SessionJumpScript {

    fun build(taskId: String): String {
        // JSON 转义防注入
        val tid = taskId.replace("\\", "\\\\").replace("'", "\\'")
        return """
(function() {
    var tid = '$tid';
    if (!tid) return;
    var GEN = '__zcodeJumpGen';
    var myGen = (window[GEN] = (window[GEN] || 0) + 1);
    var stale = function() { return window[GEN] !== myGen; };
    var deadline = Date.now() + 20000;
    var tried = new WeakSet();
    var mine = new WeakSet();

    var find = function() {
        var els = document.querySelectorAll('[data-testid]');
        for (var i = 0; i < els.length; i++) {
            var t = els[i].getAttribute('data-testid') || '';
            if (t.indexOf(tid) === -1) continue;
            if (els[i].getClientRects().length === 0) continue;
            if (!els[i].isConnected) continue;
            els[i].scrollIntoView({block: 'center'});
            els[i].click();
            return true;
        }
        return false;
    };

    // 是否处于任务列表页：存在 task-item 或存在 session item，且无 [data-session-id]
    // 会话页（任务会话视图）没有任何 task-item，此时绝不能点任何按钮——
    // 否则会误触会话页的"Git 工具/切换 Git 分支/状态面板"等折叠触发器，
    // 出现"git 工具弹层 → 审批弹层 → 思考弹层 → 消失"的刷屏
    var onListPage = function() {
        return document.querySelector('[data-session-id]') == null &&
            document.querySelectorAll('[data-testid^="task-item-"]').length > 0;
    };

    // 展开工作区分组（aria-expanded=false 的按钮）——仅限列表页：
    // 不在列表页绝不点击任何按钮（会话页按钮全是弹层触发器）
    var expandNext = function() {
        if (!onListPage()) return false;
        var heads = document.querySelectorAll('button[aria-expanded="false"]');
        for (var i = 0; i < heads.length; i++) {
            if (tried.has(heads[i])) continue;
            tried.add(heads[i]);
            mine.add(heads[i]);
            heads[i].click();
            return true;
        }
        return false;
    };

    // 恢复我们展开的分组（跳转完成后收起，保持页面整洁）——仅限列表页
    var restore = function() {
        if (!onListPage()) return false;
        var heads = document.querySelectorAll('button[aria-expanded="true"]');
        for (var i = 0; i < heads.length; i++) {
            if (!mine.has(heads[i])) continue;
            mine.delete(heads[i]);
            heads[i].click();
        }
    };

    if (find()) return;

    var iv = null, mo = null;
    var stop = function() {
        if (iv) clearInterval(iv);
        if (mo) mo.disconnect();
        iv = null; mo = null;
    };

    var tick = function() {
        if (stale()) { restore(); stop(); return; }
        if (find()) { stop(); return; }
        if (Date.now() > deadline) { restore(); stop(); return; }
        if (!onListPage() && document.querySelector('[data-session-id]') != null) {
            // 页面已是会话视图：目标任务已打开，无需再找
            stop();
            return;
        }
        expandNext();
    };

    iv = setInterval(tick, 400);
    if (document.body) {
        mo = new MutationObserver(function() {
            if (stale()) { restore(); stop(); return; }
            if (find()) { stop(); }
        });
        mo.observe(document.body, { childList: true, subtree: true });
    }
})();
"""
    }
}
