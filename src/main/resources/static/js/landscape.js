/* 家庭账房 · 整页横屏(v1.6.6 iframe 隔离 + v1.6.7 旋转遮帘)
 *
 * ══ 为什么用 iframe(前四版都失败在同一处)══════════════════════
 * v1.6.2–v1.6.4 试过直接 transform <body>,真机三个副作用(菜单展开 / 浮钮消失 / 转手机大幅重排),
 * 根因是结构性的:**CSS 媒体查询只认 viewport,不认 transform**。
 * 设备横屏时 viewport = 844px,于是模板里 453 处 Tailwind sm:/md: 与 13 处自有 @media
 * 全部切到宽屏分支;而 Tailwind 那 453 处是媒体查询编译产物,无法用 class 覆盖。
 *
 * ══ v1.6.7 补:断点本身也修了 ═══════════════════════════════
 * 上面那句"无法用 class 覆盖"是对的,但漏了另一条路:**断点可以不只看宽度**。
 * 手机横屏的真正特征是短边只有 390,不是宽边有 844。layout.html 的 tailwind.config
 * 把 5 个断点全部加上 `and (min-height: 480px)`,自有 @media 同步 —— 于是普通模式下
 * 转手机再也不会切宽屏布局。本文件的 iframe 方案仍然保留,它解决的是另一半:
 * 主动点横屏时要一个真正 844 宽的宽屏视图(iframe 里走「只看宽度」的断点)。
 *
 * iframe 有**自己的 viewport**。把页面装进一个固定尺寸的 iframe:
 *   · iframe 内的媒体查询基于 iframe 宽度 → 453 处响应式一行不用改,全部正确
 *   · iframe 尺寸创建后固定不变 → 用户转手机时**内部零重排**(这正是用户要的)
 *   · 只有外层那一个 iframe 元素的 transform 在变,内容不动
 *
 * ══ 尺寸取横屏布局(长边 × 短边)══════════════════════════════
 * iframe 宽 = 屏幕长边(如 844)、高 = 短边(如 390):
 *   · 内部 viewport 844 → md: 生效 → 宽表格真正铺开(这才是「横屏化」的目的)
 *   · 设备已横屏 → transform: none,直接铺满
 *   · 设备还竖着 → 转 90° 放进竖屏屏幕
 * 两种情况 iframe 内 viewport 都是 844,所以转手机只换外层 transform,**内容零重排**。
 *
 * ══ 嵌套自检 ══════════════════════════════════════════════
 * iframe 里加载的是同一套页面,靠 `window.self !== window.top` 自检:
 * 嵌套态不再渲染横屏入口、跳过首屏印章动画 —— 不需要任何 URL 参数或服务端改动。
 */
