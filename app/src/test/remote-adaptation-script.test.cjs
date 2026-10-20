// 直接提取 Kotlin 中的原脚本，验证导航边界及同一文档重注的行为。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const base = path.join(__dirname, '../main/java/ai/zcode/remote/ui/remote');
const jumpSource = fs.readFileSync(path.join(base, 'event/SessionJumpScript.kt'), 'utf8')
  .split('return """')[1].split('"""')[0].replace('$tid', 'target');
const activity = fs.readFileSync(path.join(base, 'RemoteControlActivity.kt'), 'utf8');
const backSource = activity.slice(activity.indexOf('private fun dispatchPageBack()'))
  .split('"""')[1];
const webSource = fs.readFileSync(path.join(base, 'web/ZCodeWebViewClient.kt'), 'utf8');
const marker = webSource.indexOf('window.__zcodeDashboardAdaptive =');
const behaviorSource = webSource.slice(webSource.lastIndexOf('(function()', marker), webSource.indexOf('""".trimIndent()', marker));
const style = { display: 'block', visibility: 'visible', opacity: '1', pointerEvents: 'auto' };
function el(attrs = {}, parent = null) {
  return { nodeType: 1, isConnected: true, parentElement: parent, hidden: false,
    getAttribute: k => attrs[k] ?? null, hasAttribute: k => k in attrs,
    getClientRects: () => [{}], getBoundingClientRect: () => ({ x: 8, y: 12, left: 8, top: 12, right: 371, bottom: 44, width: 363, height: 32 }),
    querySelector: () => null, closest: () => null, scrollIntoView() {}, textContent: '', click() {} };
}
function jumpHarness({ view = 'home', lateBack = false, targetExists = true, startExpanded = false, coveredBySettings = false, exitOverlay = false, coveredByOnboarding = false } = {}) {
  let clock = 0, groups = 0, backs = 0, rows = 0, forbidden = 0, sid = view === 'chat' ? 'other' : '';
  let expanded = startExpanded;
  const timers = new Map(); let timerId = 0;
  const root = el();
  const entry = el(); entry.closest = () => root;
  const session = el({ 'data-session-id': 'other' }); session.getAttribute = () => sid;
  const group = el(); group.click = () => { groups++; expanded = !expanded; };
  const row = el({ 'data-testid': 'task-item-target' }); row.click = () => { rows++; view = 'chat'; sid = 'target'; };
  const back = el(); back.click = () => { backs++; view = 'home'; sid = ''; };
  const settings = el(), onboarding = el(), exit = el();
  exit.click = () => { coveredBySettings = false; coveredByOnboarding = false; };
  settings.querySelector = onboarding.querySelector = () => exitOverlay ? exit : null;
  root.querySelectorAll = s => s.includes('virtual-project-toggle') &&
    ((s.includes('"false"') && !expanded) || (s.includes('"true"') && expanded)) ? [group] : [];
  const document = { body: {},
    querySelector(s) {
      if (s.includes('settings-page')) return coveredBySettings ? settings : null;
      if (s.includes('onboarding-page')) return coveredByOnboarding ? onboarding : null;
      if (s.includes('task-new-button')) return view === 'home' ? entry : null;
      if (s.includes('返回任务首页')) return view === 'chat' && (!lateBack || clock >= 800) ? back : null;
      return null;
    },
    querySelectorAll(s) {
      if (s === '[data-session-id]') return view === 'chat' ? [session] : [];
      if (s === '[data-testid]') return view === 'home' && expanded && targetExists ? [row] : [];
      if (s.includes('button[aria-expanded')) { forbidden++; return [el()]; }
      return [];
    } };
  const context = { window: { getComputedStyle: () => style }, document, WeakSet,
    Date: { now: () => clock }, MutationObserver: class { observe() {} disconnect() {} },
    setInterval(fn) { timers.set(++timerId, fn); return timerId; }, clearInterval(id) { timers.delete(id); } };
  return { run() { vm.runInNewContext(jumpSource, context); },
    advance(ms) { for (const end = clock + ms; clock < end;) { clock += 400; for (const fn of [...timers.values()]) fn(); } },
    state: () => ({ groups, backs, rows, forbidden, sid, timers: timers.size }), context };
}
test('全折叠且没有任务行时仍能展开并跳到目标', () => {
  const h = jumpHarness(); h.run(); h.advance(1200);
  assert.equal(h.state().sid, 'target'); assert.equal(h.state().rows, 1);
  assert.equal(h.state().groups, 1); assert.equal(h.state().forbidden, 0); assert.equal(h.state().timers, 0);
});
test('返回按钮延迟挂载时继续等待，不误触会话折叠器', () => {
  const h = jumpHarness({ view: 'chat', lateBack: true }); h.run(); h.advance(2000);
  assert.equal(h.state().backs, 1); assert.equal(h.state().sid, 'target'); assert.equal(h.state().forbidden, 0);
});
test('目标不存在时有界超时并恢复脚本展开的项目', () => {
  const h = jumpHarness({ targetExists: false }); h.run(); h.advance(20800);
  assert.equal(h.state().rows, 0); assert.equal(h.state().timers, 0); assert.equal(h.state().groups, 2);
});
test('设置页不展开任何项目或通用折叠器', () => {
  const h = jumpHarness({ view: 'settings' }); h.run(); h.advance(20800);
  assert.equal(h.state().groups, 0); assert.equal(h.state().forbidden, 0);
});
test('设置覆盖层后的任务入口和任务行有尺寸时也不能点击', () => {
  const h = jumpHarness({ startExpanded: true, coveredBySettings: true }); h.run(); h.advance(20800);
  assert.equal(h.state().groups, 0); assert.equal(h.state().rows, 0);
});

