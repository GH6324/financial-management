/*
 * v1.21 · 填报页流水列表的搜索 + 筛选 + 分页(纯前端)。
 *
 * 为什么需要:账单导入能一次落进几百笔,而这个列表原来是全量平铺的 ——
 * 一个月 300 笔时填报页会变成一条望不到头的长龙,想找某一笔只能 Ctrl+F。
 *
 * 为什么做在前端:数据本来就已经全量渲染在页面上了。再去服务端分页等于多一轮往返、
 * 多一套参数、多一处「分页参数和筛选参数怎么组合」的状态,而这是纯展示层的事,
 * 不碰任何取数口径 —— 底部的合计行永远是【全部】的合计,不随筛选变。
 *
 * 搜索粒度(空格分隔 = AND,每个词都要命中):
 *   · 文本  → 类目 / 账户 / 备注 / 主理人
 *   · 数字  → 也匹配金额(「32」能搜到 32.50)
 *   · 日期  → 「09-08」「2026-09」都行
 * 可搜的文本由服务端写进每行的 data-s —— 不靠读 DOM 文字:
 * 那样会把「已出账 + 流水」这类装饰文案也搜进去,而且金额被隐私模式糊过之后就搜不到了。
 *
 * 筛选器(v1.21.2)与搜索是【AND】关系,各自独立:
 * 搜索要求用户先想起自己要搜什么,筛选器把当期实际有的值摆出来直接点。
 * 每行的取值由服务端写进 data-f-<键>,和 data-s 同理 —— 不从 DOM 文字里反推。
 *
 * DOM 一律走 createElement / textContent,不拼 HTML(与 lens-select 同一条纪律)。
 */
(function () {
  'use strict';

  /** 少于这么多行整条工具条都不出现 —— 一个月记三五笔的家庭不需要搜索框,那是噪音 */
  var MIN_ROWS = 12;
  /* 默认 10 条。此前是 20 —— 手机上要滑很久才够得到分页按钮。
     想一次看更多的走工具条上的档位切换,不替用户定死。 */
  var DEFAULT_SIZE = 10;

  function init(root) {
    (root || document).querySelectorAll('[data-flow-list]').forEach(function (list) {
      if (list.dataset.flowBound) return;
      list.dataset.flowBound = '1';

      var id = list.getAttribute('data-flow-list');
      var bar = document.querySelector('[data-flow-bar="' + id + '"]');
      var rows = Array.prototype.slice.call(list.querySelectorAll('[data-flow-row]'));
      if (!bar || rows.length < MIN_ROWS) return;   // 行数少 → 保持原样,工具条不露面
      bar.hidden = false;

      var q = bar.querySelector('[data-flow-q]');
      var countEl = bar.querySelector('[data-flow-count]');
      var pgnum = bar.querySelector('[data-flow-pgnum]');
      var prev = bar.querySelector('[data-flow-prev]');
      var next = bar.querySelector('[data-flow-next]');
      var sizeSel = bar.querySelector('[data-flow-size]');
      var filters = Array.prototype.slice.call(bar.querySelectorAll('[data-flow-f]'));
      var clearBtn = bar.querySelector('[data-flow-clear]');
      var page = 0;
      var size = DEFAULT_SIZE;
      var matched = rows;

      function haystack(r) { return (r.getAttribute('data-s') || '').toLowerCase(); }

      function filter() {
        var terms = (q.value || '').toLowerCase().trim().split(/\s+/).filter(Boolean);
        /* 只取有值的筛选器 —— 空值 = 不约束这个维度,不是「筛出空字符串」 */
        var active = filters.filter(function (f) { return f.value; });
        matched = rows.filter(function (r) {
          var h = haystack(r);
          if (!terms.every(function (t) { return h.indexOf(t) >= 0; })) return false;
          return active.every(function (f) {
            return r.getAttribute('data-f-' + f.getAttribute('data-flow-f')) === f.value;
          });
        });
        if (clearBtn) clearBtn.hidden = active.length === 0 && terms.length === 0;
        page = 0;
        render();
      }

      function setCount(text, num) {
        var b = document.createElement('b');
        b.textContent = String(num);
        var parts = text.split('{}');
        var frag = document.createDocumentFragment();
        frag.appendChild(document.createTextNode(parts[0]));
        frag.appendChild(b);
        frag.appendChild(document.createTextNode(parts[1] || ''));
        countEl.replaceChildren(frag);
      }

      function render() {
        var pages = Math.max(1, Math.ceil(matched.length / size));
        if (page >= pages) page = pages - 1;
        var from = page * size;
        var show = matched.slice(from, from + size);
        var set = new Set(show);
        rows.forEach(function (r) { r.hidden = !set.has(r); });

        if (countEl) {
          /* 筛出多少 / 共多少都要说 —— 只说「12 笔」的话,
             用户会以为这个月就记了 12 笔。 */
          if (matched.length === rows.length) setCount('共 {} 笔', rows.length);
          else setCount('筛出 {} / 共 ' + rows.length + ' 笔', matched.length);
        }
        if (pgnum) pgnum.textContent = (page + 1) + ' / ' + pages;
        if (prev) prev.disabled = page === 0;
        if (next) next.disabled = page >= pages - 1;
      }

      var t = null;
      q.addEventListener('input', function () {
        clearTimeout(t);
        t = setTimeout(filter, 120);   // 几百行时每敲一个字就全量过一遍会卡
      });
      filters.forEach(function (f) { f.addEventListener('change', filter); });

      if (clearBtn) clearBtn.addEventListener('click', function () {
        q.value = '';
        filters.forEach(function (f) {
          if (!f.value) return;
          f.value = '';
          /* 派发 change 让自研下拉(lens-select)同步按钮文案 —— 只改 .value
             的话值清了但用户看到的还是「餐饮美食」。 */
          f.dispatchEvent(new Event('change', { bubbles: true }));
        });
        filter();
      });

      if (sizeSel) sizeSel.addEventListener('change', function () {
        var n = parseInt(sizeSel.value, 10);
        if (!n || n < 1) return;
        /* 切档位时保住【当前这一屏的第一条】,别把用户甩回第一页:
           正在核对第 7 页、想一次多看点,结果跳回开头是最烦的。 */
        var anchor = page * size;
        size = n;
        page = Math.floor(anchor / size);
        render();
      });

      if (prev) prev.addEventListener('click', function () { if (page > 0) { page--; render(); } });
      if (next) next.addEventListener('click', function () { page++; render(); });

      render();
    });
  }

  document.addEventListener('DOMContentLoaded', function () { init(document); });
  /* HTMX 换片段后重来一遍 —— 填报页的录入区会被整块替换 */
  document.body && document.body.addEventListener('htmx:afterSwap', function (e) { init(e.target || document); });
})();
