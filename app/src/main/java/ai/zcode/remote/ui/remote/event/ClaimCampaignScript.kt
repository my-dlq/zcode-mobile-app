package ai.zcode.remote.ui.remote.event

/** Uses the connected desktop's authenticated services, with no separate account/token storage. */
object ClaimCampaignScript {
    val js = """
(function () {
    if (window.__zcodeClaims) return;
    var bridge = window.__zcodeNative;
    if (!bridge) return;
    var services = null, offers = [], busy = false, querying = false, lastQuery = 0;
    var REFRESH_MS = 10 * 60 * 1000;
    var api = window.__zcodeClaims = {};

    function findServices() {
        if (services) return services;
        var elements = document.querySelectorAll('*'), roots = [], seen = new Set();
        for (var i = 0; i < elements.length; i++) {
            var key = Object.keys(elements[i]).find(function (k) { return k.indexOf('__reactFiber') === 0; });
            if (!key) continue;
            var root = elements[i][key];
            while (root.return) root = root.return;
            if (roots.indexOf(root) < 0) roots.push(root);
            if (roots.length >= 4) break;
        }
        function check(o) {
            if (!o || (typeof o !== 'object' && typeof o !== 'function') || seen.has(o)) return false;
            seen.add(o);
            try {
                if (o.codingPlanSubscriptionService && typeof o.codingPlanSubscriptionService !== 'function' &&
                    typeof o.codingPlanSubscriptionService.getManualClaimPlanPreviews === 'function') {
                    services = o; return true;
                }
            } catch (e) {}
            return false;
        }
        var stack = roots.slice(), count = 0;
        while (stack.length && count++ < 20000) {
            var f = stack.pop(), st = f.memoizedState;
            for (var n = 0; st && n < 40; st = st.next, n++) if (check(st.memoizedState)) return services;
            if (check(f.stateNode)) return services;
            var props = f.memoizedProps || {}, keys = Object.keys(props);
            for (var p = 0; p < keys.length && p < 40; p++) {
                var v = props[keys[p]];
                if (check(v) || check(v && v.value) || check(v && v.current)) return services;
            }
            if (f.child) stack.push(f.child);
            if (f.sibling) stack.push(f.sibling);
        }
        return null;
    }
    function planOf(delivery) {
        var banner = delivery.banner || delivery.popup;
        if (!banner) return null;
        var action = (banner.buttons || []).map(function (b) { return b.action; }).find(function (a) {
            return a && a.type === 'claim_zcode_plan' && a.args && a.args.plan_id;
        });
        if (!action) return null;
        var resource = banner.background || banner.hero;
        var plan = resource && resource.args && resource.args.zcode_plan;
        return { id: action.args.plan_id, name: plan && plan.name || '可领取活动',
            benefits: plan && plan.entitlements || [], delivery: delivery };
    }
    function detail(plan) {
        return plan.benefits.map(function (e) {
            var amount = Number(e.grant_units || e.units || 0);
            if (!isFinite(amount) || amount <= 0) return '';
            return (e.show_name || e.showName || '') + ' ' + amount.toLocaleString('en-US') + ' ' +
                ((e.unit_type || e.unitType) === 'token' ? 'tokens' : (e.unit_type || e.unitType || ''));
        }).filter(Boolean).join(' · ') || '活动权益可领取';
    }
    function status(text) {
        var el = document.getElementById('zcode-claim-status');
        if (el) { el.textContent = text; el.hidden = !text; }
    }
    function render() {
        var heading = Array.from(document.querySelectorAll('h1')).find(function (e) {
            return /当前设备上的工作区和任务|Workspaces and Tasks/i.test(e.textContent);
        });
        var host = document.getElementById('zcode-claim-offers');
        if (!heading || !offers.length) { if (host) host.remove(); return; }
        // Insert beside the dashboard header, within its scrolling content.
        var header = heading.parentElement.parentElement;
        var signature = JSON.stringify(offers.map(function (o) { return [o.key, o.plan.name, detail(o.plan)]; }));
        if (host && host.dataset.signature === signature) return;
        if (host) host.remove();
        host = document.createElement('section');
        host.id = 'zcode-claim-offers'; host.dataset.signature = signature;
        host.setAttribute('aria-label', '可领取活动');
        host.style.cssText = 'margin:12px 0;display:grid;gap:10px;';
        offers.forEach(function (offer) {
            var card = document.createElement('article');
            card.className = 'bg-surface text-foreground border-border';
            card.style.cssText = 'padding:16px;border-width:1px;border-style:solid;border-radius:16px;display:flex;align-items:center;gap:12px;';
            var info = document.createElement('div'); info.style.cssText = 'flex:1;min-width:0;';
            var title = document.createElement('strong'); title.textContent = offer.plan.name;
            title.style.cssText = 'display:block;font-size:14px;line-height:1.5;';
            var benefit = document.createElement('div'); benefit.textContent = detail(offer.plan);
            benefit.style.cssText = 'font-size:13px;line-height:1.6;margin-top:6px;overflow-wrap:anywhere;';
            info.appendChild(title); info.appendChild(benefit);
            var button = document.createElement('button'); button.type = 'button'; button.textContent = '领取';
            button.setAttribute('data-zcode-inject', '1');
            button.setAttribute('aria-label', '领取 ' + offer.plan.name);
            button.style.cssText = 'flex:none;border:1px solid currentColor;border-radius:999px;padding:8px 16px;font-size:14px;background:transparent;color:inherit;min-height:44px;';
            button.onclick = function (event) { event.stopPropagation(); claim(offer); };
            card.appendChild(info); card.appendChild(button); host.appendChild(card);
        });
        var message = document.createElement('p'); message.id = 'zcode-claim-status'; message.hidden = true;
        message.setAttribute('role', 'status'); message.style.cssText = 'margin:4px 0;font-size:13px;'; host.appendChild(message);
        header.insertAdjacentElement('afterend', host);
    }
    async function refresh(force) {
        if (querying || (!force && Date.now() - lastQuery < REFRESH_MS)) return;
        var svc = findServices(); if (!svc) return;
        querying = true;
        try {
            // Each query is a server delivery, mirroring the desktop marketing poller.
            var result = await svc.marketingTouchService.query({locale: 'zh-CN'});
            if (!result || !Array.isArray(result.deliveries) || !result.scope) throw Error('invalid delivery');
            offers = result.deliveries.map(function (d) {
                var plan = planOf(d);
                return plan && { plan: plan, key: result.scope + '|' + d.campaign_id + '|' + plan.id, scope: result.scope };
            }).filter(Boolean);
            lastQuery = Date.now();
            render();
            bridge.onClaimCampaigns(JSON.stringify(result));
        } catch (e) {
            // Older desktops may only expose plan previews. These provide an entry, without manufacturing deliveries.
            try {
                var preview = await svc.codingPlanSubscriptionService.getManualClaimPlanPreviews();
                offers = (preview.plans || []).filter(function (p) { return p.planId; }).map(function (p) {
                    return {key: 'preview|' + p.planId, scope: '', plan: {id:p.planId, name:p.name || '可领取活动', benefits:p.entitlements || []}};
                });
                lastQuery = Date.now(); render();
            } catch (ignored) { services = null; }
        } finally { querying = false; }
    }
    function loadCaptcha() {
        if (typeof window.initAliyunCaptcha === 'function') return Promise.resolve();
        return new Promise(function (resolve, reject) {
            var script = document.createElement('script');
            script.src = 'https://o.alicdn.com/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js';
            var timer = setTimeout(function () { reject(Error('验证服务加载超时，请重试')); }, 15000);
            script.onload = function () { clearTimeout(timer); resolve(); };
            script.onerror = function () { clearTimeout(timer); reject(Error('验证服务加载失败，请重试')); };
            document.head.appendChild(script);
        });
    }
    function verify(cfg) {
        return new Promise(function (resolve, reject) {
            var settled = false, instance = null, interactive = false, fallbackTimer = null;
            var holder = document.createElement('div'), trigger = document.createElement('button');
            holder.id = 'zcode-claim-captcha'; trigger.id = 'zcode-claim-captcha-trigger'; trigger.hidden = true;
            document.body.appendChild(holder); document.body.appendChild(trigger);
            function finish(error, value) {
                if (settled) return; settled = true; clearTimeout(timer); clearTimeout(fallbackTimer);
                try { if (instance && instance.destroy) instance.destroy(); } catch (e) {}
                holder.remove(); trigger.remove(); if (error) reject(Error(error)); else resolve(value);
            }
            function showInteractive() {
                if (settled || interactive) return;
                interactive = true; clearTimeout(fallbackTimer);
                status('请完成验证码后领取');
                trigger.click();
            }
            var timer = setTimeout(function () { finish('验证未完成，可重新点击领取'); }, 120000);
            window.AliyunCaptchaConfig = {region:cfg.region || 'cn', prefix:cfg.prefix || ''};
            try {
                window.initAliyunCaptcha({SceneId:cfg.sceneId, mode:'popup', language:'cn', showErrorTip:false,
                    element:'#zcode-claim-captcha', button:'#zcode-claim-captcha-trigger',
                    getInstance:function (i) { instance = i;
                        try {
                            if (i.startTracelessVerification) {
                                i.startTracelessVerification();
                                if (!settled && !interactive) fallbackTimer = setTimeout(showInteractive, 5000);
                            } else showInteractive();
                        }
                        catch (e) { finish('请稍后重新完成验证'); }
                    },
                    success:function (p) { var value = typeof p === 'string' ? p : p && p.captchaVerifyParam;
                        finish(value ? null : '验证结果为空，请重试', value); },
                    fail:showInteractive,
                    onError:function () { finish('验证服务暂不可用，请稍后重试'); }
                });
            } catch (e) { finish('无法初始化验证，请稍后重试'); }
        });
    }
    async function claim(offer) {
        if (busy) return;
        busy = true;
        if (offer.scope) bridge.onClaimCampaignClicked(offer.key);
        var buttons = document.querySelectorAll('#zcode-claim-offers button');
        buttons.forEach(function (b) { b.disabled = true; });
        try {
            status('正在加载领取验证…');
            var svc = findServices().codingPlanSubscriptionService;
            var cfg = await svc.getCaptchaConfig();
            if (!cfg || !cfg.enabled || !cfg.sceneId) throw Error('活动暂不可领取，请稍后重试');
            await loadCaptcha(); status('请完成验证');
            var param = await verify(cfg);
            status('正在领取…');
            var result = await svc.claimManualPlan({planId:offer.plan.id, captchaVerifyParam:param, captchaRegion:cfg.region || ''});
            if (result && result.success) {
                if (offer.plan.delivery && findServices().marketingTouchService) {
                    // Report a successful explicit user action, never report close/impression during discovery.
                    await findServices().marketingTouchService.report({scope:offer.scope, campaignId:offer.plan.delivery.campaign_id,
                        actionType:'confirm', locale:'zh-CN'}).catch(function () {});
                }
                status(offer.plan.name + ' 领取成功');
                offers = offers.filter(function (o) { return o.key !== offer.key; });
                setTimeout(function () { render(); refresh(true); }, 2500);
            } else {
                var messages = {1001:'套餐不存在',1002:'活动已结束或暂不可领取',1003:'该套餐已经领取过',
                    1004:'账号或客户端版本不满足领取条件',1005:'今日领取名额已用完',3007:'验证码校验失败，请重试',401:'请先在桌面端登录'};
                throw Error(result && (result.message || messages[result.code]) || '领取失败，请稍后重试');
            }
        } catch (e) { status(e.message || '领取失败，请稍后重试'); }
        finally { busy = false; buttons.forEach(function (b) { b.disabled = false; }); }
    }
    api.refresh = refresh;
    var scheduled = false;
    new MutationObserver(function () {
        if (scheduled) return; scheduled = true;
        setTimeout(function () { scheduled = false; render(); }, 250);
    }).observe(document, {childList:true, subtree:true});
    // First discovery retries until the remote services have paired; successful queries use desktop cadence.
    setInterval(function () { refresh(false); }, 10000);
    window.addEventListener('online', function () { refresh(false); });
    refresh(false);
})();
""".trimIndent()
}
