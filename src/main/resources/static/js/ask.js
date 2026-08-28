/*
 * v1.19 · 问一问 · 对话流客户端
 *
 * 一份脚本同时服务 PC 抽屉与手机整页 —— 它只认 data-ask-* 钩子,不认外面那层容器长什么样。
 *
 * 七个状态(取自 Claude / ChatGPT / Cursor 已经收敛的那套):
 *   排队 → 思考 → 流式 → 完成 → 出错 → 已停止 → 重来
 * 每个都有明确形态。**排队不给假进度条**(没有进度可报,画一个是骗人);
 * **流式必须有光标**(否则模型思考的停顿会被读成「已经结束了」);
 * **已停止要留住半截**并给「继续 / 重来」。
 *
 * 滚动(护栏 v119-ASK-NO-AUTOSCROLL):只有用户本来就在底部时才跟着滚。
 *   无条件 scrollIntoView 会在用户往回翻看上一条回答时把他弹回底部 ——
 *   这是流式界面最招人烦的一件事,而且长回答期间会反复发生。
 *
 * 安全:模型输出是**不可信输入**(提示词注入可以从账户名里来)。一律用
 *   createElement + textContent 建节点,**不拼 HTML 字符串**,转义漏一处的可能性为零。
 */
(function () {
  'use strict';

  var BOTTOM_SLACK = 96;   // 距底不足这么多像素就算「在底部」(规范给的是 100)
  var CITE_G = /\{\{cite:([A-Za-z0-9_]{1,14})\}\}/g;
  var CITE_ONLY = /^\{\{cite:([A-Za-z0-9_]{1,14})\}\}$/;
  var NEXT_G = /\{\{next:([^}\n]{1,40})\}\}/g;
  var BOLD_G = /\*\*([^*]{1,80})\*\*/g;
  /* 「- xxx」/「1. xxx」列表项 —— 服务端 AskCitationRenderer 有等价的一份,两边必须同形态 */
  var LIST_ITEM = /^(?:[-*·]|\d{1,2}[.)])\s+(.*)$/;

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function svg(pathD, extra) {
    var s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('aria-hidden', 'true');
    pathD.forEach(function (d) {
      var p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      p.setAttribute('d', d);
      s.appendChild(p);
    });
    if (extra) extra(s);
    return s;
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
   * 抽出来是因为引用标记、追问标记、**粗体** 是同一种活,写三遍必然漂移一处。
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

  function inlineNodes(line, cites, into) {
    split(line, CITE_G,
      function (m) {
        var c = cites[m[1]];
        if (c) into.appendChild(citeChip(c));
      },
      function (s) { emphasize(s, into); });
  }

  /** 抽出追问建议;正文里不再显示它们 */
  function pullNexts(raw) {
    var out = [], m;
    for (m of raw.matchAll(NEXT_G)) {
      var q = m[1].trim();
      if (q && out.indexOf(q) < 0 && out.length < 3) out.push(q);
    }
    return out;
  }

  function render(raw, cites) {
    var frag = document.createDocumentFragment();
    var ul = null;
    var closeList = function () { ul = null; };

    raw.replace(NEXT_G, '').split(/\n{2,}/).forEach(function (block) {
      block.split('\n').forEach(function (line) {
        var t = line.trim();
        if (!t) return;

        var only = t.match(CITE_ONLY);
        if (only) {
          closeList();
          if (cites[only[1]]) frag.appendChild(citeCard(cites[only[1]]));
          return;
        }

        var li = t.match(LIST_ITEM);
        if (li) {
          if (!ul) { ul = el('ul'); frag.appendChild(ul); }
          var item = el('li');
          inlineNodes(li[1], cites, item);
          ul.appendChild(item);
          return;
        }

        closeList();
        var p = el('p');
        inlineNodes(t, cites, p);
        frag.appendChild(p);
      });
    });
    return frag;
  }

  /** 复制用的纯文本:引用换成数值,追问去掉 */
  function plainText(raw, cites) {
    var out = raw.replace(NEXT_G, '').replace(CITE_G, function (m, k) {
      var c = cites[k];
      return c ? (c.label + ' ' + c.value + (c.inProgress ? '(未关账)' : '')) : '';
    });
    return out.replace(/\n{3,}/g, '\n\n').trim();
  }

  // ──────────────────────── 单个对话流 ────────────────────────

  function init(root) {
    var form = root.querySelector('[data-ask-form]');
    if (!form || form.dataset.askBound) return;
    form.dataset.askBound = '1';

    var input = root.querySelector('[data-ask-input]');
    var sendBtn = root.querySelector('[data-ask-send]');
    var stopBtn = root.querySelector('[data-ask-stop]');
    var msgs = root.querySelector('[data-ask-msgs]');
    var live = root.querySelector('[data-ask-live]');
    var busy = false;
    var current = null;          // 正在跑的那一轮

    if (input) {
      input.addEventListener('input', autoGrow);
      // 回车发送,Shift+回车换行;手机上不拦 —— 虚拟键盘的回车就该是换行
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey && !isTouch()) {
          e.preventDefault();
          form.requestSubmit();
        }
      });
    }
    function autoGrow() {
      if (!input) return;
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 112) + 'px';
    }

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var q = (input && input.value || '').trim();
      if (q) ask(q);
    });

    if (stopBtn) stopBtn.addEventListener('click', function () {
      if (current) current.stop();
    });

    // 预置问题、追问 chip、复制、重来 —— 都可能是流式期间新插进来的,所以用事件委托
    root.addEventListener('click', function (e) {
      var t = e.target.closest('[data-ask-preset]');
      if (t) { ask(t.dataset.askPreset); return; }
      var c = e.target.closest('[data-ask-copy]');
      if (c) { doCopy(c); return; }
      var r = e.target.closest('[data-ask-regen]');
      if (r) { ask(null, 'regen'); return; }
      var g = e.target.closest('[data-ask-continue]');
      if (g) { ask(null, 'continue'); return; }
      var n = e.target.closest('[data-ask-new]');
      if (n) { newConversation(); }
    });

    function doCopy(btn) {
      var text = btn.dataset.text || '';
      var done = function (ok) {
        var span = btn.lastChild;
        var old = span.textContent;
        span.textContent = ok ? '已复制' : '请手动选中';
        btn.classList.toggle('ok', ok);
        setTimeout(function () { span.textContent = old; btn.classList.remove('ok'); }, 1600);
      };
      // 自托管常见:没挂 TLS,http 下 clipboard API 直接不可用 → 老实说「请手动选中」
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { done(true); },
                                                 function () { done(false); });
      } else { done(false); }
    }

    function isTouch() {
      return window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
    }
    function atBottom() {
      if (!msgs) return true;
      return msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight < BOTTOM_SLACK;
    }
    function keepBottom(was) { if (was && msgs) msgs.scrollTop = msgs.scrollHeight; }

    function setBusy(on) {
      busy = on;
      if (sendBtn) { sendBtn.hidden = on; sendBtn.disabled = on; }
      if (stopBtn) stopBtn.hidden = !on;
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

    /** mode: 'new'(默认)/ 'regen' / 'continue' */
    function ask(q, mode) {
      if (busy) return;
      mode = mode || 'new';
      setBusy(true);
      if (mode === 'new' && input) { input.value = ''; autoGrow(); }

      var empty = root.querySelector('.ask-empty');
      if (empty) empty.remove();

      // 用户这句先上屏 —— 等服务端回声会让人觉得没发出去
      if (mode === 'new') {
        var mine = el('div', 'ask-me');
        mine.appendChild(el('div', null, q));
        live.parentNode.insertBefore(mine, live);
      }

      var turn = el('div', 'ask-turn');
      var acts = buildActs();
      var wait = el('div', 'ask-wait', mode === 'continue' ? '接着上面继续…' : '正在看你的账');
      var bodyBox = el('div', 'ask-body');
      turn.appendChild(acts.host);
      turn.appendChild(wait);
      turn.appendChild(bodyBox);
      live.parentNode.insertBefore(turn, live);
      keepBottom(true);

      var raw = '', cites = {}, es = null, stopping = false;

      current = {
        stop: function () {
          if (stopping) return;
          stopping = true;
          if (stopBtn) stopBtn.disabled = true;
          // 先告诉服务端(它才能把半截落库),再关本地连接
          post('/ask/' + root.dataset.conv + '/stop', {}).catch(function () {});
        }
      };

      ensureConversation().then(function (convId) {
        var url = '/ask/' + convId + '/stream?mode=' + mode
                + (mode === 'new' ? '&q=' + encodeURIComponent(q) : '');
        es = new EventSource(url);

        es.addEventListener('tool', function (ev) {
          var d = JSON.parse(ev.data), was = atBottom();
          acts.upsert(d);
          keepBottom(was);
        });

        es.addEventListener('cite', function (ev) {
          var d = JSON.parse(ev.data);
          cites[d.key] = d;
        });

        // 那段文字是调工具前的旁白,不是答案 —— 降级成活动区里的一行
        es.addEventListener('rollback', function (ev) {
          var t = JSON.parse(ev.data).t, was = atBottom();
          if (raw.endsWith(t)) raw = raw.slice(0, raw.length - t.length);
          paint();
          acts.narrate(t.trim());
          keepBottom(was);
        });

        es.addEventListener('delta', function (ev) {
          var was = atBottom();
          if (wait.parentNode) wait.remove();
          raw += JSON.parse(ev.data).t;
          paint();
          keepBottom(was);
        });

        es.addEventListener('done', function () { finish('done'); });
        es.addEventListener('stopped', function () { finish('stopped'); });

        es.addEventListener('failed', function (ev) {
          var d = JSON.parse(ev.data);
          finish('failed', d.message);
        });

        es.onerror = function () {
          if (busy) finish(raw ? 'stopped' : 'failed', raw ? null : '连接断了。重试一下。');
        };

        function paint() {
          bodyBox.replaceChildren(render(raw, cites));
          if (busy) bodyBox.appendChild(el('span', 'ask-caret-live'));
        }

        function finish(state, msg) {
          try { if (es) es.close(); } catch (e) { /* 已经关了 */ }
          if (wait.parentNode) wait.remove();
          setBusy(false);
          current = null;
          bodyBox.replaceChildren(render(raw, cites));   // 去掉光标

          if (state === 'failed' && msg) {
            turn.appendChild(el('div', 'ask-note').appendChild(el('span', null, msg)).parentNode);
          }
          if (state === 'stopped') {
            var note = el('div', 'ask-note');
            note.appendChild(el('span', null, raw ? '你叫停了 · 上面是已经说到的部分' : '已停止'));
            turn.appendChild(note);
          }

          // 追问 chip
          var nexts = pullNexts(raw);
          if (nexts.length) {
            var box = el('div', 'ask-nexts');
            nexts.forEach(function (n) {
              var b = el('button', 'ask-next', n);
              b.type = 'button';
              b.dataset.askPreset = n;
              box.appendChild(b);
            });
            turn.appendChild(box);
          }

          // 逐条操作
          if (raw) {
            var row = el('div', 'ask-tools-row');
            row.appendChild(mini('复制', ['M9 9h11v11H9z', 'M5 15V5a2 2 0 0 1 2-2h10'],
              function (b) { b.dataset.askCopy = ''; b.dataset.text = plainText(raw, cites); }));
            if (state === 'stopped') {
              row.appendChild(mini('继续', ['M8 5l10 7-10 7z'],
                function (b) { b.dataset.askContinue = ''; }));
            }
            row.appendChild(mini('重来', ['M21 12a9 9 0 1 1-3-6.7', 'M21 3v6h-6'],
              function (b) { b.dataset.askRegen = ''; }));
            turn.appendChild(row);
          }

          if (input) input.focus();
        }
      }).catch(function () {
        wait.textContent = '没开成对话,刷新页面再试。';
        setBusy(false);
        current = null;
      });
    }

    function mini(label, paths, tag) {
      var b = el('button', 'ask-mini');
      b.type = 'button';
      b.appendChild(svg(paths));
      b.appendChild(document.createTextNode(label));
      tag(b);
      return b;
    }

    /** 活动区:折叠的 <details>,和历史消息里那份同形态 */
    function buildActs() {
      var host = el('details', 'ask-acts');
      var sum = el('summary');
      sum.appendChild(el('span', 'ask-caret', '▸'));
      var label = el('span', null, '正在查…');
      sum.appendChild(label);
      var body = el('div', 'ask-acts-body');
      host.appendChild(sum);
      host.appendChild(body);
      host.hidden = true;
      var n = 0;

      return {
        host: host,
        upsert: function (d) {
          host.hidden = false;
          var row = body.querySelector('[data-k="' + d.tool.replace(/"/g, '') + '"]');
          if (!row) {
            row = el('div', 'ask-act');
            row.dataset.k = d.tool;
            row.appendChild(el('span', 'ask-dot'));
            row.appendChild(el('span', null, d.label));
            row.appendChild(el('span', 'ask-act-ms', ''));
            body.appendChild(row);
            n++;
          }
          var dot = row.firstChild, ms = row.lastChild;
          if (d.phase === 'start') {
            dot.classList.add('run');
            ms.textContent = '';
          } else {
            dot.classList.remove('run');
            if (!d.ok) dot.classList.add('bad');
            ms.textContent = d.ok ? (d.ms + ' ms') : '没查到';
          }
          label.textContent = '查了 ' + n + ' 项';
        },
        narrate: function (t) {
          host.hidden = false;
          var row = el('div', 'ask-act');
          row.appendChild(el('span', 'ask-dot'));
          row.appendChild(el('span', null, t));
          body.appendChild(row);
        }
      };
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
