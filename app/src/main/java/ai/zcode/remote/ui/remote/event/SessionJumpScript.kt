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

    // 展开工作区分组（aria-expanded=false 的按钮）
    var expandNext = function() {
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

    // 恢复我们展开的分组（跳转完成后收起，保持页面整洁）
    var restore = function() {
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
