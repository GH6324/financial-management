/* 家庭账房 · 屏幕方向锁定(v1.6.4 重写)
 *
 * ── 目标(用户要求)────────────────────────────────────────────
 *  方向只由我们的按钮决定,**完全不响应手机自身的横竖屏切换**。
 *  用户转手机时页面不跟着转 —— 等效于「系统竖屏锁定」,只不过锁的方向由我们说了算。
 *
 * ── 为什么这能做到(前两版我判断错了)────────────────────────
 *  拿不到 `screen.orientation.lock()`(iOS 不支持)≠ 做不到方向锁定。
 *  系统旋转屏幕这件事我们阻止不了,但「页面要不要跟着转」完全在我们手里:
 *    设备方向 == 期望方向  → 不旋转(0°)
 *    设备竖屏 + 期望横屏   → 顺时针 90°
 *    设备横屏 + 期望竖屏   → **逆时针 90°(把内容转回竖直)**  ← 关键的一步
 *  最后一条就是「屏蔽系统横屏」:用户把手机转横,我们反向转回来,
 *  内容相对机身始终不动,视觉效果与系统竖屏锁定一致。
 *
 * ── 安全边界 ────────────────────────────────────────────────
 *  只在「触屏 + 小屏」启用。桌面浏览器恒满足 innerWidth > innerHeight,
 *  若不设边界,PC 会被永久反转 90° —— 那是灾难。
 */
(function () {
  'use strict';

  var KEY = 'oriLock';                 // sessionStorage: 'portrait' | 'landscape'
  var C_LOCK = 'ori-lock', C_CW = 'ori-rot90', C_CCW = 'ori-rotm90';

  function want() {
    try { return sessionStorage.getItem(KEY) === 'landscape' ? 'landscape' : 'portrait'; }
    catch (e) { return 'portrait'; }
  }

  /** 只在触屏小屏设备上锁方向 —— PC 必须排除(它恒为「横屏」)。
   *  尺寸必须用 screen(屏幕物理尺寸,恒定),**不能用 innerWidth/innerHeight**:
   *  body 被旋转后浏览器会重算 layout viewport(实测 390×844 → 807×1745),
   *  用 inner* 判断会让 lockable 在两次 resize 之间翻转 → 反复加/删 class → 自激振荡。 */
  function lockable() {
    var coarse = window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
    var sw = window.screen ? Math.max(window.screen.width || 0, window.screen.height || 0) : 0;
    if (!sw) sw = Math.max(window.innerWidth, window.innerHeight);
    return !!coarse && sw < 1200;
  }

  /** 当前已施加的旋转 class(用于幂等判断) */
  function currentRot() {
    var c = document.documentElement.classList;
    return c.contains(C_CW) ? C_CW : (c.contains(C_CCW) ? C_CCW : '');
  }

  /* 调试钩子:方向锁这类「多次触发、最后一次说话」的逻辑很难靠猜排障,留一份最近的判断记录。 */
  window.__oriLog = [];
  function apply(src) {
    var html = document.documentElement;
    var lk = lockable();
    /* 方向判断用宽高比:body 旋转后 inner* 会被等比缩放(390×844 → 807×1745),
       比例不变,所以「谁大谁小」仍然可靠。 */
    var devLandscape = window.innerWidth > window.innerHeight;
    var wantLandscape = want() === 'landscape';
    var target = (lk && wantLandscape !== devLandscape) ? (wantLandscape ? C_CW : C_CCW) : '';
    var cur = currentRot();
    window.__oriLog.push({ src: src || '?', lockable: lk, iw: window.innerWidth, ih: window.innerHeight,
                           devL: devLandscape, wantL: wantLandscape, cur: cur, target: target });
    if (window.__oriLog.length > 12) window.__oriLog.shift();
    /* 幂等:结论没变就一个字节都不改 DOM。
       这是止住振荡的第二道闸 —— 改 class 会引起 layout 变化、可能再触发 resize,
       若每次 resize 都无条件重写 class,就会自己喂自己。 */
    if (target === cur) { syncBtn(); return; }
    html.classList.remove(C_LOCK, C_CW, C_CCW);
    if (target) html.classList.add(C_LOCK, target);
    syncBtn();
    /* 图表按新可视尺寸重绘。这里**不再主动派发 resize** —— 那正是振荡的源头;
       直接点名调用图表实例的 resize。 */
    setTimeout(function () {
      try {
        if (window.financeCharts) {
          Object.keys(window.financeCharts).forEach(function (k) {
            var ch = window.financeCharts[k];
            if (ch && typeof ch.resize === 'function') ch.resize();
          });
        }
        if (window.echarts) {
          document.querySelectorAll('#sunburst, .echart-box').forEach(function (el) {
            var i = window.echarts.getInstanceByDom(el);
            if (i) i.resize();
          });
        }
      } catch (e) {}
    }, 140);
  }

  function syncBtn() {
    var on = want() === 'landscape';
    document.querySelectorAll('[data-orientation-toggle]').forEach(function (b) {
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
      b.title = on ? '当前:横屏(点击回竖屏)· 转动手机不会改变方向' : '整页横屏查看(适合宽表格)· 转动手机不会改变方向';
      var lb = b.querySelector('.ori-label');
      if (lb) lb.textContent = on ? '回竖屏' : '横屏看';
    });
  }

  window.toggleOrientation = function () {
    var next = want() === 'landscape' ? 'portrait' : 'landscape';
    try { sessionStorage.setItem(KEY, next); } catch (e) {}
    apply('toggle');
    if (typeof window.showToast === 'function') {
      window.showToast({
        message: next === 'landscape' ? '已切到横屏 · 把手机转横过来看 · 转动手机不会再改变方向'
                                      : '已回竖屏 · 转动手机不会再改变方向',
        level: 'info'
      });
    }
  };

  document.addEventListener('click', function (e) {
    if (!e.target.closest) return;
    var b = e.target.closest('[data-orientation-toggle]');
    if (b) { e.preventDefault(); window.toggleOrientation(); }
  });

  /* 设备转向时重算 —— 这里不是「跟随系统」,而恰恰是为了**抵消**系统的旋转 */
  window.addEventListener('resize', function () { apply('resize'); });
  window.addEventListener('orientationchange', function () { setTimeout(function () { apply('orient'); }, 220); });
  document.addEventListener('DOMContentLoaded', function () { apply('domready'); });
  apply('init');
})();
