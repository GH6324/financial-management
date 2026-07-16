/* v1.1.x · 自研搜索下拉(渐进增强 · 2026-07-16 拍板范围:打标页 + 透视构建器/维度选择器)
 * 用法:原生 <select data-lsel> 保持不动(name/表单语义/无 JS 降级全保留),本组件隐藏原生控件,
 * 渲染 按钮 + 弹出面板(搜索框 + 选项列表)。搜索三路匹配:中文子串 / 全拼连写 / 首字母
 * (option 的 data-py="quan pin fen jie" 由后端枚举 getPinyin() 输出;无 data-py 的选项只按中文匹配)。
 * 动态 options(lens.js syncSelectors 重填)经 MutationObserver 自动重建,零耦合。
 * XSS:选项 label 来自枚举/维度注册表(受控),仍一律走 textContent 赋值,不拼 HTML。 */
(function () {
  'use strict';
  var OPEN = null;   // 当前打开的面板(同时只开一个)

  function norm(s) { return String(s || '').toLowerCase().replace(/\s+/g, ''); }
  function initials(py) {
    return String(py || '').trim().split(/\s+/).map(function (w) { return w.charAt(0); }).join('');
  }

  function buildItems(sel) {
    return Array.prototype.map.call(sel.options, function (o) {
      var py = o.getAttribute('data-py') || '';
      return { value: o.value, label: o.textContent.trim(), pyFull: norm(py), pyInit: initials(py) };
    });
  }

  function enhance(sel) {
    if (sel._lsel) return;
    var wrap = document.createElement('div');
    wrap.className = 'lsel';
    sel.parentNode.insertBefore(wrap, sel);
    wrap.appendChild(sel);
    sel.style.display = 'none';

    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'lsel-btn';
    wrap.appendChild(btn);

    var panel = document.createElement('div');
    panel.className = 'lsel-panel';
    panel.hidden = true;
    var q = document.createElement('input');
    q.type = 'text'; q.className = 'lsel-q'; q.placeholder = '搜索 · 中文/拼音/首字母';
    var list = document.createElement('ul');
    list.className = 'lsel-list';
    panel.appendChild(q); panel.appendChild(list);
    wrap.appendChild(panel);

    var items = buildItems(sel);
    var active = -1;

    function syncBtn() {
      var o = sel.options[sel.selectedIndex];
      btn.textContent = o ? o.textContent.trim() : '';
      btn.classList.toggle('lsel-empty', !sel.value);
    }
    function close() { panel.hidden = true; if (OPEN === panel) OPEN = null; }
    function open() {
      if (OPEN && OPEN !== panel) OPEN.hidden = true;
      OPEN = panel; panel.hidden = false;
      q.value = ''; render(''); active = -1;
      q.focus();
    }
    function pick(v) {
      sel.value = v;
      sel.dispatchEvent(new Event('change', { bubbles: true }));
      syncBtn(); close();
    }
    function render(query) {
      var nq = norm(query);
      list.textContent = '';
      var shown = 0;
      items.forEach(function (it, i) {
        if (nq && it.label.toLowerCase().indexOf(query.toLowerCase().trim()) < 0 &&
            (!it.pyFull || it.pyFull.indexOf(nq) < 0) &&
            (!it.pyInit || it.pyInit.indexOf(nq) < 0)) return;
        var li = document.createElement('li');
        li.textContent = it.label;
        li.setAttribute('data-v', it.value);
        li.setAttribute('data-i', String(i));
        if (it.value === sel.value) li.classList.add('lsel-cur');
        li.addEventListener('mousedown', function (e) { e.preventDefault(); pick(it.value); });
        list.appendChild(li); shown++;
      });
      if (!shown) {
        var empty = document.createElement('li');
        empty.className = 'lsel-none'; empty.textContent = '没有匹配项';
        list.appendChild(empty);
      }
    }
    function move(delta) {
      var lis = list.querySelectorAll('li[data-v]');
      if (!lis.length) return;
      active = (active + delta + lis.length) % lis.length;
      lis.forEach(function (li, i) { li.classList.toggle('lsel-act', i === active); });
      lis[active].scrollIntoView({ block: 'nearest' });
    }

    btn.addEventListener('click', function () { panel.hidden ? open() : close(); });
    q.addEventListener('input', function () { active = -1; render(q.value); });
    q.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown') { e.preventDefault(); move(1); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1); }
      else if (e.key === 'Enter') {
        e.preventDefault();
        var lis = list.querySelectorAll('li[data-v]');
        var li = active >= 0 ? lis[active] : lis[0];
        if (li) pick(li.getAttribute('data-v'));
      } else if (e.key === 'Escape') { close(); }
    });
    document.addEventListener('click', function (e) { if (!wrap.contains(e.target)) close(); });

    /* 动态 options(lens.js 重填)/ 外部改值 → 自动重建与同步 */
    new MutationObserver(function () { items = buildItems(sel); syncBtn(); })
      .observe(sel, { childList: true, subtree: true, attributes: true });
    sel.addEventListener('change', syncBtn);

    syncBtn();
    sel._lsel = true;
  }

  function enhanceAll(root) {
    (root || document).querySelectorAll('select[data-lsel]').forEach(enhance);
  }

  /* 纸面风格样式(与 field-input / pill 同族) */
  var style = document.createElement('style');
  style.textContent =
    '.lsel{position:relative;display:block;min-width:0}' +
    '.lsel-btn{display:block;width:100%;box-sizing:border-box;text-align:left;cursor:pointer;' +
      'border:1px solid var(--rule,#d8cfba);background:var(--card,#fffdf6);color:var(--ink,#2b2620);' +
      'padding:6px 26px 6px 10px;font-size:13px;line-height:1.3;position:relative}' +
    '.lsel-btn::after{content:"";position:absolute;right:10px;top:50%;width:7px;height:7px;' +
      'border-right:1.5px solid currentColor;border-bottom:1.5px solid currentColor;' +
      'transform:translateY(-70%) rotate(45deg);opacity:.55}' +
    '.lsel-btn.lsel-empty{color:var(--ink-subtle,#8a8172)}' +
    '.lsel-panel{position:absolute;left:0;top:calc(100% + 2px);z-index:60;min-width:100%;width:max-content;max-width:280px;' +
      'background:var(--card,#fffdf6);border:1px solid var(--rule,#d8cfba);box-shadow:0 6px 18px rgba(33,30,23,.14)}' +
    '.lsel-q{display:block;width:100%;box-sizing:border-box;border:0;border-bottom:1px solid var(--rule,#d8cfba);' +
      'background:transparent;padding:7px 10px;font-size:13px;outline:none}' +
    '.lsel-list{list-style:none;margin:0;padding:4px 0;max-height:240px;overflow-y:auto}' +
    '.lsel-list li{padding:6px 12px;font-size:13px;cursor:pointer;white-space:nowrap}' +
    '.lsel-list li:hover,.lsel-list li.lsel-act{background:var(--card-soft,#f3ecdb)}' +
    '.lsel-list li.lsel-cur{font-weight:600}' +
    '.lsel-list li.lsel-none{color:var(--ink-subtle,#8a8172);cursor:default}';
  document.head.appendChild(style);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { enhanceAll(); });
  } else {
    enhanceAll();
  }
  window.LensSelect = { enhanceAll: enhanceAll };
})();
