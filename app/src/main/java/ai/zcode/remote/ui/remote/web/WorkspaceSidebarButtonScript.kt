package ai.zcode.remote.ui.remote.web

import org.json.JSONObject

/** 在远程工作区导航按钮左侧补一个侧栏按钮；不改远程 React 的子节点。 */
internal object WorkspaceSidebarButtonScript {
    fun probe(eligible: Boolean, collapseLabel: String, expandLabel: String): String = PROBE
        .replace("__ELIGIBLE__", eligible.toString())
        .replace("__COLLAPSE_LABEL__", JSONObject.quote(collapseLabel))
        .replace("__EXPAND_LABEL__", JSONObject.quote(expandLabel))

    val remove: String = """
        (function() {
            var button = document.getElementById('zcode-remote-sidebar-toggle');
            if (button) button.remove();
            var motion = document.getElementById('zcode-remote-sidebar-motion');
            if (motion) motion.remove();
            var nav = document.querySelector('[data-zcode-sidebar-nav-shifted="true"]');
            if (nav) {
                nav.style.paddingLeft = nav.dataset.zcodeSidebarOriginalPadding || '';
                nav.style.position = nav.dataset.zcodeSidebarOriginalPosition || '';
                nav.style.top = nav.dataset.zcodeSidebarOriginalTop || '';
                delete nav.dataset.zcodeSidebarOriginalPadding;
                delete nav.dataset.zcodeSidebarOriginalPosition;
                delete nav.dataset.zcodeSidebarOriginalTop;
                delete nav.dataset.zcodeSidebarTopOffset;
                delete nav.dataset.zcodeSidebarNavShifted;
            }
        })()
    """.trimIndent()

    private val PROBE = """
        (function() {
            var button = document.getElementById('zcode-remote-sidebar-toggle');
            var motion = document.getElementById('zcode-remote-sidebar-motion');
            var shifted = document.querySelector('[data-zcode-sidebar-nav-shifted="true"]');
            function restoreNav(nav) {
                if (!nav) return;
                nav.style.paddingLeft = nav.dataset.zcodeSidebarOriginalPadding || '';
                nav.style.position = nav.dataset.zcodeSidebarOriginalPosition || '';
                nav.style.top = nav.dataset.zcodeSidebarOriginalTop || '';
                delete nav.dataset.zcodeSidebarOriginalPadding;
                delete nav.dataset.zcodeSidebarOriginalPosition;
                delete nav.dataset.zcodeSidebarOriginalTop;
                delete nav.dataset.zcodeSidebarTopOffset;
                delete nav.dataset.zcodeSidebarNavShifted;
            }
            function removeButton() {
                if (button) button.remove();
                if (motion) motion.remove();
                restoreNav(shifted);
            }
            var shell = document.querySelector('[data-workspace-shell="true"]');
            var panel = shell && shell.querySelector('[data-workspace-sidebar-panel="true"]');
            var aside = panel && panel.querySelector('aside');
            var back = shell && shell.querySelector('[data-testid="desktop-top-nav-back"]');
            var settings = document.querySelector('[data-testid="settings-page"]');
            if (!__ELIGIBLE__ || !window.matchMedia('(min-width: 768px)').matches ||
                !aside || !back || shell.getBoundingClientRect().width <= 0 ||
                (settings && settings.getClientRects().length > 0 &&
                    window.getComputedStyle(settings).visibility !== 'hidden')) {
                removeButton();
                return 'unavailable';
            }
            var nav = back.parentElement;
            if (!nav) {
                removeButton();
                return 'unavailable';
            }
            if (shifted && shifted !== nav) restoreNav(shifted);
            if (!nav.dataset.zcodeSidebarNavShifted) {
                nav.dataset.zcodeSidebarOriginalPadding = nav.style.paddingLeft;
                nav.dataset.zcodeSidebarOriginalPosition = nav.style.position;
                nav.dataset.zcodeSidebarOriginalTop = nav.style.top;
                nav.style.paddingLeft = ((parseFloat(getComputedStyle(nav).paddingLeft) || 0) + 36) + 'px';
                nav.style.position = 'relative';
                nav.dataset.zcodeSidebarNavShifted = 'true';
            }
            if (!motion) {
                motion = document.createElement('style');
                motion.id = 'zcode-remote-sidebar-motion';
                motion.textContent = '[data-workspace-shell="true"] > [data-workspace-sidebar-panel="true"]' +
                    '{transition-duration:170ms !important}';
                document.head.appendChild(motion);
            }
            if (!button) {
                button = document.createElement('button');
                button.id = 'zcode-remote-sidebar-toggle';
                button.type = 'button';
                button.className = back.className + ' rounded-xl';
                button.style.cssText = 'position:fixed;left:4px;width:32px;height:32px;' +
                    'display:flex;align-items:center;justify-content:center;padding:0;' +
                    'border:0;background:transparent;z-index:2147483000;cursor:pointer';
                button.addEventListener('click', function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    var now = Date.now();
                    if (now - (button.__lastToggle || 0) < 450) return;
                    button.__lastToggle = now;
                    // 官方快捷键只检查 Ctrl+B，不检查 isTrusted；复用页面自己的切换状态。
                    window.dispatchEvent(new KeyboardEvent('keydown', {
                        key: 'b', code: 'KeyB', ctrlKey: true, bubbles: true, cancelable: true
                    }));
                    setTimeout(updateButton, 250);
                });
                document.body.appendChild(button);
            }
            function alignWithHeader() {
                var header = shell.querySelector('header[data-workspace-header-variant]');
                var headerRect = header && header.getBoundingClientRect();
                var backRect = back.getBoundingClientRect();
                var previousOffset = parseFloat(nav.dataset.zcodeSidebarTopOffset) || 0;
                var originalCenter = backRect.top + backRect.height / 2 - previousOffset;
                var targetCenter = headerRect && headerRect.width > 0 && headerRect.height > 0
                    ? headerRect.top + headerRect.height / 2 : originalCenter;
                var offset = targetCenter - originalCenter;
                nav.style.top = offset + 'px';
                nav.dataset.zcodeSidebarTopOffset = String(offset);
                button.style.top = (targetCenter - 16) + 'px';
            }
            function updateButton() {
                if (!button.isConnected || !aside.isConnected) return;
                alignWithHeader();
                var collapsed = aside.getAttribute('aria-hidden') === 'true' ||
                    panel.getBoundingClientRect().width < 2;
                var label = collapsed ? __EXPAND_LABEL__ : __COLLAPSE_LABEL__;
                button.setAttribute('aria-label', label);
                button.title = label + ' (Ctrl+B)';
                button.style.color = getComputedStyle(back).color;
                button.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" ' +
                    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ' +
                    'stroke-linejoin="round" aria-hidden="true"><path d="M4 4h16v16H4zM9 4v16"/>' +
                    '<path d="' + (collapsed ? 'm13 9 3 3-3 3' : 'm16 9-3 3 3 3') + '"/></svg>';
                return collapsed;
            }
            return updateButton() ? 'closed' : 'open';
        })()
    """.trimIndent()
}
