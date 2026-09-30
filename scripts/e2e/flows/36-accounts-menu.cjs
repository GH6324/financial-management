/**
 * flow · v1.28.1 · issue #25 · 账户页两处 + 首页一处
 *
 * ① 账户多了以后,最下面几行的「⋯」点开没反应 —— 提交者只能用 Tab 选中。
 *    根因:表格外层是 overflow-hidden,下拉 absolute 挂在行下方,超出表格底部被裁掉(点开了,只是看不见)。
 *    这里点最后一行的 ⋯:菜单要在视口里、菜单项真的点得到(命中测试),再真的点「资产体检」走过去。
 * ② 顶上按类型的合计:各账户余额是本币(美元 / 港币),原来直接相加再标 ¥。
 *    这里拿库里的余额 × 本期汇率自己算一遍「股票」那一格,和页面上的比。
 * ③ 没登录时的首页:右上角 GitHub 角标(80×80 整块可点)在 iPhone 上压住顶栏「登录」,点登录误跳 GitHub。
 *    这里开一个没登录的会话,iPhone 15 / 平板 / PC 三种宽度对「登录」的三个点做命中测试。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const num = s => Number(String(s || '').replace(/[−–]/g, '-').replace(/[^\d.\-]/g, '') || NaN);

module.exports = {
  name: '36-accounts-menu',
  title: 'v1.28.1 · issue #25 · 账户页最下面几行的 ⋯ 点得开 · 类型合计先换成本位币再加 · 首页角标不压「登录」',

  async run(ui, report) {
    ui.flow = this.name;

    report.section('1 · 从首页点「账户」');
    await ui.goto('/');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('nav a[href="/accounts"] >> visible=true', '顶部导航点「账户」')]);
    await ui.rendered('账户页');

    // ── ① 最后一行的 ⋯ ─────────────────────────────────────────────────
    report.section('2 · 最后一行的「⋯」(issue #25 ①)');
    const menus = ui.page.locator('details.row-more > summary');
    const n = await menus.count();
    await ui.assert(n >= 5, '账户表里有一排「⋯」', `${n} 个`);
    if (n < 1) return;
    await menus.nth(n - 1).scrollIntoViewIfNeeded();
    await ui.page.waitForTimeout(300);
    await ui.click(`details.row-more >> nth=${n - 1} >> summary`, '点最后一行的「⋯」');
    const pop = ui.page.locator('details.row-more[open] .row-more-pop');
    const box = await pop.boundingBox().catch(() => null);
    const vh = await ui.page.evaluate(() => innerHeight);
    await ui.assert(!!box && box.height > 40 && box.y >= 0 && box.y + box.height <= vh,
      '菜单整个落在视口里(不再被表格底边裁掉)', JSON.stringify(box) + ` vh=${vh}`);
    // 每一项都做命中测试 —— 被裁掉的是菜单下半截(boundingBox 看不出裁剪,只有命中测试看得出)
    const hits = await ui.page.evaluate(() =>
      [...document.querySelectorAll('details.row-more[open] .row-more-pop a, details.row-more[open] .row-more-pop button')].map(a => {
        const r = a.getBoundingClientRect();
        const e = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
        return (e && (e === a || a.contains(e)) ? 'ok ' : 'BLOCKED ') + a.textContent.trim();
      }));
    await ui.assert(hits.length >= 3 && hits.every(h => h.startsWith('ok ')),
      '菜单每一项都真的点得到(命中测试不被裁掉 / 不被别的东西挡住)', hits.join(' · '));
    // 开着菜单滚一下:菜单跟着按钮走,不关(一滚就关的话,点 ⋯ 前页面恰好在滚时菜单刚开就没了)
    await ui.page.mouse.wheel(0, -40);
    await ui.page.waitForTimeout(400);
    const gap = await ui.page.evaluate(() => {
      const d = document.querySelector('details.row-more[open]');
      if (!d) return null;
      const s = d.querySelector('summary').getBoundingClientRect(), p = d.querySelector('.row-more-pop').getBoundingClientRect();
      return Math.round(Math.min(Math.abs(p.top - s.bottom), Math.abs(s.top - p.bottom)));
    });
    await ui.assert(gap !== null && gap <= 8, '开着菜单滚动一下:菜单还开着,并且贴着按钮', `间距 ${gap}px`);
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 150000 }).catch(() => {}),
                       ui.click('details.row-more[open] .row-more-pop a:has-text("资产体检")', '点菜单里的「资产体检」')]);
    await ui.assert(/\/checkup\?account=\d+/.test(ui.page.url()), '走到了这个账户的体检页', ui.page.url());

    // 打开一个再点别处:收起
    await ui.goto('/accounts');
    await ui.click('details.row-more >> nth=0 >> summary', '点第一行的「⋯」');
    await ui.click('h1, main h2 >> nth=0', '点页面别处');
    await ui.count('details.row-more[open]', 0, '点别处菜单收起');

    // ── ② 类型合计换成本位币 ───────────────────────────────────────────
    report.section('3 · 顶上「股票」那一格:先换成本位币再加(issue #25 ②)');
    const pid = fx.currentPeriod();
    // 每个在用的股票账户取账户页上显示的那个余额(进行期,没有就往前找最近一期 ——
    //   beta 预建了到 2041 的账期,「最新一期」会拿到未来期的延续值);非本位币按本期汇率换算(本位币 = 原币 ÷ rate)
    const rows = db.col(`SELECT CONCAT(a.currency,'|',
                           (SELECT s.end_balance FROM period_snapshot s JOIN period p ON p.id=s.period_id
                             WHERE s.account_id=a.id
                               AND p.period_start <= (SELECT period_start FROM period WHERE id=${pid})
                             ORDER BY p.period_start DESC LIMIT 1))
                         FROM account a WHERE a.family_id=${fx.FAM} AND a.archived_at IS NULL AND a.type='STOCK'`);
    let expect = 0, naive = 0, foreign = 0;
    for (const r of rows) {
      const [ccy, bal] = r.split('|');
      if (!bal || bal === 'NULL') continue;
      naive += Number(bal);
      if (ccy === 'CNY') { expect += Number(bal); continue; }
      foreign++;
      const rate = Number(db.one(`SELECT rate FROM fx_rate WHERE family_id=${fx.FAM} AND base_currency='CNY' AND quote_currency='${ccy}' AND period_id=${pid}`));
      expect += Number(bal) / rate;
    }
    const shown = await ui.page.evaluate(() => {
      const c = [...document.querySelectorAll('.summary-band > div')].find(d => /STOCK/.test(d.textContent));
      return c ? c.querySelector('[data-priv]').textContent.trim() : null;
    });
    if (!foreign) {
      report.skip(this.name, '股票合计换算', '这台机器上没有非人民币的股票账户,换不换算看不出差别');
    } else {
      await ui.assert(shown && shown.startsWith('¥') && Math.abs(num(shown) - expect) <= 2,
        `「股票」合计 = 各账户换成人民币后相加(≈ ¥${Math.round(expect).toLocaleString()};原来直接相加是 ${Math.round(naive).toLocaleString()})`,
        `页面 ${shown}`);
    }
    // ── ③ 落地页右上角的 GitHub 角标压住「登录」────────────────────────
    report.section('4 · 没登录时的首页:右上角 GitHub 角标不再压住「登录」(issue #25 ③)');
    const { BASE } = require('../lib/browser.cjs');
    for (const [w, h, name] of [[393, 852, 'iPhone 15'], [1024, 768, 'iPad 横屏'], [1440, 900, 'PC']]) {
      const ctx = await ui.page.context().browser().newContext({ viewport: { width: w, height: h }, isMobile: w < 500, hasTouch: w < 500 });
      try {
        const pg = await ctx.newPage();
        await pg.goto(BASE + '/', { waitUntil: 'networkidle' });
        // 启动遮罩(印章)淡出要 0.55 秒,淡出期间它还挡着整页 —— 等它真的走了再测,别把它当成角标
        await pg.waitForSelector('#page-overlay', { state: 'hidden', timeout: 10000 }).catch(() => {});
        await pg.waitForTimeout(700);
        const r = await pg.evaluate(() => {
          const a = [...document.querySelectorAll('header a[href="/login"]')].find(x => x.offsetParent);
          if (!a) return 'no login link';
          const b = a.getBoundingClientRect();
          const pts = [[b.x + b.width / 2, b.y + b.height / 2], [b.right - 3, b.y + 3], [b.right - 3, b.bottom - 3]];
          return pts.map(([x, y]) => { const e = document.elementFromPoint(x, y); return e && (e === a || a.contains(e)) ? 'ok' : 'covered:' + (e && e.closest('a') ? e.closest('a').className : e && e.tagName); }).join(' ');
        });
        await ui.assert(/^ok ok ok$/.test(r), `${name}(${w}px):「登录」整块点得到,没被角标压住`, r);
        await pg.close();
      } finally { await ctx.close(); }
    }
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup() {},
};
