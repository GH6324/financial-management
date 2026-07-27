/* 家庭账房 · 横屏查看(v1.6.5 · 回到「局部旋转」)
 *
 * ══ 为什么放弃「整页旋转」 ══════════════════════════════════════
 * v1.6.2–v1.6.4 试过转 <body> 做整页横屏,真机暴露三个问题(用户反馈):
 *   ① 横屏后顶部菜单会莫名展开   ② 三个浮钮消失/不稳定   ③ 转手机时页面大幅重排
 * 根因是同一个,而且是结构性的:
 *   **CSS 媒体查询只认 viewport,不认我们的 transform。**
 *   设备横屏时 viewport = 844px,于是
 *     · 我为 v1.6 加的 7 处 max-width:767/640 移动端样式全部失效
 *       (KPI 带回网格、汇总带回网格、折叠条 summary 显形、浮钮 display:none ← 问题②)
 *     · 模板里 347 处 Tailwind sm:/md: 前缀全部切到宽屏分支 → 布局跳成 PC 版 ← 问题③
 *   而 Tailwind 那 347 处是媒体查询编译出来的,**无法用 class 覆盖**。
 *   要让「旋转后仍按窄屏渲染」,只能把全站响应式改成 container query 或塞进 iframe —— 都不现实。
 *
 * ══ 现在的做法 ══════════════════════════════════════════════
 * 只旋转**具体的宽内容元素**(交叉透视表这类),不动 body:
 *   · 被旋转的是一个独立容器,它内部不依赖响应式断点 → 不会触发上面任何一条
 *   · 页面其余部分完全不受影响,浮钮/菜单/布局都保持原状
 * 页面通过 `data-landscape-target` 声明「本页可横屏看的元素」;没有声明的页面浮钮自动隐藏。
 *
 * 关于「屏蔽系统横竖屏」:iOS 既不支持 screen.orientation.lock(),也不读 manifest 的
 * orientation 字段,而 CSS 旋转方案又与响应式冲突 —— 因此**页面级方向锁定做不到**,
 * 这是平台与技术栈的共同约束,不是没实现。局部横屏不受此限,因为它不关心设备方向。
 */
(function () {
  'use strict';

  var active = null;

  function svgIcon(paths, size) {
    var NS = 'http://www.w3.org/2000/svg';
    var s = document.createElementNS(NS, 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('width', size || 14);
    s.setAttribute('height', size || 14);
    s.setAttribute('fill', 'none');
    s.setAttribute('stroke', 'currentColor');
    s.setAttribute('stroke-width', '2');
    s.setAttribute('stroke-linecap', 'round');
    s.setAttribute('stroke-linejoin', 'round');
    s.setAttribute('aria-hidden', 'true');
    (Array.isArray(paths) ? paths : [paths]).forEach(function (d) {
      var p = document.createElementNS(NS, 'path');
      p.setAttribute('d', d);
      s.appendChild(p);
    });
    return s;
  }

  function target() { return document.querySelector('[data-landscape-target]'); }

  /** 浮钮只在「本页有可横屏内容」时出现 —— 没有目标就没有意义 */
  function syncBtn() {
    var has = !!target();
    document.querySelectorAll('[data-orientation-toggle]').forEach(function (b) {
      b.style.display = has ? '' : 'none';
      b.setAttribute('aria-pressed', active ? 'true' : 'false');
      b.title = active ? '退出横屏查看' : '把宽表格转成横屏看(页面其余部分不变)';
    });
  }

  function resizeCharts() {
    try {
      if (window.echarts) {
        document.querySelectorAll('#sunburst, .echart-box').forEach(function (el) {
          var i = window.echarts.getInstanceByDom(el);
          if (i) i.resize();
        });
      }
    } catch (e) {}
  }

  function enter(el) {
    if (active) return;
    var ph = document.createElement('div');
    ph.className = 'rot-placeholder';
    el.parentNode.insertBefore(ph, el);

    var shell = document.createElement('div');
    shell.className = 'rot-shell';
    shell.setAttribute('role', 'dialog');
    shell.setAttribute('aria-modal', 'true');

    var inner = document.createElement('div');
    inner.className = 'rot-inner';

    var bar = document.createElement('div');
    bar.className = 'rot-bar';
    var label = document.createElement('span');
    label.className = 'rot-title';
    label.appendChild(svgIcon(['M3 7h13a2 2 0 0 1 2 2v8', 'M14 3l4 4-4 4'], 13));
    var t = document.createElement('span');
    t.textContent = el.getAttribute('data-landscape-target') || '横屏查看';
    label.appendChild(t);

    var exitBtn = document.createElement('button');
    exitBtn.type = 'button';
    exitBtn.className = 'rot-exit';
    exitBtn.appendChild(svgIcon('M18 6 6 18M6 6l12 12', 13));
    var et = document.createElement('span');
    et.textContent = '退出横屏';
    exitBtn.appendChild(et);

    bar.appendChild(label);
    bar.appendChild(exitBtn);
    inner.appendChild(bar);

    var hint = document.createElement('div');
    hint.className = 'rot-hint';
    hint.appendChild(svgIcon(['M3 7h13a2 2 0 0 1 2 2v8', 'M14 3l4 4-4 4'], 15));
    var ht = document.createElement('span');
    ht.textContent = '把手机转横过来看';
    hint.appendChild(ht);
    inner.appendChild(hint);

    inner.appendChild(el);
    shell.appendChild(inner);
    document.body.appendChild(shell);
    document.documentElement.classList.add('rot-on');

    var pushed = false;
    try { history.pushState({ rot: 1 }, ''); pushed = true; } catch (e) {}

    function sync() {
      /* 设备已经物理横屏 → 不必再由我们转(否则又转回竖的);仅在竖屏时代转。
         注意这里只影响这一个覆盖层,不碰 body,所以不会牵动全站响应式。 */
      var portrait = window.innerHeight >= window.innerWidth;
      inner.classList.toggle('rot-rotate', portrait);
      hint.style.display = portrait ? '' : 'none';
      resizeCharts();
    }
    sync();

    function exit(fromPop) {
      if (!active) return;
      active = null;
      if (ph.parentNode) ph.parentNode.replaceChild(el, ph);
      shell.remove();
      document.documentElement.classList.remove('rot-on');
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('popstate', onPop);
      window.removeEventListener('resize', sync);
      window.removeEventListener('orientationchange', onOrient);
      syncBtn();
      resizeCharts();
      if (pushed && !fromPop) { try { history.back(); } catch (e) {} }
    }
    function onKey(e) { if (e.key === 'Escape') exit(false); }
    function onPop() { exit(true); }
    function onOrient() { setTimeout(sync, 220); }

    exitBtn.addEventListener('click', function () { exit(false); });
    document.addEventListener('keydown', onKey);
    window.addEventListener('popstate', onPop);
    window.addEventListener('resize', sync);
    window.addEventListener('orientationchange', onOrient);

    active = { exit: exit };
    syncBtn();
  }

  window.toggleOrientation = function () {
    if (active) { active.exit(false); return; }
    var el = target();
    if (!el) {
      if (typeof window.showToast === 'function') {
        window.showToast({ message: '本页没有需要横屏看的宽内容', level: 'info' });
      }
      return;
    }
    enter(el);
  };

  document.addEventListener('click', function (e) {
    if (!e.target.closest) return;
    var b = e.target.closest('[data-orientation-toggle]');
    if (b) { e.preventDefault(); window.toggleOrientation(); }
  });

  document.addEventListener('DOMContentLoaded', syncBtn);
  syncBtn();
})();
