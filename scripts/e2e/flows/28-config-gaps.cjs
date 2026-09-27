/**
 * flow · v1.26 · 两个「有配置、没入口」的补上(维护者 2026-09-27「要加上」)
 *
 * 护栏 v126-CONFIG-KEY-HAS-HOME 第一次运行查出:
 *   · 填报提醒「每天几点发」(report_remind_cron)—— 代码一直在读,提醒页没有入口,只能用默认 10 点 / 20 点
 *   · 再平衡「算执行了」的比例(rebalance_match_pct)—— 同上,只能用默认 80%
 *
 * 全程从管理首页点卡片进去(卡片上要点名这两项 —— 用户是带着这些词来找的),在页面上填、存,
 * 再查库:提醒时间存成 cron、核销比例存成小数(读的一边一直按小数读,存成「90」会让门槛变成 90 倍、不报错)。
 * cleanup 把两个配置键恢复原样。
 */
const fs = require('fs');
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const KEYS = ['report_remind_cron', 'rebalance_match_pct'];
const APP_LOG = '/opt/finance/logs/app.log';
const state = {};
const cfg = (k) => db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);

/** 从管理首页点某张卡片进去 */
async function viaCard(ui, href, name) {
  await ui.goto('/admin');
  const card = `main a[href="${href}"]`;
  await ui.click(`${card} >> nth=0`, `管理首页点「${name}」卡片`);
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.rendered(name);
}

module.exports = {
  name: '28-config-gaps',
  title: 'v1.26 · 提醒时间与再平衡核销比例补上页面入口',

  async run(ui, report) {
    ui.flow = this.name;
    state.before = Object.fromEntries(KEYS.map(k => [k, cfg(k)]));

    // ── 1 · 首页卡片点名 ─────────────────────────────────────────────
    report.section('1 · 管理首页卡片上看得到这两项');
    await ui.goto('/admin');
    const remindCard = await ui.page.locator('main a[href="/admin/reminders"]').first().innerText();
    const tweakCard = await ui.page.locator('main a[href="/admin/calc-tweaks"]').first().innerText();
    await ui.assert(remindCard.includes('每天几点提醒'), '「提醒」卡片写了「每天几点提醒」', remindCard);
    await ui.assert(tweakCard.includes('再平衡'), '「数值阈值」卡片写了「再平衡」', tweakCard);

    // ── 2 · 每天几点提醒 ─────────────────────────────────────────────
    report.section('2 · 提醒页:每天几点提醒');
    await viaCard(ui, '/admin/reminders', '提醒');
    await ui.visible('input[name="remindHours"]', '提醒页上有「每天几点提醒」');
    await ui.fill('input[name="remindHours"]', '25', '先填一个不存在的时间 25');
    await ui.submit('button:has-text("保存模板与提醒时间")', '保存');
    await ui.seesText('0 到 23', '说清楚要 0 到 23 点');
    await ui.assert((cfg('report_remind_cron') || null) === (state.before.report_remind_cron || null),
                    '真值层:填错时一个字都没存(没有半截保存)', cfg('report_remind_cron'));

    const logSize = fs.existsSync(APP_LOG) ? fs.statSync(APP_LOG).size : -1;
    await ui.fill('input[name="remindHours"]', '21，9', '改成「21，9」(中文逗号、顺序反着填)');
    await ui.submit('button:has-text("保存模板与提醒时间")', '保存');
    await ui.seesText('每天 9 点、21 点看一次', '回执写清楚几点');
    await ui.assert(cfg('report_remind_cron') === '0 0 9,21 * * *', '真值层:存成每天 9 点、21 点的 cron', cfg('report_remind_cron'));
    const shown = await ui.page.inputValue('input[name="remindHours"]');
    await ui.assert(shown === '9,21', '重新打开页面,这一格显示 9,21', shown);
    if (logSize >= 0) {
      await ui.page.waitForTimeout(500);
      // 只读保存之后新写的那一段。按【字节】读:日志里有中文,把整文件读成字符串再按字节位置 slice 会错位、跳过新内容
      const size = fs.statSync(APP_LOG).size;
      const buf = Buffer.alloc(Math.max(0, size - logSize));
      const fd = fs.openSync(APP_LOG, 'r');
      try { fs.readSync(fd, buf, 0, buf.length, logSize); } finally { fs.closeSync(fd); }
      const tail = buf.toString('utf8');
      await ui.assert(/report-remind scheduled · cron=0 0 9,21 \* \* \*/.test(tail),
                      '真值层:调度立刻按新时间重排(不用重启)', tail.split('\n').filter(l => l.includes('report-remind')).join(' | ').slice(0, 200));
    } else {
      report.info('本机没有 app.log(前门模式),跳过调度重排的日志核对');
    }

    // ── 3 · 再平衡「算执行了」的比例 ─────────────────────────────────
    report.section('3 · 数值阈值页:再平衡「算执行了」的比例');
    await viaCard(ui, '/admin/calc-tweaks', '数值阈值');
    await ui.visible('input[name="rebalanceMatchPct"]', '数值阈值页上有这一格');
    const def = await ui.page.inputValue('input[name="rebalanceMatchPct"]');
    report.info(`当前显示 ${def}%`);
    await ui.fill('input[name="rebalanceMatchPct"]', '90', '改成 90%');
    await ui.submit('button:has-text("保存录入阈值")', '保存录入阈值');
    await ui.seesText('录入阈值已保存', '保存回执');
    await ui.assert(cfg('rebalance_match_pct') === '0.9', '真值层:存成小数 0.9(读的一边按小数读)', cfg('rebalance_match_pct'));
    await ui.assert(await ui.page.inputValue('input[name="rebalanceMatchPct"]') === '90', '重新打开页面显示 90');
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    // 调度是内存里排好的:只改库不会让它回到原时间。所以先走页面按原来的整点再存一次(触发重排),再把库精确还原
    const before = (state.before || {}).report_remind_cron;
    const m = (before || '0 0 10,20 * * *').match(/^0 0 ([0-9,]+) \* \* \*$/);
    if (m) {
      try {
        await ui.goto('/admin/reminders');
        await ui.page.fill('input[name="remindHours"]', m[1]);
        await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                           ui.page.click('button:has-text("保存模板与提醒时间")')]);
      } catch (e) { report.info(`还原调度时间没走通:${String(e.message).slice(0, 80)}`); }
    } else {
      report.info(`原来是自定义时间表「${before}」,页面填不回去;库已还原,调度在下一次重排或重启时回到原样`);
    }
    for (const k of KEYS) {
      const v = state.before ? state.before[k] : null;
      if (v === null || v === undefined) db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);
      else db.raw(`UPDATE family_runtime_config SET value_text='${String(v).replace(/'/g, "''")}' WHERE family_id=${fx.FAM} AND key_name='${k}'`);
    }
    const left = KEYS.filter(k => (cfg(k) || null) !== ((state.before || {})[k] || null));
    if (left.length === 0) report.info('还原:提醒时间(含调度)与再平衡核销比例恢复原样');
    else report.fail(this.name, '还原不完整', left.join(','));
  },
};
