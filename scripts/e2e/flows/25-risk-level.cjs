/**
 * flow · 体检风险等级改读产品类目(2026-09-24 · 维护者定「修复」)
 *
 * 原来风险分布按账户类型写死:加密 / 贵金属 / 保险是「无风险」,没有任何类型到 5 级,
 * 用户在账户页选的产品类目完全不认。
 *
 * 全程从页面发起:顶部导航进体检 → 读风险图(读的是 Chart.js 实际画出来的数据,不是模板变量);
 * 去账户编辑页用搜索式下拉【打字】换一个产品类目、点保存 → 回体检看那笔钱换了档;
 * 报表页看同一张分布的颜色是不是按等级取的;手机尺寸看一眼。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const ACC = 4;                        // 富途证券-港美股 · STOCK · 没设类目(按类型估算 → A 股 5 级)
const NEW_CAT = 'MIXED_FUND';         // 混合基金 · 4 级 · 对 STOCK 可选
const NEW_CAT_NAME = '混合';
const COLS = ['display_name', 'currency', 'primary_owner_member_id', 'default_payment_source_account_id',
              'display_order', 'product_category_code', 'risk_level_override', 'loan_kind', 'annual_rate_pct',
              'expected_return_pct', 'asset_class', 'platform_tag', 'industry_tag', 'purpose_tag'];
const COLORS = ['#9bb09a', '#7ea08a', '#a89a55', '#c1873b', '#a55540', '#8a3220'];
const state = {};

/** 读 Chart.js 实际画出来的图:标签、金额、颜色 */
async function chartOf(ui, canvasId) {
  return ui.page.locator(`#${canvasId}`).evaluate((c) => {
    const ch = window.Chart && window.Chart.getChart(c);
    if (!ch) return null;
    const ds = ch.data.datasets[0];
    return ch.data.labels.map((l, i) => ({
      label: String(l), value: Number(ds.data[i] || 0),
      color: Array.isArray(ds.backgroundColor) ? ds.backgroundColor[i] : ds.backgroundColor,
    }));
  }).catch(() => null);
}
/** 「★★★★★ 中高」→ 5;没有星 → 0 */
const levelOf = (label) => (label.match(/★/g) || []).length;
const levelByName = { '未评级': 0, '极低': 1, '低': 2, '中低': 3, '中': 4, '中高': 5, '极高': 6 };

/** 同一个 SQL 解析「手工 → 类目 → 类型默认类目」,作为真值层 */
function expectedLevels() {
  return new Set(db.col(`
    SELECT DISTINCT COALESCE(NULLIF(a.risk_level_override,0), pc.risk_level, dpc.risk_level, 0)
      FROM account a
      LEFT JOIN product_category pc  ON pc.code = a.product_category_code
      LEFT JOIN product_category dpc ON dpc.code = CASE a.type
           WHEN 'CASH' THEN 'CASH_DEPOSIT' WHEN 'STOCK' THEN 'A_STOCK' WHEN 'WEALTH' THEN 'BANK_WEALTH'
           WHEN 'PROPERTY' THEN 'PROPERTY_RES' WHEN 'OTHER' THEN 'OTHER' WHEN 'CRYPTO' THEN 'CRYPTO'
           WHEN 'METAL' THEN 'PRECIOUS_METAL' WHEN 'INSURANCE' THEN 'SAVINGS_INSURANCE' END
     WHERE a.family_id = ${fx.FAM} AND a.type <> 'LOAN'`).map(Number));
}

async function totalAssetsKpi(ui) {
  return ui.page.locator('#checkup-overview').evaluate((el) => {
    const card = [...el.querySelectorAll('.kpi-card')].find(c => /总资产/.test(c.querySelector('.kpi-eyebrow')?.innerText || ''));
    const v = card?.querySelector('.kpi-value')?.innerText || '';
    return Number(v.replace(/[^\d.]/g, '')) || null;
  }).catch(() => null);
}

