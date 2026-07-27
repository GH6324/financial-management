/* 家庭账房 · 自建横屏查看(v1.6.1)
 *
 * 背景:iOS Safari / PWA 不支持 screen.orientation.lock(),所以「一键横屏」拿不到系统能力。
 * 但游戏类 / 漫画类 PWA 早就在用另一条路:自己用 CSS transform 把内容旋转 90°,
 * 视口宽高互换 —— 用户看到的就是横屏。本文件把这条路做成通用能力。
 *
 * 用法(声明式,零配置):
 *   <button data-landscape="#pivot" data-landscape-title="交叉透视">横屏看</button>
 *   点击 → 把 #pivot 移入全屏旋转层;退出 → 原位放回(不克隆 DOM,
 *   所以 lens.js 之类持有该容器引用、或按 id 重渲染的脚本都不受影响)。
 *
 * 关键实现点:
 *   ① 旋转壳 .rot-inner 宽高互换(width:100dvh / height:100vw)+ translate(100vw,0) rotate(90deg)
 *      + transform-origin:0 0 —— 这样旋转后正好铺满屏幕。
 *   ② 用 100dvh 而不是 100vh:iOS 地址栏收放会改变 vh,dvh 才是当前可视高度。
 *   ③ 触摸滚动:容器被旋转后,浏览器把手指位移映射进容器局部坐标系,
 *      所以用户上下滑 = 视觉上的上下滑,方向是对的,不需要额外处理。
 *   ④ 工具条放在 .rot-inner 内部 —— 跟着一起旋转,退出按钮才在视觉上的正确位置。
 *   ⑤ 退出途径给三个:按钮 / Esc / 浏览器返回(pushState 拦一层),避免用户困在里面出不去。
 */
(function () {
  'use strict';

  var ON_CLASS = 'rot-on';
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

  function enter(target, title) {
    if (active) return;

    // 占位符:退出时按原位放回,避免破坏兄弟节点顺序
    var ph = document.createElement('div');
    ph.className = 'rot-placeholder';
    target.parentNode.insertBefore(ph, target);

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
    t.textContent = (title || '横屏查看') + ' · 横屏';
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

    /* 「把手机转过来」提示。
       这个方案的本质是把内容转 90°,所以设备还竖着时看到的是躺倒的画面 —— 必须明确告诉用户转设备。
       而一旦用户真的转了手机,浏览器自身已经是横屏,这时候我们就要把旋转撤掉(否则又转回竖的)。 */
    var hint = document.createElement('div');
    hint.className = 'rot-hint';
    hint.appendChild(svgIcon(['M3 7h13a2 2 0 0 1 2 2v8', 'M14 3l4 4-4 4'], 15));
    var ht = document.createElement('span');
    ht.textContent = '把手机转横过来看 · 转好后画面自动扶正';
    hint.appendChild(ht);
    inner.appendChild(hint);

    inner.appendChild(target);
    shell.appendChild(inner);
    document.body.appendChild(shell);
    document.documentElement.classList.add(ON_CLASS);

    /* 设备方向同步:竖屏 → 我们自己转 90°;已物理横屏 → 撤掉旋转,交给系统 */
    function sync() {
      var portrait = window.innerHeight >= window.innerWidth;
      inner.classList.toggle('rot-rotate', portrait);
      hint.style.display = portrait ? '' : 'none';
      try { window.dispatchEvent(new Event('resize')); } catch (e) {}
    }
    sync();
    var onResize = function () { sync(); };
    var onOrient = function () { setTimeout(sync, 220); };   // iOS 转屏后尺寸要一拍才稳
    window.addEventListener('resize', onResize);
    window.addEventListener('orientationchange', onOrient);

    // 浏览器返回也能退出(iOS PWA standalone 下没有系统返回,但安卓/桌面有)
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
      window.removeEventListener('resize', onResize);
      window.removeEventListener('orientationchange', onOrient);
      try { window.dispatchEvent(new Event('resize')); } catch (e) {}   // 让图表回到竖屏尺寸
      if (pushed && !fromPop) { try { history.back(); } catch (e) {} }
    }
    function onKey(e) { if (e.key === 'Escape') exit(false); }
    function onPop() { exit(true); }

    exitBtn.addEventListener('click', function () { exit(false); });
    document.addEventListener('keydown', onKey);
    window.addEventListener('popstate', onPop);

    active = { exit: exit };
    // 旋转后重算图表尺寸(ECharts / Chart.js 都监听 resize)
    setTimeout(function () { try { window.dispatchEvent(new Event('resize')); } catch (e) {} }, 60);
  }

  document.addEventListener('click', function (e) {
    var btn = e.target.closest ? e.target.closest('[data-landscape]') : null;
    if (!btn) return;
    var el = document.querySelector(btn.getAttribute('data-landscape'));
    if (!el) return;
    e.preventDefault();
    enter(el, btn.getAttribute('data-landscape-title'));
  });

  window.exitLandscape = function () { if (active) active.exit(false); };
})();
