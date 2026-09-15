/*
 * v1.21 FR-576 · 账单确认页的批量操作。
 *
 * 为什么必须有:一份月账单几百笔。第一版把每行的复选框当成「剔除」,于是
 * ① 想批量改分类/账户时没有「选中」这个概念可用;② 想剔除一批要逐个勾。
 * 维护者原话:「几百笔 你让用户自己一个个去勾选啊」。
 *
 * v1.22 起复选框 = 【录不录】,直接提交(name=include),同时也是批量操作的作用域。
 *
 * v1.21 里它是「选中(纯前端)」,而「录不录」另外用 drop/restore 两个**方向相反**的
 * 参数表达。那套心智来自「系统已经删了一批,用户捞回几个」—— 而那是错的:
 * 剔除的判据是启发式的、会错,不该由系统执行删除。现在只有一个事实:**这一行勾没勾**。
 *
 * 一个勾同时表达两件事(要录入 + 批量改作用于我),这不是偷懒:
 * 你要录的那些行才需要改分类,两者天然重合。
 *
 * 另外这里还管「已存在」那一桶的批量改(data-ex-*),它改的是**已经入账的行**,
 * 提交后由服务端 UPDATE,不是新增。
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
  /* initTabs 与 init 是两个入口(见 initTabs 的注释),但切筛选之后
     「全选」那个勾要跟着可见行重算 —— 用一个模块级引用把 refresh 递过去。 */
  var refreshRef = null;

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

        /* v1.22 ·「不建议录入」不是另一个面板 —— 它的行就在主表里(并进去、排最前)。
           这个页签只是【筛选】:只显示那些行,方便一次全选或逐行采纳。
           做成筛选而不是独立面板,是因为「全选」必须只作用于看得见的行 ——
           两套表就会有两套全选,又回到 v1.21 那种「两个桶两种动作」的老问题。 */
        var filterSuggest = (want === 'suggest');
        var panelKey = filterSuggest ? 'spend' : want;
        document.querySelectorAll('[data-bucket-panel]').forEach(function (pnl) {
          pnl.hidden = pnl.getAttribute('data-bucket-panel') !== panelKey;
        });
        document.querySelectorAll('tr[data-row]').forEach(function (tr) {
          tr.hidden = filterSuggest && tr.getAttribute('data-suggest') !== 'true';
        });
        // 分组头在筛选态下也要跟着藏,否则会留下一堆空标题
        document.querySelectorAll('tr[data-grp]').forEach(function (tr) { tr.hidden = filterSuggest; });

        /* 批量工具条和它上面那行提示只对主表有意义 */
        var onTable = (want === 'spend' || filterSuggest);
        if (bar) bar.style.display = onTable ? '' : 'none';
        if (hint) hint.style.display = onTable ? '' : 'none';
        if (typeof refreshRef === 'function') refreshRef();
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

    /* 提交区那两个数随勾选实时变 —— 勾选现在就是「录不录」,
       数字不跟着动的话用户没法确认自己到底选了多少。
       金额从 data-amt 读,【不读 DOM 文字】:隐私模式会把金额糊掉。 */
    var sumIn = document.querySelector('[data-sum-in]');
    var sumOut = document.querySelector('[data-sum-out]');
    var sumAmt = document.querySelector('[data-sum-amt]');
    var natureCount = parseInt((sumIn && sumIn.getAttribute('data-nature')) || '0', 10) || 0;

    function syncTotals() {
      var all = sels(), on = 0, amt = 0;
      all.forEach(function (c) {
        if (!c.checked) return;
        on++;
        var tr = rowOf(c);
        var v = tr && parseFloat(tr.getAttribute('data-amt'));
        if (!isNaN(v)) amt += v;
      });
      if (sumIn) sumIn.textContent = String(on + natureCount);
      if (sumOut) sumOut.textContent = String(all.length - on);
      if (sumAmt) sumAmt.textContent = '¥' + amt.toLocaleString('zh-CN',
        { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    function refresh() {
      var n = picked().length;
      if (countEl) countEl.textContent = String(n);
      syncTotals();
      // 组头的勾随组内状态走(全选中才勾上)
      form.querySelectorAll('[data-sel-grp]').forEach(function (g) {
        var key = g.getAttribute('data-grp-of');
        var inGrp = sels().filter(function (c) { return c.getAttribute('data-in-grp') === key; });
        g.checked = inGrp.length > 0 && inGrp.every(function (c) { return c.checked; });
      });
      var vis = visibleSels();
      form.querySelectorAll('[data-sel-all],[data-sel-all-2]').forEach(function (a) {
        a.checked = vis.length > 0 && vis.every(function (c) { return c.checked; });
      });
    }

    sels().forEach(function (c) { c.addEventListener('change', refresh); });

    /**
     * 当前【看得见】的那些行。
     *
     * <p>「全选」必须只作用于筛选出来的行(FR-594)。用户筛到「不建议录入 20 行」点全选,
     * 如果实现成「选中全表 200 行」,他会在毫不知情的情况下把另外 180 行的默认状态一起改掉
     * —— 而那 180 行本来是勾上的,全选之后看起来没变化,直到提交才发现录错了。</p>
     */
    function visibleSels() {
      return sels().filter(function (c) {
        var tr = rowOf(c);
        return tr && !tr.hidden && tr.offsetParent !== null;
      });
    }

    form.querySelectorAll('[data-sel-all],[data-sel-all-2]').forEach(function (a) {
      a.addEventListener('change', function () {
        visibleSels().forEach(function (c) { c.checked = a.checked; });
        refresh();
      });
    });

    form.querySelectorAll('[data-sel-grp]').forEach(function (g) {
      g.addEventListener('change', function () {
        var key = g.getAttribute('data-grp-of');
        visibleSels().forEach(function (c) {
          if (c.getAttribute('data-in-grp') === key) c.checked = g.checked;
        });
        refresh();
      });
    });

    /** 没选行就别让批量操作静默什么都不做 —— 说一句比没反应强 */
    function needSelection() {
      if (picked().length) return true;
      alert('先勾几笔 —— 可以点表头的「全选」,或者某个分组左边的勾(那是全选这一组)。');
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

    /* v1.22 · 「剔除所选 / 撤销剔除」这对按钮没了 —— 勾本身就是那个开关。
       留下的是【反选】:对「我只要那 20 行里的 3 行」这种操作最省事。 */
    var invert = bar && bar.querySelector('[data-bulk-invert]');
    if (invert) invert.addEventListener('click', function () {
      visibleSels().forEach(function (c) { c.checked = !c.checked; });
      refresh();
    });

    var none = bar && bar.querySelector('[data-bulk-none]');
    if (none) none.addEventListener('click', function () {
      visibleSels().forEach(function (c) { c.checked = false; });
      refresh();
    });

    /* 提交前拦截:一笔都不剩就别提交了 —— 让用户在这里看到原因,
       而不是跳一圈回来看一条「没有要导入的笔」的红字。 */
    form.addEventListener('submit', function (e) {
      // 「扔掉草稿」那个按钮走的是另一个 action,不拦
      if (e.submitter && e.submitter.getAttribute('formaction')) return;
      /* v1.22 · 一笔都没勾时,提交在服务端会变成「什么都没导」。
         但【改「已存在」也是有内容要提交的】—— 用户可能这次只是来修正上次的归类,
         一笔新的都不录。少了这个判断,他会被自己的前端拦死,而且提示完全对不上。 */
      var checked = sels().filter(function (c) { return c.checked; }).length;
      var exEdited = Array.prototype.slice.call(
          form.querySelectorAll('[data-ex-cat],[data-ex-acct]'))
        .filter(function (s2) { return s2.value; }).length;
      if (checked === 0 && exEdited === 0) {
        e.preventDefault();
        alert(sels().length === 0
          ? '这一批没有可录入的笔 —— 都在「已存在」里了。\n点开那一桶可以修正上次归错的分类或账户。'
          : '一笔都没勾 —— 在表格里勾上要录的那些再提交。\n(勾 = 录入;不勾的不会被记进账,也不会被删除)');
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

    refreshRef = refresh;
    refresh();
    initExisting();
  }

  /**
   * 「已存在」那一桶的批量改(FR-598)。
   *
   * <p>它和主表是<b>两套独立的选中</b> —— 语义完全不同:主表的勾是「录不录这一笔新的」,
   * 这里的勾是「批量改这几笔<b>已经入账</b>的」。合并成一套会让「全选」在两个桶之间漏来漏去。</p>
   */
  function initExisting() {
    var exBar = document.querySelector('[data-ex-bar]');
    if (!exBar || exBar.dataset.bound) return;
    exBar.dataset.bound = '1';

    var rows = function () { return Array.prototype.slice.call(document.querySelectorAll('[data-ex-row]')); };
    var sels = function () {
      return rows().map(function (tr) { return tr.querySelector('[data-ex-sel]'); }).filter(Boolean);
    };
    var picked = function () { return sels().filter(function (c) { return c.checked; }); };
    var countEl = exBar.querySelector('[data-ex-count]');
    var allBox = exBar.querySelector('[data-ex-all]');

    function refresh() {
      if (countEl) countEl.textContent = String(picked().length);
      var all = sels();
      if (allBox) allBox.checked = all.length > 0 && all.every(function (c) { return c.checked; });
    }
    sels().forEach(function (c) { c.addEventListener('change', refresh); });
    if (allBox) allBox.addEventListener('change', function () {
      sels().forEach(function (c) { c.checked = allBox.checked; });
      refresh();
    });

    function needSel() {
      if (picked().length) return true;
      alert('先勾几笔 —— 左边那一列的勾是「批量改这几笔」。');
      return false;
    }

    var bulkCat = exBar.querySelector('[data-ex-bulk-cat]');
    if (bulkCat) bulkCat.addEventListener('change', function () {
      if (!bulkCat.value) return;
      if (!needSel()) { setValue(bulkCat, ''); return; }
      picked().forEach(function (c) {
        setValue(c.closest('tr').querySelector('[data-ex-cat]'), bulkCat.value);
      });
      setValue(bulkCat, '');
    });

    var bulkAcct = exBar.querySelector('[data-ex-bulk-acct]');
    if (bulkAcct) bulkAcct.addEventListener('change', function () {
      if (!bulkAcct.value) return;
      if (!needSel()) { setValue(bulkAcct, ''); return; }
      var skipped = 0;
      picked().forEach(function (c) {
        var tr = c.closest('tr');
        /* 已关账的期不许改账户(FR-600)—— 批量时【静默跳过】会让用户以为改上了,
           所以最后要说一句实话:跳过了几笔、为什么。 */
        if (tr.getAttribute('data-closed') === 'true') { skipped++; return; }
        setValue(tr.querySelector('[data-ex-acct]'), bulkAcct.value);
      });
      setValue(bulkAcct, '');
      if (skipped > 0) {
        alert('有 ' + skipped + ' 笔在已关账的账期,账户没改 —— 改账户要动那几期已封存的余额。\n'
            + '它们的分类还是可以改的。');
      }
    });

    refresh();
  }

  document.addEventListener('DOMContentLoaded', function () { initTabs(); init(document); });
  document.body && document.body.addEventListener('htmx:afterSwap', function (e) { initTabs(); init(e.target || document); });
})();