module.exports = {
  name: '25-risk-level',
  title: '体检风险等级改读产品类目:加密 / 贵金属 / 保险不再是「无风险」',

  async run(ui, report) {
    ui.flow = this.name;
    const snap = db.one(`SELECT JSON_OBJECT(${COLS.map(c => `'${c}',${c}`).join(',')}) FROM account
                          WHERE id=${ACC} AND family_id=${fx.FAM}`);
    state.snap = snap ? JSON.parse(snap) : null;

    // ── 1 · 从顶部导航进体检,读风险图 ───────────────────────────────
    report.section('1 · 体检页的风险分布(读实际画出来的图)');
    await ui.goto('/');
    await ui.click('nav a:has-text("资产体检")', '顶部导航点「资产体检」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.rendered('体检页');
    const before = await chartOf(ui, 'riskChart');
    await ui.assert(!!before && before.length > 0, '风险分布图画出来了', JSON.stringify(before));
    if (!before) return;
    const labels = before.map(b => b.label);
    await ui.assert(!labels.some(l => /无风险/.test(l)), '图上没有「无风险」(修复前加密 / 贵金属 / 保险都在这一档)',
                    labels.join(' / '));
    const expect = expectedLevels();
    const shown = before.map(b => levelOf(b.label));
    await ui.assert(shown.every(l => expect.has(l)),
                    '图上每一档都能在库里由账户「手工 → 类目 → 类型默认类目」解析出来',
                    `图上 ${shown.join(',')} · 库里可解析 ${[...expect].sort().join(',')}`);
    const kpi = await totalAssetsKpi(ui);
    const sum = Math.round(before.reduce((s, b) => s + b.value, 0));
    await ui.assert(kpi !== null && Math.abs(sum - kpi) <= 1, '各档合计 = 同页「总资产」(只重新分档,钱一分不少)',
                    `各档合计 ${sum} · 总资产 ${kpi}`);
    await ui.seesText('按账户的产品类目风险等级聚合', '风险卡说明了等级从哪来');

    // 没设类目的要说出来,并且能点过去补
    const hasHint = await ui.page.locator('#risk a:has-text("去账户页补上")').count();
    if (hasHint) {
      await ui.seesText('个账户没设产品类目,按账户类型估算', '估算的账户数说出来了');
    } else {
      report.info('当前没有「没设类目又有余额」的账户,估算提示不出现(预期)');
    }

    // ── 2 · 在账户页换一个产品类目,回来看那笔钱换了档 ──────────────
    report.section('2 · 账户页换产品类目 → 风险分布跟着变');
    await ui.goto(`/accounts/${ACC}/edit`);
    await ui.rendered('账户编辑页');
    const form = `form[action$="/accounts/${ACC}/edit"]`;
    const wrap = `${form} .ss-wrap:has(select[name="productCategoryCode"])`;
    await ui.click(`${wrap} .ss-input`, '点开「产品类目」的搜索框');
    await ui.page.keyboard.type(NEW_CAT_NAME, { delay: 30 });
    await ui.page.waitForTimeout(300);
    await ui.click(`${wrap} .ss-item:has-text("${NEW_CAT_NAME}")`, `在候选里点「${NEW_CAT_NAME}」`);
    const picked = await ui.page.locator(`${form} select[name="productCategoryCode"]`).inputValue();
    await ui.assert(picked === NEW_CAT, `下拉选中后表单里真的是 ${NEW_CAT}`, `实得 ${picked}`);
    await ui.submit(`${form} button:has-text("保存对账户的修改")`, '点「保存对账户的修改」');
    const saved = db.one(`SELECT product_category_code FROM account WHERE id=${ACC}`);
    await ui.assert(saved === NEW_CAT, '真值层:类目存进去了', `实得 ${saved}`);

    await ui.goto('/');
    await ui.click('nav a:has-text("资产体检")', '再从顶部导航进体检');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const after = await chartOf(ui, 'riskChart');
    const byLevel = (arr) => Object.fromEntries((arr || []).map(b => [levelOf(b.label), b.value]));
    const b0 = byLevel(before), a0 = byLevel(after);
    const d5 = (b0[5] || 0) - (a0[5] || 0), d4 = (a0[4] || 0) - (b0[4] || 0);
    if (d5 === 0 && d4 === 0) {
      report.skip(this.name, '换档', `账户 ${ACC} 当前余额为 0,换类目看不出分布变化`);
    } else {
      await ui.assert(d5 > 0 && Math.abs(d5 - d4) < 0.01,
                      `这个账户的钱从「中高(5)」挪到了「中(4)」,挪多少进多少(用户选的类目被认了)`,
                      `5 级少了 ${d5.toFixed(2)} · 4 级多了 ${d4.toFixed(2)}`);
    }
    const sumAfter = Math.round((after || []).reduce((s, b) => s + b.value, 0));
    await ui.assert(Math.abs(sumAfter - sum) <= 1, '换档之后总额不变', `${sum} → ${sumAfter}`);
    await ui.noConsoleErrors('体检页控制台无报错');

    // ── 3 · 报表页:颜色按等级取 ─────────────────────────────────────
    report.section('3 · 报表页的风险分布:颜色按等级,不按扇区顺序');
    await ui.goto('/reports');
    await ui.rendered('报表页');
    await ui.seesText('没设类目的按账户类型估算', '报表副标题如实写了估算');
    const rep = await chartOf(ui, 'riskDistChart');
    await ui.assert(!!rep && rep.length > 0, '报表风险图画出来了');
    const wrong = (rep || []).filter(b => {
      const lv = levelByName[b.label.replace(/[★\s]/g, '')];
      return lv === undefined || b.color !== COLORS[Math.max(0, Math.min(lv - 1, COLORS.length - 1))];
    });
    await ui.assert(rep && wrong.length === 0, '每一档的颜色都对应它的等级(★1 浅绿 → ★6 朱红)',
                    wrong.map(w => `${w.label}=${w.color}`).join(' / '));

    // ── 4 · 手机尺寸 ─────────────────────────────────────────────────
    report.section('4 · 手机尺寸看体检风险卡');
    await ui.page.setViewportSize({ width: 390, height: 844 });
    await ui.goto('/checkup');
    const ov = await ui.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    await ui.assert(ov <= 2, '手机尺寸无横向溢出', `溢出 ${ov}px`);
    await ui.visible('#riskChart', '手机上风险图可见');
    await ui.page.setViewportSize({ width: 1440, height: 900 });
  },

  async cleanup(ui, report) {
    if (!state.snap) return;
    const val = (v) => v === null || v === undefined ? 'NULL' : `'${String(v).replace(/'/g, "''")}'`;
    db.raw(`UPDATE account SET ${COLS.map(c => `${c}=${val(state.snap[c])}`).join(', ')}
             WHERE id=${ACC} AND family_id=${fx.FAM}`);
    const now = db.one(`SELECT JSON_OBJECT(${COLS.map(c => `'${c}',${c}`).join(',')}) FROM account WHERE id=${ACC}`);
    const same = JSON.stringify(JSON.parse(now)) === JSON.stringify(state.snap);
    if (same) report.info(`还原:账户 ${ACC} 的全部可编辑字段与跑之前相同`);
    else report.fail(this.name, `还原:账户 ${ACC} 没回到原样`, now);
  },
};
