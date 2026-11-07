package ai.zcode.remote.ui.remote.web

/** 2026-11-07 真机核对：任务列表容器与标题均有稳定 testid；只读可见性，不读取账号或任务。 */
object TaskListVisibilityScript {
    val js = """
        (function() {
            var region = document.querySelector('[data-testid="sidebar-task-list-region"]');
            var title = region && region.querySelector('[data-testid="sidebar-list-title"]');
            if (!title || !/^(任务列表|Task list|Tasks)$/i.test((title.textContent || '').trim())) return false;
            for (var el = title; el && el.nodeType === 1; el = el.parentElement) {
                var style = window.getComputedStyle(el);
                if (el.hidden || el.getAttribute('aria-hidden') === 'true' || el.hasAttribute('inert') ||
                    style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' ||
                    parseFloat(style.opacity) === 0) return false;
            }
            var rect = title.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.right > 0 &&
                rect.top < window.innerHeight && rect.left < window.innerWidth;
        })()
    """.trimIndent()
}
