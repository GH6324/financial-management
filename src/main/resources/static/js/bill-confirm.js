/*
 * v1.21 FR-576 · 账单确认页的批量操作。
 *
 * 为什么必须有:一份月账单几百笔。第一版把每行的复选框当成「剔除」,于是
 * ① 想批量改分类/账户时没有「选中」这个概念可用;② 想剔除一批要逐个勾。
 * 维护者原话:「几百笔 你让用户自己一个个去勾选啊」。
 *
 * 现在复选框 = 【选中】(纯前端,不提交),批量操作作用在选中行上:
 *   · 全选 / 全选某一组(按分类分的组,组头一个勾)
 *   · 批量改分类 / 批量改账户
 *   · 剔除所选 / 撤销剔除
 *
 * 「剔除」用 <input type="hidden" name="drop"> 表达,由这里增删 —— 因为它要提交,
 * 而「选中」不提交。两个概念分开,别再挤在同一个复选框上。
 */
(function () {
  'use strict';

  /**
   * 赋值后必须派发 change。
   *
   * <p>这些 select 挂了 data-lsel:原生控件被 lens-select 隐藏、另渲染一份自定义下拉,
   * 它的按钮文案靠 `sel.addEventListener('change', syncBtn)` 同步。
   * 只改 .value 不派发,值是对的但<b>用户看到的还是旧文案</b> —— 批量改完一片没反应,
   * 会以为功能坏了。</p>
   */
  function setValue(sel, v) {
    if (!sel || sel.disabled) return;
    sel.value = v;
    sel.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function init(root) {
    var bar = (root || document).querySelector('[data-bill-bar]');
    if (!bar || bar.dataset.bound) return;
    bar.dataset.bound = '1';

    var form = bar.closest('form');
    if (!form) return;

    var rows = function () { return Array.prototype.slice.call(form.querySelectorAll('tr[data-row]')); };
    var sels = function () { return Array.prototype.slice.call(form.querySelectorAll('input[data-sel]')); };
    var picked = function () { return sels().filter(function (c) { return c.checked; }); };
    var countEl = bar.querySelector('[data-sel-count]');

    function rowOf(sel) { return sel.closest('tr'); }

    function refresh() {
      var n = picked().length;
      if (countEl) countEl.textContent = String(n);
      // 组头的勾随组内状态走(全选中才勾上)
      form.querySelectorAll('[data-sel-grp]').forEach(function (g) {
        var key = g.getAttribute('data-grp-of');
        var inGrp = sels().filter(function (c) { return c.getAttribute('data-in-grp') === key; });
        g.checked = inGrp.length > 0 && inGrp.every(function (c) { return c.checked; });
      });
      var all = sels();
      form.querySelectorAll('[data-sel-all],[data-sel-all-2]').forEach(function (a) {
        a.checked = all.length > 0 && all.every(function (c) { return c.checked; });
      });
    }

    /* 剔除 = 给这一行挂一个 hidden name="drop",并把行做成「划掉」的样子。
       不删 DOM:用户要能一眼看到自己剔了哪些,并且能撤销。 */
    function setDropped(tr, dropped) {
      var idx = tr.querySelector('input[name="idx"]');
      if (!idx) return;
      var existing = tr.querySelector('input[name="drop"]');
      if (dropped && !existing) {
        var h = document.createElement('input');
        h.type = 'hidden'; h.name = 'drop'; h.value = idx.value;
        tr.appendChild(h);
      } else if (!dropped && existing) {
        existing.remove();
      }
      tr.style.opacity = dropped ? '.42' : '';
      tr.style.textDecoration = dropped ? 'line-through' : '';
      /* 被剔除的行,它的 idx/cat/acct 不该再提交 —— 否则服务端会拿到一条
         「既在 drop 里、又在 cat 里」的矛盾输入。禁用即不提交。 */
      tr.querySelectorAll('select[data-cat],select[data-acct]').forEach(function (s) { s.disabled = dropped; });
      idx.disabled = dropped;
    }

    function isDropped(tr) { return !!tr.querySelector('input[name="drop"]'); }

    sels().forEach(function (c) { c.addEventListener('change', refresh); });

    form.querySelectorAll('[data-sel-all],[data-sel-all-2]').forEach(function (a) {
      a.addEventListener('change', function () {
        sels().forEach(function (c) { c.checked = a.checked; });
        refresh();
      });
    });

    form.querySelectorAll('[data-sel-grp]').forEach(function (g) {
      g.addEventListener('change', function () {
        var key = g.getAttribute('data-grp-of');
        sels().forEach(function (c) {
          if (c.getAttribute('data-in-grp') === key) c.checked = g.checked;
        });
        refresh();
      });
    });

    /** 没选行就别让批量操作静默什么都不做 —— 说一句比没反应强 */
    function needSelection() {
      if (picked().length) return true;
      alert('先选几笔 —— 可以点表头的「全选」,或者某个分类组左边的勾(那是全选这一组)。');
      return false;
    }

    var bulkCat = bar.querySelector('[data-bulk-cat]');
    if (bulkCat) bulkCat.addEventListener('change', function () {
      if (!bulkCat.value) return;
      if (!needSelection()) { setValue(bulkCat, ''); return; }
      picked().forEach(function (c) { setValue(rowOf(c).querySelector('select[data-cat]'), bulkCat.value); });
      setValue(bulkCat, '');
    });

    var bulkAcct = bar.querySelector('[data-bulk-acct]');
    if (bulkAcct) bulkAcct.addEventListener('change', function () {
      if (!bulkAcct.value) return;
      if (!needSelection()) { setValue(bulkAcct, ''); return; }
      picked().forEach(function (c) { setValue(rowOf(c).querySelector('select[data-acct]'), bulkAcct.value); });
      setValue(bulkAcct, '');
    });

    var drop = bar.querySelector('[data-bulk-drop]');
    if (drop) drop.addEventListener('click', function () {
      if (!needSelection()) return;
      picked().forEach(function (c) { setDropped(rowOf(c), true); });
    });

    var keep = bar.querySelector('[data-bulk-keep]');
    if (keep) keep.addEventListener('click', function () {
      if (!needSelection()) return;
      picked().forEach(function (c) { setDropped(rowOf(c), false); });
    });

    /* 提交前拦截:一笔都不剩就别提交了 —— 让用户在这里看到原因,
       而不是跳一圈回来看一条「没有要导入的笔」的红字。 */
    form.addEventListener('submit', function (e) {
      // 「扔掉草稿」那个按钮走的是另一个 action,不拦
      if (e.submitter && e.submitter.getAttribute('formaction')) return;
      var left = rows().filter(function (tr) { return !isDropped(tr); });
      if (left.length === 0) {
        e.preventDefault();
        alert('所有笔都被剔除了,没有可导入的内容。\n选中几笔点「撤销剔除」,或者直接「取消本次导入」。');
        return;
      }
      var acct = form.querySelector('select[name="accountId"]');
      if (acct && !acct.value) {
        e.preventDefault();
        alert('先选一下「算在哪个账户名下」。');
      }
    });

    refresh();
  }

  document.addEventListener('DOMContentLoaded', function () { init(document); });
  document.body && document.body.addEventListener('htmx:afterSwap', function (e) { init(e.target || document); });
})();
