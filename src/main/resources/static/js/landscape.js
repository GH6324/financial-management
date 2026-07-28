/* 家庭账房 · 方向控制(v1.6.8 · 尺寸交给 CSS,JS 只管开关)
 *
 * ══ v1.6.2–v1.6.7 一路错在哪 ═══════════════════════════════════
 * 调研了 GitHub 上通行的强制横屏实现(QiShaoXuan/css_tricks 横屏范文、
 * MapoMagpie/comic-looms 漫画阅读器、izzapay 游戏方向插件),它们的共同点是:
 *
 *   尺寸用**交换后的视口单位**(width:100vh / height:100vw),
 *   旋转由**媒体查询**驱动,JS 里一个监听都没有。
 *
 * 我之前反过来做:JS 在进入时量 innerWidth/innerHeight 写死 px,再监听 orientationchange
 * toggle class。两个必然的后果:
 *   ① iOS 竖屏工具栏 ≠ 横屏工具栏 → 竖屏量到的短边在横屏不成立 → 旋转后尺寸错、位移
 *   ② JS 扳正永远晚于 iOS 的旋转动画 → 用户先看到跟着转一圈,再被扳回 = 两次运动
 * 所以本文件不再计算任何尺寸,全部交给 style.css 的 .ls-stage 媒体查询对。
 *
 * ══ 本文件现在只做三件事 ══════════════════════════════════════
 *   1. 横屏模式的开/关(建 shell + 同源 iframe;iframe 提供独立 viewport,
 *      453 处响应式一行不用改就在里面正确,且 iframe 内 md: 生效 → 宽表格真正铺开)
 *   2. 嵌套自检(self !== top)→ 嵌套态不出现横屏入口、跳过首屏印章动画
 *   3. 旋转遮帘 —— iOS 那段旋转动画抹不掉(manifest.orientation iOS 不支持 /
 *      screen.orientation.lock 需 fullscreen / orientationchange 不可 cancel),
 *      只能在旋转期间盖成一张纯色纸面:纯色转动看不出转动。必须瞬盖,否则那一眼就露了。
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

    /* 这里**不再量任何尺寸** —— 舞台的宽高由 style.css 的视口单位交换决定(见 .ls-stage)。
       JS 量出来的 px 在设备旋转后就不成立了,那正是 v1.6.6 旋转后位移的根因。 */
    shell = document.createElement('div');
    shell.className = 'ls-shell';
    shell.setAttribute('role', 'dialog');
    shell.setAttribute('aria-modal', 'true');

    var stage = document.createElement('div');
    stage.className = 'ls-stage';

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

    /* 旋转期间盖上遮帘。注意:这里**只管遮帘**,舞台方向是 CSS 媒体查询的事,
       所以即使这个定时器慢了,布局也已经是对的 —— 慢的只是揭帘时机。 */
    var turnTimer = null;
    function curtain() {
      shell.classList.add('ls-turning');
      if (turnTimer) clearTimeout(turnTimer);
      turnTimer = setTimeout(function () {
        turnTimer = null;
        shell.classList.remove('ls-turning');
      }, 420);
    }

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

  /* ── 普通模式的旋转遮帘 ──────────────────────────────────────────
     普通页面(没进横屏模式)转手机时,排版由 style.css 冻结成竖屏宽度、一个字不动,
     但 iOS 自己的旋转动画照样会演一遍。同样盖成纯纸面。
     用 matchMedia 而不是自己算尺寸:监听的就是 CSS 里那条完全相同的查询,
     所以揭帘时刻与样式切换时刻严格同步,不会出现「样式已换、遮帘还在」。 */
  var FREEZE_Q = '(pointer: coarse) and (orientation: landscape) and (max-height: 479px) and (min-width: 480px)';
  var root = document.documentElement;
  var oriTimer = null;
  function oriCurtain() {
    root.classList.add('ori-turning');
    if (oriTimer) clearTimeout(oriTimer);
    oriTimer = setTimeout(function () {
      oriTimer = null;
      root.classList.remove('ori-turning');
    }, 420);
  }
  if (window.matchMedia) {
    var mq = window.matchMedia(FREEZE_Q);
    var onMq = function () { if (!shell) oriCurtain(); };
    if (mq.addEventListener) mq.addEventListener('change', onMq);
    else if (mq.addListener) mq.addListener(onMq);
  }
  window.addEventListener('orientationchange', function () { if (!shell) oriCurtain(); });
})();
