/**
 * 长文目录 scrollspy + 移动 sheet · v0.5.6(reports)→ v0.5.7 抽为共用(dashboard/checkup/…)
 *
 * 页面无关:自动接 [data-toc-nav] 里的 a[href^="#"] 与其指向的 section。
 * - PC 右/左栏常驻树状目录:滚动高亮当前所在节(aria-current 样式钩子)
 * - 取"顶部已越参考线的最后一节" → 天然处理嵌套 + HTMX 换区块后重算
 * - 移动:左上唤醒钮 → 底部 sheet(× / Esc / 点击跳转后收起)
 * 用法:页面含 .toc-rail / [data-toc-nav] / #toc-fab-btn / #toc-sheet,末尾 <script src="/js/toc.js">。
 */
(function () {
  function init() {
    function tocLinks() { return Array.prototype.slice.call(document.querySelectorAll('[data-toc-nav] a[href^="#"]')); }
    function targetIds() {
      var seen = {}, ids = [];
      var nav = document.querySelector('[data-toc-nav]');           // 第一份 nav 定文档序(两份 id 集相同)
      if (!nav) return ids;
      nav.querySelectorAll('a[href^="#"]').forEach(function (a) {
        var id = a.getAttribute('href').slice(1);
        if (id && !seen[id]) { seen[id] = 1; ids.push(id); }
      });
      return ids;
    }
    var LINE = 110, ticking = false;
    function spy() {
      ticking = false;
      var ids = targetIds(), cur = ids.length ? ids[0] : null;
      for (var i = 0; i < ids.length; i++) {
        var el = document.getElementById(ids[i]);
        if (el && el.getBoundingClientRect().top - LINE <= 0) cur = ids[i];   // 顶部越线的最后一节
      }
      tocLinks().forEach(function (a) {
        if (a.getAttribute('href').slice(1) === cur) a.setAttribute('aria-current', 'true');
        else a.removeAttribute('aria-current');
      });
    }
    window.addEventListener('scroll', function () { if (!ticking) { ticking = true; requestAnimationFrame(spy); } }, { passive: true });
    window.addEventListener('resize', spy);
    if (document.body) document.body.addEventListener('htmx:afterSettle', function () { spy(); });   // HTMX 换区块后重算

    // 移动 sheet
    var fab = document.getElementById('toc-fab-btn'),
        sheet = document.getElementById('toc-sheet'),
        mask = document.getElementById('toc-mask');
    function closeSheet() { if (!sheet) return; sheet.classList.remove('open'); mask.classList.remove('open'); if (fab) fab.setAttribute('aria-expanded', 'false'); }
    function openSheet() { if (!sheet) return; sheet.classList.add('open'); mask.classList.add('open'); if (fab) fab.setAttribute('aria-expanded', 'true'); }
    if (fab && sheet && mask) {
      fab.addEventListener('click', openSheet);
      mask.addEventListener('click', closeSheet);
      var x = document.getElementById('toc-sheet-x');
      if (x) x.addEventListener('click', closeSheet);
      document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeSheet(); });   // 不只靠手势
    }
    // 平滑跳转 + 跳完收起 sheet(section 设 scroll-margin-top 处理 sticky-nav 偏移)
    tocLinks().forEach(function (a) {
      a.addEventListener('click', function (e) {
        e.preventDefault();
        var el = document.querySelector(this.getAttribute('href'));
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        closeSheet();
      });
    });

    spy();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
