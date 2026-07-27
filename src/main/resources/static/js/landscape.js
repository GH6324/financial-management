/* 家庭账房 · 横屏查看(v1.6.2 · 全局 + 局部两种模式)
 *
 * ── 平台事实(先说清楚,免得再走弯路)────────────────────────────────
 *  · `screen.orientation.lock()`  —— iOS Safari / iOS PWA 均不支持(Android Chrome 全屏下可用)
 *  · manifest `"orientation":"portrait"` —— 我们早就设了,但 **iOS 不读这个字段**,
 *    所以「屏蔽系统自动横屏」在 iOS 上做不到,这是平台限制,不是没实现。
 *  结论:iOS 上只能自己用 CSS transform 转内容(本文件),并且必须与系统横屏「和平共处」。
 *
 * ── 两种模式 ────────────────────────────────────────────────
 *  全局:toggleGlobalLandscape() —— 转 <body>,整页横屏(nav / 浮钮 / 内容全跟着转)。
 *        入口在 nav(与 ☰ 、隐私眼并列),是顶级功能。
 *  局部:<button data-landscape="#pivot"> —— 只把某个元素移进旋转层(交叉表保留此入口)。
 *
 * ── 与系统横屏的关系(v1.6.2 修正)────────────────────────────────
 *  v1.6.1 的做法是「检测到物理横屏 → 撤掉 rotate 但留着全屏层」,
 *  结果用户转手机时会经历「系统重排 + 我们撤旋转」两次跳动。
 *  现在改成:**检测到物理横屏就直接整体退出** —— 系统已经给了真横屏,我们的层是多余的,
 *  退出后用户看到的就是原生横屏页面,只剩系统那一次不可避免的重排。
 */
(function () {
  'use strict';

  var G_CLASS = 'force-landscape';
  var ON_CLASS = 'rot-on';
  var SS_KEY = 'landscapeGlobal';
  var active = null;   // 局部模式的当前实例

  function isPhysicalLandscape() {
    return window.innerWidth > window.innerHeight;
  }

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

  function fireResize() { try { window.dispatchEvent(new Event('resize')); } catch (e) {} }

  /* ══════════ 全局横屏 ══════════ */

  function globalOn() { return document.documentElement.classList.contains(G_CLASS); }

  function setGlobal(on, silent) {
    var html = document.documentElement;
    if (on) {
      // 已经物理横屏就没必要再转 —— 转了反而变成竖的
      if (isPhysicalLandscape()) {
        if (!silent) toast('手机已经是横屏了,直接看就行');
        return false;
      }
      html.classList.add(G_CLASS);
      if (!silent) toast('已横屏 · 把手机转过来看 · 再点一次恢复');
    } else {
      html.classList.remove(G_CLASS);
    }
    try { sessionStorage.setItem(SS_KEY, on ? '1' : '0'); } catch (e) {}
    syncGlobalBtn();
    setTimeout(fireResize, 60);   // 图表按新尺寸重绘
    return true;
  }

  function toast(msg) {
    if (typeof window.showToast === 'function') window.showToast({ message: msg, level: 'info' });
  }

  function syncGlobalBtn() {
    var on = globalOn();
    document.querySelectorAll('[data-landscape-global]').forEach(function (b) {
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
      b.title = on ? '恢复竖屏' : '整页横屏查看(适合看宽表格)';
      var t = b.querySelector('.ls-label');
      if (t) t.textContent = on ? '恢复竖屏' : '横屏看';
    });
  }

  window.toggleGlobalLandscape = function () { setGlobal(!globalOn()); };

  /* ══════════ 局部横屏(某个元素)══════════ */

  function enterLocal(target, title) {
    if (active) return;
    if (isPhysicalLandscape()) { toast('手机已经是横屏了,直接看就行'); return; }

    var ph = document.createElement('div');
    ph.className = 'rot-placeholder';
    target.parentNode.insertBefore(ph, target);

    var shell = document.createElement('div');
    shell.className = 'rot-shell';
    shell.setAttribute('role', 'dialog');
    shell.setAttribute('aria-modal', 'true');

    var inner = document.createElement('div');
    inner.className = 'rot-inner rot-rotate';

    var bar = document.createElement('div');
    bar.className = 'rot-bar';
    var label = document.createElement('span');
    label.className = 'rot-title';
    label.appendChild(svgIcon(['M3 7h13a2 2 0 0 1 2 2v8', 'M14 3l4 4-4 4'], 13));
    var t = document.createElement('span');
    t.textContent = (title || '横屏查看');
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

    inner.appendChild(target);
    shell.appendChild(inner);
    document.body.appendChild(shell);
    document.documentElement.classList.add(ON_CLASS);

    var pushed = false;
    try { history.pushState({ rot: 1 }, ''); pushed = true; } catch (e) {}

    function exit(fromPop) {
      if (!active) return;
      active = null;
      if (ph.parentNode) ph.parentNode.replaceChild(target, ph);
      shell.remove();
      document.documentElement.classList.remove(ON_CLASS);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('popstate', onPop);
      window.removeEventListener('resize', onGeo);
      window.removeEventListener('orientationchange', onOrient);
      fireResize();
      if (pushed && !fromPop) { try { history.back(); } catch (e) {} }
    }
    function onKey(e) { if (e.key === 'Escape') exit(false); }
    function onPop() { exit(true); }
    /* 用户真把手机转横了 → 系统已给真横屏,我们整体退出(不再只撤 rotate) */
    function onGeo() { if (isPhysicalLandscape()) exit(false); }
    function onOrient() { setTimeout(onGeo, 220); }   // iOS 转屏后尺寸要一拍才稳

    exitBtn.addEventListener('click', function () { exit(false); });
    document.addEventListener('keydown', onKey);
    window.addEventListener('popstate', onPop);
    window.addEventListener('resize', onGeo);
    window.addEventListener('orientationchange', onOrient);

    active = { exit: exit };
    setTimeout(fireResize, 60);
  }

  /* ══════════ 绑定 ══════════ */

  document.addEventListener('click', function (e) {
    if (!e.target.closest) return;
    var gb = e.target.closest('[data-landscape-global]');
    if (gb) { e.preventDefault(); window.toggleGlobalLandscape(); return; }
    var lb = e.target.closest('[data-landscape]');
    if (lb) {
      var el = document.querySelector(lb.getAttribute('data-landscape'));
      if (el) { e.preventDefault(); enterLocal(el, lb.getAttribute('data-landscape-title')); }
    }
  });

  /* 全局横屏下用户转了手机 → 系统接管,撤掉我们的旋转(否则又转回竖的) */
  function globalGeoSync() {
    if (globalOn() && isPhysicalLandscape()) setGlobal(false, true);
  }
  window.addEventListener('resize', globalGeoSync);
  window.addEventListener('orientationchange', function () { setTimeout(globalGeoSync, 220); });

  document.addEventListener('DOMContentLoaded', function () {
    syncGlobalBtn();
    globalGeoSync();
  });
  window.exitLandscape = function () { if (active) active.exit(false); setGlobal(false, true); };
})();
