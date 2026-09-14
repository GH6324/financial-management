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
   * 取页面上那个 CSRF hidden 域,连【名字】一起带走。
   *
   * <p>第一版是自己拼 header `X-CSRF-TOKEN` —— 但本项目配的是 CookieCsrfTokenRepository,
   * 它认的 header 叫 <b>X-XSRF-TOKEN</b>,于是每次都 403,前端 catch 到解析失败,
   * 给用户报了一句「网络没通」。<b>把 403 说成网络问题,用户会一直重试一个永远不会好的东西。</b></p>
   *
   * <p>现在改成把 token 当【表单参数】发:参数名从 hidden 域的 name 上读,
   * 服务端怎么配都对得上,不依赖任何 header 命名约定。</p>
   */
  function csrfField() {
    var el = document.querySelector('input[type="hidden"][name="_csrf"]')
          || document.querySelector('form input[type="hidden"][value][name$="csrf"]');
    return el ? { name: el.name, value: el.value } : null;
  }

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

  /**
   * 分桶页签(FR-580)。各桶的可动性不一样,这里只管切换显示;能不能改由模板的 disabled 决定。
   *
   * <p><b>【为什么独立于 init】</b>「要导入 0 笔」时(月底重导同一份账单最常见的一屏)
   * 模板不渲染批量工具条 —— 而 init 是以 <code>[data-bill-bar]</code> 为入口的,
   * 绑在里面的话页签会跟着一起失效,可那一屏里页签恰恰是<b>唯一</b>还有用的东西。</p>
   *
   * <p><b>【作用域】</b>页签在 <code>&lt;form&gt;</code> 之<b>外</b>(它是这一批的总览,
   * 不是要提交的字段),所以必须从 document 找。用 form.querySelector 会拿到 null,
   * 表现是「点了没反应、也不报错」—— 开发时正是这么漏过去的。</p>
   */
  function initTabs() {
    var tabs = document.querySelector('[data-bucket-tabs]');
    if (!tabs || tabs.dataset.bound) return;
    tabs.dataset.bound = '1';
    var bar = document.querySelector('[data-bill-bar]');
    var hint = document.querySelector('[data-bill-hint]');
    tabs.querySelectorAll('[data-bucket]').forEach(function (t) {
      t.addEventListener('click', function () {
        var want = t.getAttribute('data-bucket');
        tabs.querySelectorAll('[data-bucket]').forEach(function (o) { o.classList.toggle('on', o === t); });
        document.querySelectorAll('[data-bucket-panel]').forEach(function (pnl) {
          pnl.hidden = pnl.getAttribute('data-bucket-panel') !== want;
        });
        /* 批量工具条和它上面那行提示只对「要导入」有意义 —— 别的桶里它们是误导 */
        var onSpend = (want === 'spend');
        if (bar) bar.style.display = onSpend ? '' : 'none';
        if (hint) hint.style.display = onSpend ? '' : 'none';
      });
    });
  }

  /**
   * 逐笔表的选中 / 批量 / 提交拦截。
   *
   * <p><b>入口是 &lt;form&gt; 而不是批量工具条</b> —— 工具条在「要导入 0 笔」时不渲染,
   * 而<b>提交拦截在那一屏仍然必须生效</b>(用户就是在那一屏去「已剔除」里捞回来的)。
   * 第一版以工具条为入口,把拦截和它绑死了:工具条一没,提交就直接打到服务端,
   * 用户又会看到一条从后端返回的红字 —— 而这正是当初要求前端拦截的原因。</p>
   */
  function init(root) {
    var form = (root || document).querySelector('form[data-bill-form]')
            || (root || document).querySelector('[data-bill-table]') &&
               (root || document).querySelector('[data-bill-table]').closest('form');
    if (!form || form.dataset.billBound) return;
    form.dataset.billBound = '1';

    /* 批量工具条是【可选】的:0 笔可导时模板不渲染它 */
    var bar = document.querySelector('[data-bill-bar]');

    var rows = function () { return Array.prototype.slice.call(form.querySelectorAll('tr[data-row]')); };
    var sels = function () { return Array.prototype.slice.call(form.querySelectorAll('input[data-sel]')); };
    var picked = function () { return sels().filter(function (c) { return c.checked; }); };
    var countEl = bar ? bar.querySelector('[data-sel-count]') : null;

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

    var bulkCat = bar && bar.querySelector('[data-bulk-cat]');
    if (bulkCat) bulkCat.addEventListener('change', function () {
      if (!bulkCat.value) return;
      if (!needSelection()) { setValue(bulkCat, ''); return; }
      picked().forEach(function (c) { setValue(rowOf(c).querySelector('select[data-cat]'), bulkCat.value); });
      setValue(bulkCat, '');
    });

    var bulkAcct = bar && bar.querySelector('[data-bulk-acct]');
    if (bulkAcct) bulkAcct.addEventListener('change', function () {
      if (!bulkAcct.value) return;
      if (!needSelection()) { setValue(bulkAcct, ''); return; }
      picked().forEach(function (c) { setValue(rowOf(c).querySelector('select[data-acct]'), bulkAcct.value); });
      setValue(bulkAcct, '');
    });

    var drop = bar && bar.querySelector('[data-bulk-drop]');
    if (drop) drop.addEventListener('click', function () {
      if (!needSelection()) return;
      picked().forEach(function (c) { setDropped(rowOf(c), true); });
    });

    var keep = bar && bar.querySelector('[data-bulk-keep]');
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
      /* 「捞回来」也是有内容要提交 —— 少了这一条,第二次导同一份账单
         (要导入 0 笔,全是已存在)想把误剔的几笔捞回来时会被自己拦死,
         而且弹的是「所有笔都被剔除了」,和用户正在做的事完全对不上。 */
      var restored = form.querySelectorAll('input[name="restore"]:checked').length;
      if (left.length === 0 && restored === 0) {
        e.preventDefault();
        alert(rows().length === 0
          ? '这一批没有要导入的笔 —— 都在「已存在」里了。\n想捞回被剔除的,去「已剔除」那一桶勾「捞回来」。'
          : '所有笔都被剔除了,没有可导入的内容。\n选中几笔点「撤销剔除」,或者直接「取消本次导入」。');
        return;
      }
      var acct = form.querySelector('select[name="accountId"]');
      if (acct && !acct.value) {
        e.preventDefault();
        alert('先选一下「算在哪个账户名下」。');
      }
    });


    /* ═══ 「让 AI 猜这 N 笔」(FR-579)═══
       只有这个按钮转圈,整页照常可用。结果在客户端应用,
       【用户改过的行跳过】—— 所以不存在「AI 覆盖了我刚改的」这种事。 */
    var aiBtn = document.querySelector('[data-ai-guess]');
    var aiMsg = document.querySelector('[data-ai-msg]');
    // 用户一旦手动改过某行的分类,就打上标记,AI 不再碰它
    form.querySelectorAll('select[data-cat]').forEach(function (sel) {
      sel.addEventListener('change', function () {
        if (!sel.dataset.aiApplying) sel.dataset.touched = '1';
      });
    });
    if (aiBtn) aiBtn.addEventListener('click', function () {
      if (aiBtn.getAttribute('data-available') !== 'true') {
        if (aiMsg) aiMsg.textContent = '还没配 AI(或者 key 没余额)—— 没有它也能用,认不出来的落「其他」等你改。';
        return;
      }
      var label = aiBtn.textContent;
      aiBtn.disabled = true;
      aiBtn.textContent = '问 AI 中…';
      if (aiMsg) aiMsg.textContent = '';
      var tok = csrfField();
      var body = new URLSearchParams();
      if (tok) body.append(tok.name, tok.value);
      fetch('/expense/import/ai-guess', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: body
      }).then(function (r) {
        /* 非 2xx 要单独说 —— 掉进 .catch 里会被报成「网络没通」,那是假的 */
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      }).then(function (j) {
        aiBtn.disabled = false;
        aiBtn.textContent = label;
        if (!j || !j.ok) { if (aiMsg) aiMsg.textContent = (j && j.reason) || 'AI 没答上来。'; return; }
        var n = 0;
        form.querySelectorAll('tr[data-row]').forEach(function (tr) {
          var sel = tr.querySelector('select[data-cat]');
          if (!sel || sel.disabled || sel.dataset.touched) return;   // 改过的不碰
          var m = tr.getAttribute('data-merchant');
          var g = m && j.guess ? j.guess[m] : null;
          if (!g) return;
          sel.dataset.aiApplying = '1';
          setValue(sel, String(g.id));
          delete sel.dataset.aiApplying;
          var badge = tr.querySelector('[data-how-badge]');
          if (badge) { badge.textContent = 'AI 猜的'; badge.classList.remove('border-rust', 'text-rust'); }
          n++;
        });
        if (aiMsg) aiMsg.textContent = n > 0
          ? ('AI 改了 ' + n + ' 笔 —— 还是要扫一眼,它只是在猜。')
          : 'AI 这次没给出能用的建议,剩下的手动改一下。';
      }).catch(function (e) {
        aiBtn.disabled = false;
        aiBtn.textContent = label;
        /* 原因带上 —— 「网络没通」对 403/500 是撒谎,用户会一直重试 */
        if (aiMsg) aiMsg.textContent = '没问成(' + (e && e.message ? e.message : '未知') + ')—— 不影响手动改,照常提交就行。';
      });
    });

    refresh();
  }

  document.addEventListener('DOMContentLoaded', function () { initTabs(); init(document); });
  document.body && document.body.addEventListener('htmx:afterSwap', function (e) { initTabs(); init(e.target || document); });
})();
