/* 家庭账房 · 方向控制(v1.6.15 · 原地切断点,不再重载页面)
 *
 * ══ 为什么去掉 iframe ══════════════════════════════════════════
 * v1.6.6–v1.6.14 用「同源 iframe + 独立 viewport」实现整页横屏。它确实解决了
 * 「453 处响应式断点在 844×390 下集体切换」的问题,但代价被用户一眼指出:
 *   点一下横屏 = 一次完整的 /dashboard 后端渲染 + 12 个脚本重跑。
 *   实测 beta(本机、热缓存)**1362ms**;真机走网络更久 —— 用户原话「一段很长的卡顿」。
 * 而且 iframe 是新文档 → 滚动位置必然丢(用户反馈:在旭日章节切完回到顶部)。
 *
 * ══ 现在的做法:原地换断点 ═════════════════════════════════════
 * 本项目的 Tailwind 是 **Play CDN 运行时编译器**,重新赋值 `tailwind.config`
 * 会触发它就地重编译。实测 **114ms**,`md:` 当场从 off 翻到 on。
 * 所以横屏模式 =
 *   ① 把 sm/md 换成「永远匹配」的 raw 查询(lg/xl 仍按宽度 → 与旧 iframe 的 844 档观感一致)
 *   ② body 锁成「长边 × 短边」并在设备竖屏时转 90°(视口单位交换 · 纯 CSS · 见 style.css)
 *   ③ 交回滚动位置(滚动容器从 html 变 body,两套 scrollTop)
 * 零网络请求、零重新渲染、滚动位置保住。
 *
 * ══ 断点基线只有一处 ══════════════════════════════════════════
 * 基线在 layout.html 的 `window.TW_SCREENS_BASE`(含「短边 ≥480」判据),
 * extend(颜色/字体)在 `window.TW_EXTEND_BASE`。这里只换 screens、原样带上 extend,
 * 退出时换回基线 —— 不复制一份配置,避免两处漂移。
 */
