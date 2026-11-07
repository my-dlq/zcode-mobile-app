// 从 Kotlin 提取实际探测脚本；不模拟登录凭据或调用远端服务。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname,
  '../main/java/ai/zcode/remote/ui/remote/web/TaskListVisibilityScript.kt'), 'utf8').split('"""')[1];

function detect({ present = true, text = '任务列表', parentHidden = false,
  ariaHidden = false, inert = false, display = 'block', opacity = '1',
  visibility = 'visible', width = 80, top = 100, height = 24, left = 8 } = {}) {
  const root = { nodeType: 1, parentElement: null, hidden: parentHidden,
    getAttribute: name => name === 'aria-hidden' && ariaHidden ? 'true' : null,
    hasAttribute: name => name === 'inert' && inert };
  const title = { nodeType: 1, parentElement: root, hidden: false, textContent: text,
    getAttribute: () => null, hasAttribute: () => false,
    getBoundingClientRect: () => ({ width, height, top, left, bottom: top + height, right: left + width }) };
  const document = { querySelector(selector) {
    assert.equal(selector, '[data-testid="sidebar-task-list-region"]');
    return present ? { querySelector(selector) {
      assert.equal(selector, '[data-testid="sidebar-list-title"]');
      return title;
    } } : null;
  } };
  return vm.runInNewContext(source, { document, window: {
    innerWidth: 400, innerHeight: 800,
    getComputedStyle: () => ({ display, visibility, opacity })
  } });
}

test('可见的任务列表触发，无任务条目也成立', () => {
  assert.equal(detect(), true);
  assert.equal(detect({ text: 'Task list' }), true);
  assert.equal(detect({ text: 'Tasks' }), true);
});
test('登录页、加载页、错误页没有列表时不触发', () => {
  assert.equal(detect({ present: false }), false);
  assert.equal(detect({ text: '登录后继续使用 Web 远程控制' }), false);
});
test('隐藏祖先、无交互子树、透明和折叠元素不算看见列表', () => {
  for (const options of [{ parentHidden: true }, { ariaHidden: true }, { inert: true },
    { display: 'none' }, { opacity: '0' }, { visibility: 'hidden' }, { visibility: 'collapse' }]) {
    assert.equal(detect(options), false);
  }
});
test('屏幕外或没有尺寸的标题不触发', () => {
  for (const options of [{ width: 0 }, { height: 0 }, { top: 800 }, { top: -30 }, { left: 401 }]) {
    assert.equal(detect(options), false);
  }
});
test('多次探测只读页面，不点击或写入DOM', () => {
  assert.equal(detect(), true);
  assert.equal(detect(), true);
});
