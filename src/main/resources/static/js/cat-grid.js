/*
 * v1.21(第 2 稿)· 支出分类宫格的行为。
 *
 * 宫格是两个 hidden 字段的输入方式:
 *   · name="categoryCode"        —— 性质(consumption / loan_payment / interest_paid / to_relatives)
 *   · name="expenseCategoryId"   —— 消费分类(只在 consumption 下有意义)
 *
 * 界面上是一层、底层是两个字段(FR-551)。点消费分类 → 性质自动 consumption;
 * 点那三个性质项 → 消费分类清空。任何一笔都只需要【一次点击】。
 *
 * 与 expense-liability.js 的分工:**「选中负债账户时藏掉还贷/利息支出」整件事归它管**
 * (含「被藏掉的正好是当前选中项 → 退回默认」)。这里不重复做 —— 同一个状态两个所有者,
 * 迟早会出现「谁最后跑谁说了算」的竞态。这里只管「用户点了什么」。
 */
(function () {
  'use strict';

  function init(root) {
    var grid = root.querySelector('[data-cat-grid]');
    if (!grid || grid.dataset.bound) return;
    grid.dataset.bound = '1';

    var form = grid.closest('form');
    var catInput = grid.querySelector('[data-cat-input]');
    var codeInput = form && form.querySelector('[name="categoryCode"]');
    var subs = grid.querySelector('[data-cat-subs]');
    var subLabel = grid.querySelector('[data-cat-sub-label]');

    function clearOn(sel) {
      grid.querySelectorAll(sel).forEach(function (b) { b.classList.remove('on'); });
    }

    /* 格子分两种,靠有没有 data-code 区分:
         · 有 data-code  = 性质项(还贷/利息支出/转账给亲属)—— 不是消费
         · 没有          = 消费分类(大类,或「最近常用」里的任意一个)
       两者【同尺寸同 class】(.cat-cell),所以只能靠 data 属性分,不能靠外观分。 */
    grid.querySelectorAll('.cat-cell').forEach(function (btn) {
      btn.addEventListener('click', function () {
        clearOn('.cat-cell');
        clearOn('.cat-chip');
        btn.classList.add('on');
        if (btn.dataset.code) {
          /* 性质项:清掉消费分类,收起细类条 */
          if (catInput) catInput.value = '';
          if (codeInput) codeInput.value = btn.dataset.code;
          if (subs) subs.hidden = true;
        } else {
          if (catInput) catInput.value = btn.dataset.catId || '';
          if (codeInput) codeInput.value = 'consumption';
          showSubs(btn);
        }
      });
    });

    /* 细类 chip:性质仍是 consumption,只是分类更细一层 */
    grid.querySelectorAll('.cat-chip[data-sub-of]').forEach(function (chip) {
      chip.addEventListener('click', function () {
        clearOn('.cat-chip');
        chip.classList.add('on');
        if (catInput) catInput.value = chip.dataset.catId || '';
        if (codeInput) codeInput.value = 'consumption';
      });
    });

    function showSubs(btn) {
      if (!subs) return;
      var id = btn.dataset.catId;
      var any = false;
      subs.querySelectorAll('.cat-chip[data-sub-of]').forEach(function (chip) {
        var mine = chip.dataset.subOf === id;
        chip.hidden = !mine;
        if (mine) any = true;
      });
      subs.hidden = !any;
      if (any && subLabel) subLabel.textContent = (btn.textContent || '').replace(/›/g, '').trim() + ' ›';
    }

  }

  document.addEventListener('DOMContentLoaded', function () { init(document); });
  /* HTMX 局部替换之后重新绑一次 —— 填报页大量用 hx-swap */
  document.body && document.body.addEventListener('htmx:afterSwap', function (e) {
    init(e.target || document);
  });
})();
