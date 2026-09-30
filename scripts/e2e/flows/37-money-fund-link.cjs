/**
 * flow · v1.28.2 · 仪表盘「应急金充足 + 超额闲置」提示条上的链接说人话,并直达「货币基金」那一行
 *
 * 维护者 2026-09-30:「→ MONEY_FUND 参考」看不懂;点过去是一整张产品类目表,以为跳错了页。
 * 现在写「→ 货币基金的风险与参考收益」,点过去产品类目页滚到「货币基金」那一行并高亮。
 *
 * 前置:提示条只在「流动资产 > 应急金需求 × 1.5」时出现。beta 数据不一定够,
 *   跑之前把家庭配置「应急金备几个月」临时调成 1(cleanup 还原)。动作全部从页面发起。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const KEY = 'emergency_fund_months';
const state = {};

async function check(ui, report, label) {
  await ui.goto('/');
  await ui.rendered(`${label} · 首页`);
  const banner = 'section:has(> div > span:text("应急金充足 + 超额闲置"))';
  await ui.visible(banner, `${label}:首页出现「应急金充足 + 超额闲置」提示条`);
  const txt = (await ui.page.locator(banner).first().innerText().catch(() => '')).replace(/\s+/g, ' ');
  await ui.assert(txt.includes('货币基金的风险与参考收益') && !/MONEY_FUND|LIQUID/.test(txt),
    `${label}:提示条里没有 MONEY_FUND / LIQUID 这种代码,链接写「货币基金的风险与参考收益」`, txt.slice(0, 160));
  await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                     ui.click(`${banner} a[data-money-fund-link]`, `${label}:点「→ 货币基金的风险与参考收益」`)]);
  await ui.page.waitForTimeout(800);
  await ui.assert(/\/admin\/product-categories#cat-MONEY_FUND$/.test(ui.page.url()), `${label}:到了产品类目页,定位到货币基金`, ui.page.url());
  const row = await ui.page.evaluate(() => {
    const tr = document.getElementById('cat-MONEY_FUND');
    if (!tr) return null;
    const r = tr.getBoundingClientRect();
    const bg = getComputedStyle(tr.querySelector('td')).backgroundColor;
    const other = document.querySelector('tr.pc-row:not(#cat-MONEY_FUND) td');
    return { top: Math.round(r.top), bottom: Math.round(r.bottom), vh: innerHeight,
             name: tr.innerText.replace(/\s+/g, ' ').slice(0, 40), bg, otherBg: other ? getComputedStyle(other).backgroundColor : null };
  });
  await ui.assert(!!row && row.top >= 60 && row.bottom <= row.vh, `${label}:「货币基金」那一行就在眼前(没被顶栏盖住)`, JSON.stringify(row));
  await ui.assert(!!row && row.bg !== row.otherBg, `${label}:这一行高亮了,和别的行不一样`, row ? `${row.bg} vs ${row.otherBg}` : '');
  // 表格还是表格:这一行的 8 格在同一条水平线上(v1.28.2 开发时行 class 撞上填报页类目宫格的 .cat-row,
  //   整张表被排成格子,上面几条断言照样全绿 —— 只有这条抓得到)
  await ui.alignedTops('#cat-MONEY_FUND > td', `${label}:这一行的各格排在同一行(表格没被排成格子)`);
}

module.exports = {
  name: '37-money-fund-link',
  title: 'v1.28.2 · 「超额闲置」提示条的链接说人话 · 直达并高亮产品类目里的货币基金',

  async run(ui, report) {
    ui.flow = this.name;
    state.prev = db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${KEY}'`);
    db.raw(`INSERT INTO family_runtime_config(family_id, key_name, value_text) VALUES (${fx.FAM}, '${KEY}', '1')
            ON DUPLICATE KEY UPDATE value_text='1'`);
    report.info(`前置:「应急金备几个月」临时调成 1(原值 ${state.prev ?? '未设置'})· 配置缓存 5 秒`);
    await ui.page.waitForTimeout(5500);

    report.section('1 · 电脑');
    await check(ui, report, '电脑');

    report.section('2 · 手机(390px)');
    await ui.page.setViewportSize({ width: 390, height: 844 });
    await check(ui, report, '手机');
    await ui.page.setViewportSize({ width: 1440, height: 900 });
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    await ui.page.setViewportSize({ width: 1440, height: 900 }).catch(() => {});
    if (state.prev == null) db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${KEY}'`);
    else db.raw(`UPDATE family_runtime_config SET value_text='${state.prev}' WHERE family_id=${fx.FAM} AND key_name='${KEY}'`);
    report.info(`还原:「应急金备几个月」= ${state.prev ?? '未设置'}`);
  },
};
