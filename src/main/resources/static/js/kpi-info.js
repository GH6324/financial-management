/**
 * KPI 口径说明 tooltip · v0.4.13
 *
 * 交互:点击 [data-kpi-info] 按钮 → toggle 同层 .kpi-info-panel 显隐
 * 手机端 tap toggle · 桌面端也是 click(不用 hover · 保持一致)
 * 点击页面其它位置 → 关闭所有 panel
 *
 * 事件委托 · HTMX afterSettle 不需要重绑
 */
(function () {
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-kpi-info]');
    if (btn) {
      e.preventDefault();
      e.stopPropagation();
      var panel = btn.nextElementSibling;
      if (!panel || !panel.classList.contains('kpi-info-panel')) return;
      var wasHidden = panel.classList.contains('hidden');
      // 先关闭所有已开的
      document.querySelectorAll('.kpi-info-panel:not(.hidden)').forEach(function (p) {
        p.classList.add('hidden');
      });
      // toggle 当前
      if (wasHidden) panel.classList.remove('hidden');
    } else {
      // 点击其它区域 → 关闭所有
      document.querySelectorAll('.kpi-info-panel:not(.hidden)').forEach(function (p) {
        p.classList.add('hidden');
      });
    }
  });
})();
