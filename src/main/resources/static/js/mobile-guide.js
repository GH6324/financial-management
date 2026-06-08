/* 家庭账房 · 移动端引导(FR-33 微信 + FR-34 iOS PWA)· v0.7 强引导改版
 * 目标:iOS 用户「强烈建议」装成 PWA。整屏拦截 + 成果截图预览 +「想留在浏览器/微信」两段挽留。
 * 渐进增强 — 失败/不支持环境完全静默。详见 prd/tech-design v0.7。
 */
(function () {
  'use strict';

  // ---------- Storage 降级(隐私模式/沙箱可能抛) ----------
  var S = (function () {
    try {
      localStorage.setItem('__t__', '1'); localStorage.removeItem('__t__');
      return localStorage;
    } catch (e) {
      return { getItem: function () { return null; }, setItem: function () {}, removeItem: function () {} };
    }
  })();

  // ---------- UA 检测 ----------
  var ua = navigator.userAgent;
  var isWX = /MicroMessenger/i.test(ua);
  var isIOS = /iPad|iPhone|iPod/.test(ua) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1); // iPadOS 13+ 伪装桌面
  var isSafari = /^((?!chrome|android|crios|fxios|edgios).)*safari/i.test(ua);
  var inStandalone = window.navigator.standalone === true ||
    (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches);

  var SNOOZE = 3 * 86400000; // 两段挽留后仍坚持 → 3 天内不再弹
  var V = window.__buildVersion || Date.now();

  // ---------- DOM helpers(安全 API · 不用 innerHTML) ----------
  function injectStyle(id, css) {
    if (document.getElementById(id)) return;
    var s = document.createElement('style'); s.id = id; s.textContent = css;
    document.head.appendChild(s);
  }
  function el(tag, css, text) {
    var e = document.createElement(tag);
    if (css) e.style.cssText = css;
    if (text != null) e.textContent = text;
    return e;
  }
  function svgEl(tag, attrs) {
    var e = document.createElementNS('http://www.w3.org/2000/svg', tag);
    if (attrs) Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    return e;
  }
  /** inline SVG 图标(项目纪律:不用 emoji)· d 可为字符串或数组 */
  function icon(d, size, stroke) {
    var s = svgEl('svg', {
      viewBox: '0 0 24 24', width: size || 16, height: size || 16, fill: 'none',
      stroke: stroke || 'currentColor', 'stroke-width': '2',
      'stroke-linecap': 'round', 'stroke-linejoin': 'round'
    });
    s.style.cssText = 'flex-shrink:0;vertical-align:middle';
    (Array.isArray(d) ? d : [d]).forEach(function (p) { s.appendChild(svgEl('path', { d: p })); });
    return s;
  }
  var I = {
    close: 'M18 6 6 18M6 6l12 12',
    check: 'M20 6 9 17l-5-5',
    image: ['M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2z', 'M21 15l-5-5L5 21'],
    share: ['M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7', 'M12 16V3', 'M8 7l4-4 4 4'],
    more: 'M5 12h.01M12 12h.01M19 12h.01', // ⋯
    plusSquare: ['M3 3h18v18H3z', 'M12 8v8', 'M8 12h8']
  };

  /** iPhone 边框包一张图(成果/步骤预览) */
  function phoneShot(src, alt, maxW) {
    var frame = el('div',
      'background:#1d1d1f;border-radius:30px;padding:5px;margin:0 auto;' +
      'box-shadow:0 14px 38px -10px rgba(0,0,0,.55);max-width:' + (maxW || 190) + 'px;');
    var img = document.createElement('img');
    img.alt = alt || ''; img.decoding = 'async';
    img.style.cssText = 'width:100%;display:block;border-radius:25px;background:#2c2c2e;min-height:90px;';
    img.addEventListener('error', function () {
      img.style.cssText += 'padding:30px 12px;color:#ff6b6b;font:11px monospace;text-align:center;';
      img.alt = '预览加载失败';
    });
    img.src = src;
    frame.appendChild(img);
    return frame;
  }

  function primaryBtn(label, iconPath) {
    var b = el('button',
      'width:100%;padding:13px;margin-top:14px;border:0;background:#1a1714;color:#fdf8ef;' +
      'font:600 15px/1 "Fraunces","Noto Serif SC",serif;letter-spacing:.03em;cursor:pointer;' +
      'display:flex;align-items:center;justify-content:center;gap:8px;border-radius:2px;');
    if (iconPath) b.appendChild(icon(iconPath, 17, '#fdf8ef'));
    b.appendChild(el('span', null, label));
    return b;
  }
  function subtleBtn(label) {
    return el('button',
      'display:block;width:100%;margin-top:10px;padding:6px;border:0;background:transparent;' +
      'font:11px/1 monospace;letter-spacing:.12em;color:#9b9389;cursor:pointer;', label);
  }

  // ============ 整屏遮罩 ============
  function overlay(id, z) {
    var m = el('div',
      'position:fixed;inset:0;z-index:' + z + ';background:rgba(20,16,12,.86);' +
      'backdrop-filter:blur(3px);-webkit-backdrop-filter:blur(3px);' +
      'overflow-y:auto;-webkit-overflow-scrolling:touch;' +
      'display:flex;align-items:flex-start;justify-content:center;padding:8vh 18px 40px;' +
      'font-family:"Source Serif 4","Noto Serif SC",serif;animation:gFade .25s ease-out;');
    m.id = id; m.setAttribute('role', 'dialog'); m.setAttribute('aria-modal', 'true');
    return m;
  }
  function card() {
    return el('div',
      'position:relative;width:100%;max-width:384px;background:#fdf8ef;border:1px solid #1a1714;' +
      'box-shadow:0 18px 50px -12px rgba(0,0,0,.6);padding:24px 22px 18px;border-radius:3px;' +
      'animation:gRise .35s ease-out;');
  }
  function eyebrow(text) {
    return el('div',
      'font:10px/1 monospace;letter-spacing:.26em;color:#b08a3e;text-transform:uppercase;margin-bottom:10px;', text);
  }
  function valueRow(text) {
    var li = el('li', 'display:flex;align-items:baseline;gap:9px;font:13px/1.5 inherit;color:#3a3530;padding:3px 0;');
    var dot = icon(I.check, 15, '#2f5d50'); dot.style.cssText += ';position:relative;top:2px';
    li.appendChild(dot); li.appendChild(el('span', null, text));
    return li;
  }

  function ensureKeyframes() {
    injectStyle('g-guide-kf',
      '@keyframes gFade{from{opacity:0}to{opacity:1}}' +
      '@keyframes gRise{from{transform:translateY(18px);opacity:0}to{transform:translateY(0);opacity:1}}' +
      '@keyframes gArrow{0%,100%{transform:translateY(0)}50%{transform:translateY(-9px)}}');
  }

  function removeEl(id) { var e = document.getElementById(id); if (e) e.remove(); }
  function closeAllGuides() { removeEl('g-obstruct'); removeEl('g-shots'); removeEl('g-wx'); removeEl('g-pwa'); }

  // ============ 两段挽留(想跑要被拦两次)============
  // onInstall:返回安装(留在引导);snoozeKey:两次都拒后写入,3 天内不再弹
  function twoStepLeave(snoozeKey, onInstall) {
    showObstruction({
      step: 1,
      title: '真的用浏览器?',
      body: '浏览器模式:主屏没有图标、每次都要手动输网址、不能全屏 —— 体验大打折扣。',
      stay: '返回安装', leave: '仍要继续',
      onStay: onInstall,
      onLeave: function () {
        showObstruction({
          step: 2,
          title: '装一次,只要 20 秒',
          body: '装好后跟微信、备忘录一样:主屏一点就开、自动全屏。真的不试一下?',
          stay: '好,装一下', leave: '就用浏览器',
          onStay: onInstall,
          onLeave: function () { S.setItem(snoozeKey, String(Date.now())); closeAllGuides(); }
        });
      }
    });
  }
  function showObstruction(o) {
    removeEl('g-obstruct');
    var m = overlay('g-obstruct', 100001);
    var c = card();
    c.style.cssText += ';border-left:4px solid #9f4a3a;max-width:340px';
    c.appendChild(eyebrow('第 ' + o.step + ' 次 · 确认'));
    c.appendChild(el('h3', 'font:600 19px/1.25 "Fraunces","Noto Serif SC",serif;color:#1a1714;margin:0 0 8px;', o.title));
    c.appendChild(el('p', 'font:13px/1.6 inherit;color:#3a3530;margin:0 0 4px;', o.body));
    var stay = primaryBtn(o.stay, I.check);
    stay.addEventListener('click', function () { removeEl('g-obstruct'); if (o.onStay) o.onStay(); });
    var leave = subtleBtn(o.leave);
    leave.addEventListener('click', function () { removeEl('g-obstruct'); o.onLeave(); });
    c.appendChild(stay); c.appendChild(leave);
    m.appendChild(c);
    document.body.appendChild(m);
  }

  // ============ iOS Safari · 整屏强引导 ============
  function showIosPwaInterstitial() {
    if (Date.now() - (+S.getItem('pwa_dismissed_at') || 0) < SNOOZE) return;
    if (document.getElementById('g-pwa')) return;
    ensureKeyframes();
    var m = overlay('g-pwa', 99999);
    var c = card();

    // 顶部关闭(也走两段挽留,不给一键逃)
    var x = el('button',
      'position:absolute;top:8px;right:8px;width:30px;height:30px;border:0;background:transparent;' +
      'color:#b3a89c;cursor:pointer;display:flex;align-items:center;justify-content:center;');
    x.setAttribute('aria-label', '关闭'); x.appendChild(icon(I.close, 18, '#b3a89c'));
    x.addEventListener('click', function () { twoStepLeave('pwa_dismissed_at', noop); });
    c.appendChild(x);

    c.appendChild(eyebrow('iOS · 装 · 成 · APP'));
    c.appendChild(el('h2',
      'font:600 23px/1.25 "Fraunces","Noto Serif SC",serif;color:#1a1714;margin:0 0 4px;',
      '在 iPhone 上,请把账房装成 App'));
    c.appendChild(el('p',
      'font:13px/1.55 inherit;color:#6b635a;margin:0 0 16px;',
      '账房是按 App 模式打造的。装到主屏后,体验跟原生 App 一样 —— 强烈建议先装,再用。'));

    // 成果图
    c.appendChild(phoneShot('/img/safari-screen/home-screen.jpg?v=' + V, '装好后主屏上的账房图标', 172));
    c.appendChild(el('p',
      'text-align:center;font:10px/1 monospace;letter-spacing:.16em;color:#b08a3e;margin:9px 0 14px;text-transform:uppercase;',
      '装好后 · 主屏一点就开'));

    var ul = el('ul', 'list-style:none;margin:0 0 4px;padding:0;');
    ['全屏打开 · 没有浏览器地址栏', '主屏有图标 · 一点秒进', '跟备忘录、微信一样顺手'].forEach(function (t) { ul.appendChild(valueRow(t)); });
    c.appendChild(ul);

    var go = primaryBtn('看怎么装 · 4 步真机图', I.image);
    go.addEventListener('click', function () { showIosPwaScreenshots(); });
    c.appendChild(go);

    var later = subtleBtn('暂时用浏览器');
    later.addEventListener('click', function () { twoStepLeave('pwa_dismissed_at', noop); });
    c.appendChild(later);

    m.appendChild(c);
    document.body.appendChild(m);
  }
  function noop() {} // 「返回安装」= 关掉挽留,留在整屏引导(引导本体仍在)

  // ============ 微信(iOS · 内核装不了 PWA)· 整屏强引导:先去 Safari ============
  function showWxGuide() {
    if (Date.now() - (+S.getItem('wx_dismissed_at') || 0) < SNOOZE) return;
    if (document.getElementById('g-wx')) return;
    ensureKeyframes();
    var m = overlay('g-wx', 99999);

    // 指向右上 ⋯ 的大箭头
    var arrow = svgEl('svg', { viewBox: '0 0 60 76' });
    arrow.style.cssText = 'position:fixed;top:6px;right:20px;width:56px;height:72px;z-index:1;animation:gArrow 1.4s ease-in-out infinite;';
    arrow.appendChild(svgEl('path', { d: 'M30 70 Q 34 35, 50 8', stroke: '#d6a04a', 'stroke-width': '3', 'stroke-linecap': 'round', fill: 'none', 'stroke-dasharray': '5 4' }));
    arrow.appendChild(svgEl('path', { d: 'M50 8 L 42 14 M50 8 L 56 16', stroke: '#d6a04a', 'stroke-width': '3', 'stroke-linecap': 'round' }));
    m.appendChild(arrow);

    var c = card();
    c.appendChild(eyebrow('微信 · 请 · 转 · SAFARI'));
    c.appendChild(el('h2',
      'font:600 22px/1.25 "Fraunces","Noto Serif SC",serif;color:#1a1714;margin:0 0 4px;',
      '微信里装不了主屏 App'));
    c.appendChild(el('p',
      'font:13px/1.55 inherit;color:#6b635a;margin:0 0 14px;',
      '账房建议装成主屏 App 来用,但微信内置浏览器不支持。请先用 Safari 打开 —— 之后 20 秒就能装好。'));

    // 两步:⋯ → 在 Safari 打开
    var ol = el('ol', 'list-style:none;margin:0 0 14px;padding:0;');
    [[I.more, '点右上角「', '⋯', '」按钮(见上方箭头)'], [I.share, '选「', '在 Safari 中打开', '」']].forEach(function (s, idx) {
      var li = el('li', 'display:flex;align-items:center;gap:9px;font:13px/1.4 inherit;color:#3a3530;padding:4px 0;');
      var num = el('span',
        'width:20px;height:20px;background:#1a1714;color:#fdf8ef;border-radius:50%;flex-shrink:0;' +
        'display:inline-flex;align-items:center;justify-content:center;font:11px monospace;', String(idx + 1));
      li.appendChild(num);
      li.appendChild(icon(s[0], 16, '#1a1714'));
      var sp = el('span', null, s[1]);
      sp.appendChild(el('b', 'color:#1a1714', s[2]));
      sp.appendChild(document.createTextNode(s[3]));
      li.appendChild(sp);
      ol.appendChild(li);
    });
    c.appendChild(ol);

    c.appendChild(phoneShot('/img/safari-screen/home-screen.jpg?v=' + V, '装好后主屏上的账房图标', 150));
    c.appendChild(el('p',
      'text-align:center;font:10px/1 monospace;letter-spacing:.16em;color:#b08a3e;margin:9px 0 0;text-transform:uppercase;',
      '到 Safari 后 · 我们带你装好'));

    var go = primaryBtn('好,我去 Safari 打开', I.share);
    go.addEventListener('click', function () { closeAllGuides(); }); // 不 snooze:还在微信里就再提醒
    c.appendChild(go);

    var stay = subtleBtn('继续在微信用');
    stay.addEventListener('click', function () { twoStepLeave('wx_dismissed_at', noop); });
    c.appendChild(stay);

    m.appendChild(c);
    document.body.appendChild(m);
  }

  // ============ 4 步真机操作截图(从整屏引导主按钮进入)============
  function showIosPwaScreenshots() {
    if (document.getElementById('g-shots')) return;
    ensureKeyframes();
    var modal = overlay('g-shots', 100000);
    modal.style.alignItems = 'flex-start';

    var closeBtn = el('button',
      'position:fixed;top:14px;right:14px;width:38px;height:38px;border:0;background:rgba(255,255,255,.14);' +
      'color:#fff;border-radius:50%;cursor:pointer;z-index:1;display:flex;align-items:center;justify-content:center;' +
      'backdrop-filter:blur(8px);');
    closeBtn.setAttribute('aria-label', '关闭'); closeBtn.appendChild(icon(I.close, 18, '#fff'));
    closeBtn.addEventListener('click', function () { modal.remove(); });
    modal.appendChild(closeBtn);

    var box = el('div', 'max-width:520px;margin:0 auto;padding:56px 20px 40px;');
    box.appendChild(el('h2', 'font:600 25px/1.2 "Fraunces","Noto Serif SC",serif;color:#fdf8ef;margin:0 0 6px;text-align:center;', '添加到主屏 · 4 步'));
    box.appendChild(el('p', 'font:10px/1 monospace;letter-spacing:.22em;color:#a09487;text-align:center;margin:0 0 28px;text-transform:uppercase;', 'iOS · SAFARI · ADD TO HOME SCREEN'));

    var steps = [
      ['01', '底部地址栏「⋯」→「共享」', '在 Safari 底部地址栏右下角点「⋯」(更多),选「共享」。', 'step1'],
      ['02', '选择「共享」', '弹出菜单第一项即「共享」。中文版 iOS 叫「共享」不叫「分享」。', 'step2'],
      ['03', '共享面板底部「⋯ 更多」', '默认不显示「添加到主屏幕」,在底部应用行右侧点「⋯ 更多」展开。', 'step3'],
      ['04', '选「添加到主屏幕」', '滚动找到「添加到主屏幕」(红圈)→ 右上角「添加」即完成。', 'step4']
    ];
    steps.forEach(function (s) {
      var fig = el('figure', 'margin:0 0 34px;');
      var head = el('div', 'display:flex;align-items:baseline;gap:13px;margin-bottom:11px;');
      head.appendChild(el('span', 'font:600 34px/1 "Fraunces",serif;color:#d6a04a;flex-shrink:0;', s[0]));
      var hr = el('div', 'flex:1;');
      hr.appendChild(el('h3', 'font:600 16px/1.2 "Fraunces","Noto Serif SC",serif;color:#fdf8ef;margin:0 0 2px;', s[1]));
      hr.appendChild(el('p', 'font:11px/1.5 inherit;color:#a09487;margin:0;', s[2]));
      head.appendChild(hr); fig.appendChild(head);
      fig.appendChild(phoneShot('/img/safari-screen/' + s[3] + '.jpg?v=' + V, 'STEP ' + s[0] + ' · ' + s[1], 270));
      box.appendChild(fig);
    });

    var done = primaryBtn('我知道了', I.check);
    done.style.cssText += ';max-width:220px;margin:18px auto 0;background:#fdf8ef;color:#1a1714';
    done.querySelector('svg').setAttribute('stroke', '#1a1714');
    done.addEventListener('click', function () { modal.remove(); });
    box.appendChild(done);

    modal.appendChild(box);
    modal.addEventListener('click', function (e) { if (e.target === modal) modal.remove(); });
    document.body.appendChild(modal);
  }

  // ============ Dev escape: ?reset_pwa=1 / ?reset_wx=1 ============
  (function () {
    var qs = window.location.search || '';
    if (qs.indexOf('reset_pwa=1') > -1) S.removeItem('pwa_dismissed_at');
    if (qs.indexOf('reset_wx=1') > -1) S.removeItem('wx_dismissed_at');
  })();

  // ============ 触发分发(微信优先互斥;已装/非 iOS 静默)============
  function dispatch() {
    if (inStandalone) return;                                          // 已装成 PWA → 完全静默
    if (isWX && isIOS) { setTimeout(showWxGuide, 600); }               // iOS 微信 → 强引导转 Safari
    else if (isIOS && isSafari) { setTimeout(showIosPwaInterstitial, 700); } // iOS Safari → 强引导装 PWA
    // 其它(安卓微信 / 桌面 / iOS 非 Safari):静默(本次只强推 iOS · 安卓另议)
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', dispatch);
  else dispatch();
})();
