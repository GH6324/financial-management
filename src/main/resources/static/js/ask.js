/*
 * v1.19 · 问一问 · 对话流客户端
 *
 * 一份脚本同时服务 PC 抽屉与手机整页 —— 它只认 data-ask-* 钩子,不认外面那层容器长什么样。
 *
 * 滚动策略(护栏 v119-ASK-NO-AUTOSCROLL):
 *   **只有当用户本来就在底部时才跟着滚。** 无条件 scrollIntoView 会在用户往回翻看
 *   上一条回答时把他弹回底部 —— 这是流式界面最招人烦的一件事,而且长回答期间会反复发生。
 *
 * 安全:模型输出是**不可信输入**(提示词注入可以从账户名里来)。这里一律用
 *   createElement + textContent 建节点,**不拼 HTML 字符串**,于是转义漏一处的可能性为零。
 */
(function () {
  'use strict';

  var BOTTOM_SLACK = 48;   // 距底不足这么多像素就算「在底部」
  var CITE_G = /\{\{cite:([A-Za-z0-9_]{1,14})\}\}/g;
  var CITE_ONLY = /^\{\{cite:([A-Za-z0-9_]{1,14})\}\}$/;
  var BOLD_G = /\*\*([^*]{1,80})\*\*/g;

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  /* ── 渲染 ──
     服务端有一份等价实现(AskCitationRenderer),历史消息走那边。
     这里这份只服务「流式进行中」的那几秒 —— 那时候还没落库,服务端渲不了。
     两份必须同形态,否则流完刷新一下页面样子会变。 */
  function citeCard(c) {
    var a = el('a', 'ask-cite');
    a.href = c.href || '#';
    var top = el('span', 'ask-cite-top');
    top.appendChild(el('span', 'ask-cite-k', c.label));
    top.appendChild(el('span', 'ask-cite-v', c.value));
    a.appendChild(top);
    var meta = (c.period || '') + (c.inProgress ? ((c.period ? ' · ' : '') + '未关账') : '');
    if (meta) {
      var m = el('span', 'ask-cite-meta');
      m.appendChild(el('span', 'ask-cite-per', meta));
      a.appendChild(m);
    }
    return a;
  }

  function citeChip(c) {
    var a = el('a', 'ask-chip', c.value);
    a.href = c.href || '#';
    if (c.inProgress) a.appendChild(el('i', null, '未关账'));
    return a;
  }

  /**
   * 按正则把一段文本切成「命中段」与「间隔段」,分别交给两个回调。
   * 抽出来是因为「引用标记」和「**粗体**」是同一种活,写两遍必然漂移一处。
   */
  function split(text, re, onMatch, onPlain) {
    var last = 0;
    for (var m of text.matchAll(re)) {
      if (m.index > last) onPlain(text.slice(last, m.index));
      onMatch(m);
      last = m.index + m[0].length;
    }
    if (last < text.length) onPlain(text.slice(last));
  }

  /** 模型输出里常见的 markdown 就 **粗体** 这一样,不为它引一个解析器 */
  function emphasize(text, into) {
    split(text, BOLD_G,
      function (m) { into.appendChild(el('strong', null, m[1])); },
      function (s) { into.appendChild(document.createTextNode(s)); });
  }

  /** 一行文本 → 若干节点(文本 + 行内引用 + 粗体) */
  function inlineNodes(line, cites, into) {
    split(line, CITE_G,
      function (m) {
        var c = cites[m[1]];
        if (c) into.appendChild(citeChip(c));
      },
      function (s) { emphasize(s, into); });
  }

  function render(raw, cites) {
    var frag = document.createDocumentFragment();
    raw.split(/\n{2,}/).forEach(function (block) {
      block.split('\n').forEach(function (line) {
        var t = line.trim();
        if (!t) return;
        var only = t.match(CITE_ONLY);
        if (only) {
          if (cites[only[1]]) frag.appendChild(citeCard(cites[only[1]]));
          return;
        }
        var p = el('p');
        inlineNodes(t, cites, p);
        frag.appendChild(p);
      });
    });
    return frag;
  }

  // ──────────────────────── 单个对话流 ────────────────────────

  function init(root) {
    var form = root.querySelector('[data-ask-form]');
    if (!form || form.dataset.askBound) return;
    form.dataset.askBound = '1';

    var input = root.querySelector('[data-ask-input]');
    var sendBtn = root.querySelector('[data-ask-send]');
    var msgs = root.querySelector('[data-ask-msgs]');
    var live = root.querySelector('[data-ask-live]');
    var busy = false;

    if (input) {
      input.addEventListener('input', function () {
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 112) + 'px';
      });
      // 回车发送,Shift+回车换行;手机上不拦 —— 虚拟键盘的回车就该是换行
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey && !isTouch()) {
          e.preventDefault();
          form.requestSubmit();
        }
      });
    }

    root.querySelectorAll('[data-ask-preset]').forEach(function (b) {
      b.addEventListener('click', function () { ask(b.dataset.askPreset); });
    });

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var q = (input && input.value || '').trim();
      if (q) ask(q);
    });

    root.querySelectorAll('[data-ask-new]').forEach(function (b) {
      b.addEventListener('click', newConversation);
    });

    function isTouch() {
      return window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
    }

    function atBottom() {
      if (!msgs) return true;
      return msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight < BOTTOM_SLACK;
    }

    function keepBottom(wasAtBottom) {
      if (wasAtBottom && msgs) msgs.scrollTop = msgs.scrollHeight;
    }

    function newConversation() {
      post('/ask/new', {}).then(function (r) {
        if (root.closest('[data-ask-drawer]')) {
          openPanel(r.id);
        } else {
          var url = new URL(window.location.href);
          url.searchParams.set('conv', r.id);
          window.location.href = url.toString();
        }
      });
    }

    function ensureConversation() {
      var id = root.dataset.conv;
      if (id) return Promise.resolve(id);
      return post('/ask/new', {}).then(function (r) {
        root.dataset.conv = String(r.id);
        return String(r.id);
      });
    }

    function ask(q) {
      if (busy) return;
      busy = true;
      if (sendBtn) sendBtn.disabled = true;
      if (input) { input.value = ''; input.style.height = 'auto'; }

      var empty = root.querySelector('.ask-empty');
      if (empty) empty.remove();

      // 用户这句先上屏 —— 等服务端回声会让人觉得没发出去
      var mine = el('div', 'flex justify-end mb-3');
      mine.appendChild(el('div',
        'ask-bub-me max-w-[85%] px-3.5 py-2.5 rounded-2xl rounded-br-sm bg-ink text-paper ' +
        'text-[13.5px] leading-relaxed whitespace-pre-wrap', q));
      live.parentNode.insertBefore(mine, live);

      var turn = el('div', 'mb-4');
      turn.appendChild(el('div', 'text-[11px] tracking-[.2em] text-ink-soft mb-1.5', '账 房 助 手'));
      var tools = el('div', 'mb-2 flex flex-col gap-1');
      var thinking = el('div', 'ask-thinking text-[12px] text-ink-soft', '正在看你的账…');
      var bodyBox = el('div', 'ask-body text-[13.5px] leading-[1.75]');
      turn.appendChild(tools);
      turn.appendChild(thinking);
      turn.appendChild(bodyBox);
      live.parentNode.insertBefore(turn, live);
      keepBottom(true);

      ensureConversation().then(function (convId) {
        var raw = '';
        var cites = {};
        var es = new EventSource('/ask/' + convId + '/stream?q=' + encodeURIComponent(q));

        es.addEventListener('tool', function (ev) {
          var d = JSON.parse(ev.data);
          var wasBottom = atBottom();
          var row = null;
          for (var i = 0; i < tools.children.length; i++) {
            if (tools.children[i].dataset.k === d.tool) { row = tools.children[i]; break; }
          }
          if (!row) {
            row = el('div', 'ask-tool flex items-center gap-1.5 text-[11.5px] text-ink-soft');
            row.dataset.k = d.tool;
            row.appendChild(el('span', 'ask-dot'));
            row.appendChild(el('span', null, ''));
            tools.appendChild(row);
          }
          var dot = row.firstChild, lab = row.lastChild;
          if (d.phase === 'start') {
            dot.classList.add('run');
            lab.textContent = '正在查 ' + d.label;
          } else {
            dot.classList.remove('run');
            if (!d.ok) dot.classList.add('bad');
            lab.textContent = d.ok ? ('读取 ' + d.label) : (d.label + ' · 没查到');
          }
          keepBottom(wasBottom);
        });

        es.addEventListener('cite', function (ev) {
          var d = JSON.parse(ev.data);
          cites[d.key] = d;
        });

        // 那段文字是调工具前的旁白,不是答案 —— 降级成工具区里的一行灰字
        es.addEventListener('rollback', function (ev) {
          var t = JSON.parse(ev.data).t;
          raw = raw.endsWith(t) ? raw.slice(0, raw.length - t.length) : raw;
          bodyBox.replaceChildren(render(raw, cites));
          var line = el('div', 'ask-narr text-[11.5px] text-ink-subtle', t.trim());
          tools.appendChild(line);
          keepBottom(atBottom());
        });

        es.addEventListener('delta', function (ev) {
          var wasBottom = atBottom();
          if (thinking.parentNode) thinking.remove();
          raw += JSON.parse(ev.data).t;
          bodyBox.replaceChildren(render(raw, cites));
          keepBottom(wasBottom);
        });

        es.addEventListener('failed', function (ev) {
          if (thinking.parentNode) thinking.remove();
          turn.appendChild(el('div',
            'my-2 text-[12px] text-ink-soft bg-card-soft border border-rule rounded-lg px-3 py-2',
            JSON.parse(ev.data).message));
          finish(es);
        });

        es.addEventListener('done', function () {
          if (thinking.parentNode) thinking.remove();
          finish(es);
        });

        es.onerror = function () {
          if (!busy) return;
          if (thinking.parentNode) thinking.remove();
          if (!raw) {
            turn.appendChild(el('div',
              'my-2 text-[12px] text-ink-soft bg-card-soft border border-rule rounded-lg px-3 py-2',
              '连接断了。重试一下。'));
          }
          finish(es);
        };
      }).catch(function () {
        thinking.textContent = '没开成对话,刷新页面再试。';
        busy = false;
        if (sendBtn) sendBtn.disabled = false;
      });
    }

    function finish(es) {
      try { es.close(); } catch (e) { /* 已经关了 */ }
      busy = false;
      if (sendBtn) sendBtn.disabled = false;
      if (input) input.focus();
    }
  }

  function post(url, data) {
    var h = { 'Content-Type': 'application/x-www-form-urlencoded' };
    var tok = document.querySelector('meta[name="_csrf"]');
    var hdr = document.querySelector('meta[name="_csrf_header"]');
    if (tok && hdr && tok.content) h[hdr.content] = tok.content;
    return fetch(url, {
      method: 'POST', headers: h, credentials: 'same-origin',
      body: new URLSearchParams(data).toString()
    }).then(function (r) { return r.json(); });
  }

  // ──────────────────────── PC 抽屉 ────────────────────────

  /* 片段加载走项目已有的 htmx —— 全站局部替换都用它,这里没理由自己再写一套 */
  function openPanel(convId) {
    var drawer = document.querySelector('[data-ask-drawer]');
    if (!drawer || !window.htmx) return;
    var body = drawer.querySelector('[data-ask-drawer-body]');
    htmx.ajax('GET', '/ask/panel' + (convId ? ('?conv=' + convId) : ''),
              { target: body, swap: 'innerHTML' })
      .then(function () {
        drawer.classList.add('open');
        document.documentElement.classList.add('ask-open');
        drawer.querySelectorAll('.ask-stream').forEach(init);
        drawer.querySelectorAll('[data-ask-close]').forEach(function (b) {
          b.addEventListener('click', closePanel);
        });
        var inp = drawer.querySelector('[data-ask-input]');
        if (inp) inp.focus();
      });
  }

  function closePanel() {
    var drawer = document.querySelector('[data-ask-drawer]');
    if (!drawer) return;
    drawer.classList.remove('open');
    document.documentElement.classList.remove('ask-open');
  }

  function boot() {
    document.querySelectorAll('.ask-stream').forEach(init);
    document.querySelectorAll('[data-ask-open]').forEach(function (b) {
      if (b.dataset.askBound) return;
      b.dataset.askBound = '1';
      b.addEventListener('click', function (e) { e.preventDefault(); openPanel(null); });
    });
  }

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') closePanel();
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
  document.addEventListener('htmx:afterSwap', boot);
})();