test('通知从设置覆盖层退出，再展开项目进入目标会话', () => {
  const h = jumpHarness({ coveredBySettings: true, exitOverlay: true }); h.run(); h.advance(2000);
  assert.equal(h.state().sid, 'target'); assert.equal(h.state().rows, 1);
  assert.equal(h.state().groups, 1); assert.equal(h.state().forbidden, 0);
});

test('通知从引导覆盖层退出，原会话不是目标时返回首页后跳转', () => {
  const h = jumpHarness({ view: 'chat', coveredByOnboarding: true, exitOverlay: true }); h.run(); h.advance(2400);
  assert.equal(h.state().sid, 'target'); assert.equal(h.state().backs, 1);
  assert.equal(h.state().forbidden, 0); assert.equal(h.state().timers, 0);
});
test('新跳转取代旧跳转，旧定时器不会继续导航', () => {
  const h = jumpHarness(); h.run(); h.run(); h.advance(1600);
  assert.equal(h.state().rows, 1); assert.equal(h.state().sid, 'target'); assert.equal(h.state().timers, 0);
});
function backHarness(kind) {
  let deviceClicks = 0, settingClicks = 0;
  const hiddenParent = el({ 'aria-hidden': 'true' });
  const device = el({ 'aria-label': '切换设备' }); device.click = () => deviceClicks++;
  const home = el();
  const settingsBack = el({ 'aria-label': '返回工作区', 'data-testid': 'settings-back-button' }, kind === 'hidden-settings' ? hiddenParent : null);
  settingsBack.click = () => settingClicks++;
  const popup = el();
  const document = { body: { innerText: '' }, getElementById: () => null,
    querySelector: () => null,
    querySelectorAll(s) {
      if (s.includes('role="menu"')) return kind === 'popup' ? [popup] : [];
      if (s.includes('settings-back-button')) return kind.includes('settings') ? [settingsBack] : [];
      if (s === 'button, a, div[role="button"], span[role="button"]') return [device];
      if (s.includes('task-new-button')) return [home];
      return [];
    } };
  const result = vm.runInNewContext(backSource, { document, window: {
    innerWidth: 379, innerHeight: 742, getComputedStyle: () => style,
    getSelection: () => null, history: { length: 1 },
  } });
  return { result, deviceClicks, settingClicks };
}
test('首页返回交给原生退出，不点击切换设备', () => {
  assert.deepEqual(backHarness('home'), { result: 'finish', deviceClicks: 0, settingClicks: 0 });
});
test('明确设置返回仍有效，后台隐藏的设置返回不可命中', () => {
  assert.equal(backHarness('settings').settingClicks, 1);
  assert.deepEqual(backHarness('hidden-settings'), { result: 'finish', deviceClicks: 0, settingClicks: 0 });
});
test('打开菜单时先关闭菜单，不触发页面返回', () => {
  assert.deepEqual(backHarness('popup'), { result: 'dismiss-popup', deviceClicks: 0, settingClicks: 0 });
});
test('完整适配脚本重注不增加行为监听，样式和模式仍可更新', () => {
  const nodes = new Map(); let observers = 0, listeners = 0;
  function node() { return { style: {}, appendChild(n) { nodes.set(n.id, n); }, querySelector: () => null,
    querySelectorAll: () => [], remove() { nodes.delete(this.id); } }; }
  const document = { head: node(), body: node(), documentElement: node(),
    getElementById: id => nodes.get(id), createElement: node,
    querySelector: () => null, querySelectorAll: () => [], addEventListener() { listeners++; } };
  const context = { document, window: { addEventListener() { listeners++; }, getComputedStyle: () => style },
    HTMLElement: class { focus() {} }, MutationObserver: class { observe() { observers++; } },
    requestAnimationFrame: () => 1, setTimeout: () => 1, clearTimeout() {}, console };
  const inject = (adaptive, css) => vm.runInNewContext(behaviorSource
    .replaceAll('$dashboardAdaptive', String(adaptive)).replaceAll('$dashboardCss', 'dashboard').replaceAll('$css', css), context);
  inject(true, 'first');
  assert.equal(context.window.__zcodePageBehaviorsInstalled, true);
  const first = { observers, listeners }; assert.ok(observers > 0 && listeners > 0);
  for (let i = 0; i < 10; i++) inject(false, 'updated');
  assert.deepEqual({ observers, listeners }, first);
  assert.equal(nodes.get('zcode-mobile-fast-touch-style').innerHTML, 'updated');
  assert.equal(nodes.has('zcode-dashboard-adaptive-style'), false);
  assert.equal(context.window.__zcodeDashboardAdaptive, false);
});
