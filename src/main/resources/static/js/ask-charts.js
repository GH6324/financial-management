/*
 * v1.19 · 超级 Agent · 图表与自由 HTML 的渲染
 *
 * 契约:服务端(AskCitationRenderer)与流式渲染(ask.js)都只吐一个容器 —
 *   图表     <div class="ask-chart" data-ask-chart='{...}'>
 *   自由 HTML <figure class="ask-artifact"><iframe sandbox="allow-scripts" srcdoc="…">
 * 真正画图在这里。两条路径共用同一个容器契约,所以「流完刷新一下样子会变」不会发生。
 *
 * 数字从哪来:图上的每个点都来自工具返回的引用值(服务端已解析并写进 data-ask-chart)。
 * 模型只决定「画成什么形状」,不决定数值 —— 这是这一版不能破的那条线。
 */
(function () {
  'use strict';

  /* 与全站图表同一套莫兰迪色系(style.css 的 --brass / --forest / --rust 同族),
     不另起一套配色:同一个应用里两套色板会让人以为是两个东西。 */
  var PALETTE = ['#b08d4f', '#4a6b52', '#8c6f5a', '#6b7f8f', '#a4552f',
                 '#7d7462', '#9a8fa8', '#5e7a6f', '#c0a06a', '#8a6b6b'];
  var INK = '#1a1714', INK_SOFT = '#6b6157', RULE = '#ddd5c8';

  function money(p) { return p.text != null ? p.text : p.value; }

  /** 饼 / 环:占比类问题最常用的一种 */
  function pieCfg(spec, doughnut) {
    return {
      type: 'doughnut',
      data: {
        labels: spec.points.map(function (p) { return p.label; }),
        datasets: [{
          data: spec.points.map(function (p) { return p.value; }),
          backgroundColor: spec.points.map(function (_, i) { return PALETTE[i % PALETTE.length]; }),
          borderColor: '#f7f4ee', borderWidth: 2
        }]
      },
      options: {
        cutout: doughnut ? '58%' : 0,
        plugins: {
          legend: { position: 'right', labels: { color: INK_SOFT, boxWidth: 10, font: { size: 11 } } },
          /* 数字直接浮在扇片上 —— hover tooltip 不算(项目既有约定) */
          datalabels: {
            color: '#fff', font: { size: 10, weight: '600' },
            formatter: function (v, ctx) {
              var total = ctx.dataset.data.reduce(function (a, b) { return a + b; }, 0);
              var pct = total ? (v / total * 100) : 0;
              return pct < 6 ? '' : pct.toFixed(0) + '%';     // 太窄的片不写字,写了也看不清
            }
          }
        }
      }
    };
  }

  function barCfg(spec, horizontal) {
    return {
      type: 'bar',
      data: {
        labels: spec.points.map(function (p) { return p.label; }),
        datasets: [{
          data: spec.points.map(function (p) { return p.value; }),
          backgroundColor: spec.points.map(function (p, i) {
            return p.value < 0 ? '#a4552f' : PALETTE[i % PALETTE.length];
          }),
          borderRadius: 2, maxBarThickness: 34
        }]
      },
      options: {
        indexAxis: horizontal ? 'y' : 'x',
        plugins: {
          legend: { display: false },
          datalabels: {
            color: INK_SOFT, font: { size: 10 },
            anchor: 'end', align: horizontal ? 'right' : 'top', offset: 2,
            formatter: function (v, ctx) { return money(spec.points[ctx.dataIndex]); }
          }
        },
        scales: {
          x: { grid: { display: !horizontal, color: RULE }, ticks: { color: INK_SOFT, font: { size: 10 } } },
          y: { grid: { display: horizontal, color: RULE }, ticks: { color: INK_SOFT, font: { size: 10 } } }
        }
      }
    };
  }

  function lineCfg(spec) {
    return {
      type: 'line',
      data: {
        labels: spec.points.map(function (p) { return p.label; }),
        datasets: [{
          data: spec.points.map(function (p) { return p.value; }),
          borderColor: '#b08d4f', backgroundColor: 'rgba(176,141,79,.12)',
          fill: true, tension: .28, pointRadius: 3, pointBackgroundColor: '#b08d4f'
        }]
      },
      options: {
        plugins: {
          legend: { display: false },
          datalabels: {
            color: INK_SOFT, font: { size: 10 }, align: 'top', offset: 4,
            formatter: function (v, ctx) { return money(spec.points[ctx.dataIndex]); }
          }
        },
        scales: {
          x: { grid: { display: false }, ticks: { color: INK_SOFT, font: { size: 10 } } },
          y: { grid: { color: RULE }, ticks: { color: INK_SOFT, font: { size: 10 } } }
        }
      }
    };
  }

  /**
   * 瀑布:Chart.js 没有原生瀑布,用「浮动条」([from,to] 区间)拼出来。
   * points 里 kind=total 的那几根从 0 起(小计/合计),其余按前一根的终点接上。
   */
  function waterfallCfg(spec) {
    var run = 0, bars = [], colors = [];
    spec.points.forEach(function (p) {
      if (p.kind === 'total') {
        bars.push([0, p.value]); colors.push(INK); run = p.value;
      } else {
        bars.push([run, run + p.value]);
        colors.push(p.value < 0 ? '#a4552f' : '#4a6b52');
        run += p.value;
      }
    });
    return {
      type: 'bar',
      data: {
        labels: spec.points.map(function (p) { return p.label; }),
        datasets: [{ data: bars, backgroundColor: colors, borderRadius: 2, maxBarThickness: 40 }]
      },
      options: {
        plugins: {
          legend: { display: false },
          datalabels: {
            color: INK_SOFT, font: { size: 10 }, anchor: 'end', align: 'top', offset: 2,
            formatter: function (v, ctx) { return money(spec.points[ctx.dataIndex]); }
          }
        },
        scales: {
          x: { grid: { display: false }, ticks: { color: INK_SOFT, font: { size: 10 } } },
          y: { grid: { color: RULE }, ticks: { color: INK_SOFT, font: { size: 10 } } }
        }
      }
    };
  }

  function build(spec) {
    switch (spec.type) {
      case 'bar':       return barCfg(spec, false);
      case 'hbar':      return barCfg(spec, true);
      case 'line':      return lineCfg(spec);
      case 'waterfall': return waterfallCfg(spec);
      case 'pie':       return pieCfg(spec, false);
      default:          return pieCfg(spec, true);     // doughnut 兜底
    }
  }

  function render(host) {
    if (host.dataset.askChartDone) return;
    host.dataset.askChartDone = '1';
    var spec;
    try { spec = JSON.parse(host.dataset.askChart); } catch (e) { return; }
    if (!spec || !spec.points || !spec.points.length || !window.Chart) return;

    if (spec.title) host.appendChild(el('div', 'ask-chart-title', spec.title));
    var wrap = el('div', 'ask-chart-canvas');
    var cv = document.createElement('canvas');
    wrap.appendChild(cv);
    host.appendChild(wrap);

    var cfg = build(spec);
    cfg.options = cfg.options || {};
    cfg.options.responsive = true;
    cfg.options.maintainAspectRatio = false;
    cfg.options.animation = window.matchMedia
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches ? false : { duration: 420 };
    if (window.ChartDataLabels) cfg.plugins = [window.ChartDataLabels];
    try {
      new Chart(cv.getContext('2d'), cfg);
    } catch (e) {
      host.textContent = '';                          // 画不出来就当没有,别留个空白框
    }
  }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  /* ── 自由 HTML 的脚手架(唯一一份)──
     服务端渲染历史消息和流式渲染都只吐一个 <figure data-ask-artifact="…">,
     iframe 在这里组装。两边各拼一次 srcdoc 的话,注进去的样式与脚本迟早漂移,
     表现就是「流完刷新一下,图变了个样」。

     隔离:sandbox 且**不给 allow-same-origin** → opaque origin。
     脚本能跑,但读不到我们的 cookie / DOM / localStorage,也发不出带凭据的请求。
     这与 Claude Artifacts 是同一个手法。

     注进去的东西:本地 Chart.js(自托管用户很多在墙内,外链图表库会直接白屏)
     + 一套纸感基础样式(省掉模型每次重复描述配色的 token)。 */
  var HEAD = '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
    + '<script src="/vendor/chart.umd.min.js"><\/script>'
    + '<script src="/vendor/chartjs-plugin-datalabels.min.js"><\/script>'
    + '<style>'
    + ':root{--paper:#f7f4ee;--ink:#1a1714;--ink-soft:#6b6157;--ink-subtle:#9a9086;'
    + '--rule:#ddd5c8;--brass:#b08d4f;--forest:#4a6b52;--rust:#a4552f}'
    + '*{box-sizing:border-box}'
    + 'body{margin:0;padding:10px 2px;background:transparent;color:var(--ink);'
    + 'font:14px/1.7 -apple-system,"Noto Sans SC",sans-serif}'
    + 'h1,h2,h3{font-weight:600;margin:.2em 0 .5em}h1{font-size:17px}h2{font-size:15px}h3{font-size:14px}'
    + 'table{border-collapse:collapse;width:100%;font-size:13px}'
    + 'th,td{padding:.4em .5em;border-bottom:1px solid var(--rule);text-align:left}'
    + 'th{color:var(--ink-soft);font-weight:500}'
    + 'td.num,th.num{text-align:right;font-family:"JetBrains Mono",monospace}'
    + 'canvas{max-width:100%}svg{max-width:100%;height:auto}'
    + '</style></head><body>';

  /* 尾部脚本只干一件事:把内容高度报给父页,好让 iframe 自适应 */
  var TAIL = '<script>(function(){function h(){try{parent.postMessage('
    + '{askArtifactHeight:Math.ceil(document.documentElement.scrollHeight)},"*")}catch(e){}}'
    + 'new ResizeObserver(h).observe(document.body);'
    + 'addEventListener("load",h);setTimeout(h,60);setTimeout(h,400)})()<\/script></body></html>';

  function mountArtifact(fig) {
    if (fig.dataset.askArtifactDone) return;
    fig.dataset.askArtifactDone = '1';
    var f = document.createElement('iframe');
    f.setAttribute('sandbox', 'allow-scripts');
    f.setAttribute('loading', 'lazy');
    f.setAttribute('title', '助手绘制的图');
    f.srcdoc = HEAD + (fig.dataset.askArtifact || '') + TAIL;
    fig.appendChild(f);
    var cap = document.createElement('figcaption');
    cap.textContent = '助手画的 · 数字与上面的引用块同源';
    fig.appendChild(cap);
  }

  /* iframe 自适应高度:sandbox 无 same-origin,父页读不到里面的 scrollHeight,
     只能由里面 postMessage 报上来。只认数字、只用来设高度 —— 消息内容不做任何别的事。 */
  window.addEventListener('message', function (ev) {
    var h = ev.data && ev.data.askArtifactHeight;
    if (typeof h !== 'number' || !(h > 0)) return;
    document.querySelectorAll('.ask-artifact iframe').forEach(function (f) {
      if (f.contentWindow === ev.source) {
        f.style.height = Math.min(Math.max(h + 8, 90), 720) + 'px';   // 夹住:别让它把整屏吃掉
      }
    });
  });

  /** 供 ask.js 在流式渲染后调用;也在页面加载时扫一遍历史消息 */
  window.askRenderVisuals = function (scope) {
    var root = scope || document;
    root.querySelectorAll('[data-ask-chart]').forEach(render);
    root.querySelectorAll('[data-ask-artifact]').forEach(mountArtifact);
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { window.askRenderVisuals(); });
  } else {
    window.askRenderVisuals();
  }
  document.addEventListener('htmx:afterSwap', function () { window.askRenderVisuals(); });
})();
