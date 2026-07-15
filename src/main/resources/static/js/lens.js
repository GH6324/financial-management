/* v1.1 · 资产透视前端(tech-design v1.1 决策 6:本页走原生 JS + fetch JSON,唯一后端 = POST /lens/query)
 * 状态机:board(当前看板)+ drill(筛选栈)+ sunDims(旭日两级维度)+ pivot 行/列 + 度量。
 * 组件:A 旭日(ECharts sunburst·点块下钻/面包屑回退)· B 切片排行 · C 交叉透视(热力+小计)· D 明细抽屉。
 * 移动端:旭日恒 2 环 · 透视横滑 sticky 首列 · 看板 chips 横滑。金额元素带 data-priv(隐私模式模糊)。
 * XSS:所有插入 innerHTML 的动态值(维度标签/账户名/看板名)一律经 esc() 转义。 */
(function () {
  'use strict';
  var parse = function (v) { return typeof v === 'string' ? JSON.parse(v) : (v || []); };
  var DIMS = parse(window.LENS_META.dims);
  var MEASURES = parse(window.LENS_META.measures);
  var USER_BOARDS = parse(window.LENS_META.boards);
  var DIM_LABEL = {}; DIMS.forEach(function (d) { DIM_LABEL[d.key] = d.label; });
  var ALL_MEASURES = MEASURES.map(function (m) { return m.key; });
  var PALETTE = ['#4F6B47', '#B08642', '#9C4A2A', '#3C4A5A', '#5C3A4B', '#7A9471', '#8C6A33', '#5b7a3a', '#6f7f98', '#A09486', '#3d5636', '#c4a35a'];

  /* 预设 5 看板(prd v1.1 FR-6 · 已拍板 D6)—— 只是 query spec,非硬编码页面 */
  var PRESETS = [
    { key: 'risk',     name: '风险总览',   sun: ['risk', 'assetClass'],   rows: ['risk'],     cols: ['assetClass'], filters: {} },
    { key: 'industry', name: '行业集中',   sun: ['industry', 'platform'], rows: ['industry'], cols: ['platform'],   filters: { assetClass: ['权益'] } },
    { key: 'platform', name: '平台安全',   sun: ['platform', 'assetClass'], rows: ['platform'], cols: ['assetClass'], filters: {} },
    { key: 'couple',   name: '夫妻结构',   sun: ['owner', 'risk'],        rows: ['owner'],    cols: ['assetClass'], filters: {} },
    { key: 'ccy',      name: '币种与市场', sun: ['currency', 'region'],   rows: ['region'],   cols: ['industry'],   filters: {} },
    { key: 'purpose',  name: '资金用途',   sun: ['purpose', 'owner'],     rows: ['purpose'],  cols: ['assetClass'], filters: {} }
  ];

  var state = {
    boardKey: 'risk',
    sunDims: ['risk', 'assetClass'],
    dimStack: [],            // 下钻前的 sunDims 快照,回退恢复
    drill: [],               // [{dim, value}] 筛选栈(含看板预置筛选,均可移除)
    pivotRows: ['risk'], pivotCols: ['assetClass'],
    measure: 'value',
    lastPivot: null
  };
  var chart = null;

  /* ---------- 基础 ---------- */
  function csrf() {
    var t = document.querySelector('meta[name="_csrf"]');
    var h = document.querySelector('meta[name="_csrf_header"]');
    return { header: h ? h.content : 'X-XSRF-TOKEN', token: t ? t.content : '' };
  }
  function query(spec) {
    var c = csrf(); var headers = { 'Content-Type': 'application/json' }; headers[c.header] = c.token;
    return fetch('/lens/query', { method: 'POST', headers: headers, body: JSON.stringify(spec) })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); });
  }
  function filtersObj() {
    var f = {};
    state.drill.forEach(function (d) { (f[d.dim] = f[d.dim] || []).push(d.value); });
    return f;
  }
  function fmtMoney(v) {
    if (v === null || v === undefined) return '—';
    var n = Number(v);
    return (n < 0 ? '−' : '') + '¥' + Math.abs(n).toLocaleString('zh-CN', { maximumFractionDigits: 0 });
  }
  function fmtVal(key, v) {
    if (v === null || v === undefined) return '—';
    if (key === 'share' || key === 'cumReturn') return Number(v).toFixed(2) + '%';
    return fmtMoney(v);
  }
  function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;'); }
  function nextDimAfter(key) {
    var used = state.drill.map(function (d) { return d.dim; }).concat([key]);
    for (var i = 0; i < DIMS.length; i++) if (used.indexOf(DIMS[i].key) < 0) return DIMS[i].key;
    return DIMS[0].key;
  }

  /* ---------- 看板 ---------- */
  function applyBoard(b, key) {
    state.boardKey = key;
    state.sunDims = b.sun ? b.sun.slice() : [(b.rows && b.rows[0]) || 'risk', (b.rows && b.rows[1]) || (b.cols && b.cols[0]) || 'assetClass'];
    if (state.sunDims[0] === state.sunDims[1]) state.sunDims[1] = nextDimAfter(state.sunDims[0]);
    state.pivotRows = (b.rows && b.rows.length ? b.rows : [state.sunDims[0]]).slice(0, 1);
    state.pivotCols = (b.cols && b.cols.length ? b.cols : []).slice(0, 1);
    state.drill = [];
    state.dimStack = [];
    Object.keys(b.filters || {}).forEach(function (dim) {
      (b.filters[dim] || []).forEach(function (v) { state.drill.push({ dim: dim, value: v }); });
    });
    syncSelectors(); renderBoards(); refresh();
  }
  function renderBoards() {
    var el = document.getElementById('boardChips');
    var html = '<span class="font-mono text-[10px] tracking-[0.14em] uppercase text-ink-subtle mr-1 whitespace-nowrap">看板</span>';
    PRESETS.forEach(function (p) {
      html += '<button data-board="' + p.key + '" class="pill whitespace-nowrap' + (state.boardKey === p.key ? ' pill-ink-active' : '') + '">' + esc(p.name) + '</button>';
    });
    USER_BOARDS.forEach(function (b) {
      html += '<span class="inline-flex items-center gap-0.5 whitespace-nowrap"><button data-uboard="' + b.id + '" class="pill" style="border-color:var(--brass-deep);color:var(--brass-deep)' + (state.boardKey === 'u' + b.id ? ';background:var(--brass-deep);color:var(--paper)' : '') + '">' + esc(b.name) + '</button>' +
        '<form method="post" action="/lens/boards/' + b.id + '/delete" class="inline">' + csrfInput() + '<button class="text-ink-subtle hover:text-rust text-xs px-0.5" title="删除看板">×</button></form></span>';
    });
    html += '<button id="builderToggle" class="pill whitespace-nowrap" style="border-style:dashed">+ 自定义</button>';
    el.innerHTML = html;
    el.querySelectorAll('[data-board]').forEach(function (btn) {
      btn.onclick = function () { var p = PRESETS.find(function (x) { return x.key === btn.dataset.board; }); applyBoard(p, p.key); };
    });
    el.querySelectorAll('[data-uboard]').forEach(function (btn) {
      btn.onclick = function () {
        var b = USER_BOARDS.find(function (x) { return String(x.id) === btn.dataset.uboard; });
        var spec = typeof b.spec === 'string' ? JSON.parse(b.spec) : b.spec;
        applyBoard({ rows: spec.rows, cols: spec.cols, filters: spec.filters }, 'u' + b.id);
      };
    });
    document.getElementById('builderToggle').onclick = function () {
      var w = document.getElementById('builderWrap');
      w.classList.toggle('hidden');
      if (!w.classList.contains('hidden')) {   // 展开即滚动聚焦(修复:此前藏在页面底部,点了像没反应)
        w.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    };
  }
  function csrfInput() {
    return '<input type="hidden" name="_csrf" value="' + esc(csrf().token) + '">';
  }

  /* ---------- 面包屑(drill 栈) ---------- */
  function renderCrumbs() {
    var el = document.getElementById('crumbs');
    var html = '<span>全部资产</span>';
    state.drill.forEach(function (d, i) {
      html += '<span class="text-ink-subtle">›</span><button data-crumb="' + i + '" class="pill text-[10px]">' +
        esc(DIM_LABEL[d.dim] || d.dim) + ' = ' + esc(d.value) + ' ×</button>';
    });
    html += '<span class="text-ink-subtle">›</span><span>按 <b>' + esc(DIM_LABEL[state.sunDims[0]]) + '</b> 展开</span>';
    el.innerHTML = html;
    el.querySelectorAll('[data-crumb]').forEach(function (btn) {
      btn.onclick = function () {
        var i = Number(btn.dataset.crumb);
        state.drill = state.drill.slice(0, i);
        if (state.dimStack.length > i) { state.sunDims = state.dimStack[i]; state.dimStack = state.dimStack.slice(0, i); }
        syncSelectors(); refresh();
      };
    });
  }

  /* ---------- 组件 A · 旭日 ---------- */
  function renderSunburst() {
    var spec = { rows: [state.sunDims[0], state.sunDims[1]], cols: [], measures: ['value', 'share'], filters: filtersObj() };
    return query(spec).then(function (resp) {
      var r = resp.result;
      var level1 = {}; var order = [];
      r.cells.forEach(function (c) {
        var k1 = c.row[0], k2 = c.row[1];
        if (!level1[k1]) { level1[k1] = { name: k1, value: 0, children: [] }; order.push(k1); }
        level1[k1].value += Number(c.values[0] || 0);
        level1[k1].children.push({ name: k2, value: Number(c.values[0] || 0) });
      });
      var data = order.map(function (k, i) {
        var n = level1[k]; n.itemStyle = { color: PALETTE[i % PALETTE.length] };
        return n;
      });
      var el = document.getElementById('sunburst');
      if (!chart) chart = echarts.init(el);
      chart.setOption({
        series: [{
          type: 'sunburst', radius: ['18%', '92%'], data: data, sort: null,
          label: { fontSize: 11, minAngle: 12, formatter: function (p) { return p.name; } },
          levels: [{}, { r0: '18%', r: '58%' }, { r0: '58%', r: '92%', label: { fontSize: 10 } }],
          emphasis: { focus: 'ancestor' },
          nodeClick: false
        }],
        tooltip: {
          formatter: function (p) {
            var grand = Number(r.grand[0] || 0);
            var pct = grand > 0 ? (p.value * 100 / grand).toFixed(1) + '%' : '';
            return esc(p.name) + '<br>' + fmtMoney(p.value) + ' · ' + pct;
          }
        }
      }, true);
      chart.off('click');
      chart.on('click', function (p) {
        if (!p.data || !p.data.name) return;
        var depth = p.treePathInfo ? p.treePathInfo.length - 1 : 1;
        if (depth >= 2) {  // 点外环:先落内环值再落外环值(两级下钻)
          var parent = p.treePathInfo[1] && p.treePathInfo[1].name;
          if (parent) { state.dimStack.push(state.sunDims.slice()); state.drill.push({ dim: state.sunDims[0], value: parent }); }
          state.dimStack.push(state.sunDims.slice());
          state.drill.push({ dim: state.sunDims[1], value: p.data.name });
          var d1 = nextDimAfter(state.sunDims[1]);
          state.sunDims = [d1, nextDimAfter(d1)];
        } else {          // 点内环:下钻一层,外环维度上位
          state.dimStack.push(state.sunDims.slice());
          state.drill.push({ dim: state.sunDims[0], value: p.data.name });
          state.sunDims = [state.sunDims[1], nextDimAfter(state.sunDims[1])];
        }
        syncSelectors(); refresh();
      });
    });
  }

  /* ---------- 组件 B · 切片排行 ---------- */
  function renderRanking() {
    var spec = { rows: [state.sunDims[1]], cols: [], measures: ['value', 'share'], filters: filtersObj() };
    return query(spec).then(function (resp) {
      var r = resp.result;
      var html = '<div class="eyebrow mb-1">当前范围 · 按 ' + esc(DIM_LABEL[state.sunDims[1]]) + '</div>';
      r.rowKeys.forEach(function (rk, i) {
        var t = r.rowTotals[i];
        var pct = t[1] === null ? 0 : Number(t[1]);
        html += '<div><div class="flex justify-between text-sm mb-1"><span>' + esc(rk[0]) + '</span>' +
          '<span class="font-mono tnum" data-priv>' + fmtMoney(t[0]) + ' · ' + pct.toFixed(1) + '%</span></div>' +
          '<div style="height:18px;background:var(--card-soft);position:relative;overflow:hidden">' +
          '<span style="position:absolute;left:0;top:0;height:100%;width:' + Math.min(pct, 100) + '%;background:' + PALETTE[i % PALETTE.length] + '"></span></div></div>';
      });
      if (!r.rowKeys.length) html += '<p class="text-sm text-ink-subtle">当前范围没有头寸。</p>';
      document.getElementById('ranking').innerHTML = html;
    });
  }

  /* ---------- 组件 C · 交叉透视 ---------- */
  function renderPivot() {
    var rows = state.pivotRows.filter(Boolean), cols = state.pivotCols.filter(Boolean);
    var spec = { rows: rows, cols: cols, measures: ALL_MEASURES, filters: filtersObj() };
    return query(spec).then(function (resp) {
      state.lastPivot = resp;
      var r = resp.result;
      var mi = ALL_MEASURES.indexOf(state.measure);
      var maxAbs = 0;
      r.cells.forEach(function (c) { var v = c.values[mi]; if (v !== null) maxAbs = Math.max(maxAbs, Math.abs(Number(v))); });
      var heat = function (v) {
        if (v === null || maxAbs === 0) return '';
        var a = Math.min(0.55, 0.08 + 0.47 * Math.abs(Number(v)) / maxAbs);
        var base = Number(v) < 0 ? '156,74,42' : '176,134,66';
        return 'background:rgba(' + base + ',' + a.toFixed(2) + ')';
      };
      var cellMap = {};
      r.cells.forEach(function (c, i) { cellMap[c.row.join('|') + '×' + c.col.join('|')] = i; });
      var moneyLike = state.measure !== 'share' && state.measure !== 'cumReturn';
      var showTotalCol = cols.length > 0;
      var html = '<table class="lens-pivot"><tr><th class="sticky-col">' +
        esc(rows.map(function (k) { return DIM_LABEL[k]; }).join(' / ') || '—') +
        (cols.length ? ' \\ ' + esc(cols.map(function (k) { return DIM_LABEL[k]; }).join(' / ')) : '') + '</th>';
      r.colKeys.forEach(function (ck) { html += '<th>' + esc(ck.join(' · ') || '合计') + '</th>'; });
      if (showTotalCol) html += '<th>小计</th>';
      html += '</tr>';
      r.rowKeys.forEach(function (rk, ri) {
        html += '<tr><td class="sticky-col rowhead">' + esc(rk.join(' · ') || '合计') + '</td>';
        r.colKeys.forEach(function (ck) {
          var idx = cellMap[rk.join('|') + '×' + ck.join('|')];
          if (idx === undefined) { html += '<td class="tnum">—</td>'; return; }
          var v = r.cells[idx].values[mi];
          html += '<td class="tnum lens-cell" data-cell="' + idx + '" style="' + heat(v) + ';cursor:pointer"' + (moneyLike ? ' data-priv' : '') + '>' + fmtVal(state.measure, v) + '</td>';
        });
        if (showTotalCol) html += '<td class="tnum"' + (moneyLike ? ' data-priv' : '') + '><b>' + fmtVal(state.measure, r.rowTotals[ri][mi]) + '</b></td>';
        html += '</tr>';
      });
      html += '<tr><td class="sticky-col rowhead"><b>合计</b></td>';
      r.colKeys.forEach(function (ck, ci) { html += '<td class="tnum"' + (moneyLike ? ' data-priv' : '') + '><b>' + fmtVal(state.measure, r.colTotals[ci][mi]) + '</b></td>'; });
      if (showTotalCol) html += '<td class="tnum"' + (moneyLike ? ' data-priv' : '') + '><b>' + fmtVal(state.measure, r.grand[mi]) + '</b></td>';
      html += '</tr></table>';
      if (r.holdingLevelSplit && (state.measure === 'latestPnl' || state.measure === 'cumReturn')) {
        html += '<p class="text-[11px] mt-2" style="color:var(--rust)">行业 / 地域维度会拆开持仓账户 —— 本期收益额 / 累计收益率无法精确归因,显示「—」;累计收益额按持有口径(市值−成本)。</p>';
      }
      document.getElementById('pivot').innerHTML = html;
      document.querySelectorAll('.lens-cell').forEach(function (td) {
        td.onclick = function () { openDrawer(Number(td.dataset.cell)); };
      });
    });
  }

  /* ---------- 组件 D · 明细抽屉 ---------- */
  function openDrawer(cellIdx) {
    var resp = state.lastPivot; if (!resp) return;
    var cell = resp.result.cells[cellIdx];
    var scope = cell.row.join(' · ') + (cell.col.length ? ' × ' + cell.col.join(' · ') : '');
    var list = cell.pos.map(function (i) { return resp.positions[i]; })
      .sort(function (a, b) { return Number(b.value) - Number(a.value); });
    var html = '';
    list.forEach(function (p) {
      html += '<div class="flex items-center justify-between border border-rule p-3 gap-3">' +
        '<div class="min-w-0"><b>' + esc(p.label) + '</b>' +
        (p.holding ? ' <span class="pill text-[9px]">持仓</span>' : ' <span class="pill text-[9px]">账户</span>') +
        '<div class="text-[11px] text-ink-subtle truncate">' + esc(p.accountName) +
        (p.platform ? ' · ' + esc(p.platform) : '') + (p.industry ? ' · ' + esc(p.industry) : '') + '</div></div>' +
        '<div class="text-right shrink-0"><div class="font-mono tnum" data-priv>' + fmtMoney(p.value) + '</div>' +
        '<a class="font-mono text-[11px] no-underline" style="color:var(--brass-deep)" href="/accounts/' + Number(p.accountId) + '">账户详情 →</a></div></div>';
    });
    document.getElementById('drawerScope').textContent = scope + ' · ' + list.length + ' 笔头寸';
    document.getElementById('drawer').innerHTML = html || '<p class="text-sm text-ink-subtle">无头寸。</p>';
    document.getElementById('drawerWrap').classList.remove('hidden');
    document.getElementById('drawerWrap').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  /* ---------- 选择器 ---------- */
  function fillDimSelect(sel, allowEmpty, current) {
    var html = allowEmpty ? '<option value="">(无)</option>' : '';
    DIMS.forEach(function (d) { html += '<option value="' + d.key + '"' + (d.key === current ? ' selected' : '') + '>' + esc(d.label) + '</option>'; });
    sel.innerHTML = html;
  }
  function syncSelectors() {
    fillDimSelect(document.getElementById('nextDimSel'), false, state.sunDims[1]);
    fillDimSelect(document.getElementById('pivotRowSel'), false, state.pivotRows[0]);
    fillDimSelect(document.getElementById('pivotColSel'), true, state.pivotCols[0] || '');
    var ms = document.getElementById('measureSel');
    ms.innerHTML = MEASURES.map(function (m) { return '<option value="' + m.key + '"' + (m.key === state.measure ? ' selected' : '') + '>' + esc(m.label) + '</option>'; }).join('');
    renderCrumbs();
  }

  /* ---------- 刷新(三组件并发 · 同一 drill-path) ---------- */
  function refresh() {
    renderCrumbs();
    Promise.all([renderSunburst(), renderRanking(), renderPivot()]).catch(function (e) {
      document.getElementById('pivot').innerHTML = '<p class="text-sm" style="color:var(--rust)">加载失败:' + esc(e.message) + '</p>';
    });
  }

  /* ---------- 事件 ---------- */
  document.getElementById('nextDimSel').addEventListener('change', function () {
    state.sunDims[1] = this.value;
    if (state.sunDims[1] === state.sunDims[0]) state.sunDims[0] = nextDimAfter(state.sunDims[1]);
    refresh();
  });
  document.getElementById('pivotRowSel').addEventListener('change', function () { state.pivotRows = [this.value]; renderPivot(); });
  document.getElementById('pivotColSel').addEventListener('change', function () { state.pivotCols = this.value ? [this.value] : []; renderPivot(); });
  document.getElementById('measureSel').addEventListener('change', function () { state.measure = this.value; renderPivot(); });
  document.getElementById('drawerClose').onclick = function () { document.getElementById('drawerWrap').classList.add('hidden'); };

  /* 构建器 */
  fillDimSelect(document.getElementById('bRow'), false, 'platform');
  fillDimSelect(document.getElementById('bRow2'), true, '');
  fillDimSelect(document.getElementById('bCol'), true, 'assetClass');
  document.getElementById('bMeasure').innerHTML = MEASURES.map(function (m) { return '<option value="' + m.key + '">' + esc(m.label) + '</option>'; }).join('');
  function builderSpec() {
    var rows = [document.getElementById('bRow').value];
    var r2 = document.getElementById('bRow2').value; if (r2 && r2 !== rows[0]) rows.push(r2);
    var col = document.getElementById('bCol').value;
    return { rows: rows, cols: col ? [col] : [], measures: ALL_MEASURES, filters: {} };
  }
  document.getElementById('bApply').onclick = function () {
    var s = builderSpec();
    state.measure = document.getElementById('bMeasure').value;
    applyBoard({ sun: s.rows.length > 1 ? s.rows : [s.rows[0], s.cols[0] || nextDimAfter(s.rows[0])], rows: [s.rows[0]], cols: s.cols, filters: {} }, 'custom');
  };
  document.getElementById('bSaveForm').addEventListener('submit', function () {
    document.getElementById('bSpecJson').value = JSON.stringify(builderSpec());
  });

  /* 透视表样式(sticky 首列 + 晚清账册) */
  var style = document.createElement('style');
  style.textContent = '.lens-pivot{border-collapse:collapse;width:100%;font-size:13px}' +
    '.lens-pivot th,.lens-pivot td{border:1px solid var(--rule);padding:6px 10px;text-align:right;font-family:"JetBrains Mono",monospace;white-space:nowrap}' +
    '.lens-pivot th{background:var(--card-soft);font-family:"Noto Serif SC",serif;font-size:12px}' +
    '.lens-pivot .rowhead{text-align:left;background:var(--card-soft);font-family:"Noto Serif SC",serif}' +
    '.lens-pivot .sticky-col{position:sticky;left:0;z-index:1}' +
    '.pill-ink-active{background:var(--ink);color:var(--paper);border-color:var(--ink)}';
  document.head.appendChild(style);

  /* 启动 */
  renderBoards();
  applyBoard(PRESETS[0], 'risk');
})();