(function () {
  'use strict';

  var EMBEDDED = false;
  try { EMBEDDED = window.self !== window.top; } catch (e) { EMBEDDED = true; }

  /* ── 嵌套态:关掉横屏入口与首屏动画,其余一切照常 ── */
  if (EMBEDDED) {
    document.documentElement.classList.add('is-embedded');
    var killOverlay = function () {
      var o = document.getElementById('page-overlay');
      if (o) o.classList.add('hidden');
    };
    killOverlay();
    document.addEventListener('DOMContentLoaded', killOverlay);
    return;
  }

  var shell = null;

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

  function syncBtn() {
    document.querySelectorAll('[data-orientation-toggle]').forEach(function (b) {
      b.setAttribute('aria-pressed', shell ? 'true' : 'false');
      b.title = shell ? '退出横屏' : '整页横屏查看 · 转动手机页面不会重排';
    });
  }

  function enter() {
    if (shell) return;

    /* 尺寸在进入时定一次,之后**固定不变** —— 这是「转手机零重排」的关键。
       用 inner*(贴合当前可视区,已扣掉 iOS 地址栏)而不是 screen(会多出地址栏高度导致底部被裁)。 */
    var L = Math.max(window.innerWidth, window.innerHeight);
    var S = Math.min(window.innerWidth, window.innerHeight);

    shell = document.createElement('div');
    shell.className = 'ls-shell';
    shell.setAttribute('role', 'dialog');
    shell.setAttribute('aria-modal', 'true');

    var stage = document.createElement('div');
    stage.className = 'ls-stage';
    stage.style.width = L + 'px';
    stage.style.height = S + 'px';
    stage.style.setProperty('--ls-short', S + 'px');

    var frame = document.createElement('iframe');
    frame.className = 'ls-frame';
    frame.setAttribute('title', '横屏视图');
    /* 同源 iframe:cookie / sessionStorage 共享 → 登录态、隐私模式、字号档位全部沿用 */
    frame.src = window.location.href;
    stage.appendChild(frame);

    var bar = document.createElement('div');
    bar.className = 'ls-bar';
    var exitBtn = document.createElement('button');
    exitBtn.type = 'button';
    exitBtn.className = 'ls-exit';
    exitBtn.appendChild(svgIcon('M18 6 6 18M6 6l12 12', 13));
    var et = document.createElement('span');
    et.textContent = '退出横屏';
    exitBtn.appendChild(et);
    bar.appendChild(exitBtn);
    stage.appendChild(bar);

    shell.appendChild(stage);
    document.body.appendChild(shell);
    document.documentElement.classList.add('ls-on');

    /* 设备方向只决定外层要不要转 90°;iframe 内部尺寸恒定,内容不受影响 */
    function syncRotation() {
      var devLandscape = window.innerWidth > window.innerHeight;
      stage.classList.toggle('ls-rotate', !devLandscape);
    }
    syncRotation();

    /* ── 旋转遮帘(v1.6.7)──────────────────────────────────────────────
       iOS Safari 自己那段约 0.4s 的旋转动画**抹不掉**:manifest.orientation iOS 不支持
       (MDN BCD:safari false)、screen.orientation.lock() 需要 fullscreen 而 iPhone Safari
       没有元素级 fullscreen、orientationchange 不可 cancel。
       末态本来就是对的(内容钉在设备坐标系、iframe 内零重排),坏的只是那段过程 ——
       用户看到内容跟着系统转了一圈再被我们扳回来,就以为"没屏蔽系统旋转"。
       所以在旋转期间把内容盖掉:只留一张纯色纸面。纯色转动看不出转动。 */
    var turnTimer = null;
    function curtain() {
      shell.classList.add('ls-turning');
      if (turnTimer) clearTimeout(turnTimer);
      turnTimer = setTimeout(function () {
        turnTimer = null;
        syncRotation();
        shell.classList.remove('ls-turning');
      }, 420);
    }
    function onResize() { if (!turnTimer) syncRotation(); }

    var pushed = false;
    try { history.pushState({ ls: 1 }, ''); pushed = true; } catch (e) {}

    function exit(fromPop) {
      if (!shell) return;
      /* iframe 里可能已经导航到别的页面 —— 退出时把父页面同步过去,别让用户白点 */
      var inner = null;
      try {
        var loc = frame.contentWindow && frame.contentWindow.location;
        if (loc && loc.href && loc.href !== 'about:blank') inner = loc.href;
      } catch (e) { inner = null; }

      shell.remove();
      shell = null;
      document.documentElement.classList.remove('ls-on');
      if (turnTimer) { clearTimeout(turnTimer); turnTimer = null; }
      window.removeEventListener('resize', onResize);
      window.removeEventListener('orientationchange', onOrient);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('popstate', onPop);
      syncBtn();
      if (pushed && !fromPop) { try { history.back(); } catch (e) {} }
      if (inner && inner.split('#')[0] !== window.location.href.split('#')[0]) {
        window.location.href = inner;
      }
    }
    function onKey(e) { if (e.key === 'Escape') exit(false); }
    function onPop() { exit(true); }
    function onOrient() { curtain(); }

    exitBtn.addEventListener('click', function () { exit(false); });
    window.addEventListener('resize', onResize);
    window.addEventListener('orientationchange', onOrient);
    document.addEventListener('keydown', onKey);
    window.addEventListener('popstate', onPop);

    syncBtn();
  }

  window.toggleOrientation = function () {
    if (shell) {
      var b = document.querySelector('.ls-exit');
      if (b) b.click();
      return;
    }
    enter();
  };

  document.addEventListener('click', function (e) {
    if (!e.target.closest) return;
    var b = e.target.closest('[data-orientation-toggle]');
    if (b) { e.preventDefault(); window.toggleOrientation(); }
  });

  document.addEventListener('DOMContentLoaded', syncBtn);
  syncBtn();
})();
