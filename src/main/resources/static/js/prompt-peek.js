/*
 * v1.28 · 看 AI 收到了什么(PRD §3.2 ~ §3.4 · FR-916 ~ FR-918)
 *
 * 卡片头的 >_ (data-peek-btn) 点开 → 在那一行(data-peek-host)正下方插一个槽,htmx 拉终端面板进来;
 * 再点 / 按 Esc / 点 esc 收起。面板里能敲命令,每条命令都有一个按钮(data-peek-run)等价。
 * 所有回显一律 textContent,不拼 HTML。
 *
 * 另外导出 window.privText(el, text):把 AI 正文写进元素,金额包进 data-priv(隐私模式下糊掉)。
 * MONEY_REGEX 与服务端 AiText.MONEY_REGEX 是同一个字符串字面量(单测逐字比对)。
 */
(function () {
  'use strict';

  var MONEY_REGEX = "(?:US\\$|HK\\$|[¥￥$€£])\\s?-?[0-9](?:[0-9,]*[0-9])?(?:\\.[0-9]+)?(?:\\s?[万亿kKwW])?|-?[0-9](?:[0-9,]*[0-9])?(?:\\.[0-9]+)?\\s?(?:万元|亿元|万|亿|元)";

  /** 把一段 AI 正文写进 el:普通文字走 textContent,金额包 <span data-priv> */
  window.privText = function (el, text) {
    el.replaceChildren();
    if (!text) return;
    var last = 0;
    Array.from(String(text).matchAll(new RegExp(MONEY_REGEX, 'g'))).forEach(function (m) {
      if (m.index > last) el.appendChild(document.createTextNode(text.slice(last, m.index)));
      var s = document.createElement('span');
      s.setAttribute('data-priv', '');
      s.textContent = m[0];
      el.appendChild(s);
      last = m.index + m[0].length;
    });
    if (last < text.length) el.appendChild(document.createTextNode(text.slice(last)));
  };

  /**
   * JSON 渲染的 AI 卡片拿到新结果后调:有 src → 亮出 >_ 并指向这一份;没有 → 藏起。
   * 上一份结果的面板(如果开着)一并收掉 —— 它讲的是上一次发出去的内容。
   */
  window.peekSet = function (btn, src) {
    if (!btn) return;
    var host = btn.closest('[data-peek-host]') || btn.parentElement;
    var next = host.nextElementSibling;
    if (next && next.hasAttribute('data-peek-slot')) next.replaceChildren();
    btn.classList.remove('on');
    btn.setAttribute('aria-expanded', 'false');
    if (src) { btn.setAttribute('data-peek-src', src); btn.hidden = false; }
    else { btn.removeAttribute('data-peek-src'); btn.hidden = true; }
  };

  /** 现做一个 >_ 按钮(流式回答结束后插进操作行;与服务端 btn 片段同一套属性) */
  window.peekButton = function (src) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'peek-btn';
    b.setAttribute('data-peek-btn', '');
    b.setAttribute('data-peek-src', src);
    b.setAttribute('aria-expanded', 'false');
    b.setAttribute('aria-label', '看 AI 收到了什么');
    b.title = '看 AI 收到了什么';
    var NS = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(NS, 'svg');
    [['width', '15'], ['height', '15'], ['viewBox', '0 0 24 24'], ['fill', 'none'], ['stroke', 'currentColor'],
     ['stroke-width', '2'], ['stroke-linecap', 'round'], ['stroke-linejoin', 'round'], ['aria-hidden', 'true']]
      .forEach(function (a) { svg.setAttribute(a[0], a[1]); });
    var pl = document.createElementNS(NS, 'polyline');
    pl.setAttribute('points', '4 17 10 11 4 5');
    var ln = document.createElementNS(NS, 'line');
    [['x1', '12'], ['y1', '19'], ['x2', '20'], ['y2', '19']].forEach(function (a) { ln.setAttribute(a[0], a[1]); });
    svg.appendChild(pl);
    svg.appendChild(ln);
    b.appendChild(svg);
    return b;
  };

  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /** >_ 所在那一行的正下方放面板;没有槽就建一个 */
  function slotFor(btn) {
    var host = btn.closest('[data-peek-host]') || btn.parentElement;
    var next = host.nextElementSibling;
    if (next && next.hasAttribute('data-peek-slot')) return next;
    var slot = document.createElement('div');
    slot.setAttribute('data-peek-slot', '');
    host.after(slot);
    return slot;
  }

  function setBtn(btn, open) {
    if (!btn) return;
    btn.classList.toggle('on', open);
    btn.setAttribute('aria-expanded', String(open));
    btn.title = open ? '收起' : '看 AI 收到了什么';
  }

  /** FR-918 · 第一行命令逐字「打」出来(总时长 ≤ 0.6 秒);减少动效时直接出现 */
  function typeLine(panel) {
    var el = panel.querySelector('[data-peek-type]');
    if (!el || reduce) return;
    var full = el.dataset.full || el.textContent;
    el.dataset.full = full;
    el.textContent = '';
    var step = Math.max(8, Math.floor(600 / Math.max(1, full.length)));
    var i = 0;
    var t = setInterval(function () {
      el.textContent = full.slice(0, ++i);
      if (i >= full.length) clearInterval(t);
    }, step);
  }

  function open(btn) {
    var slot = slotFor(btn);
    var panel = slot.querySelector('[data-peek]');
    if (panel) {
      var show = panel.hidden;
      panel.hidden = !show;
      setBtn(btn, show);
      if (show) typeLine(panel);
      return;
    }
    setBtn(btn, true);
    slot._peekBtn = btn;
    if (window.htmx) {
      window.htmx.ajax('GET', btn.getAttribute('data-peek-src'), { target: slot, swap: 'innerHTML' });
    }
  }

  function close(panel) {
    if (!panel) return;
    panel.hidden = true;
    var slot = panel.parentElement;
    setBtn(slot && slot._peekBtn, false);
  }

  function sec(panel, key) { return panel.querySelector('[data-sec="' + key + '"]'); }
  function openSec(s, open) {
    if (!s) return;
    s.classList.toggle('open', open);
    var tri = s.querySelector('.tri');
    if (tri) tri.textContent = open ? '▾' : '▸';
  }

  function echo(panel, cmd, lines) {
    var log = panel.querySelector('[data-peek-log]');
    var c = document.createElement('span');
    c.className = 'ln cmd';
    c.textContent = cmd;
    log.appendChild(c);
    lines.forEach(function (l) {
      var s = document.createElement('span');
      s.className = 'ln ' + l[0];
      s.textContent = l[1];
      log.appendChild(s);
    });
    log.lastChild.scrollIntoView({ block: 'nearest' });
  }

  var HELP = [['cmt', '# 能用的命令(每条下面都有按钮):'],
    ['cmt', '#   rules      展开「规矩」—— 每个模板都一样的那段'],
    ['cmt', '#   data       只看「你家的数据」'],
    ['cmt', '#   mine       只看你的设置带来的段落(范围 / 模板 / 偏好)'],
    ['cmt', '#   copy       复制全部      copy data  只复制你家的数据'],
    ['cmt', '#   clear      清掉命令回显'],
    ['cmt', '#   exit       收起(也可以按 Esc)']];

  function privacyOn() { return document.documentElement.classList.contains('privacy'); }

  function copyText(text, done) {
    function fallback() {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (err) { ok = false; }
      ta.remove();
      done(ok);
    }
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(function () { done(true); }, fallback);
    } else {
      fallback();
    }
  }

  function chars(n) { return n.toLocaleString('en-US'); }

  function run(panel, raw) {
    var cmd = String(raw || '').trim().replace(/\s+/g, ' ').toLowerCase();
    if (!cmd) return;
    panel.classList.remove('only-mine');
    var has = !!panel.querySelector('[data-sec="data"]');
    if (!has && ['rules', 'data', 'mine', 'copy', 'copy data'].indexOf(cmd) >= 0) {
      echo(panel, cmd, [['warn', '# 这里没有发出去的内容可看。']]);
      return;
    }
    switch (cmd) {
      case 'help': echo(panel, cmd, HELP); break;
      case 'rules':
        openSec(sec(panel, 'rules'), true);
        echo(panel, cmd, [['ok', '# 已展开「规矩」—— 每个模板都一样,改不了']]);
        break;
      case 'data':
        openSec(sec(panel, 'rules'), false);
        openSec(sec(panel, 'data'), true);
        echo(panel, cmd, [['ok', '# 只看「你家的数据」']]);
        break;
      case 'mine': {
        var n = Number(panel.querySelector('[data-peek-log]').dataset.peekMineCount || 0);
        if (n === 0) {
          echo(panel, cmd, [['cmt', '# 这一次没有用到你的设置(综合体检 · 全部资产 · 没有分析偏好)—— 就是默认的样子。']]);
          break;
        }
        openSec(sec(panel, 'data'), true);
        panel.classList.add('only-mine');
        echo(panel, cmd, [['ok', '# 你的设置带来了 ' + n + ' 段(标色的那几段),其余变淡。敲 data 恢复。']]);
        break;
      }
      case 'copy':
      case 'copy data': {
        if (privacyOn()) { echo(panel, cmd, [['warn', '# 隐私模式开着 —— 先关隐私模式再复制。']]); break; }
        var ta = panel.querySelector('[data-peek-raw="' + (cmd === 'copy' ? 'all' : 'data') + '"]');
        var text = ta ? ta.value : '';
        copyText(text, function (ok) {
          echo(panel, cmd, ok ? [['ok', '# 已复制' + (cmd === 'copy' ? '全部' : '「你家的数据」') + ' · ' + chars(text.length) + ' 字(含真实金额)']]
                              : [['warn', '# 浏览器不让复制 —— 可以手动选中上面的文字复制。']]);
        });
        break;
      }
      case 'clear': panel.querySelector('[data-peek-log]').replaceChildren(); break;
      case 'exit': case 'q': case 'quit': close(panel); break;
      default:
        echo(panel, String(raw).trim(), [['warn', '# 没有「' + String(raw).trim() + '」这条命令。']].concat(HELP));
    }
  }

  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-peek-btn]');
    if (btn) { e.preventDefault(); open(btn); return; }
    var panel = e.target.closest('[data-peek]');
    if (!panel) return;
    if (e.target.closest('[data-peek-close]')) { close(panel); return; }
    var chip = e.target.closest('[data-peek-run]');
    if (chip) { run(panel, chip.getAttribute('data-peek-run')); return; }
    var head = e.target.closest('[data-sec-toggle]');
    if (head) { var s = head.parentElement; openSec(s, !s.classList.contains('open')); }
  });

  document.addEventListener('keydown', function (e) {
    var input = e.target.closest && e.target.closest('[data-peek-in]');
    if (input && e.key === 'Enter') {
      e.preventDefault();
      run(input.closest('[data-peek]'), input.value);
      input.value = '';
      return;
    }
    if (e.key === 'Escape') {
      document.querySelectorAll('[data-peek]:not([hidden])').forEach(close);
    }
    var head = e.target.closest && e.target.closest('[data-sec-toggle]');
    if (head && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      var s = head.parentElement;
      openSec(s, !s.classList.contains('open'));
    }
  });

  document.addEventListener('htmx:afterSwap', function (e) {
    var slot = e.target;
    if (!slot || !slot.hasAttribute || !slot.hasAttribute('data-peek-slot')) return;
    var panel = slot.querySelector('[data-peek]');
    if (panel) typeLine(panel);
  });
})();
