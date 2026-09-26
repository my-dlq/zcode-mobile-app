package ai.zcode.remote.ui.remote.event

/**
 * 任务事件捕获注入脚本：镜像页面与服务器之间的流量（fetch 响应 / EventSource
 * 消息 / WebSocket 消息）并回传原生，同时上报长连接状态供原生层断线恢复。
 *
 * 脚本只读镜像流量，不修改请求、响应或页面自身的重连行为。
 */
object EventCaptureScript {

    /**
     * 内层任务帧最大体积。远端的 wire 信封常含 base64，外层文本会比内层大约 1/3；
     * 不能再按 512 KiB 截断外层，否则 JSON 不完整，dataBase64 永远无法解开。
     */
    private const val MAX_BYTES = 4 * 1024 * 1024

    private const val BRIDGE_TOKEN = "\"__ZCODE_BRIDGE_NAME__\""
    private const val MAX_TOKEN = "__ZCODE_MAX_BYTES__"

    val js: String = """
(function() {
    var BRIDGE = window[$BRIDGE_TOKEN];
    if (!BRIDGE) return;
    if (window.__zcodeEventCapture) return;
    window.__zcodeEventCapture = true;
    var MAX = $MAX_TOKEN;
    // base64 wire 信封会膨胀，容许读取外层但只将不超过 MAX 的内层交给原生。
    var WIRE_MAX = MAX * 2;
    var reportState = function(state, transport, url, reason) {
        try { BRIDGE.onConnectionState(state, transport, String(url || ''), reason || ''); } catch (e) {}
    };
    var send = function(body) {
        try {
            if (typeof body !== 'string' || body.length === 0 || body.length > MAX) return;
            BRIDGE.onTraffic(body);
        } catch (e) {}
    };

    // wireVersion 信封中的 dataBase64 可能比桥接的安全上限更大。先在页面端
    // 解出内层 JSON，再回传原生；分片在此处重组，避免把截断的外层 JSON 交给原生。
    var fragmentAsm = {};
    var fragmentOrder = [];
    var base64Bytes = function(data) {
        var binary = atob(data);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return bytes;
    };
    var sendDecoded = function(bytes) {
        if (!bytes || bytes.length === 0 || bytes.length > MAX) return false;
        var text = new TextDecoder('utf-8', {fatal: false}).decode(bytes);
        var first = text.indexOf('{');
        var last = text.lastIndexOf('}');
        if (first < 0 || last <= first) return false;
        send(text.slice(first, last + 1));
        return true;
    };
    var tryDecodePayload = function(payload) {
        try {
            if (!payload || typeof payload.dataBase64 !== 'string' || payload.dataBase64.length === 0) return false;
            var sizeHint = payload.messageBytes != null
                ? Number(payload.messageBytes)
                : Math.ceil(payload.dataBase64.length * 0.75);
            if (!isFinite(sizeHint) || sizeHint > MAX) return true;
            if (payload.kind !== 'fragment' || Number(payload.fragmentCount || 1) <= 1) {
                return sendDecoded(base64Bytes(payload.dataBase64));
            }
            var frameId = payload.logicalFrameId;
            var index = Number(payload.fragmentIndex);
            var total = Number(payload.fragmentCount);
            if (!frameId || index < 0 || total <= 1) return true;
            var slot = fragmentAsm[frameId];
            if (!slot) {
                if (fragmentOrder.length >= 32) delete fragmentAsm[fragmentOrder.shift()];
                slot = fragmentAsm[frameId] = { parts: {}, got: 0, total: total };
                fragmentOrder.push(frameId);
            }
            if (!(index in slot.parts)) {
                slot.parts[index] = base64Bytes(payload.dataBase64);
                slot.got++;
            }
            if (slot.got < slot.total) return true;
            delete fragmentAsm[frameId];
            var orderIndex = fragmentOrder.indexOf(frameId);
            if (orderIndex >= 0) fragmentOrder.splice(orderIndex, 1);
            var size = 0;
            for (var partIndex = 0; partIndex < slot.total; partIndex++) {
                var part = slot.parts[partIndex];
                if (!part) return true;
                size += part.length;
            }
            if (size > MAX) return true;
            var merged = new Uint8Array(size);
            var offset = 0;
            for (var copyIndex = 0; copyIndex < slot.total; copyIndex++) {
                merged.set(slot.parts[copyIndex], offset);
                offset += slot.parts[copyIndex].length;
            }
            return sendDecoded(merged);
        } catch (e) {
            return false;
        }
    };
    var sendWithDecode = function(body) {
        try {
            if (typeof body !== 'string' || body.length === 0 || body.length > WIRE_MAX) return;
            var envelope = JSON.parse(body);
            var payload = envelope && (envelope.payload || (envelope.frame && envelope.frame.payload));
            if (tryDecodePayload(payload)) return;
        } catch (e) {}
        send(body);
    };

    var activeTransportCount = 0;
    var reportTransportUp = function(transport, url, reason) {
        activeTransportCount++;
        reportState('up', transport, url, reason);
    };
    var reportTransportDown = function(transport, url, reason) {
        activeTransportCount = Math.max(0, activeTransportCount - 1);
        if (activeTransportCount === 0) reportState('down', transport, url, reason);
    };

    // ---- WebSocket：镜像消息，并上报连接状态 ----
    var OrigWS = window.WebSocket;
    if (OrigWS) {
        var WSWrapped = function(url, protocols) {
            var ws = (protocols === undefined) ? new OrigWS(url) : new OrigWS(url, protocols);
            var countedOpen = false;
            try {
                ws.addEventListener('open', function() {
                    if (!countedOpen) {
                        countedOpen = true;
                        reportTransportUp('websocket', url, 'open');
                    } else {
                        reportState('up', 'websocket', url, 'open');
                    }
                });
                ws.addEventListener('message', function(ev) {
                    try {
                        var d = ev.data;
                        if (typeof d === 'string') {
                            sendWithDecode(d);
                        } else if (d && typeof d.text === 'function') {
                            if (d.size > 0 && d.size <= WIRE_MAX) d.text().then(sendWithDecode).catch(function() {});
                        } else if (d && d.byteLength > 0 && d.byteLength <= WIRE_MAX) {
                            try { sendWithDecode(new TextDecoder('utf-8', {fatal: false}).decode(d)); } catch (e2) {}
                        }
                    } catch (e) {}
                });
                ws.addEventListener('error', function() {
                    if (!countedOpen && ws.readyState === OrigWS.CLOSED && activeTransportCount === 0) {
                        reportState('down', 'websocket', url, 'error');
                    }
                });
                ws.addEventListener('close', function(ev) {
                    if (countedOpen) {
                        countedOpen = false;
                        reportTransportDown('websocket', url, 'close:' + (ev.code || 0));
                    } else if (activeTransportCount === 0) {
                        reportState('down', 'websocket', url, 'close:' + (ev.code || 0));
                    }
                });
            } catch (e) {}
            return ws;
        };
        WSWrapped.prototype = OrigWS.prototype;
        ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function(k) { WSWrapped[k] = OrigWS[k]; });
        window.WebSocket = WSWrapped;
    }

    // ---- EventSource（SSE）：镜像消息，并上报自动重连状态 ----
    var OrigES = window.EventSource;
    if (OrigES) {
        var ESWrapped = function(url, cfg) {
            var es = new OrigES(url, cfg);
            var countedOpen = false;
            try {
                // 页面有时不用默认的 message，而是 addEventListener 注册自定义 SSE
                // 事件名。监听调用时额外挂一个只读镜像 listener，覆盖这类事件。
                var nativeAdd = es.addEventListener;
                var watchedTypes = {};
                var watchType = function(type) {
                    if (!type || type === 'open' || type === 'error' || watchedTypes[type]) return;
                    watchedTypes[type] = true;
                    nativeAdd.call(es, type, function(ev) { sendWithDecode(ev && ev.data); });
                };
                watchType('message');
                es.addEventListener = function(type, listener, options) {
                    watchType(type);
                    return nativeAdd.call(es, type, listener, options);
                };
                es.addEventListener('open', function() {
                    if (!countedOpen) {
                        countedOpen = true;
                        reportTransportUp('eventsource', url, 'open');
                    } else {
                        reportState('up', 'eventsource', url, 'open');
                    }
                });
                es.addEventListener('error', function() {
                    if (countedOpen) {
                        countedOpen = false;
                        reportTransportDown('eventsource', url, 'error:' + es.readyState);
                    } else if (activeTransportCount === 0) {
                        reportState('down', 'eventsource', url, 'error:' + es.readyState);
                    }
                });
            } catch (e) {}
            return es;
        };
        ESWrapped.prototype = OrigES.prototype;
        ['CONNECTING', 'OPEN', 'CLOSED'].forEach(function(k) { ESWrapped[k] = OrigES[k]; });
        window.EventSource = ESWrapped;
    }

    // ---- fetch：镜像 200 的文本响应；失败仅用于诊断，不触发整页重载 ----
    var origFetch = window.fetch;
    if (origFetch) {
        window.fetch = function() {
            var requestUrl = '';
            try {
                var u = arguments[0], init = arguments[1];
                requestUrl = (typeof u === 'string') ? u : ((u && u.url) || '');
                var method = ((init && init.method) || 'GET').toUpperCase();
                if (requestUrl.indexOf('/mobile-view-state') >= 0 && method === 'POST') {
                    var b = init && init.body;
                    if (typeof b === 'string') sendWithDecode(b);
                }
            } catch (e) {}
            var p = origFetch.apply(this, arguments);
            try {
                p.then(function(res) {
                    try {
                        if (!res || res.status < 200 || res.status >= 300) return;
                        var ct = '';
                        try { ct = (res.headers && res.headers.get('content-type')) || ''; } catch (e2) {}
                        var length = 0;
                        try { length = Number((res.headers && res.headers.get('content-length')) || 0); } catch (e3) {}
                        if (length > MAX) return;
                        // 不镜像 HTML、脚本和静态资源；全量读取它们会阻塞 JS 桥，
                        // 反而延迟真正的实时事件。任务 API 的 JSON/SSE 仍全部保留。
                        var isEventApi = /(?:mobile-view-state|session|task|event|workspace|remote)/i.test(requestUrl);
                        var isStructured = /(?:application\/(?:json|problem\+json|x-ndjson)|text\/event-stream)/i.test(ct);
                        if (!isEventApi && !isStructured) return;
                        res.clone().text().then(function(t) {
                            if (t && t.length > 0 && t.length <= MAX) sendWithDecode(t);
                        }).catch(function() {});
                    } catch (e3) {}
                }).catch(function() {
                    reportState('degraded', 'fetch', requestUrl, 'rejected');
                });
            } catch (e4) {}
            return p;
        };
    }
    try { BRIDGE.onCaptureReady(); } catch (e) {}
    try {
        setInterval(function() {
            try { BRIDGE.onHeartbeat(); } catch (e) {}
        }, 10000);
    } catch (e) {}
})();
"""

    fun build(bridgeName: String): String =
        js.replace(BRIDGE_TOKEN, "\"$bridgeName\"").replace(MAX_TOKEN, MAX_BYTES.toString())
}
