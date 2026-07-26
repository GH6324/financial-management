/* v1.1 · 资产透视前端(tech-design v1.1 决策 6:本页走原生 JS + fetch JSON,唯一后端 = POST /lens/query)
 * 状态机:board(当前看板)+ drill(筛选栈)+ sunDims(旭日两级维度)+ pivot 行/列 + 度量。
 * 组件:A 旭日(ECharts sunburst·点块下钻/面包屑回退)· B 切片排行 · C 交叉透视(热力+小计)· D 明细抽屉。
 * 移动端:旭日恒 2 环 · 透视横滑 sticky 首列 · 看板 chips 横滑。金额元素带 data-priv(隐私模式模糊)。
 * XSS:所有插入 innerHTML 的动态值(维度标签/账户名/看板名)一律经 esc() 转义。 */
(function () {
  'use strict';
  var chartFont = window.chartFont || function (b) { return b; };   // v1.2.2 字号档位:旭日/引线字号跟随
  var parse = function (v) { return typeof v === 'string' ? JSON.parse(v) : (v || []); };
  var DIMS = parse(window.LENS_META.dims);
  var MEASURES = parse(window.LENS_META.measures);
  var USER_BOARDS = parse(window.LENS_META.boards);
  var DIM_LABEL = {}; DIMS.forEach(function (d) { DIM_LABEL[d.key] = d.label; });
  var ALL_MEASURES = MEASURES.map(function (m) { return m.key; });
  var MEASURE_LABEL = {}; MEASURES.forEach(function (m) { MEASURE_LABEL[m.key] = m.label; });
  var DIM_HOLDING = {}; DIMS.forEach(function (d) { DIM_HOLDING[d.key] = !!d.holdingLevel; });

  /* v1.3 · 旭日可选分析指标(子集 + 渲染类型):
       amount 弧长=金额,维值配色 · pnl 弧长=|收益额|,绿赚赭亏 · ratio 弧长=市值,颜色=收益率热力(比率不可按角度分)。
     持仓级维度(行业/地域)下账户级收益(latestPnl/cumReturn)不可精确归因 → 灰置(承 PivotEngine 诚实降级)。 */
  var SUN_METRICS = [
    { key: 'value', kind: 'amount' }, { key: 'netPrincipal', kind: 'amount' },
    { key: 'latestPnl', kind: 'pnl' }, { key: 'latestReturn', kind: 'ratio' },
    { key: 'cumPnl', kind: 'pnl' }, { key: 'cumReturn', kind: 'ratio' }
  ];
  var SUN_KIND = {}; SUN_METRICS.forEach(function (m) { SUN_KIND[m.key] = m.kind; });
  /* 账户级度量,持仓级维度(行业/地域)下不可精确归因 → 灰置(cumPnl 有持有口径兜底,value 恒可) */
  var SUN_HOLDING_DEGRADE = { netPrincipal: 1, latestPnl: 1, latestReturn: 1, cumReturn: 1 };
  function sunMetricUnavailable(mkey) {
    var holding = DIM_HOLDING[state.sunDims[0]] || DIM_HOLDING[state.sunDims[1]];
    return holding && !!SUN_HOLDING_DEGRADE[mkey];
  }
  /* 收益色:绿赚赭亏(深浅按幅度)/ 收益率热力(绿高→赭负) */
  var CLR_F = '#4f6b47', CLR_R = '#9c4a2a';
  function hex2(x) { return [parseInt(x.slice(1, 3), 16), parseInt(x.slice(3, 5), 16), parseInt(x.slice(5, 7), 16)]; }
  function mixHex(a, b, t) { var A = hex2(a), B = hex2(b); return '#' + [0, 1, 2].map(function (i) { return Math.round(A[i] + (B[i] - A[i]) * t).toString(16).padStart(2, '0'); }).join(''); }
  function pnlColor(v, maxA) { var a = Math.min(1, Math.abs(Number(v || 0)) / (maxA || 1)), t = 0.32 + 0.55 * a; return Number(v) >= 0 ? mixHex('#cfe0c4', CLR_F, t) : mixHex('#e8cabb', CLR_R, t); }
  function rateColor(r) { if (r === null || r === undefined) return '#d8cfba'; var c = Math.max(-1, Math.min(1, Number(r) / 15)); return c >= 0 ? mixHex('#eef1e9', CLR_F, c) : mixHex('#f0e0d8', CLR_R, -c); }
  /* 五套环级配色方案(preview/v1.1/sunburst-palette.html · 2026-07-16 拍板):管理页可配,默认 D 莫兰迪。
   * 每套 [内环, 外环];色值与 admin/calc-tweaks.html 的色卡预览同源,改动需两处同步。 */
  var PALETTE_PLANS = {
    A: [ /* 飞书原味 深内浅外(VChart 官方主板 + 官方浅色伴生) */
      ['#1664FF', '#1AC6FF', '#FF8A00', '#3CC780', '#7442D4', '#FFC400', '#304D77', '#B48DEB', '#009488', '#FF7DDA'],
      ['#B2CFFF', '#94EFFF', '#FFCE7A', '#B9EDCD', '#DDC5FA', '#FAE878', '#8B959E', '#EFE3FF', '#59BAA8', '#FFCFEE']
    ],
    B: [ /* 外环原版主打 内环压墨托底(主板 ×0.72 预计算) */
      ['#1048B8', '#138FB8', '#B86300', '#2B8F5C', '#543099', '#B88D00', '#233756', '#8266A9', '#006B62', '#B85A9D'],
      ['#1664FF', '#1AC6FF', '#FF8A00', '#3CC780', '#7442D4', '#FFC400', '#304D77', '#B48DEB', '#009488', '#FF7DDA']
    ],
    C: [ /* 深内浅外 + 色相错位(浅档循环错 3 位) */
      ['#1664FF', '#1AC6FF', '#FF8A00', '#3CC780', '#7442D4', '#FFC400', '#304D77', '#B48DEB', '#009488', '#FF7DDA'],
      ['#B9EDCD', '#DDC5FA', '#FAE878', '#8B959E', '#EFE3FF', '#59BAA8', '#FFCFEE', '#B2CFFF', '#94EFFF', '#FFCE7A']
    ],
    D: [ /* 莫兰迪高级灰(默认) */
      ['#5B6A78', '#8A6E63', '#6E7F5C', '#7D6A85', '#A9865F', '#5F7E7B', '#946B6B', '#6B7FA0', '#867E58', '#75616E'],
      ['#B9C2CC', '#D6C3BB', '#C2CCB4', '#CBBFD1', '#E0CDAE', '#B7CCCA', '#D8BFBF', '#BFC9DC', '#D3CDB0', '#C9B9C4']
    ],
    E: [ /* 国风传统色(青黛/胭脂/缃金/竹青…) */
      ['#2B5E7D', '#9D2933', '#3C7A63', '#B8823B', '#5A4A78', '#316B65', '#8C4356', '#6B7A3A', '#7A4E2D', '#44506B'],
      ['#A8C4D4', '#E0A9A9', '#A9CBB7', '#E4C98E', '#C3B5D6', '#9FC9C3', '#D4A9B8', '#C5CB9A', '#D0AF93', '#AEB8CC']
    ]
  };
  var RING_PALETTES = PALETTE_PLANS[window.LENS_META.palette] || PALETTE_PLANS.D;
  /* 环内防撞:对本环出现的全部维值按字典序统一分配 —— 哈希定起点、线性探测避让已用色,
   * 值≤色数时保证互不同色;字典序使分配与遍历顺序无关(旭日外环与排行条对同一值集必得同色)。 */
  /* v1.6 UED review B4-3 · 风险是有序(ordinal)维度,套分类色板会得到
     「高风险=紫 / 中风险=粉紫 / 低风险=青」这种从颜色读不出高低的组合 —— 用户无法凭色判断轻重。
     检测到风险等级维值时改用低→高顺序色阶(与 checkup riskChart 的 riskColors 同一套)。 */
  var RISK_RANK = {
    '无风险': 0, '无': 0, '极低风险': 1, '极低': 1, '低风险': 2, '低': 2, '中低风险': 3, '中低': 3,
    '中风险': 4, '中': 4, '中高风险': 5, '中高': 5, '高风险': 6, '高': 6, '极高风险': 7, '极高': 7
  };
  var RISK_SCALE = ['#9bb09a', '#8fa892', '#7ea08a', '#a89a55', '#c1873b', '#b06a3c', '#a55540', '#8a3220'];
  function riskColorMap(values) {
    var map = {}, hit = 0;
    values.forEach(function (v) {
      v = String(v);
      if (map[v] !== undefined) return;
      var r = RISK_RANK[v];
      if (r !== undefined) { map[v] = RISK_SCALE[r]; hit++; }
      else map[v] = '#c8c0ae';                 // 未分类 / 其他 → 中性纸灰,不参与冷暖序
    });
    return hit >= 2 ? map : null;              // 至少两档命中才认定这是风险维度
  }

  function colorMapFor(values, ring) {
    var riskMap = riskColorMap(values);
    if (riskMap) return riskMap;
    var pal = RING_PALETTES[(ring || 0) % RING_PALETTES.length];
    var uniq = []; var seen = {};
    values.forEach(function (v) { v = String(v); if (!seen[v]) { seen[v] = 1; uniq.push(v); } });
    uniq.sort();
    var used = {}; var map = {};
    uniq.forEach(function (v) {
      var h = 0;
      for (var i = 0; i < v.length; i++) h = (h * 31 + v.charCodeAt(i)) >>> 0;
      var idx = h % pal.length;
      for (var k = 0; k < pal.length; k++) {
        var c = pal[(idx + k) % pal.length];
        if (!used[c]) { used[c] = 1; map[v] = c; return; }
      }
      map[v] = pal[idx];   // 值多于色数才会复用
    });
    return map;
  }

  /* 预设看板 · 全维度覆盖(2026-07-17 评审:10 维每维一块;「夫妻结构」→「成员结构」,家庭不一定只两人)
     只是 query spec,非硬编码页面;chips 行 overflow-x 横滑 */
  var PRESETS = [
    /* 排序 = 家庭用户关心度(2026-07-17 评审 #3):先看钱是什么(资产类型)→ 风险高不高 → 谁在管
       → 篮子安不安全 → 是否押注单一行业 → 每笔钱为谁服务 → 变现能力 → 汇率/地域敞口 → 记账口径 */
    { key: 'assetcls',  name: '资产类型',  sun: ['assetClass', 'risk'],     rows: ['assetClass'], cols: ['owner'],      filters: {} },
    { key: 'risk',      name: '风险总览',  sun: ['risk', 'assetClass'],     rows: ['risk'],       cols: ['assetClass'], filters: {} },
    { key: 'member',    name: '成员结构',  sun: ['owner', 'risk'],          rows: ['owner'],      cols: ['assetClass'], filters: {} },
    { key: 'platform',  name: '平台安全',  sun: ['platform', 'assetClass'], rows: ['platform'],   cols: ['assetClass'], filters: {} },
    { key: 'industry',  name: '行业集中',  sun: ['industry', 'platform'],   rows: ['industry'],   cols: ['platform'],   filters: {}  },
    { key: 'purpose',   name: '资金用途',  sun: ['purpose', 'owner'],       rows: ['purpose'],    cols: ['assetClass'], filters: {} },
    { key: 'liquidity', name: '流动性',    sun: ['liquidity', 'assetClass'], rows: ['liquidity'], cols: ['owner'],      filters: {} },
    { key: 'ccy',       name: '币种敞口',  sun: ['currency', 'region'],     rows: ['currency'],   cols: ['assetClass'], filters: {} },
    { key: 'region',    name: '市场地域',  sun: ['region', 'industry'],     rows: ['region'],     cols: ['industry'],   filters: {} },
    { key: 'acctype',   name: '账户类型',  sun: ['type', 'owner'],          rows: ['type'],       cols: ['owner'],      filters: {} }
  ];

  var state = {
    boardKey: 'assetcls',
    sunDims: ['assetClass', 'risk'],
    dimStack: [],            // 下钻前的 sunDims 快照,回退恢复
    drill: [],               // [{dim, value}] 筛选栈(含看板预置筛选,均可移除)
    pivotRows: ['assetClass'], pivotCols: ['owner'],
    measures: ['value', 'share'],   // 指标多选(2026-07-17 #2)· 默认 金额+占比 · 至少 1 个
    measurePos: 'col',             // v1.5.2 · 多指标参与笛卡尔:放列(默认)或行
    sunMetric: 'value',             // v1.3 · 旭日分析指标(单选)· 默认金额
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
  /* 短金额(旭日扇区/中心盘用):¥98万 · ¥1.02亿 · <1万 取整 */
  function fmtShort(v) {
    var n = Number(v || 0), a = Math.abs(n), s = n < 0 ? '−' : '';
    if (a >= 1e8) return s + '¥' + (a / 1e8).toFixed(a >= 1e9 ? 0 : 2) + '亿';
    if (a >= 1e4) return s + '¥' + (a / 1e4).toFixed(a >= 1e6 ? 0 : 1) + '万';
    return s + '¥' + Math.round(a);
  }
  function privacyOn() { return document.documentElement.classList.contains('privacy'); }
  function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;'); }
  function nextDimAfter(key) {
    var used = state.drill.map(function (d) { return d.dim; }).concat([key]);
    for (var i = 0; i < DIMS.length; i++) if (used.indexOf(DIMS[i].key) < 0) return DIMS[i].key;
    return DIMS[0].key;
  }
  /* v1.4.x · 横滑行"还有更多"提示:溢出且未滑到底 → 给 .lens-hscroll 包裹加 .more(CSS 右缘渐隐 + ›) */
  function markHScroll(scroller) {
    if (!scroller) return;
    var wrap = scroller.parentElement;
    if (!wrap || wrap.className.indexOf('lens-hscroll') < 0) return;
    wrap.classList.toggle('more', (scroller.scrollWidth - scroller.clientWidth - scroller.scrollLeft) > 4);
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
    var bw = document.getElementById('builderWrap');           // 切看板 = 放弃自定义,构建器收起
    if (bw) bw.classList.add('hidden');
    syncSelectors(); renderBoards(); refresh();
  }
  function renderBoards() {
    var el = document.getElementById('boardChips');
    var html = '<span class="font-mono text-[10px] tracking-[0.14em] uppercase text-ink-subtle flex-none w-14 whitespace-nowrap">看板</span>';
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
    requestAnimationFrame(function () { markHScroll(el); });
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

  /* ---------- 组件 A · 旭日(v1.3 可选分析指标) ----------
     rows=[内] cols=[外] → rowTotals 给内环"正确聚合"(比率类父级 ≠ 子级之和,必须引擎算,不能前端求和)。
     三模式:amount 弧长=金额+维值色 · pnl 弧长=|收益额|+绿赚赭亏 · ratio 弧长=市值+颜色收益率热力。 */
  function renderSunburst() {
    var mkey = state.sunMetric, kind = SUN_KIND[mkey] || 'amount';
    var measures = mkey === 'value' ? ['value'] : ['value', mkey];
    var mi = measures.indexOf(mkey), vi = measures.indexOf('value');
    var spec = { rows: [state.sunDims[0]], cols: [state.sunDims[1]], measures: measures, filters: filtersObj() };
    return query(spec).then(function (resp) {
      var r = resp.result;
      var inners = r.rowKeys.map(function (rk) { return rk[0]; });
      var childrenByInner = {}, childNames = [];
      r.cells.forEach(function (c) {
        var ik = c.row[0], ok = c.col[0];
        (childrenByInner[ik] = childrenByInner[ik] || []).push({ name: ok, mv: c.values[mi], val: c.values[vi] });
        childNames.push(ok);
      });
      var innerColor = colorMapFor(inners, 0), outerColor = colorMapFor(childNames, 1);
      /* 弧长:比率类=市值(不能按比率分角)· 其余(金额/净投入/收益额)= |该指标| */
      function arcSize(mv, val) { return kind === 'ratio' ? Number(val || 0) : Math.abs(Number(mv || 0)); }
      /* #2 修:所有指标下环色都用配色方案(follow 管理页设置);指标靠 弧长+标签+中心+排行 表达,不再用绿赭覆盖环色 */
      function colorFor(mv, name, isInner) { return isInner ? innerColor[name] : outerColor[name]; }
      var data = inners.map(function (ik, i) {
        var t = r.rowTotals[i];
        return { name: ik, value: arcSize(t[mi], t[vi]), _mv: t[mi], _val: t[vi], itemStyle: { color: colorFor(t[mi], ik, true) },
          children: (childrenByInner[ik] || []).map(function (c) {
            return { name: c.name, value: arcSize(c.mv, c.val), _mv: c.mv, _val: c.val, itemStyle: { color: colorFor(c.mv, c.name, false) } };
          }) };
      });
      var totalSize = data.reduce(function (s, d) { return s + d.value; }, 0);
      function lblFor(mv) {   // 金额/净投入→占比% · 收益额→±短金额 · 收益率→率%
        if (kind === 'ratio') return (mv === null || mv === undefined) ? '—' : (Number(mv) >= 0 ? '+' : '') + Number(mv).toFixed(1) + '%';
        // 收益额是金额 · 隐私模式糊(扇区标签在 canvas 上,CSS data-priv 管不到,渲染时判)
        if (kind === 'pnl') return privacyOn() ? '···' : fmtShort(mv);
        return (totalSize ? Math.abs(Number(mv || 0)) * 100 / totalSize : 0).toFixed(1) + '%';
      }
      data.forEach(function (n) { n._lbl = lblFor(n._mv); n.children.forEach(function (c) { c._lbl = lblFor(c._mv); }); });

      var el = document.getElementById('sunburst');
      if (!chart) chart = echarts.init(el);
      var compact = el.clientWidth < 480;
      /* v1.6 UED review B4-1 · 窄屏环内不写字。
         ECharts 把标签沿弧旋转,在小半径上中文会被转到接近垂直 —— 竖排中文读不了
         (实测「黄金加密」「债券理财」「未分类」多处),小块标签还溢出圆外。
         规范 §7.4 的做法:环只负责结构感,精确读数交给下方已有的「切片排行」列表
         (同一份数据、可精确读、可点)。窄屏只在足够大的块(≥40° ≈ 11%)留一个占比作方位锚点,
         且不旋转(rotate:0)。 */
      var sliceLabel = function (p) {
        var d = p.data;
        if (compact) {
          var deg = totalSize ? p.value * 360 / totalSize : 0;
          // ≥60°(约 17%)的块横排放得下两行「名称 + 占比」;40–60° 只放占比;更小的留白(看下方排行)
          if (deg >= 60) return d.name + '\n' + d._lbl;
          return deg >= 40 ? d._lbl : '';
        }
        var lines = [d.name, d._lbl];
        if (kind === 'amount' && !privacyOn() && totalSize && p.value * 360 / totalSize >= 28) lines.push(fmtShort(d._mv));
        return lines.join('\n');
      };
      var rOuter = compact ? '88%' : '82%', rMid = compact ? '58%' : '54%';
      chart.setOption({
        series: [{
          type: 'sunburst', radius: ['24%', rOuter], data: data, sort: null,
          label: { fontSize: chartFont(compact ? 12 : 11), minAngle: compact ? 40 : 14, lineHeight: 15,
                   rotate: compact ? 0 : 'radial', formatter: sliceLabel },
          levels: [{}, { r0: '24%', r: rMid }, { r0: rMid, r: rOuter,
                   label: { fontSize: chartFont(compact ? 11 : 10), lineHeight: 13, rotate: compact ? 0 : 'radial' } }],
          emphasis: { focus: 'ancestor' },
          nodeClick: false
        }],
        tooltip: {
          formatter: function (p) {
            var d = p.data;
            if (kind === 'ratio') return esc(d.name) + '<br>' + d._lbl + ' · 市值 ' + (privacyOn() ? '···' : fmtShort(d._val));
            if (kind === 'pnl') return esc(d.name) + '<br>' + (privacyOn() ? '···' : fmtMoney(d._mv));
            return esc(d.name) + '<br>' + (privacyOn() ? '···' : fmtMoney(d._mv)) + ' · ' + d._lbl;
          }
        }
      }, true);
      renderLeaders(data, totalSize, el, compact, parseFloat(rOuter) / 100, parseFloat(rMid) / 100);
      /* 中心信息盘:当前指标的总计(点击 → 换指标,见 renderSunMetricBar / boot 绑定);hover 扇区 = 该块 */
      var center = document.getElementById('sunCenter');
      var gm = r.grand[mi];
      renderSunCenter(center, kind, mkey, gm, null);
      chart.off('mouseover'); chart.off('mouseout');
      chart.on('mouseover', function (p) { if (p.data && p.data.name) renderSunCenter(center, kind, mkey, gm, p.data); });
      chart.on('mouseout', function () { renderSunCenter(center, kind, mkey, gm, null); });
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

  /* 中心信息盘渲染(按指标)· node=null 显总计+换指标提示;node=悬停块 */
  function renderSunCenter(center, kind, mkey, grand, node) {
    if (!center) return;
    var head, big, cls = '', mv = node ? node._mv : grand;
    if (node) {
      head = node.name;
      if (kind === 'ratio') { big = (mv == null) ? '—' : (Number(mv) >= 0 ? '+' : '') + Number(mv).toFixed(1) + '%'; cls = Number(mv) >= 0 ? 'num-pos' : 'num-neg'; }
      else if (kind === 'pnl') { big = fmtShort(mv); cls = Number(mv) >= 0 ? 'num-pos' : 'num-neg'; }
      else { big = fmtShort(node._mv); }
    } else if (kind === 'ratio') { head = mkey === 'latestReturn' ? '本期收益率' : '整体收益率'; big = (grand == null) ? '—' : (Number(grand) >= 0 ? '+' : '') + Number(grand).toFixed(1) + '%'; cls = Number(grand) >= 0 ? 'num-pos' : 'num-neg'; }
    else if (kind === 'pnl') { head = mkey === 'latestPnl' ? '本期净收益' : '累计净收益'; big = (Number(grand) >= 0 ? '+' : '') + fmtShort(grand); cls = Number(grand) >= 0 ? 'num-pos' : 'num-neg'; }
    else { head = mkey === 'value' ? '合计' : (MEASURE_LABEL[mkey] || '合计'); big = fmtShort(grand); }
    var priv = (kind !== 'ratio') ? ' data-priv' : '';
    center.innerHTML = '<div class="text-[11px] text-ink-subtle leading-tight" style="max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(head) + '</div>' +
      '<div class="font-display text-sm leading-tight ' + cls + '"' + priv + '>' + big + '</div>' +
      (node ? '' : '<div class="font-mono text-[9px] leading-tight" style="color:var(--brass-deep)">▾ 点换指标</div>');
  }

  /* ---------- 组件 A.1 · 分析指标选择器(v1.3)· 持仓级维度下收益类灰置 ---------- */
  /* v1.4.2 · 重置 = 完全回到"页面刷新后的初始状态":默认看板(资产类型)+ 默认指标(金额+占比 / 总资产)
     + 无下钻 + 收起明细/自定义。此前只重置"当前看板"→ 切了别的看板再点回不到刷新初始态,用户反馈很奇怪。 */
  function resetLens() {
    state.sunMetric = 'value';
    state.measures = ['value', 'share'];   // 透视指标复位默认(与 boot 初始态一致)
    var dw = document.getElementById('drawerWrap'); if (dw) dw.classList.add('hidden');   // 收起明细抽屉
    applyBoard(PRESETS[0], PRESETS[0].key);   // 默认看板 → 清 drill/dimStack/pivot + syncSelectors + renderBoards + refresh(refresh 内 syncInsightCard 管 AI 卡显隐)
  }
  function setSunMetric(k) {
    if (!SUN_KIND[k] || sunMetricUnavailable(k)) return;
    state.sunMetric = k;
    renderSunMetricBar();
    Promise.all([renderSunburst(), renderRanking()]);
  }
  function renderSunMetricBar() {
    var anyOff = false;
    var pillHtml = function (m, forPop) {
      var off = sunMetricUnavailable(m.key); if (off) anyOff = true;
      var on = state.sunMetric === m.key;
      if (forPop) return '<button type="button" data-sm="' + m.key + '"' + (off ? ' disabled' : '') + ' class="' + (on ? 'on' : '') + (off ? ' off' : '') + '">' + esc(MEASURE_LABEL[m.key]) + '</button>';
      return '<button type="button" data-sm="' + m.key + '"' + (off ? ' disabled title="行业/地域(持仓级)维度下账户级收益不可精确归因"' : '') +
        ' class="pill whitespace-nowrap' + (on ? ' pill-ink-active' : '') + (off ? ' sun-m-off' : '') + '">' + esc(MEASURE_LABEL[m.key]) + '</button>';
    };
    var bar = document.getElementById('sunMetricBar');
    if (bar) {   // 与看板 chips 同结构:标签 + pills · overflow-x 横滑不换行
      bar.innerHTML = '<span class="font-mono text-[10px] tracking-[0.14em] uppercase text-ink-subtle flex-none w-14 whitespace-nowrap">分析指标</span>' +
        SUN_METRICS.map(function (m) { return pillHtml(m, false); }).join('');
      bar.querySelectorAll('[data-sm]').forEach(function (btn) { if (!btn.disabled) btn.onclick = function () { setSunMetric(btn.dataset.sm); }; });
      requestAnimationFrame(function () { markHScroll(bar); });
    }
    var pop = document.getElementById('sunMetricPop');
    if (pop) {
      pop.innerHTML = SUN_METRICS.map(function (m) { return pillHtml(m, true); }).join('');
      pop.querySelectorAll('[data-sm]').forEach(function (btn) { if (!btn.disabled) btn.onclick = function (e) { e.stopPropagation(); pop.classList.add('hidden'); setSunMetric(btn.dataset.sm); }; });
    }
    var note = document.getElementById('sunDegradeNote');
    if (note) note.classList.toggle('hidden', !anyOff);
  }

  /* ---------- 组件 A.5 · 小扇区引导线(Excel 式)----------
   * ECharts sunburst 原生无 labelLine:角度 < minAngle(14°)的块 label 被隐藏,比例/名称不可见。
   * PC:graphic 自绘 折线引导 到圆外空白,线端 色点+名称+占比,左右分侧 + 纵向避让(每侧 ≤8 条);
   * 移动(容器 <480px):外侧空间放不下文字 → 退化为图下「小块补注」清单,信息等价不丢。 */
  function renderLeaders(data, grand, el, compact, rOuterPct, rMidPct) {
    var MIN_DEG = 14;
    /* v1.6 UED review B4-2 · PC 上占比极小的块也拉引导线,几条线在同一侧堆成平行线,反而更难读
       (实测「低风险 1.5% / 低风险 0.6% / 中风险 2.1%」三条挤在左侧)。
       低于此占比不拉线,直接落图下「其余小块」补注 —— 信息不丢,画面干净。 */
    var LINE_MIN_PCT = 2.0;
    var lineItems = [], noteItems = [];
    var cum = 0;
    data.forEach(function (n) {
      var span = grand ? n.value * 360 / grand : 0;
      var pctN = grand ? n.value * 100 / grand : 0;
      /* 内环小块不拉线:径向长线要穿过整个外环,与外环小块的引导线大量交叉(2026-07-17 修)→ 落图下补注 */
      if (span > 0 && span < MIN_DEG && pctN >= 0.1) noteItems.push({ name: n.name, pct: pctN, lbl: n._lbl, color: n.itemStyle.color });
      var ccum = cum;
      (n.children || []).forEach(function (c) {
        var cspan = grand ? c.value * 360 / grand : 0;
        var pctC = grand ? c.value * 100 / grand : 0;
        if (cspan > 0 && cspan < MIN_DEG && pctC >= 0.1) {
          if (pctC >= LINE_MIN_PCT) lineItems.push({ mid: ccum + cspan / 2, name: c.name, pct: pctC, lbl: c._lbl, color: c.itemStyle.color });
          else noteItems.push({ name: c.name, pct: pctC, lbl: c._lbl, color: c.itemStyle.color });   // v1.6 B4-2
        }
        ccum += cspan;
      });
      cum += span;
    });
    var W = el.clientWidth, H = el.clientHeight, cx = W / 2, cy = H / 2, R = Math.min(W, H) / 2;
    var pt = function (rp, deg) {   // deg = 自 12 点顺时针
      var rad = (90 - deg) * Math.PI / 180;
      return [cx + rp * R * Math.cos(rad), cy - rp * R * Math.sin(rad)];
    };
    /* 移动:引线无处放 → 全走图下补注;PC:外环小块拉引线,超出容纳数的也落补注 */
    var sides = { right: [], left: [] };
    if (compact) { noteItems = noteItems.concat(lineItems); }
    else { lineItems.forEach(function (it) { sides[((it.mid % 360) + 360) % 360 < 180 ? 'right' : 'left'].push(it); }); }

    var GAP = 17, top = 13, bot = H - 13, maxFit = Math.max(1, Math.floor((bot - top) / GAP) + 1);
    var children = [];
    ['right', 'left'].forEach(function (side) {
      var arr = sides[side];
      if (!arr.length) return;
      arr.forEach(function (it) { it.iy = pt(rOuterPct, it.mid)[1]; });   // 理想 y = 外环出口点
      arr.sort(function (a, b) { return a.iy - b.iy; });
      if (arr.length > maxFit) { arr.slice(maxFit).forEach(function (it) { noteItems.push(it); }); arr = arr.slice(0, maxFit); }
      /* 贪心自上而下:每条不低于上一条 + GAP(理想位=出口 y)→ 保序、不重叠;整体越底再上移并补间距 */
      var prev = top - GAP;
      arr.forEach(function (it) { it.slotY = Math.max(it.iy, prev + GAP); prev = it.slotY; });
      var over = arr.length ? arr[arr.length - 1].slotY - bot : 0;
      if (over > 0) {
        arr.forEach(function (it) { it.slotY = Math.max(top, it.slotY - over); });
        for (var i = 1; i < arr.length; i++) if (arr[i].slotY < arr[i - 1].slotY + GAP) arr[i].slotY = arr[i - 1].slotY + GAP;
      }
      var colX = side === 'right' ? cx + R * rOuterPct + 18 : cx - R * rOuterPct - 18;   // 标签列 · 环外
      arr.forEach(function (it) {
        var y = it.slotY, edge = pt(rOuterPct + 0.005, it.mid), elbowX = side === 'right' ? colX - 13 : colX + 13;
        children.push({ type: 'polyline', silent: true, z: 5, shape: { points: [edge, [elbowX, y], [colX, y]] },
          style: { stroke: '#a79d89', fill: 'none', lineWidth: 1 } });   // 外缘 → 斜 → 平(保序不交叉)
        children.push({ type: 'rect', silent: true, z: 5, shape: { x: side === 'right' ? colX + 2 : colX - 9, y: y - 3.5, width: 7, height: 7 }, style: { fill: it.color } });
        children.push({ type: 'text', silent: true, z: 5, x: side === 'right' ? colX + 13 : colX - 13, y: y,
          style: { text: it.name + ' ' + it.lbl, fill: '#6b6353', font: chartFont(10) + 'px sans-serif', align: side === 'right' ? 'left' : 'right', verticalAlign: 'middle' } });
      });
    });

    var notes = document.getElementById('sunSmallNotes');
    if (notes) {
      notes.innerHTML = noteItems.length ? '<span class="text-ink-subtle mr-1">' + (compact ? '小块:' : '其余小块:') + '</span>' + noteItems.map(function (it) {
        return '<span class="inline-flex items-center gap-1 mr-3 whitespace-nowrap"><span style="width:7px;height:7px;background:' + it.color + ';display:inline-block"></span>' +
          esc(it.name) + ' <span class="font-mono">' + it.lbl + '</span></span>';
      }).join('') : '';
    }
    chart.setOption({ graphic: { elements: [{ id: 'leaders', type: 'group', $action: 'replace', children: children }] } });
  }

  /* ---------- 组件 B · 切片排行 ---------- */
  function renderRanking() {
    var mkey = state.sunMetric, kind = SUN_KIND[mkey] || 'amount';
    var measures = mkey === 'value' ? ['value', 'share'] : ['value', mkey];
    var mi = measures.indexOf(mkey), vi = measures.indexOf('value');
    var spec = { rows: [state.sunDims[1]], cols: [], measures: measures, filters: filtersObj() };
    return query(spec).then(function (resp) {
      var r = resp.result;
      var rows = r.rowKeys.map(function (rk, i) { return { name: rk[0], mv: r.rowTotals[i][mi], val: r.rowTotals[i][vi] }; });
      rows.sort(function (a, b) {
        if (kind === 'pnl') return Math.abs(Number(b.mv || 0)) - Math.abs(Number(a.mv || 0));
        if (kind === 'ratio') return (b.mv == null ? -1e18 : Number(b.mv)) - (a.mv == null ? -1e18 : Number(a.mv));
        return Number(b.mv || 0) - Number(a.mv || 0);   // amount(金额/净投入)按该指标降序
      });
      var barColor = colorMapFor(rows.map(function (x) { return x.name; }), 1);   // 与旭日外环同色
      var totalMv = rows.reduce(function (s, x) { return s + Number(x.mv || 0); }, 0);
      var maxAbs = rows.reduce(function (m, x) { return Math.max(m, Math.abs(Number(x.mv || 0))); }, 0);
      var html = '<div class="eyebrow mb-1">当前范围 · 按 ' + esc(MEASURE_LABEL[mkey] || '总资产') + ' · ' + esc(DIM_LABEL[state.sunDims[1]]) + '</div>';
      rows.forEach(function (x) {
        var right, barW, barBg = barColor[x.name], rcls = '', priv = ' data-priv';
        if (kind === 'ratio') {
          right = (x.mv == null) ? '—' : (Number(x.mv) >= 0 ? '+' : '') + Number(x.mv).toFixed(1) + '%';
          barW = maxAbs ? Math.abs(Number(x.mv || 0)) / maxAbs * 100 : 0; barBg = Number(x.mv) < 0 ? CLR_R : CLR_F;
          rcls = Number(x.mv) >= 0 ? 'num-pos' : 'num-neg'; priv = '';
        } else if (kind === 'pnl') {
          right = fmtShort(x.mv); barW = maxAbs ? Math.abs(Number(x.mv || 0)) / maxAbs * 100 : 0;
          barBg = Number(x.mv) < 0 ? CLR_R : CLR_F; rcls = Number(x.mv) >= 0 ? 'num-pos' : 'num-neg';
        } else {
          var pct = totalMv ? Number(x.mv || 0) * 100 / totalMv : 0; right = fmtMoney(x.mv) + ' · ' + pct.toFixed(1) + '%'; barW = Math.min(pct, 100);
        }
        html += '<div><div class="flex justify-between text-sm mb-1"><span>' + esc(x.name) + '</span>' +
          '<span class="font-mono tnum ' + rcls + '"' + priv + '>' + right + '</span></div>' +
          '<div style="height:18px;background:var(--card-soft);position:relative;overflow:hidden">' +
          '<span style="position:absolute;left:0;top:0;height:100%;width:' + barW + '%;background:' + barBg + '"></span></div></div>';
      });
      if (!rows.length) html += '<p class="text-sm text-ink-subtle">当前范围没有头寸。</p>';
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
      var mkeys = state.measures.slice();
      var mis = mkeys.map(function (k) { return ALL_MEASURES.indexOf(k); });
      var multi = mkeys.length > 1;                      // v1.5.2 · 多指标 → 指标作维度参与笛卡尔
      var mpos = state.measurePos === 'row' ? 'row' : 'col';
      var groupStable = function (keys) {
        if (!keys.length || keys[0].length < 2) return keys.map(function (_, i) { return i; });
        var groups = {}, order = [];
        keys.forEach(function (k, i) { if (!groups[k[0]]) { groups[k[0]] = []; order.push(k[0]); } groups[k[0]].push(i); });
        var idx = []; order.forEach(function (g) { groups[g].forEach(function (i) { idx.push(i); }); });
        return idx;
      };
      var rIdx = groupStable(r.rowKeys), cIdx = groupStable(r.colKeys);
      var rowKeys = rIdx.map(function (i) { return r.rowKeys[i]; });
      var colKeys = cIdx.map(function (i) { return r.colKeys[i]; });
      var rowTotals = rIdx.map(function (i) { return r.rowTotals[i]; });
      var colTotals = cIdx.map(function (i) { return r.colTotals[i]; });
      var cellMap = {};
      r.cells.forEach(function (c, i) { cellMap[c.row.join('|') + '×' + c.col.join('|')] = i; });
      var maxAbsByM = {};   // 热力按每个指标各自量纲
      mis.forEach(function (m) { var mx = 0; r.cells.forEach(function (c) { var v = c.values[m]; if (v !== null) mx = Math.max(mx, Math.abs(Number(v))); }); maxAbsByM[m] = mx; });
      var priv = function (k) { return k !== 'share' && k !== 'cumReturn'; };
      var heatM = function (v, m) { var mx = maxAbsByM[m]; if (v === null || !mx) return ''; var a = Math.min(0.55, 0.08 + 0.47 * Math.abs(Number(v)) / mx); var base = Number(v) < 0 ? '156,74,42' : '176,134,66'; return 'background:rgba(' + base + ',' + a.toFixed(2) + ')'; };
      var dimLabels = function (ds) { return esc(ds.map(function (k) { return DIM_LABEL[k]; }).join(' / ')); };
      var rowDims = rows.length || 1, hasCol = cols.length > 0;
      var td = function (rk, ck, m, j) {
        var idx = cellMap[rk.join('|') + '×' + ck.join('|')];
        if (idx === undefined) return '<td class="tnum">—</td>';
        var v = r.cells[idx].values[m];
        return '<td class="tnum lens-cell" data-cell="' + idx + '" style="' + heatM(v, m) + ';cursor:pointer"' + (priv(mkeys[j]) ? ' data-priv' : '') + '>' + fmtVal(mkeys[j], v) + '</td>';
      };
      var totTd = function (vals, m, j) { return '<td class="tnum"' + (priv(mkeys[j]) ? ' data-priv' : '') + '><b>' + fmtVal(mkeys[j], vals[m]) + '</b></td>'; };
      var mHeadTh = function () { var h = ''; mkeys.forEach(function (k) { h += '<th class="text-[10px]">' + esc(MEASURE_LABEL[k]) + '</th>'; }); return h; };
      var span2 = {};
      if (rows.length === 2) { rowKeys.forEach(function (rk, ri) { if (ri > 0 && rowKeys[ri - 1][0] === rk[0]) return; var n = 0; for (var j = ri; j < rowKeys.length && rowKeys[j][0] === rk[0]; j++) n++; span2[ri] = n; }); }
      var rowHead = function (rk, ri) {
        if (rows.length === 2) return (span2[ri] ? '<td class="sticky-col rowhead" rowspan="' + span2[ri] + '"><b>' + esc(rk[0]) + '</b></td>' : '') + '<td class="rowhead">' + esc(rk[1]) + '</td>';
        return '<td class="sticky-col rowhead">' + esc(rk.join(' · ') || '合计') + '</td>';
      };
      var html = '<table class="lens-pivot">';

      if (!multi) {
        var m0 = mis[0];
        if (cols.length === 2) {
          html += '<tr><th class="sticky-col" colspan="' + rowDims + '" rowspan="2">' + dimLabels(rows) + ' \\ ' + dimLabels(cols) + '</th>';
          var runs = []; colKeys.forEach(function (ck) { if (runs.length && runs[runs.length - 1].v === ck[0]) runs[runs.length - 1].n++; else runs.push({ v: ck[0], n: 1 }); });
          runs.forEach(function (g) { html += '<th colspan="' + g.n + '">' + esc(g.v) + '</th>'; });
          html += '<th rowspan="2">小计</th></tr><tr>';
          colKeys.forEach(function (ck) { html += '<th>' + esc(ck[1]) + '</th>'; });
          html += '</tr>';
        } else {
          html += '<tr><th class="sticky-col" colspan="' + rowDims + '">' + (dimLabels(rows) || '—') + (hasCol ? ' \\ ' + dimLabels(cols) : '') + '</th>';
          colKeys.forEach(function (ck) { html += '<th>' + esc(ck.join(' · ') || '合计') + '</th>'; });
          if (hasCol) html += '<th>小计</th>';
          html += '</tr>';
        }
        rowKeys.forEach(function (rk, ri) {
          html += '<tr>' + rowHead(rk, ri);
          colKeys.forEach(function (ck) { html += td(rk, ck, m0, 0); });
          if (hasCol) html += totTd(rowTotals[ri], m0, 0);
          html += '</tr>';
        });
        html += '<tr><td class="sticky-col rowhead" colspan="' + rowDims + '"><b>合计</b></td>';
        colKeys.forEach(function (ck, ci) { html += totTd(colTotals[ci], m0, 0); });
        if (hasCol) html += totTd(r.grand, m0, 0);
        html += '</tr>';
      } else if (mpos === 'col') {
        var mC = mkeys.length;
        if (hasCol) {
          html += '<tr><th class="sticky-col" colspan="' + rowDims + '" rowspan="2">' + (dimLabels(rows) || '—') + ' \\ ' + dimLabels(cols) + '</th>';
          colKeys.forEach(function (ck) { html += '<th colspan="' + mC + '">' + esc(ck.join(' · ')) + '</th>'; });
          html += '<th colspan="' + mC + '">小计</th></tr><tr>';
          colKeys.forEach(function () { html += mHeadTh(); });
          html += mHeadTh() + '</tr>';
        } else {
          html += '<tr><th class="sticky-col" colspan="' + rowDims + '">' + (dimLabels(rows) || '—') + '</th>' + mHeadTh() + '</tr>';
        }
        rowKeys.forEach(function (rk, ri) {
          html += '<tr>' + rowHead(rk, ri);
          colKeys.forEach(function (ck) { mis.forEach(function (m, j) { html += td(rk, ck, m, j); }); });
          if (hasCol) mis.forEach(function (m, j) { html += totTd(rowTotals[ri], m, j); });
          html += '</tr>';
        });
        html += '<tr><td class="sticky-col rowhead" colspan="' + rowDims + '"><b>合计</b></td>';
        colKeys.forEach(function (ck, ci) { mis.forEach(function (m, j) { html += totTd(colTotals[ci], m, j); }); });
        if (hasCol) mis.forEach(function (m, j) { html += totTd(r.grand, m, j); });
        html += '</tr>';
      } else {
        var mR = mkeys.length;
        html += '<tr><th class="sticky-col" colspan="' + (rowDims + 1) + '">' + (dimLabels(rows) || '—') + ' · 指标' + (hasCol ? ' \\ ' + dimLabels(cols) : '') + '</th>';
        colKeys.forEach(function (ck) { html += '<th>' + esc(ck.join(' · ') || '合计') + '</th>'; });
        if (hasCol) html += '<th>小计</th>';
        html += '</tr>';
        rowKeys.forEach(function (rk, ri) {
          mis.forEach(function (m, j) {
            html += '<tr>';
            if (j === 0) html += '<td class="sticky-col rowhead" rowspan="' + mR + '"><b>' + esc(rk.join(' · ') || '合计') + '</b></td>';
            html += '<td class="rowhead text-[11px]">' + esc(MEASURE_LABEL[mkeys[j]]) + '</td>';
            colKeys.forEach(function (ck) { html += td(rk, ck, m, j); });
            if (hasCol) html += totTd(rowTotals[ri], m, j);
            html += '</tr>';
          });
        });
        mis.forEach(function (m, j) {
          html += '<tr>';
          if (j === 0) html += '<td class="sticky-col rowhead" rowspan="' + mR + '"><b>合计</b></td>';
          html += '<td class="rowhead text-[11px]">' + esc(MEASURE_LABEL[mkeys[j]]) + '</td>';
          colKeys.forEach(function (ck, ci) { html += totTd(colTotals[ci], m, j); });
          if (hasCol) html += totTd(r.grand, m, j);
          html += '</tr>';
        });
      }
      html += '</table>';
      if (r.holdingLevelSplit && (state.measures.indexOf('latestPnl') >= 0 || state.measures.indexOf('cumReturn') >= 0)) {
        html += '<p class="text-[11px] mt-2" style="color:var(--rust)">行业 / 地域维度会拆开持仓账户 —— 本期收益额 / 累计收益率无法精确归因,显示「—」;累计收益额按持有口径(市值−成本)。</p>';
      }
      document.getElementById('pivot').innerHTML = html;
      document.querySelectorAll('.lens-cell').forEach(function (cell) { cell.onclick = function () { openDrawer(Number(cell.dataset.cell)); }; });
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
    DIMS.forEach(function (d) { html += '<option value="' + d.key + '" data-py="' + esc(d.key) + '"' + (d.key === current ? ' selected' : '') + '>' + esc(d.label) + '</option>'; });
    sel.innerHTML = html;
  }
  function syncSelectors() {
    fillDimSelect(document.getElementById('nextDimSel'), false, state.sunDims[1]);
    fillDimSelect(document.getElementById('pivotRowSel'), false, state.pivotRows[0]);
    fillDimSelect(document.getElementById('pivotRow2Sel'), true, state.pivotRows[1] || '');
    fillDimSelect(document.getElementById('pivotColSel'), true, state.pivotCols[0] || '');
    fillDimSelect(document.getElementById('pivotCol2Sel'), true, state.pivotCols[1] || '');
    renderMeasurePills();
    renderCrumbs();
  }

  /* ---------- 指标 pills(多选 · 至少 1 个) ---------- */
  function renderMeasurePills() {
    var el = document.getElementById('measurePills');
    if (!el) return;
    var html = MEASURES.map(function (m) {
      var on = state.measures.indexOf(m.key) >= 0;
      return '<button type="button" data-m="' + m.key + '" class="pill text-[10px]' + (on ? ' pill-ink-active' : '') + '">' + esc(m.label) + '</button>';
    }).join('');
    // v1.5.2 · 多指标时:指标作为一个维度参与笛卡尔,拨片选放「列」(默认)还是「行」
    if (state.measures.length > 1) {
      var pos = state.measurePos === 'row' ? 'row' : 'col';
      html += '<span class="ml-2 text-[10px] text-ink-subtle whitespace-nowrap">指标放</span>'
        + '<span class="inline-flex" style="border:1px solid var(--rule);border-radius:999px;overflow:hidden">'
        + '<button type="button" data-mp="col" class="text-[10px] px-2 py-0.5" style="' + (pos === 'col' ? 'background:var(--ink);color:var(--paper)' : 'color:var(--ink-soft)') + '">列</button>'
        + '<button type="button" data-mp="row" class="text-[10px] px-2 py-0.5" style="' + (pos === 'row' ? 'background:var(--ink);color:var(--paper)' : 'color:var(--ink-soft)') + '">行</button>'
        + '</span>';
    }
    el.innerHTML = html;
    el.querySelectorAll('[data-m]').forEach(function (btn) {
      btn.onclick = function () {
        var k = btn.dataset.m, i = state.measures.indexOf(k);
        if (i >= 0) { if (state.measures.length > 1) state.measures.splice(i, 1); }   // 至少留 1 个
        else state.measures.push(k);
        renderMeasurePills(); renderPivot();
      };
    });
    el.querySelectorAll('[data-mp]').forEach(function (btn) {
      btn.onclick = function () { state.measurePos = btn.dataset.mp; renderMeasurePills(); renderPivot(); };
    });
  }

  /* ---------- 刷新(三组件并发 · 同一 drill-path) ---------- */
  function refresh() {
    renderCrumbs();
    if (sunMetricUnavailable(state.sunMetric)) state.sunMetric = 'cumPnl';   // v1.3 · 维度变持仓级 → 回退到可用指标
    renderSunMetricBar();
    if (typeof syncInsightCard === 'function') syncInsightCard();   // 洞察卡随视图键显隐(缓存恢复/切走隐藏)
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
  function pivotDimsChanged() {   // Excel 式多维:行≤2 列≤2,同一维度去重
    var seen = {};
    var pick = function (id) {
      var v = document.getElementById(id).value;
      if (!v || seen[v]) return null;
      seen[v] = 1; return v;
    };
    state.pivotRows = [pick('pivotRowSel'), pick('pivotRow2Sel')].filter(Boolean);
    state.pivotCols = [pick('pivotColSel'), pick('pivotCol2Sel')].filter(Boolean);
    if (!state.pivotRows.length) state.pivotRows = [DIMS[0].key];
    renderPivot();
  }
  ['pivotRowSel', 'pivotRow2Sel', 'pivotColSel', 'pivotCol2Sel'].forEach(function (id) {
    document.getElementById(id).addEventListener('change', pivotDimsChanged);
  });
  document.getElementById('drawerClose').onclick = function () { document.getElementById('drawerWrap').classList.add('hidden'); };

  /* ---------- v1.1.x #7 · AI 洞察(工程判信号 · LLM 只解读)· 按视图键缓存,切换视图自动显隐恢复 ---------- */
  var INSIGHT_CACHE = {};   // viewKey → {text, vendor, at, dismissed}
  function insightKey() {
    return JSON.stringify({ b: state.boardKey, r: state.pivotRows, f: filtersObj() });
  }
  function showInsight(entry) {
    var card = document.getElementById('lensInsightCard');
    if (!card) return;
    document.getElementById('lensInsightBody').textContent = entry.text;
    document.getElementById('lensInsightMeta').textContent = '· ' + entry.vendor + ' · ' + entry.at;
    card.classList.remove('hidden');
  }
  /* 视图切换钩子:本视图有缓存且未被收起 → 恢复展示;否则隐藏(上个视图的洞察不残留) */
  function syncInsightCard() {
    var card = document.getElementById('lensInsightCard');
    if (!card) return;
    var e = INSIGHT_CACHE[insightKey()];
    if (e && !e.dismissed) showInsight(e); else card.classList.add('hidden');
  }
  function fetchInsight(force) {
    var key = insightKey();
    if (!force && INSIGHT_CACHE[key]) { INSIGHT_CACHE[key].dismissed = false; showInsight(INSIGHT_CACHE[key]); return; }
    var btn = document.getElementById('lensInsightBtn');
    var card = document.getElementById('lensInsightCard');
    var body = document.getElementById('lensInsightBody');
    if (btn) { btn.disabled = true; btn.textContent = 'AI 解读中…'; }
    card.classList.remove('hidden');
    document.getElementById('lensInsightMeta').textContent = '';
    body.textContent = '正在解读当前视图(按 ' + (DIM_LABEL[state.pivotRows[0]] || '') + ' 切分)…';
    var c = csrf(); var headers = { 'Content-Type': 'application/json' }; headers[c.header] = c.token;
    fetch('/lens/insight', { method: 'POST', headers: headers,
      body: JSON.stringify({ rows: state.pivotRows, cols: [], measures: ['value', 'share'], filters: filtersObj() }) })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (resp) {
        if (resp.ok) {
          var now = new Date();
          var entry = { text: resp.text, vendor: resp.vendor || 'AI',
            at: ('0' + now.getHours()).slice(-2) + ':' + ('0' + now.getMinutes()).slice(-2), dismissed: false };
          INSIGHT_CACHE[key] = entry;
          showInsight(entry);
        } else {
          body.textContent = resp.text || '暂无解读。';
        }
      })
      .catch(function (e) { body.textContent = '解读失败:' + e.message + ' · 稍后再试'; })
      .finally(function () { if (btn) { btn.disabled = false; btn.textContent = 'AI 解读当前视图'; } });
  }
  var insightBtn = document.getElementById('lensInsightBtn');
  if (insightBtn) {
    insightBtn.addEventListener('click', function () { fetchInsight(false); });
    document.getElementById('lensInsightRefresh').onclick = function () { fetchInsight(true); };
    document.getElementById('lensInsightClose').onclick = function () {
      var e = INSIGHT_CACHE[insightKey()];
      if (e) e.dismissed = true;   // 记住"这个视图我收起了",切回来不自动弹
      document.getElementById('lensInsightCard').classList.add('hidden');
    };
  }

  /* 构建器 */
  fillDimSelect(document.getElementById('bRow'), false, 'platform');
  fillDimSelect(document.getElementById('bRow2'), true, '');
  fillDimSelect(document.getElementById('bCol'), true, 'assetClass');
  document.getElementById('bMeasure').innerHTML = MEASURES.map(function (m) { return '<option value="' + m.key + '" data-py="' + esc(m.key) + '">' + esc(m.label) + '</option>'; }).join('');
  function builderSpec() {
    var rows = [document.getElementById('bRow').value];
    var r2 = document.getElementById('bRow2').value; if (r2 && r2 !== rows[0]) rows.push(r2);
    var col = document.getElementById('bCol').value;
    return { rows: rows, cols: col ? [col] : [], measures: ALL_MEASURES, filters: {} };
  }
  document.getElementById('bApply').onclick = function () {
    var s = builderSpec();
    state.measures = [document.getElementById('bMeasure').value || 'value'];   // 构建器单选 → 指标集重置为该项
    applyBoard({ sun: s.rows.length > 1 ? s.rows : [s.rows[0], s.cols[0] || nextDimAfter(s.rows[0])], rows: [s.rows[0]], cols: s.cols, filters: {} }, 'custom');
  };
  document.getElementById('bSaveForm').addEventListener('submit', function () {
    document.getElementById('bSpecJson').value = JSON.stringify(builderSpec());
  });

  /* 透视表样式(sticky 首列 + 晚清账册) */
  var style = document.createElement('style');
  style.textContent = '.lens-pivot{border-collapse:collapse;width:100%;font-size:calc(13px*var(--fs-scale,1))}' +
    /* 宽度自适配(2026-07-17 #1):数值列 min-width 保证数据区不被行头挤瘪,表格恒铺满容器 */
    '.lens-pivot th,.lens-pivot td{border:1px solid var(--rule);padding:9px 12px;text-align:right;font-family:"JetBrains Mono",monospace;white-space:nowrap}' +
    '.lens-pivot td.tnum{min-width:92px}' +
    '.lens-pivot .rowhead{min-width:96px}' +
    '.lens-pivot th{background:var(--card-soft);font-family:"Noto Serif SC",serif;font-size:calc(12px*var(--fs-scale,1))}' +
    '.lens-pivot .rowhead{text-align:left;background:var(--card-soft);font-family:"Noto Serif SC",serif}' +
    '.lens-pivot .sticky-col{position:sticky;left:0;z-index:1;box-shadow:2px 0 5px -3px rgba(60,44,20,.35)}' +
    '.pill-ink-active{background:var(--ink);color:var(--paper);border-color:var(--ink)}';
  document.head.appendChild(style);

  /* 启动 · 懒加载(性能 B):透视区在仪表盘底部,滚到附近才初始化(ECharts + 3 查询),
     首屏不被透视拖累;锚点直达 #lens-section 会立刻进入视口 → IO 立即触发,天然覆盖;
     无 IntersectionObserver 的老浏览器降级为立即启动。 */
  function boot() {
    renderBoards(); applyBoard(PRESETS[0], PRESETS[0].key);
    /* v1.3 · 点旭日中心圆 = 换分析指标(弹层);点别处收起 */
    var sc = document.getElementById('sunCenter');
    if (sc) sc.onclick = function (e) { e.stopPropagation(); var pop = document.getElementById('sunMetricPop'); if (pop) pop.classList.toggle('hidden'); };
    var rb = document.getElementById('sunResetBtn'); if (rb) rb.onclick = resetLens;   // v1.3 #3 重置
    document.addEventListener('click', function () { var pop = document.getElementById('sunMetricPop'); if (pop) pop.classList.add('hidden'); });
    /* v1.4.x · 横滑行滚动/窗口缩放时更新"还有更多"渐隐提示 */
    var _smb = document.getElementById('sunMetricBar'), _bc = document.getElementById('boardChips');
    if (_smb) _smb.addEventListener('scroll', function () { markHScroll(_smb); });
    if (_bc) _bc.addEventListener('scroll', function () { markHScroll(_bc); });
    window.addEventListener('resize', function () { markHScroll(_smb); markHScroll(_bc); });
    /* v1.5.2 · 交叉表移动端横屏提示(可×掉 · 会话内不再打扰) */
    var _ph = document.getElementById('pivotHint');
    if (_ph) {
      try { if (sessionStorage.getItem('pivotHintX')) _ph.style.display = 'none'; } catch (e) {}
      var _phc = document.getElementById('pivotHintClose');
      if (_phc) _phc.onclick = function () { _ph.style.display = 'none'; try { sessionStorage.setItem('pivotHintX', '1'); } catch (e) {} };
    }
    /* 隐私模式开关切换(html.privacy)→ 重绘旭日:canvas 里的金额 label 不受 CSS 模糊管辖,必须重出图 */
    new MutationObserver(function () { if (chart) renderSunburst(); })
      .observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  }
  var lensSec = document.getElementById('lens-section');
  if (lensSec && 'IntersectionObserver' in window) {
    var io = new IntersectionObserver(function (entries) {
      if (entries.some(function (e) { return e.isIntersecting; })) { io.disconnect(); boot(); }
    }, { rootMargin: '400px' });
    io.observe(lensSec);
  } else {
    boot();
  }
})();
