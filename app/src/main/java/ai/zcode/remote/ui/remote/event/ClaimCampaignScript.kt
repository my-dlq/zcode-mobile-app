package ai.zcode.remote.ui.remote.event

/** Uses the connected desktop's authenticated services, with no separate account/token storage. */
object ClaimCampaignScript {
    val js = """
(function () {
    if (window.__zcodeClaims) return;
    var bridge = window.__zcodeNative;
    if (!bridge) return;
    var services = null, servicesFoundAt = -1, servicesGeneration = 0;
    var offers = [], busy = false, querying = false, lastQuery = 0;
    var REFRESH_MS = 10 * 60 * 1000;
    var activeClaimKey = '', lastStatus = '';
    var api = window.__zcodeClaims = {};

    function usablePlanService(value) {
        try {
            return value && typeof value.getManualClaimPlanPreviews === 'function' &&
                (typeof value.getRequestVerificationConfig === 'function' || typeof value.getCaptchaConfig === 'function') &&
                typeof value.claimManualPlan === 'function';
        } catch (e) { return false; }
    }
    function checkServiceContext(value) {
        if (!value || (typeof value !== 'object' && typeof value !== 'function')) return false;
        try {
            if (usablePlanService(value.codingPlanSubscriptionService)) {
                services = value; servicesFoundAt = servicesGeneration; return true;
            }
        } catch (e) {}
        return false;
    }
    function findServices() {
        if (services && servicesFoundAt === servicesGeneration) return services;
        // 优先读取当前页面的服务上下文，避免跨工作区搜索时拿到旧代理。
        var anchor = document.querySelector('[data-testid="login-trigger"]') ||
            document.querySelector('[data-testid="manual-claim-plan-banner"]') ||
            document.querySelector('[data-session-id]');
        if (anchor) {
            var key = Object.keys(anchor).find(function (k) { return k.indexOf('__reactFiber') === 0; });
            var fiber = key && anchor[key];
            for (var depth = 0; fiber && depth < 80; depth++, fiber = fiber.return) {
                var st = fiber.memoizedState;
                for (var n = 0; st && n < 40; st = st.next, n++) {
                    if (checkServiceContext(st.memoizedState)) return services;
                }
                if (checkServiceContext(fiber.stateNode)) return services;
            }
        }
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
                if (checkServiceContext(o)) return true;
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
            benefits: plan && plan.entitlements || [], delivery: delivery, banner: banner };
    }
    function compactNumber(value) {
        var number = Number(value || 0);
        if (!isFinite(number) || number <= 0) return '';
        if (number >= 100000000) return (number / 100000000).toFixed(number % 100000000 ? 1 : 0) + ' 亿';
        if (number >= 10000) return (number / 10000).toFixed(number % 10000 ? 1 : 0) + ' 万';
        return number.toLocaleString('en-US');
    }
    // Mirrors the desktop banner: max model-usage grant is the headline, model names are the caption.
    function bannerText(offer) {
        var all = offer.plan.benefits;
        var usage = all.filter(function (e) { return e.meter === 'model_usage'; });
        if (!usage.length) usage = all;
        var models = usage.map(function (e) { return e.showName || e.show_name || ''; }).filter(Boolean).join(' · ');
        var amount = usage.reduce(function (max, e) {
            return Math.max(max, Number(e.grantUnits || e.grant_units || 0));
        }, 0);
        var unit = usage.length ? (usage[0].unitType || usage[0].unit_type || '') : '';
        return {
            title: offer.plan.name,
            detail: amount > 0 ? compactNumber(amount) + ' ' + (unit === 'token' ? 'TOKENS' : unit.toUpperCase()) : '活动权益可领取',
            models: models
        };
    }
    // Keep the offer visible in the task list; the conversation dialog remains the native fallback.
    function renderOffers() {
        var host = document.getElementById('zcode-claim-offers');
        var message = document.getElementById('zcode-claim-status');
        var footer = document.querySelector('[data-testid="login-trigger"]');
        // The same WebView can be showing a conversation; remove a stale home banner.
        if (document.querySelector('[data-session-id]') || !footer || !offers.length) {
            if (host) host.remove();
            return;
        }
        if (!message) {
            message = document.createElement('p');
            message.id = 'zcode-claim-status';
            message.hidden = true;
            message.setAttribute('role', 'status');
            message.style.cssText = 'display:block;margin:0 8px 4px;font-size:12px;line-height:1.5;color:inherit;';
        }
        var footerRow = footer.parentElement;
        var footerBar = footerRow && footerRow.parentElement;
        if (!footerRow || !footerBar) { if (host) host.remove(); return; }
        var signature = JSON.stringify(offers.map(function (o) {
            return [o.key, o.plan.name, bannerText(o), o.plan.banner && o.plan.banner.background];
        }));
        if (host && host.dataset.signature === signature && host.parentElement === footerBar) return;
        if (host) host.remove();
        host = document.createElement('section');
        host.id = 'zcode-claim-offers';
        host.dataset.signature = signature;
        host.setAttribute('aria-label', '可领取活动');
        host.style.cssText = 'display:flex;flex-direction:column;gap:8px;padding:8px 8px 4px;flex:none;';
        offers.forEach(function (offer) {
            var text = bannerText(offer);
            var card = document.createElement('article');
            card.style.cssText = 'position:relative;overflow:hidden;border-radius:14px;padding:14px;' +
                'min-height:86px;color:#fff;background:linear-gradient(120deg,#111 0%,#24212e 58%,#30235e 100%);';
            var title = document.createElement('div');
            title.textContent = text.title;
            title.style.cssText = 'font-size:12px;line-height:1.4;font-weight:700;letter-spacing:.02em;' +
                'opacity:.9;padding-right:82px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
            var benefit = document.createElement('div');
            benefit.textContent = text.detail;
            benefit.style.cssText = 'font-size:22px;line-height:1.2;font-weight:800;margin-top:4px;' +
                'padding-right:82px;overflow-wrap:anywhere;letter-spacing:-.01em;';
            var models = document.createElement('div');
            models.textContent = text.models;
            models.style.cssText = 'font-size:11px;line-height:1.4;margin-top:5px;opacity:.72;' +
                'padding-right:82px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
            var action = document.createElement('button');
            action.type = 'button';
            action.textContent = '领取';
            action.setAttribute('data-zcode-inject', '1');
            action.setAttribute('aria-label', '领取 ' + offer.plan.name);
            action.style.cssText = 'position:absolute;right:12px;top:50%;transform:translateY(-50%);' +
                'min-width:56px;min-height:36px;padding:0 12px;border:1px solid rgba(255,255,255,.7);' +
                'border-radius:999px;background:rgba(255,255,255,.12);color:#fff;font-size:13px;font-weight:600;';
            action.onclick = function (event) {
                event.stopPropagation();
                api.claim(offer.key);
            };
            var orb = document.createElement('span');
            orb.textContent = 'Z';
            orb.setAttribute('aria-hidden', 'true');
            orb.style.cssText = 'position:absolute;right:68px;bottom:-18px;width:72px;height:72px;' +
                'display:flex;align-items:center;justify-content:center;border-radius:18px;' +
                'transform:rotate(18deg);background:linear-gradient(145deg,#5f5cff,#8e5cff);' +
                'box-shadow:0 0 28px rgba(112,84,255,.55);font-size:34px;font-weight:900;opacity:.75;';
            card.appendChild(title);
            card.appendChild(benefit);
            card.appendChild(models);
            card.appendChild(orb);
            card.appendChild(action);
            host.appendChild(card);
        });
        footerBar.insertBefore(host, footerRow);
        if (message.parentElement !== footerBar) footerBar.insertBefore(message, footerRow);
    }
    function setStatus(text) {
        var el = document.getElementById('zcode-claim-status');
        if (el) { el.textContent = text; el.hidden = !text; }
    }
    async function verificationConfig(svc) {
        // 2026-10-24：桌面 3.16.2 已改名，远程静态页仍调用旧接口。
        // RPC Proxy 对任意属性都返回函数，必须依据实际调用结果判断兼容性。
        if (typeof svc.getRequestVerificationConfig === 'function') {
            try { return await svc.getRequestVerificationConfig(); }
            catch (e) {
                if (!/^Method not found:\s*getRequestVerificationConfig\s*$/i.test(String(e && e.message || e))) throw e;
            }
        }
        if (typeof svc.getCaptchaConfig !== 'function') throw Error('桌面端暂不支持领取验证，请更新 ZCode 后重试');
        return await svc.getCaptchaConfig();
    }
    function status(text) {
        lastStatus = text;
        setStatus(text);
        if (activeClaimKey && typeof bridge.onClaimCampaignStatus === 'function') {
            bridge.onClaimCampaignStatus(activeClaimKey, text, !busy);
        }
    }
    async function refresh(force) {
        if (querying || (!force && Date.now() - lastQuery < REFRESH_MS)) return;
        var svc = findServices();
        if (!svc) {
            servicesGeneration++;
            return;
        }
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
            renderOffers();
            bridge.onClaimCampaigns(JSON.stringify(result));
        } catch (e) {
            // Older desktops may only expose plan previews. These provide an entry, without manufacturing deliveries.
            try {
                var preview = await svc.codingPlanSubscriptionService.getManualClaimPlanPreviews();
                offers = (preview.plans || []).filter(function (p) { return p.planId; }).map(function (p) {
                    return {key: 'preview|' + p.planId, scope: '', plan: {id:p.planId, name:p.name || '可领取活动', benefits:p.entitlements || []}};
                });
                lastQuery = Date.now(); renderOffers();
            } catch (ignored) {
                services = null;
                servicesGeneration++;
            }
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
        activeClaimKey = offer.key;
        if (offer.scope) bridge.onClaimCampaignClicked(offer.key);
        try {
            status('正在加载领取验证…');
            var svc = findServices().codingPlanSubscriptionService;
            var cfg = await verificationConfig(svc);
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
                renderOffers();
                setTimeout(function () { refresh(true); }, 2500);
            } else {
                var messages = {1001:'套餐不存在',1002:'活动已结束或暂不可领取',1003:'该套餐已经领取过',
                    1004:'账号或客户端版本不满足领取条件',1005:'今日领取名额已用完',3007:'验证码校验失败，请重试',401:'请先在桌面端登录'};
                throw Error(result && (result.message || messages[result.code]) || '领取失败，请稍后重试');
            }
        } catch (e) {
            services = null;
            servicesGeneration++;
            status(e.message || '领取失败，请稍后重试');
        }
        finally {
            busy = false;
            status(lastStatus); activeClaimKey = '';
        }
    }
    api.refresh = refresh;
    api.render = renderOffers;
    // Native reminders can claim from a task conversation without a dashboard button.
    api.claim = function (key) {
        var offer = offers.find(function (o) { return o.key === key; });
        if (busy || !offer || !findServices()) return false;
        claim(offer); return true;
    };
    var scheduled = false;
    new MutationObserver(function () {
        if (scheduled) return; scheduled = true;
        setTimeout(function () {
            scheduled = false;
            renderOffers();
        }, 250);
    }).observe(document, {childList:true, subtree:true});
    // First discovery retries until the remote services have paired; successful queries use desktop cadence.
    setInterval(function () { refresh(false); }, 10000);
    window.addEventListener('online', function () { refresh(false); });
    refresh(false);
})();
""".trimIndent()
}
