package ai.zcode.remote.ui.remote.event

/**
 * 任务事件捕获注入脚本：镜像页面与服务器之间的流量（fetch 响应 / EventSource
 * 消息 / WebSocket 消息）并回传原生。思路与注入手法参考同类远程客户端的
 * event_observer（hook 三条通道 + 体积上限 + 缓冲防丢），改为面向
 * Android WebView addJavascriptInterface 桥的原生实现。
 *
 * 纪律：脚本只读镜像流量，不修改任何请求/响应；对页面零干扰。
 */
object EventCaptureScript {

    /** 单条镜像体积上限（与原生侧截断一致）。 */
    private const val MAX_BYTES = 512 * 1024

    private const val BRIDGE_TOKEN = "\"__ZCODE_BRIDGE_NAME__\""
    private const val MAX_TOKEN = "__ZCODE_MAX_BYTES__"

    val js: String = """
(function() {
    if (window.__zcodeEventCapture) return;
    window.__zcodeEventCapture = true;
    var BRIDGE = window[$BRIDGE_TOKEN];
    if (!BRIDGE) return;
    var MAX = $MAX_TOKEN;
    var send = function(body) {
        try {
            if (typeof body !== 'string' || body.length === 0) return;
            BRIDGE.onTraffic(body.length > MAX ? body.slice(0, MAX) : body);
        } catch (e) {}
    };

    // ---- WebSocket：消息镜像送事件解析 ----
    var OrigWS = window.WebSocket;
    if (OrigWS) {
        var WSWrapped = function(url, protocols) {
            var ws = (protocols === undefined) ? new OrigWS(url) : new OrigWS(url, protocols);
            try {
                ws.addEventListener('message', function(ev) {
                    try {
                        var d = ev.data;
                        if (typeof d === 'string') {
                            send(d);
                        } else if (d && typeof d.text === 'function') {
                            // Blob
                            d.text().then(send).catch(function() {});
                        } else if (d && d.byteLength > 0 && d.byteLength < MAX) {
                            // ArrayBuffer（二进制帧）
                            try { send(new TextDecoder('utf-8', {fatal: false}).decode(d)); } catch (e2) {}
                        }
                    } catch (e) {}
                });
            } catch (e) {}
            return ws;
        };
        WSWrapped.prototype = OrigWS.prototype;
        ['CONNECTING', 'OPEN', 'CLOSED'].forEach(function(k) { WSWrapped[k] = OrigWS[k]; });
        window.WebSocket = WSWrapped;
    }

    // ---- EventSource（SSE）：任务事件的主要实时通道 ----
    var OrigES = window.EventSource;
    if (OrigES) {
        var ESWrapped = function(url, cfg) {
            var es = new OrigES(url, cfg);
            try {
                es.addEventListener('message', function(ev) { send(ev.data); });
            } catch (e) {}
            return es;
        };
        ESWrapped.prototype = OrigES.prototype;
        ['CONNECTING', 'OPEN', 'CLOSED'].forEach(function(k) { ESWrapped[k] = OrigES[k]; });
        window.EventSource = ESWrapped;
    }

    // ---- fetch：镜像 200 的文本响应；POST /mobile-view-state 请求体也回传 ----
    var origFetch = window.fetch;
    if (origFetch) {
        window.fetch = function() {
            try {
                var u = arguments[0], init = arguments[1];
                var url = (typeof u === 'string') ? u : ((u && u.url) || '');
                var method = ((init && init.method) || 'GET').toUpperCase();
                if (url.indexOf('/mobile-view-state') >= 0 && method === 'POST') {
                    var b = init && init.body;
                    if (typeof b === 'string') send(b);
                }
            } catch (e) {}
            var p = origFetch.apply(this, arguments);
            try {
                p.then(function(res) {
                    try {
                        if (!res || res.status !== 200) return;
                        var ct = '';
                        try { ct = (res.headers && res.headers.get('content-type')) || ''; } catch (e2) {}
                        if (/^(image|audio|video|font)\//.test(ct)) return;
                        res.clone().text().then(function(t) {
                            if (t && t.length > 0 && t.length < MAX) send(t);
                        }).catch(function() {});
                    } catch (e3) {}
                }).catch(function() {});
            } catch (e4) {}
            return p;
        };
    }
})();
"""

    fun build(bridgeName: String): String =
        js.replace(BRIDGE_TOKEN, "\"$bridgeName\"").replace(MAX_TOKEN, MAX_BYTES.toString())
}