(function () {
  'use strict';

  var root = document.documentElement;
  var lastY = 0;          /* 最近一次已知滚动位置(两种滚动容器共用) */
  var exitBtn = null;
  var oriTimer = null;

  /* ── 滚动位置:两种容器的 scrollTop 是两套值,切换时必须手动交接 ── */
  function curY() {
    return Math.max(document.body ? document.body.scrollTop : 0, root.scrollTop || 0, window.scrollY || 0);
  }
  function trackY() { var y = curY(); if (y > 0) lastY = y; }
  /* capture 阶段才能收到元素级滚动(body 当滚动容器时 scroll 不冒泡) */
  document.addEventListener('scroll', trackY, true);

  function restoreY(y) {
    if (!y) return;
    requestAnimationFrame(function () {
      requestAnimationFrame(function () {
        if (document.body && getComputedStyle(document.body).position === 'fixed') document.body.scrollTop = y;
        else window.scrollTo(0, y);
      });
    });
  }

  /* ── 切换后按**章节**还原,而不是按像素 ────────────────────────────
     横屏把布局宽度从短边换成长边,同一段内容的像素位置本来就不一样(实测 2928 → 3630),
     照像素还原必然错位。用户要的是"还在旭日章节",所以记住当前顶部那个带 id 的章节。

     两个坑,都实测踩过:
       ① **不能用 getBoundingClientRect().top 找章节** —— 横屏时 body 被 rotate(90°),
          rect 是屏幕坐标,阅读流的"上"映射到屏幕的"右";照它挑会挑到毫不相干的元素
          (实测退出后章节漂到 top=3125)。要在**布局坐标系**里算:累加 offsetTop。
       ② **不能用 scrollIntoView** —— 同样有视觉坐标语义问题;直接写 scrollTop 最确定。 */
  function layoutTop(el) {
    var y = 0;
    while (el && el !== document.body) { y += el.offsetTop || 0; el = el.offsetParent; }
    return y;
  }
  function bodyIsScroller() {
    return !!document.body && getComputedStyle(document.body).position === 'fixed';
  }
  function currentAnchor() {
    var top = bodyIsScroller() ? document.body.scrollTop : (window.scrollY || root.scrollTop || 0);
    var nodes = document.querySelectorAll('main [id]');
    var best = null, bestDelta = -1e9;
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!el.id || el.offsetHeight < 40) continue;      /* 跳过小锚点 */
      var d = layoutTop(el) - top;
      if (d <= 90 && d > bestDelta) { bestDelta = d; best = el.id; }
    }
    return best;
  }
  function setScrollTop(y) {
    if (bodyIsScroller()) document.body.scrollTop = y;
    else window.scrollTo(0, y);
  }
  /* 还原要**重复几次**:Tailwind 的重编译是异步的(实测约 114ms),rAF×2 早于它落定 ——
     只做一次会被随后的重排冲掉。写同一个 scrollTop 是幂等的,多做几次没有副作用。 */
  function restoreAnchor(id, fallbackY) {
    var run = function () {
      var el = id && document.getElementById(id);
      setScrollTop(el ? Math.max(0, layoutTop(el) - 8) : (fallbackY || 0));
    };
    requestAnimationFrame(function () { requestAnimationFrame(run); });
    setTimeout(run, 160);
    setTimeout(run, 400);
  }

  /* ── 断点切换 ── */
  function setScreens(screens) {
    if (!window.tailwind || !screens) return false;
    window.tailwind.config = { theme: { screens: screens, extend: window.TW_EXTEND_BASE || {} } };
    return true;
  }
  /* 横屏档:sm/md 强制命中 —— 布局宽度是长边,但媒体查询看的是 innerWidth(短边),
     所以不能靠 min-width,只能强制;lg/xl 仍按宽度。 */
  var WIDE_SCREENS = {
    sm: { raw: '(min-width: 1px)' },
    md: { raw: '(min-width: 1px)' },
    lg: { raw: '(min-width: 1024px)' },
    xl: { raw: '(min-width: 1280px)' },
    '2xl': { raw: '(min-width: 1536px)' }
  };

  function svgIcon(d, size) {
    var NS = 'http://www.w3.org/2000/svg';
    var s = document.createElementNS(NS, 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('width', size); s.setAttribute('height', size);
    s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor');
    s.setAttribute('stroke-width', '2');
    s.setAttribute('stroke-linecap', 'round'); s.setAttribute('stroke-linejoin', 'round');
    s.setAttribute('aria-hidden', 'true');
    var pa = document.createElementNS(NS, 'path'); pa.setAttribute('d', d); s.appendChild(pa);
    return s;
  }

  function syncBtn() {
    var on = root.classList.contains('ls-wide');
    document.querySelectorAll('[data-orientation-toggle]').forEach(function (b) {
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
      if (b !== exitBtn) b.title = on ? '退回竖屏' : '整页横屏查看(适合宽表格)';
    });
  }

  /* 横屏时把「本页目录」钮搬进导航行的 flex 流(结构上不可能与隐私钮重叠) */
  function tocIntoNav(into) {
    var fab = document.querySelector('.toc-fab');
    var acts = document.querySelector('.nav-actions');
    if (!fab || !acts) return;
    if (into) {
      if (fab.parentElement !== acts) { fab.setAttribute('data-toc-moved', '1'); acts.insertBefore(fab, acts.firstChild); }
    } else if (fab.getAttribute('data-toc-moved') && fab.parentElement === acts) {
      document.body.appendChild(fab); fab.removeAttribute('data-toc-moved');
    }
  }

  /* 退出钮必须**放进导航行**,不能用 position:fixed ————————————————————
     body 被 transform 后,里面 position:fixed 的元素包含块变成 body,
     于是它跟着内容一起滚走(实测 rect 3413,730 —— 滚到屏外)。
     放进 .nav-actions:随 sticky 导航常驻、不会被 iPhone 圆角切、也不用给它预留边距。 */
  function ensureExit() {
    if (exitBtn && exitBtn.isConnected) return exitBtn;
    exitBtn = document.createElement('button');
    exitBtn.type = 'button';
    exitBtn.className = 'ls-exit';
    exitBtn.setAttribute('data-orientation-toggle', '');
    exitBtn.setAttribute('title', '退回竖屏');
    exitBtn.appendChild(svgIcon('M18 6 6 18M6 6l12 12', 12));
    var t = document.createElement('span'); t.textContent = '退出横屏';
    exitBtn.appendChild(t);
    var acts = document.querySelector('.nav-actions');
    (acts || document.body).appendChild(exitBtn);
    return exitBtn;
  }

  function enter() {
    if (root.classList.contains('ls-wide')) return;
    var y = curY() || lastY, anchor = currentAnchor();
    if (!setScreens(WIDE_SCREENS)) return;      /* 运行时编译器不在 → 不做半套 */
    root.classList.add('ls-wide');
    ensureExit();
    tocIntoNav(true);
    /* 图表按容器宽度自适应:body 宽度变了,派发一次 resize 让 Chart.js / ECharts 重排 */
    window.dispatchEvent(new Event('resize'));
    restoreAnchor(anchor, y);
    syncBtn();
  }

  function exit() {
    if (!root.classList.contains('ls-wide')) return;
    var y = curY() || lastY, anchor = currentAnchor();
    root.classList.remove('ls-wide');
    setScreens(window.TW_SCREENS_BASE);
    tocIntoNav(false);
    if (exitBtn) { exitBtn.remove(); exitBtn = null; }
    window.dispatchEvent(new Event('resize'));
    restoreAnchor(anchor, y);
    syncBtn();
  }

  window.toggleOrientation = function () {
    if (root.classList.contains('ls-wide')) exit(); else enter();
  };

  document.addEventListener('click', function (e) {
    if (!e.target.closest) return;
    var b = e.target.closest('[data-orientation-toggle]');
    if (b) { e.preventDefault(); window.toggleOrientation(); }
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && root.classList.contains('ls-wide')) exit();
  });

  document.addEventListener('DOMContentLoaded', syncBtn);
  syncBtn();

  /* ══ 普通模式(未进横屏)· 把内容钉在设备坐标系 ══════════════════════
     样式在 style.css 的冻结块。这里只做两件 CSS 做不到的事:
       ① 旋转符号:screen.orientation.angle 是 viewport 相对自然方向**顺时针**转过的角度,
          抵消就是转 -angle → angle 270(设备顺时针)挂 html.ori-cw 取 +90°。
          iOS 16.4+ 有 screen.orientation,更老回落 window.orientation。
       ② 滚动位置交接 + 旋转遮帘(iOS 自己的旋转动画抹不掉,只能盖成纯纸面)。 */
  var FREEZE_Q = '(pointer: coarse) and (orientation: landscape) and (max-height: 479px) and (min-width: 480px)';

  function syncAngle() {
    var a = null;
    if (window.screen && window.screen.orientation && typeof window.screen.orientation.angle === 'number') {
      a = window.screen.orientation.angle;
    } else if (typeof window.orientation === 'number') {
      a = window.orientation < 0 ? 270 : window.orientation;
    }
    root.classList.toggle('ori-cw', a === 270);
  }
  function oriCurtain() {
    if (root.classList.contains('ls-wide')) return;
    root.classList.add('ori-turning');
    if (oriTimer) clearTimeout(oriTimer);
    oriTimer = setTimeout(function () { oriTimer = null; root.classList.remove('ori-turning'); }, 460);
  }
  function onTurn() { syncAngle(); oriCurtain(); restoreY(lastY); }

  syncAngle();
  if (window.matchMedia) {
    var mq = window.matchMedia(FREEZE_Q);
    if (mq.addEventListener) mq.addEventListener('change', onTurn);
    else if (mq.addListener) mq.addListener(onTurn);
  }
  window.addEventListener('orientationchange', onTurn);
  if (window.screen && window.screen.orientation && window.screen.orientation.addEventListener) {
    window.screen.orientation.addEventListener('change', onTurn);
  }
})();
