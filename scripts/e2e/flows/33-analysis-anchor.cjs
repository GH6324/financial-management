/**
 * flow · v1.27 · issue #23 · 报表配置锚:范围内没有的桶不参与、其余按比例放大 · 自定义锚真的能填
 *
 * PRD 验收 #4 #6 · FR-827 / FR-870 / FR-871 / FR-873:
 *   · 报表「当前配置 vs 模板」切到「金融资产」:房产桶不参与,标普 4321 按其余三类放大,当场写明
 *   · 下拉选「自定义」→ 没填过 → 不出对照、给「去填 →」→ 分析设置里填(合计不是 100 不让存)→ 存完回报表
 *   · 「其他」类单独一行,不进四桶
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const state = {};
// mysql -N 把 SQL NULL 打成字符串 'NULL' —— 这里还原成 null,别把它当成一个值写回去
const fam = col => { const v = db.one(`SELECT ${col} FROM family WHERE id=${fx.FAM}`); return v === 'NULL' ? null : v; };

module.exports = {
  name: '33-analysis-anchor',
  title: 'v1.27 · 报表配置锚:金融资产范围下房产不参与 + 按比例放大 · 自定义锚能填、合计必须 100',

  async run(ui, report) {
    ui.flow = this.name;
    state.anchor = fam('allocation_anchor');
    state.custom = fam('allocation_anchor_custom');
    db.raw(`UPDATE family SET allocation_anchor='SP_4321', allocation_anchor_custom=NULL WHERE id=${fx.FAM}`);

    // ── 1 · 报表 · 金融资产:房产桶不参与,其余放大 ─────────────────────
    report.section('1 · 报表配置锚对照 · 金融资产');
    await ui.goto('/');
    await ui.click('nav a:has-text("报表")', '顶部导航点「报表」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.rendered('报表页');
    await ui.visible('#allocation-diff [data-bucket="PROPERTY"]', '全部资产:房产桶参与对照');
    const hasFinancial = await ui.page.isVisible('#allocation-diff [data-alloc-scope] a[data-scope="FINANCIAL"]').catch(() => false);
    if (!hasFinancial) {
      report.skip(this.name, '金融资产范围', 'beta 上没有房产类 / 其他类账户,不会出现「金融资产」选项');
    } else {
      await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                         ui.click('#allocation-diff [data-alloc-scope] a[data-scope="FINANCIAL"]', '点「金融资产」')]);
      await ui.assert(new URL(ui.page.url()).searchParams.get('scope') === 'FINANCIAL', '记在网址上', ui.page.url());
      await ui.notVisible('#allocation-diff [data-bucket="PROPERTY"]', '房产桶不再参与对照');
      await ui.visible('#allocation-diff [data-bucket-dropped="PROPERTY"]', '房产那一行灰掉、写「范围内没有 · 不参与」');
      const note = (await ui.page.locator('#allocation-diff [data-rescale-note]').innerText().catch(() => '')).trim();
      await ui.assert(/^范围内没有房产 · 标普 4321 按其余(三|两)类放大 → 现金 \d+%/.test(note), '当场写明「按其余几类放大 → …」', note);
      const targets = await ui.page.locator('#allocation-diff [data-bucket] .tnum span:nth-child(2)')
        .evaluateAll(es => es.map(e => parseFloat(e.textContent)));
      const sum = targets.reduce((a, b) => a + b, 0);
      await ui.assert(Math.abs(sum - 100) < 0.05, '参与的桶目标合计 100', targets.join(' + ') + ' = ' + sum);
      await ui.visible('#allocation-diff [data-scope-note]', '标题旁写明「不含:」');
    }

    // ── 2 · 选「自定义」:没填过就不出对照 ───────────────────────────────
    report.section('2 · 自定义配置锚(FR-871)');
    await ui.goto('/reports');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.page.selectOption('#allocation-diff select[name=anchor]', 'CUSTOM')]);
    await ui.assert(fam('allocation_anchor') === 'CUSTOM', '真值层:家里的锚 = CUSTOM');
    await ui.visible('#allocation-diff [data-custom-unset]', '「还没填自定义目标 —— 去填 →」,不拿 0 去比');
    await ui.notVisible('#allocation-diff [data-bucket]', '没填之前一根对照条都不画');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('#allocation-diff [data-custom-unset] a', '点「去填 →」')]);
    await ui.assert(new URL(ui.page.url()).pathname === '/admin/analysis', '落在分析设置', ui.page.url());
    await ui.visible('[data-analysis-back]', '顶上有「← 回到刚才那页」');

    // 合计不是 100 不让存
    await ui.fill('[data-custom-anchor-form] input[name=cash]', '20', '现金 20');
    await ui.fill('[data-custom-anchor-form] input[name=invest]', '70', '投资 70');
    await ui.fill('[data-custom-anchor-form] input[name=property]', '0', '房产 0');
    await ui.fill('[data-custom-anchor-form] input[name=insurance]', '0', '保险 0');
    await ui.assert((await ui.page.locator('[data-pct-sum]').innerText()).includes('还差 10'), '边填边算:合计 90 · 还差 10');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('[data-custom-anchor-form] button[type=submit]', '合计 90 时点「存自定义目标」')]);
    await ui.visible('[data-flash-error]', '不让存,并说差几个点');
    await ui.assert(fam('allocation_anchor_custom') === null, '真值层:没存');

    await ui.fill('[data-custom-anchor-form] input[name=cash]', '20', '现金 20');
    await ui.fill('[data-custom-anchor-form] input[name=invest]', '80', '投资 80');
    await ui.fill('[data-custom-anchor-form] input[name=property]', '0', '房产 0');
    await ui.fill('[data-custom-anchor-form] input[name=insurance]', '0', '保险 0');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('[data-custom-anchor-form] button[type=submit]', '合计 100,点「存自定义目标」')]);
    const saved = JSON.parse(fam('allocation_anchor_custom') || '{}');
    await ui.assert(saved.cash == 20 && saved.invest == 80 && saved.property == 0 && saved.insurance == 0,
      '真值层:存了 20 / 80 / 0 / 0', JSON.stringify(saved));
    await ui.assert(new URL(ui.page.url()).pathname === '/reports', '存完回到报表', ui.page.url());
    await ui.visible('#allocation-diff [data-bucket="CASH"]', '现在出对照了');
    const cashTarget = await ui.page.locator('#allocation-diff [data-bucket="CASH"] .tnum span:nth-child(2)').innerText().catch(() => '');
    await ui.assert(parseFloat(cashTarget) === 20, '现金目标就是填的 20', cashTarget);

    // 「其他」类单独一行
    const other = db.num(`SELECT COUNT(*) FROM account WHERE family_id=${fx.FAM} AND type='OTHER' AND archived_at IS NULL`);
    if (other > 0 && await ui.page.isVisible('#allocation-diff [data-other-line]').catch(() => false)) {
      await ui.seesText('不参与四桶对照', '「其他」类单独一行:另有其他资产 · 不参与四桶对照');
    } else {
      report.info('beta 上「其他」类账户这一期没有余额,这一行不出现(由单测守)');
    }
    await ui.noConsoleErrors('报表 / 分析设置控制台无报错');
  },

  async cleanup() {
    const a = state.anchor || 'SP_4321';
    db.raw(`UPDATE family SET allocation_anchor='${a}', allocation_anchor_custom=${state.custom ? `'${state.custom}'` : 'NULL'} WHERE id=${fx.FAM}`);
    db.raw(`DELETE FROM rebalance_advice_cache WHERE family_id=${fx.FAM} AND anchor_code LIKE 'CUSTOM%'`);
  },
};
