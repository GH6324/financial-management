/**
 * v1.19.3 · 支出录入 · 负债账户的类目约束(信用卡)
 *
 * 背景:v1.19.3 之前支出账户下拉排掉了整个 LOAN 类,导致**信用卡消费根本录不进去**。
 * 放开之后冒出来的新风险是「支出双计」—— 刷卡时在信用卡上记一笔「消费」,月底还款时
 * 又在现金账户记一笔「还贷」,同一笔钱进了两次本月支出。服务端 EntryService.recordExpense
 * 会硬拦(REPAYMENT_CATEGORIES),这里只是让用户在点下去之前就看不到那两个选项。
 *
 * ── v1.21 改写 ──
 *
 * 类目从 `<select data-lsel>` 换成了常驻宫格(_cat-grid.html),所以这里从
 * 「摘 option 再插回原位」变成「藏按钮」—— 简单得多,而且不用管顺序。
 *
 * **改写不是可选的**:旧实现调 `cat.options`,而 `data-expense-cat` 现在是一个
 * hidden input,`Array.prototype.slice.call(undefined)` 在 strict 模式下直接抛 TypeError。
 * 更糟的是它**只在用户选中信用卡那一刻才抛** —— 首屏默认是现金账户,走的是恢复分支、
 * 提前 return,所以页面打开时一切正常,护栏和冒烟测试都看不出来。
 */
(function () {
  'use strict';

  function apply(form) {
    var acct = form.querySelector('[data-expense-acct]');
    var codeInput = form.querySelector('[data-expense-cat]');
    if (!acct || !codeInput) return;
    var grid = form.querySelector('[data-cat-grid]');
    var hint = (form.parentNode || document).querySelector('[data-expense-liability-hint]');

    var picked = acct.selectedOptions && acct.selectedOptions.length ? acct.selectedOptions[0] : null;
    var isLiability = !!picked && picked.getAttribute('data-liability') === 'true';

    if (grid) {
      grid.querySelectorAll('[data-repayment="true"]').forEach(function (btn) {
        btn.hidden = isLiability;
        // 当前选中的正好被藏起来 → hidden 里还留着 loan_payment,提交会被服务端拒。
        // 报错是对的,但用户看不出自己选了什么 —— 所以退回默认。
        if (isLiability && btn.classList.contains('on')) {
          btn.classList.remove('on');
          codeInput.value = 'consumption';
          var catInput = form.querySelector('[data-cat-input]');
          if (catInput) catInput.value = '';
        }
      });
    }
    if (hint) hint.hidden = !isLiability;
  }

  function init(root) {
    (root || document).querySelectorAll('form [data-expense-acct]').forEach(function (acct) {
      var form = acct.closest('form');
      if (!form || form._expenseLiability) return;
      form._expenseLiability = true;
      acct.addEventListener('change', function () { apply(form); });
      apply(form); // 首屏:下拉默认选中的可能本来就是信用卡
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { init(document); });
  } else {
    init(document);
  }
  // HTMX 换片段后重新挂(填报页的录入区会被 hx-swap 整块替换)
  document.body && document.body.addEventListener('htmx:afterSwap', function (e) { init(e.target); });
})();
