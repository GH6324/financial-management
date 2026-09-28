/**
 * flow · v1.26.2 · 点「+ 添加账户(开向导)」之后,向导要出现在眼前
 *
 * 向导是追加在账户列表最下面的,点按钮是整页刷新、落回页顶 —— 向导在视口以下(PC 约 2400px、手机约 5600px)。
 * 维护者 2026-09-28 在 PC 上连点了好几次,以为没反应。现在落到 /accounts/new 就把向导滚进视口。
 *
 * 三个入口都落在同一页:账户页按钮、填报页「+ 新账户」、首次引导页。这里走前两个(首次引导只在没账户时出现),
 * 再用 390px 走一遍账户页;最后真的用向导建一个账户,确认滚动没把向导本身弄坏。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const NAME = 'e2e · 向导直达';

/** 向导在不在眼前:顶边落在视口上半部分(不是「在页面某处」)*/
async function wizardInView(ui) {
  await ui.page.waitForTimeout(1500);   // 平滑滚动要一点时间
  return ui.page.evaluate(() => {
    const el = document.querySelector('#account-wizard .envelope-modal');
    if (!el) return { ok: false, why: '页面上没有向导' };
    const r = el.getBoundingClientRect();
    return { ok: r.top >= 0 && r.top < innerHeight / 2, top: Math.round(r.top), vh: innerHeight, path: location.pathname };
  });
}

module.exports = {
  name: '29-account-wizard-arrives',
  title: 'v1.26.2 · 点「添加账户」后向导出现在眼前(不是藏在页面最下面)',

  async run(ui, report) {
    ui.flow = this.name;

    report.section('1 · 账户页 · PC');
    await ui.goto('/accounts');
    await ui.rendered('账户页');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('main a:has-text("添加账户(开向导)")', '点「+ 添加账户(开向导)」')]);
    let v = await wizardInView(ui);
    await ui.assert(v.ok && v.path === '/accounts/new', '点完之后向导就在眼前(顶边在视口上半部分)', JSON.stringify(v));

    report.section('2 · 填报页「+ 新账户」');
    await ui.goto('/entry');
    await ui.rendered('填报页');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('a:has-text("+ 新账户") >> nth=0', '点「+ 新账户」')]);
    v = await wizardInView(ui);
    await ui.assert(v.ok, '从填报页过来,向导同样在眼前', JSON.stringify(v));

    report.section('3 · 真的用向导建一个账户(滚动没把向导弄坏)');
    await ui.click('#account-wizard .tpl-card:has-text("招商银行")', '点模板「招商银行」');
    await ui.visible('#tplPickedHint', '回显「已套用模板」');
    await ui.fill('#account-wizard input[name="displayName"]', NAME, '改个名字');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('#account-wizard button:has-text("+ 添加账户")', '点「+ 添加账户」')]);
    await ui.seesText(NAME, '账户列表里出现了新账户');
    const made = db.num(`SELECT COUNT(*) FROM account WHERE family_id=${fx.FAM} AND display_name='${NAME}' AND type='CASH' AND currency='CNY'`);
    await ui.assert(made === 1, '真值层:库里多了这个账户(现金 · CNY,来自模板)', `找到 ${made} 个`);

    report.section('4 · 手机(390px)');
    await ui.page.setViewportSize({ width: 390, height: 844 });
    await ui.goto('/accounts');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('main a:has-text("添加账户(开向导)")', '手机上点「+ 添加账户(开向导)」')]);
    v = await wizardInView(ui);
    await ui.assert(v.ok, '手机上向导同样在眼前', JSON.stringify(v));
    await ui.page.setViewportSize({ width: 1440, height: 900 });
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    await ui.page.setViewportSize({ width: 1440, height: 900 }).catch(() => {});
    const ids = db.col(`SELECT id FROM account WHERE family_id=${fx.FAM} AND display_name='${NAME}'`);
    for (const a of ids) {
      for (const sql of [
        `DELETE FROM snapshot_todo WHERE account_id=${a}`,
        `DELETE FROM period_account_attr WHERE account_id=${a}`,
        `DELETE FROM period_snapshot WHERE account_id=${a}`,
        `DELETE FROM cash_flow WHERE account_id=${a}`,
        `DELETE FROM account_group_member WHERE account_id=${a}`,
        `DELETE FROM account WHERE id=${a} AND family_id=${fx.FAM} AND display_name='${NAME}'`,
      ]) { try { db.raw(sql); } catch (e) { report.info(`还原跳过:${sql.slice(0, 60)} · ${String(e.message).slice(0, 80)}`); } }
    }
    const left = db.num(`SELECT COUNT(*) FROM account WHERE family_id=${fx.FAM} AND display_name='${NAME}'`);
    if (left === 0) report.info('还原:向导建的测试账户已删除');
    else report.fail(this.name, '还原不完整', `还剩 ${left} 个测试账户`);
  },
};
