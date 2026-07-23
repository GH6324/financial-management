// =========================================================
// searchable-select · 把 <select data-searchable> 升级成搜索式下拉
//
// 用法:
//   <select name="type" data-searchable class="...">
//     <option value="CASH">现金 (CASH)</option>
//     ...
//   </select>
//
// 行为:
//   - 隐藏原 select(form 提交时仍带其 value)
//   - 在原位置插入一个 input + dropdown wrapper
//   - 输入框 onfocus 全选 + 弹下拉
//   - 输入过滤(value 和 text 都参与匹配,大小写无关)
//   - 上下方向键 + Enter / ESC 键盘支持
//   - 选中后 dispatch 原 select 的 'change' 事件,兼容现有 onchange 处理
//   - HTMX swap 后自动重新挂(监听 htmx:afterSettle)
//
// 移动端:
//   - inputmode="search" 让 iOS / Android 软键盘弹搜索栏
//   - dropdown max-height: 60vh + overflow: auto,长列表能滚
//   - 每个选项 py-2.5,触屏点击区够大
// =========================================================
(function () {
  'use strict';

  function init(root) {
    const scope = root || document;
    scope.querySelectorAll('select[data-searchable]:not([data-ss-wired])').forEach(wire);
  }

  function clearChildren(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function wire(sel) {
    sel.setAttribute('data-ss-wired', '1');

    // ---- wrapper ----
    const wrap = document.createElement('div');
    wrap.className = 'ss-wrap relative';
    sel.parentNode.insertBefore(wrap, sel);
    wrap.appendChild(sel);
    sel.style.display = 'none';
    sel.setAttribute('aria-hidden', 'true');

    // 复制原 select 的关键样式占位,让 input 看起来跟原 select 一样
    const placeholderClass = sel.className || 'field-input bg-transparent';

    // ---- input ----
    const input = document.createElement('input');
    input.type = 'text';
    input.autocomplete = 'off';
    input.spellcheck = false;
    input.setAttribute('inputmode', 'search');
    input.className = placeholderClass + ' ss-input cursor-pointer';
    input.placeholder = '搜索 / 选择…';
    syncInputFromSelect();
    wrap.appendChild(input);

    // ---- caret(右侧三角)----
    const caret = document.createElement('span');
    caret.className = 'absolute right-2 top-1/2 -translate-y-1/2 pointer-events-none text-ink-subtle font-mono text-xs';
    caret.textContent = '▾';
    wrap.appendChild(caret);

    // ---- dropdown ----
    const dd = document.createElement('div');
    dd.className = 'ss-dropdown absolute z-50 left-0 right-0 mt-1 bg-card border-2 border-ink shadow-lg max-h-[60vh] overflow-auto hidden';
    dd.setAttribute('role', 'listbox');
    wrap.appendChild(dd);

    let activeIdx = -1;
    let filteredOpts = []; // [{el: optionElement, item: domNode}]

    function syncInputFromSelect() {
      const cur = sel.options[sel.selectedIndex];
      input.value = cur ? cur.text.trim() : '';
    }

    function rebuild(filter) {
      const f = (filter || '').trim().toLowerCase();
      clearChildren(dd);
      filteredOpts = [];
      Array.from(sel.options).forEach((opt, i) => {
        const txt = opt.text;
        const val = opt.value || '';
        // 跳过空 placeholder option
        if (!val && !txt.trim()) return;
        if (f && !txt.toLowerCase().includes(f) && !val.toLowerCase().includes(f)) return;

        const item = document.createElement('div');
        item.className = 'ss-item px-3 py-2.5 cursor-pointer text-sm border-b border-rule-soft last:border-b-0';
        if (i === sel.selectedIndex) item.classList.add('bg-card-soft', 'font-medium');
        // v1.4.2 · option 带 data-owner 时(如划转目标账户)渲染主理人头像色圆,首字母 + avatar-N 背景
        if (opt.dataset && opt.dataset.owner) {
          item.classList.add('flex', 'items-center', 'gap-2');
          const av = document.createElement('span');
          av.className = 'avatar-' + (opt.dataset.oc || '4');
          av.style.cssText = 'width:18px;height:18px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-size:10px;color:var(--paper);flex:none';
          av.textContent = opt.dataset.owner.slice(0, 1);
          item.appendChild(av);
          const tx = document.createElement('span');
          tx.className = 'truncate';
          tx.textContent = txt;
          item.appendChild(tx);
        } else {
          item.textContent = txt;
        }
        item.setAttribute('role', 'option');
        item.dataset.value = val;

        // mousedown(blur 前)选中,避免 input.blur 关 dropdown 导致 click 不触发
        item.addEventListener('mousedown', function (e) {
          e.preventDefault();
          pick(opt);
        });
        item.addEventListener('mouseenter', function () {
          activeIdx = filteredOpts.findIndex(x => x.el === opt);
          highlight();
        });

        filteredOpts.push({ el: opt, item });
        dd.appendChild(item);
      });
      if (!filteredOpts.length) {
        const empty = document.createElement('div');
        empty.className = 'px-3 py-3 text-sm text-ink-subtle italic';
        empty.textContent = '无匹配';
        dd.appendChild(empty);
      }
      activeIdx = filteredOpts.findIndex(x => x.el === sel.options[sel.selectedIndex]);
      highlight();
    }

    function highlight() {
      filteredOpts.forEach((x, i) => {
        x.item.classList.toggle('ss-active', i === activeIdx);
        if (i === activeIdx) {
          x.item.style.background = 'var(--brass-soft, #f0e6d2)';
          x.item.scrollIntoView({ block: 'nearest' });
        } else {
          x.item.style.background = '';
        }
      });
    }

    function open() {
      rebuild(input.value === currentText() ? '' : input.value);
      dd.classList.remove('hidden');
    }
    function close() {
      dd.classList.add('hidden');
      // 输入框值不匹配任何 option → 还原为当前选中项
      const match = Array.from(sel.options).some(o => o.text.trim() === input.value.trim());
      if (!match) syncInputFromSelect();
    }
    function currentText() {
      const cur = sel.options[sel.selectedIndex];
      return cur ? cur.text.trim() : '';
    }
    function pick(opt) {
      if (opt.disabled) return;
      sel.value = opt.value;
      input.value = opt.text.trim();
      sel.dispatchEvent(new Event('change', { bubbles: true }));
      close();
    }

    input.addEventListener('focus', function () {
      input.select();
      open();
    });
    input.addEventListener('input', function () {
      open();
    });
    input.addEventListener('blur', function () {
      // 延迟关闭,让 mousedown 先触发
      setTimeout(close, 120);
    });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (dd.classList.contains('hidden')) open();
        else if (filteredOpts.length) {
          activeIdx = Math.min(activeIdx + 1, filteredOpts.length - 1);
          if (activeIdx < 0) activeIdx = 0;
          highlight();
        }
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (filteredOpts.length) {
          activeIdx = Math.max(activeIdx - 1, 0);
          highlight();
        }
      } else if (e.key === 'Enter') {
        if (!dd.classList.contains('hidden') && filteredOpts[activeIdx]) {
          e.preventDefault();
          pick(filteredOpts[activeIdx].el);
        }
      } else if (e.key === 'Escape') {
        input.blur();
      }
    });

    // 外部点击关闭
    document.addEventListener('mousedown', function (e) {
      if (!wrap.contains(e.target)) close();
    });

    // 上游 JS 改 sel.value 时同步 input
    sel.addEventListener('ss:sync', syncInputFromSelect);
  }

  // 启动 + HTMX swap 重挂
  if (document.readyState !== 'loading') init();
  else document.addEventListener('DOMContentLoaded', init);
  if (document.body) {
    document.body.addEventListener('htmx:afterSettle', function (e) { init(e.target); });
  }
})();
