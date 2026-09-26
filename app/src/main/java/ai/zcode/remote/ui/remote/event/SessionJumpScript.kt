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
    var wentHome = false;

    // 当前会话视图的会话 ID（无 [data-session-id] 时为空 = 列表页/其他页）
    var currentSessionId = function() {
        var p = document.querySelector('[data-session-id]');
        return p ? (p.getAttribute('data-session-id') || '') : '';
    };

    var find = function() {
        var els = document.querySelectorAll('[data-testid]');
        for (var i = 0; i < els.length; i++) {
            var t = els[i].getAttribute('data-testid') || '';
            // 只匹配任务条目本身（task-item-<完整 taskId>），避免 indexOf 把
            // 前缀相同的其它条目（如 task-item-<id>-sub）误判为目标
            if (t !== 'task-item-' + tid) continue;
            if (els[i].getClientRects().length === 0) continue;
            if (!els[i].isConnected) continue;
            if (tried.has(els[i])) continue; // 已点过等跳转，不重复点击
            tried.add(els[i]);
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

    var iv = null, mo = null;
    var stop = function() {
        if (iv) clearInterval(iv);
        if (mo) mo.disconnect();
        iv = null; mo = null;
    };

    var tick = function() {
        if (stale()) { restore(); stop(); return; }
        // 目标会话已打开（当前会话 ID 与目标一致）才算成功。
        // 不能只凭“页面处于会话视图”就停止：通知点击后若远端恢复的是
        // 上一次会话（非目标），会误停在旧会话页 —— 表现为点 A 通知跳到 B。
        var sid = currentSessionId();
        if (sid === tid) { restore(); stop(); return; }
        if (find()) return; // 点击后等会话切换，由下一轮 tick 确认
        if (Date.now() > deadline) { restore(); stop(); return; }
        if (!onListPage() && sid !== '') {
            // 处于其它会话视图：先返回任务首页（列表），回到列表后由
            // 后续 tick 的 find()/expandNext() 继续找目标。找不到返回
            // 按钮时不能放弃——继续等页面渲染（可能有异步加载延迟）。
            if (!wentHome) {
                wentHome = true;
                var backBtn = document.querySelector(
                    'button[aria-label="返回任务首页"], button[aria-label="返回工作区"], button[aria-label="Back to workspace"]');
                if (backBtn) {
                    backBtn.click();
                    return;
                }
            }
            return;
        }
        expandNext();
    };

    // 立即 tick 一次（可能已在列表页且目标可见，直接点击进入）
    tick();
    iv = setInterval(tick, 400);
    if (document.body) {
        mo = new MutationObserver(function() {
            if (stale()) { restore(); stop(); return; }
            // DOM 变化时只做一次轻量检查；成功判定统一交给 tick
            // （sid === tid 才 stop），避免会话切换未完成就提前退出。
        });
        mo.observe(document.body, { childList: true, subtree: true });
    }
})();
"""
    }
}
