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
  function colorMapFor(values, ring) {
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
    { key: 'industry',  name: '行业集中',  sun: ['industry', 'platform'],   rows: ['industry'],   cols: ['platform'],   filters: { assetClass: ['股票股权'] } },
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
      var outerVals = [];
      order.forEach(function (k) { level1[k].children.forEach(function (c) { outerVals.push(c.name); }); });
      var innerColor = colorMapFor(order, 0);      // 内环 · 深调
      var outerColor = colorMapFor(outerVals, 1);  // 外环 · 浅调独立色系 · 环内同值同色(跨父块)
      var data = order.map(function (k) {
        var n = level1[k];
        n.itemStyle = { color: innerColor[k] };
        n.children.forEach(function (c) { c.itemStyle = { color: outerColor[c.name] }; });
        return n;
      });
      var el = document.getElementById('sunburst');
      if (!chart) chart = echarts.init(el);
      var grand = Number(r.grand[0] || 0);
      /* 扇区常显 名称+占比;角度足够(≥28°)且非隐私 → 第三行短金额。角度 = 值占总比 × 360(sunburst 按值分角) */
      var sliceLabel = function (p) {
        if (!grand) return p.name;
        var pct = p.value * 100 / grand;
        var lines = [p.name, pct.toFixed(1) + '%'];
        if (pct * 3.6 >= 28 && !privacyOn()) lines.push(fmtShort(p.value));
        return lines.join('\n');
      };
      /* PC 收窄外半径给引导线标签腾空间;移动端空间不够,小块信息走图下补注(见 renderLeaders) */
      var compact = el.clientWidth < 480;
      var rOuter = compact ? '88%' : '82%', rMid = compact ? '58%' : '54%';
      chart.setOption({
        series: [{
          type: 'sunburst', radius: ['24%', rOuter], data: data, sort: null,
          label: { fontSize: 11, minAngle: 14, lineHeight: 15, formatter: sliceLabel },
          levels: [{}, { r0: '24%', r: rMid }, { r0: rMid, r: rOuter, label: { fontSize: 10, lineHeight: 13 } }],
          emphasis: { focus: 'ancestor' },
          nodeClick: false
        }],
        tooltip: {
          formatter: function (p) {
            var pct = grand > 0 ? (p.value * 100 / grand).toFixed(1) + '%' : '';
            return esc(p.name) + '<br>' + (privacyOn() ? '···' : fmtMoney(p.value)) + ' · ' + pct;
          }
        }
      }, true);
      renderLeaders(data, grand, el, compact, parseFloat(rOuter) / 100, parseFloat(rMid) / 100);
      /* 中心信息盘:默认 = 当前范围合计;hover 任一环扇区 = 该块 名称/金额/占比(内环金额难放扇区里,由此补齐) */
      var center = document.getElementById('sunCenter');
      var centerHtml = function (name, val, pct) {
        return '<div class="text-[11px] text-ink-subtle leading-tight" style="max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(name) + '</div>' +
          '<div class="font-display text-sm leading-tight" data-priv>' + fmtShort(val) + '</div>' +
          (pct === null ? '' : '<div class="font-mono text-[10px] text-ink-subtle leading-tight">' + pct.toFixed(1) + '%</div>');
      };
      if (center) {
        center.innerHTML = centerHtml('合计', grand, null);
        chart.off('mouseover'); chart.off('mouseout');
        chart.on('mouseover', function (p) {
          if (p.data && p.data.name && grand) center.innerHTML = centerHtml(p.data.name, p.value, p.value * 100 / grand);
        });
        chart.on('mouseout', function () { center.innerHTML = centerHtml('合计', grand, null); });
      }
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

  /* ---------- 组件 A.5 · 小扇区引导线(Excel 式)----------
   * ECharts sunburst 原生无 labelLine:角度 < minAngle(14°)的块 label 被隐藏,比例/名称不可见。
   * PC:graphic 自绘 折线引导 到圆外空白,线端 色点+名称+占比,左右分侧 + 纵向避让(每侧 ≤8 条);
   * 移动(容器 <480px):外侧空间放不下文字 → 退化为图下「小块补注」清单,信息等价不丢。 */
  function renderLeaders(data, grand, el, compact, rOuterPct, rMidPct) {
    var MIN_DEG = 14;
    var lineItems = [], noteItems = [];
    var cum = 0;
    data.forEach(function (n) {
      var span = grand ? n.value * 360 / grand : 0;
      var pctN = grand ? n.value * 100 / grand : 0;
      /* 内环小块不拉线:径向长线要穿过整个外环,与外环小块的引导线大量交叉(2026-07-17 修)→ 落图下补注 */
      if (span > 0 && span < MIN_DEG && pctN >= 0.1) noteItems.push({ name: n.name, pct: pctN, color: n.itemStyle.color });
      var ccum = cum;
      (n.children || []).forEach(function (c) {
        var cspan = grand ? c.value * 360 / grand : 0;
        var pctC = grand ? c.value * 100 / grand : 0;
        if (cspan > 0 && cspan < MIN_DEG && pctC >= 0.1) lineItems.push({ mid: ccum + cspan / 2, name: c.name, pct: pctC, color: c.itemStyle.color });
        ccum += cspan;
      });
      cum += span;
    });
    var notes = document.getElementById('sunSmallNotes');
    if (compact) {   // 移动:全部走图下补注
      noteItems = noteItems.concat(lineItems);
      lineItems = [];
    }
    if (notes) {
      notes.innerHTML = noteItems.length ? '<span class="text-ink-subtle mr-1">' + (compact ? '小块:' : '内环小块:') + '</span>' + noteItems.map(function (it) {
        return '<span class="inline-flex items-center gap-1 mr-3 whitespace-nowrap"><span style="width:7px;height:7px;background:' + it.color + ';display:inline-block"></span>' +
          esc(it.name) + ' <span class="font-mono">' + it.pct.toFixed(1) + '%</span></span>';
      }).join('') : '';
    }
    if (!lineItems.length) {
      chart.setOption({ graphic: { elements: [{ id: 'leaders', type: 'group', $action: 'replace', children: [] }] } });
      return;
    }
    var W = el.clientWidth, H = el.clientHeight, cx = W / 2, cy = H / 2, R = Math.min(W, H) / 2;
    var pt = function (rp, deg) {   // deg = 自 12 点顺时针
      var rad = (90 - deg) * Math.PI / 180;
      return [cx + rp * R * Math.cos(rad), cy - rp * R * Math.sin(rad)];
    };
    var sides = { right: [], left: [] };
    lineItems.forEach(function (it) { sides[((it.mid % 360) + 360) % 360 < 180 ? 'right' : 'left'].push(it); });
    var children = [];
    ['right', 'left'].forEach(function (side) {
      /* 同侧按弧上出口 y 排序 → 标签槽位保序;源点全在同一圆弧、末段全水平 → 斜段互不相交 */
      var arr = sides[side].slice(0, 8);
      arr.forEach(function (it) { it.iy = pt(rOuterPct, it.mid)[1]; });
      arr.sort(function (a, b) { return a.iy - b.iy; });
      /* 均匀散开(2026-07-17 修"挤在一起"):以出口点质心为中心、按 18px 等距分配槽位,
         而不是只向下推挤 —— 一束密集小块的标签会对称展开,单条时仍贴出口点 */
      var GAP = 18;
      if (arr.length > 1) {
        var centroid = arr.reduce(function (t, it) { return t + it.iy; }, 0) / arr.length;
        var start = centroid - (arr.length - 1) * GAP / 2;
        start = Math.min(Math.max(start, 12), H - 12 - (arr.length - 1) * GAP);
        arr.forEach(function (it, i) { it.slotY = start + i * GAP; });
      } else if (arr.length === 1) {
        arr[0].slotY = Math.min(Math.max(arr[0].iy, 12), H - 12);
      }
      arr.forEach(function (it) {
        var y = it.slotY;
        var p0 = pt((rMidPct + rOuterPct) / 2, it.mid), p1 = pt(rOuterPct + 0.03, it.mid);
        var lx = side === 'right' ? cx + R * (rOuterPct + 0.11) : cx - R * (rOuterPct + 0.11);
        var elbowX = side === 'right' ? lx - 12 : lx + 12;
        children.push({ type: 'polyline', silent: true, shape: { points: [p0, p1, [elbowX, y], [side === 'right' ? lx - 4 : lx + 4, y]] },
          style: { stroke: '#8a8172', fill: 'none', lineWidth: 1, opacity: 0.8 } });
        children.push({ type: 'rect', silent: true, shape: { x: side === 'right' ? lx : lx - 7, y: y - 3.5, width: 7, height: 7 }, style: { fill: it.color } });
        children.push({ type: 'text', silent: true, x: side === 'right' ? lx + 11 : lx - 11, y: y,
          style: { text: it.name + ' ' + it.pct.toFixed(1) + '%', fill: '#6b6353', font: '10px sans-serif',
                   align: side === 'right' ? 'left' : 'right', verticalAlign: 'middle' } });
      });
    });
    chart.setOption({ graphic: { elements: [{ id: 'leaders', type: 'group', $action: 'replace', children: children }] } });
  }

  /* ---------- 组件 B · 切片排行 ---------- */
  function renderRanking() {
    var spec = { rows: [state.sunDims[1]], cols: [], measures: ['value', 'share'], filters: filtersObj() };
    return query(spec).then(function (resp) {
      var r = resp.result;
      var barColor = colorMapFor(r.rowKeys.map(function (rk) { return rk[0]; }), 1);  // 排行=外环维度 → 外环色系,与旭日外环同色
      var html = '<div class="eyebrow mb-1">当前范围 · 按 ' + esc(DIM_LABEL[state.sunDims[1]]) + '</div>';
      r.rowKeys.forEach(function (rk, i) {
        var t = r.rowTotals[i];
        var pct = t[1] === null ? 0 : Number(t[1]);
        html += '<div><div class="flex justify-between text-sm mb-1"><span>' + esc(rk[0]) + '</span>' +
          '<span class="font-mono tnum" data-priv>' + fmtMoney(t[0]) + ' · ' + pct.toFixed(1) + '%</span></div>' +
          '<div style="height:18px;background:var(--card-soft);position:relative;overflow:hidden">' +
          '<span style="position:absolute;left:0;top:0;height:100%;width:' + Math.min(pct, 100) + '%;background:' + barColor[rk[0]] + '"></span></div></div>';
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
      var mis = state.measures.map(function (k) { return ALL_MEASURES.indexOf(k); });   // 选中指标索引(≥1)
      var mi = mis[0];                                                                   // 热力/排序基准 = 第一个指标
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
      /* 2 维行/列时引擎输出的 key 序第一维可能不连续(未分类沉底等排序规则)→ rowspan/colspan 合并会断开、
         同一父值出现两组。按"第一维首现顺序"分组稳定重排(组内保序),totals 随索引映射。 */
      var groupStable = function (keys) {
        if (!keys.length || keys[0].length < 2) return keys.map(function (_, i) { return i; });
        var groups = {}, order = [];
        keys.forEach(function (k, i) {
          if (!groups[k[0]]) { groups[k[0]] = []; order.push(k[0]); }
          groups[k[0]].push(i);
        });
        var idx = [];
        order.forEach(function (g) { groups[g].forEach(function (i) { idx.push(i); }); });
        return idx;
      };
      var rIdx = groupStable(r.rowKeys), cIdx = groupStable(r.colKeys);
      var rowKeys = rIdx.map(function (i) { return r.rowKeys[i]; });
      var colKeys = cIdx.map(function (i) { return r.colKeys[i]; });
      var rowTotals = rIdx.map(function (i) { return r.rowTotals[i]; });
      var colTotals = cIdx.map(function (i) { return r.colTotals[i]; });
      var moneyLike = state.measures.some(function (k) { return k !== 'share' && k !== 'cumReturn'; });   // 含金额类指标才需要隐私模糊
      /* 多指标单元格:每个选中指标一行(第一行=热力基准) */
      var cellHtml = function (values) {
        return mis.map(function (m, j) {
          var key = state.measures[j];
          return '<div' + (j > 0 ? ' class="text-[11px] text-ink-subtle"' : '') + '>' + fmtVal(key, values[m]) + '</div>';
        }).join('');
      };
      var showTotalCol = cols.length > 0;
      var rowDims = rows.length || 1, colDims = cols.length;
      /* Excel 式多级表头:列 2 维时两行列头(第一级 colspan 合并);行 2 维时两列行头(第一级 rowspan 合并) */
      var html = '<table class="lens-pivot">';
      if (colDims === 2) {
        html += '<tr><th class="sticky-col" colspan="' + rowDims + '" rowspan="2">' +
          esc(rows.map(function (k) { return DIM_LABEL[k]; }).join(' / ')) +
          ' \\ ' + esc(cols.map(function (k) { return DIM_LABEL[k]; }).join(' / ')) + '</th>';
        var runs = [];
        colKeys.forEach(function (ck) {
          if (runs.length && runs[runs.length - 1].v === ck[0]) runs[runs.length - 1].n++;
          else runs.push({ v: ck[0], n: 1 });
        });
        runs.forEach(function (g) { html += '<th colspan="' + g.n + '">' + esc(g.v) + '</th>'; });
        if (showTotalCol) html += '<th rowspan="2">小计</th>';
        html += '</tr><tr>';
        colKeys.forEach(function (ck) { html += '<th>' + esc(ck[1]) + '</th>'; });
        html += '</tr>';
      } else {
        html += '<tr><th class="sticky-col" colspan="' + rowDims + '">' +
          esc(rows.map(function (k) { return DIM_LABEL[k]; }).join(' / ') || '—') +
          (cols.length ? ' \\ ' + esc(cols.map(function (k) { return DIM_LABEL[k]; }).join(' / ')) : '') + '</th>';
        colKeys.forEach(function (ck) { html += '<th>' + esc(ck.join(' · ') || '合计') + '</th>'; });
        if (showTotalCol) html += '<th>小计</th>';
        html += '</tr>';
      }
      /* 行体:行 2 维时第一级 rowspan 合并 */
      var rowSpan = {};
      if (rows.length === 2) {
        rowKeys.forEach(function (rk, ri) {
          if (ri > 0 && rowKeys[ri - 1][0] === rk[0]) return;
          var n = 0;
          for (var j = ri; j < rowKeys.length && rowKeys[j][0] === rk[0]; j++) n++;
          rowSpan[ri] = n;
        });
      }
      rowKeys.forEach(function (rk, ri) {
        html += '<tr>';
        if (rows.length === 2) {
          if (rowSpan[ri]) html += '<td class="sticky-col rowhead" rowspan="' + rowSpan[ri] + '"><b>' + esc(rk[0]) + '</b></td>';
          html += '<td class="rowhead">' + esc(rk[1]) + '</td>';
        } else {
          html += '<td class="sticky-col rowhead">' + esc(rk.join(' · ') || '合计') + '</td>';
        }
        colKeys.forEach(function (ck) {
          var idx = cellMap[rk.join('|') + '×' + ck.join('|')];
          if (idx === undefined) { html += '<td class="tnum">—</td>'; return; }
          var v = r.cells[idx].values[mi];
          html += '<td class="tnum lens-cell" data-cell="' + idx + '" style="' + heat(v) + ';cursor:pointer"' + (moneyLike ? ' data-priv' : '') + '>' + cellHtml(r.cells[idx].values) + '</td>';
        });
        if (showTotalCol) html += '<td class="tnum"' + (moneyLike ? ' data-priv' : '') + '><b>' + cellHtml(rowTotals[ri]) + '</b></td>';
        html += '</tr>';
      });
      html += '<tr><td class="sticky-col rowhead" colspan="' + rowDims + '"><b>合计</b></td>';
      colKeys.forEach(function (ck, ci) { html += '<td class="tnum"' + (moneyLike ? ' data-priv' : '') + '><b>' + cellHtml(colTotals[ci]) + '</b></td>'; });
      if (showTotalCol) html += '<td class="tnum"' + (moneyLike ? ' data-priv' : '') + '><b>' + cellHtml(r.grand) + '</b></td>';
      html += '</tr></table>';
      if (r.holdingLevelSplit && (state.measures.indexOf('latestPnl') >= 0 || state.measures.indexOf('cumReturn') >= 0)) {
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
    el.innerHTML = MEASURES.map(function (m) {
      var on = state.measures.indexOf(m.key) >= 0;
      return '<button type="button" data-m="' + m.key + '" class="pill text-[10px]' + (on ? ' pill-ink-active' : '') + '">' + esc(m.label) + '</button>';
    }).join('');
    el.querySelectorAll('[data-m]').forEach(function (btn) {
      btn.onclick = function () {
        var k = btn.dataset.m, i = state.measures.indexOf(k);
        if (i >= 0) { if (state.measures.length > 1) state.measures.splice(i, 1); }   // 至少留 1 个
        else state.measures.push(k);
        renderMeasurePills(); renderPivot();
      };
    });
  }

  /* ---------- 刷新(三组件并发 · 同一 drill-path) ---------- */
  function refresh() {
    renderCrumbs();
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
  style.textContent = '.lens-pivot{border-collapse:collapse;width:100%;font-size:13px}' +
    /* 宽度自适配(2026-07-17 #1):数值列 min-width 保证数据区不被行头挤瘪,表格恒铺满容器 */
    '.lens-pivot th,.lens-pivot td{border:1px solid var(--rule);padding:9px 12px;text-align:right;font-family:"JetBrains Mono",monospace;white-space:nowrap}' +
    '.lens-pivot td.tnum{min-width:92px}' +
    '.lens-pivot .rowhead{min-width:96px}' +
    '.lens-pivot th{background:var(--card-soft);font-family:"Noto Serif SC",serif;font-size:12px}' +
    '.lens-pivot .rowhead{text-align:left;background:var(--card-soft);font-family:"Noto Serif SC",serif}' +
    '.lens-pivot .sticky-col{position:sticky;left:0;z-index:1}' +
    '.pill-ink-active{background:var(--ink);color:var(--paper);border-color:var(--ink)}';
  document.head.appendChild(style);

  /* 启动 · 懒加载(性能 B):透视区在仪表盘底部,滚到附近才初始化(ECharts + 3 查询),
     首屏不被透视拖累;锚点直达 #lens-section 会立刻进入视口 → IO 立即触发,天然覆盖;
     无 IntersectionObserver 的老浏览器降级为立即启动。 */
  function boot() {
    renderBoards(); applyBoard(PRESETS[0], PRESETS[0].key);
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
