/**
 * v1.19.3 · 支出录入 · 负债账户的类目约束(信用卡)
 *
 * 背景:v1.19.3 之前支出账户下拉排掉了整个 LOAN 类,导致**信用卡消费根本录不进去**。
 * 放开之后冒出来的新风险是「支出双计」—— 刷卡时在信用卡上记一笔「消费」,月底还款时
 * 又在现金账户记一笔「还贷」,同一笔钱进了两次本月支出。服务端 EntryService.recordExpense
 * 会硬拦(REPAYMENT_CATEGORIES),这里只是让用户在点下去之前就看不到那两个选项。
 *
 * 为什么是「摘掉 option」而不是「置灰」:这两个 select 都挂了 data-lsel,原生控件被
 * lens-select.js 隐藏、另渲染一份自定义下拉,而它的 render() **不读 option.disabled** ——
 * 置灰在自定义 UI 上根本看不出来,用户照样点得到。lens-select 对 select 挂了
 * MutationObserver({childList:true}),所以增删 option 会自动触发它重建列表,这条路是通的。
 */
(function () {
  'use strict';

  /** 摘走的 option 连同它的原始下标一起记着 —— 恢复时要插回原位,不能一律 append。 */
  function detachRepayment(cat) {
    if (cat._repaymentStash) return cat._repaymentStash;
    var stash = [];
    Array.prototype.slice.call(cat.options).forEach(function (opt, i) {
      if (opt.getAttribute('data-repayment') === 'true') stash.push({ opt: opt, index: i });
    });
    stash.forEach(function (s) { if (s.opt.parentNode) s.opt.parentNode.removeChild(s.opt); });
    cat._repaymentStash = stash;
    return stash;
  }

  function restoreRepayment(cat) {
    var stash = cat._repaymentStash;
    if (!stash) return;
    // 从小到大插回:先插的那个到位之后,后一个的原始下标才是对的
    stash.slice().sort(function (a, b) { return a.index - b.index; })
      .forEach(function (s) {
        var ref = cat.options[s.index] || null;
        cat.insertBefore(s.opt, ref);
      });
    cat._repaymentStash = null;
  }

  function apply(form) {
    var acct = form.querySelector('[data-expense-acct]');
    var cat = form.querySelector('[data-expense-cat]');
    if (!acct || !cat) return;
    var hint = (form.parentNode || document).querySelector('[data-expense-liability-hint]');

    var picked = acct.options[acct.selectedIndex];
    var isLiability = !!picked && picked.getAttribute('data-liability') === 'true';

    if (isLiability) {
      var stash = detachRepayment(cat);
      // 当前选中的正好被摘走 → select.value 会变成空,提交时服务端报「类目不存在」。
      // 落回「消费」(没有就用第一个剩下的),并派发 change 让 lens-select 同步按钮文案。
      var wasRepayment = stash.some(function (s) { return s.opt.value === cat.value; }) || !cat.value;
      if (wasRepayment) {
        var fallback = cat.querySelector('option[value="consumption"]') || cat.options[0];
        if (fallback) {
          cat.value = fallback.value;
          cat.dispatchEvent(new Event('change', { bubbles: true }));
        }
      }
    } else {
      restoreRepayment(cat);
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
