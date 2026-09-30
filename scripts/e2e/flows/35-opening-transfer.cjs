/**
 * flow · v1.28.1 · 新开一个空账户、从老账户转进钱 —— 转进来的钱不算「开账基线」
 *
 * 维护者 2026-09-30 在 prod 上遇到:新开一个账户(开户 0),从老账户转入一笔钱;
 * 仪表盘「本月资产收益 · 剔除收入」凭空少了同样的数 —— 整笔转入被当成「开账基线」(新纳入的存量)从收益里扣掉,
 * 而转出那边只是余额少了,两边加起来等于把自家的钱算成亏损。
 * 口径:只有新账户开户时直接校准的余额才是开账基线;从家里别的账户转进去的,是左手倒右手。
 *
 * 全程从页面发起:账户页开向导建账户 → 填报页在老账户那一行划转(搜索式下拉打字选新账户)→ 回首页看 KPI。
 * cleanup 删掉划转与新账户,并核对老账户本期余额与跑之前逐分相同。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const FROM = 2;                    // 工商银行-备用金 · CNY
const NAME = 'e2e · 转入开户';
const AMT = 50000;
const state = {};

const num = s => {
  if (s == null) return NaN;
  const t = String(s).replace(/[−–]/g, '-').replace(/[^\d.\-]/g, '');
  return t === '' || t === '-' ? NaN : Number(t);
};

/** 首页上用户看得见的三个数:本月资产收益(金额)· 开账基线(没有这一行 = 0)· 新账户那一行的累计净投入 */
async function readDash(ui) {
  await ui.goto('/');
  await ui.page.waitForSelector('.kpi-card', { timeout: 60000 }).catch(() => {});
  return ui.page.evaluate((name) => {
    const card = [...document.querySelectorAll('a.kpi-card')].find(a => /本月资产收益/.test(a.querySelector('.kpi-eyebrow')?.textContent || ''));
    const pnl = card ? card.querySelector('.kpi-delta')?.textContent.trim() : null;
    const row = [...document.querySelectorAll('div.flex')].find(d => d.firstElementChild && d.firstElementChild.textContent.trim() === '开账基线');
    const opening = row ? row.lastElementChild.textContent.trim() : null;
    const tr = [...document.querySelectorAll('tr')].find(r => r.querySelector('td.acct-sticky')?.textContent.includes(name));
    const np = tr ? tr.querySelector('td[data-mcol="net_principal"]') : null;
    return { pnl, opening, netPrincipal: np ? np.textContent.trim() : null };
  }, NAME);
}

module.exports = {
  name: '35-opening-transfer',
  title: 'v1.28.1 · 新开空账户从老账户转入:不算开账基线,本月资产收益不被凭空扣掉',

  async run(ui, report) {
    ui.flow = this.name;
    state.pid = fx.currentPeriod();
    state.maxTid = db.num(`SELECT COALESCE(MAX(id),0) FROM transfer`);
    state.fromBal = db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${FROM}`);
    state.fromTodo = db.one(`SELECT CONCAT(status,'|',IFNULL(done_at,'')) FROM snapshot_todo WHERE period_id=${state.pid} AND account_id=${FROM} LIMIT 1`);

    // ── 1 · 转账之前,首页上的读数 ─────────────────────────────────────
    report.section('1 · 转账之前:首页「本月资产收益」与「开账基线」');
    const before = await readDash(ui);
    await ui.assert(!Number.isNaN(num(before.pnl)), '读到「本月资产收益 · 剔除收入」的金额', JSON.stringify(before));

    // ── 2 · 开一个空账户(向导)────────────────────────────────────────
    report.section('2 · 账户页开向导,建一个空账户(不填余额)');
    await ui.goto('/accounts');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('main a:has-text("添加账户(开向导)")', '点「+ 添加账户(开向导)」')]);
    await ui.click('#account-wizard .tpl-card:has-text("招商银行")', '点模板「招商银行」');
    await ui.fill('#account-wizard input[name="displayName"]', NAME, '起名');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('#account-wizard button:has-text("+ 添加账户")', '点「+ 添加账户」')]);
    state.acc = db.num(`SELECT COALESCE(MAX(id),0) FROM account WHERE family_id=${fx.FAM} AND display_name='${NAME}'`);
    await ui.assert(state.acc > 0, '真值层:库里多了这个账户', `id=${state.acc}`);
    if (!state.acc) return;

    // ── 3 · 从老账户转进去 ────────────────────────────────────────────
    report.section(`3 · 填报页:从老账户划 ${AMT} 到新账户`);
    await ui.goto('/entry');
    await ui.rendered('填报页');
    const fold = `#entry-block-${FROM} details.entry-fold`;
    const isOpen = await ui.page.locator(fold).first().evaluate(d => d.open).catch(() => null);
    if (isOpen === false) await ui.click(`${fold} > summary`, '展开老账户那一行');
    const form = `#entry-block-${FROM} form[hx-post$="/entry/${FROM}/transfer"]`;
    await ui.click(`${form} .ss-input`, '点开「转给哪个账户」的搜索框');
    await ui.page.keyboard.type('转入开户', { delay: 30 });
    await ui.page.waitForTimeout(300);
    await ui.click(`${form} .ss-item:has-text("${NAME}")`, `在候选里点「${NAME}」`);
    await ui.fill(`${form} input[name="amount"]`, AMT, `金额填 ${AMT}`);
    await Promise.all([
      ui.page.waitForResponse(r => r.url().includes(`/entry/${FROM}/transfer`), { timeout: 20000 }).catch(() => null),
      ui.click(`${form} button:has-text("划转")`, '点「↔ 划转」'),
    ]);
    await ui.page.waitForTimeout(1200);
    const t = db.one(`SELECT id FROM transfer WHERE id > ${state.maxTid} AND from_account_id=${FROM} AND to_account_id=${state.acc} AND deleted_at IS NULL`);
    await ui.assert(!!t, '真值层:库里多了这笔划转');
    const newBal = db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${state.acc}`);
    await ui.assert(Number(newBal) === AMT, `真值层:新账户本期余额 = ${AMT}(全是转进来的)`, `end_balance=${newBal}`);

    // ── 4 · 回首页:收益不变、开账基线不变 ──────────────────────────────
    report.section('4 · 回首页:转进来的钱不是开账基线');
    const after = await readDash(ui);
    const dPnl = num(after.pnl) - num(before.pnl);
    await ui.assert(Math.abs(dPnl) <= 2,
      `「本月资产收益 · 剔除收入」没变(修复前会凭空少 ${AMT})`, `前 ${before.pnl} → 后 ${after.pnl}`);
    const o0 = before.opening == null ? 0 : num(before.opening);
    const o1 = after.opening == null ? 0 : num(after.opening);
    await ui.assert(Math.abs(o1 - o0) <= 2, '「开账基线」没多出这笔转入', `前 ${before.opening ?? '(无)'} → 后 ${after.opening ?? '(无)'}`);
    if (after.netPrincipal != null) {
      const np = num(after.netPrincipal);
      await ui.assert(Math.abs(np - AMT) <= 2, `新账户「累计净投入」= ${AMT}(修复前算两遍 = ${AMT * 2})`, after.netPrincipal);
    } else {
      report.skip(this.name, '新账户的累计净投入', '账户列表这一列没开(指标芯片里没勾「累计净投入」)');
    }
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    const ids = db.col(`SELECT id FROM account WHERE family_id=${fx.FAM} AND display_name='${NAME}'`);
    for (const a of ids) {
      for (const sql of [
        `DELETE FROM transfer WHERE from_account_id=${a} OR to_account_id=${a}`,
        `DELETE FROM snapshot_todo WHERE account_id=${a}`,
        `DELETE FROM period_account_attr WHERE account_id=${a}`,
        `DELETE FROM period_snapshot WHERE account_id=${a}`,
        `DELETE FROM cash_flow WHERE account_id=${a}`,
        `DELETE FROM account_group_member WHERE account_id=${a}`,
        `DELETE FROM account WHERE id=${a} AND family_id=${fx.FAM} AND display_name='${NAME}'`,
      ]) { try { db.raw(sql); } catch (e) { report.info(`还原跳过:${sql.slice(0, 60)} · ${String(e.message).slice(0, 80)}`); } }
    }
    if (state.pid) {
      const now = db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${FROM}`);
      if (now !== state.fromBal) {
        if (state.fromBal === null) db.raw(`DELETE FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${FROM}`);
        else db.raw(`UPDATE period_snapshot SET end_balance=${state.fromBal} WHERE period_id=${state.pid} AND account_id=${FROM}`);
      }
      if (state.fromTodo) {
        const [st, at] = state.fromTodo.split('|');
        db.raw(`UPDATE snapshot_todo SET status='${st}', done_at=${at ? `'${at}'` : 'NULL'} WHERE period_id=${state.pid} AND account_id=${FROM}`);
      }
      const fin = db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${FROM}`);
      if (fin === state.fromBal) report.info('还原:老账户本期余额与跑之前相同');
      else report.fail(this.name, '还原:老账户本期余额没回到原值', `${fin} ≠ ${state.fromBal}`);
    }
    const left = db.num(`SELECT COUNT(*) FROM account WHERE family_id=${fx.FAM} AND display_name='${NAME}'`);
    if (left === 0) report.info('还原:测试账户与划转已删除');
    else report.fail(this.name, '还原不完整', `还剩 ${left} 个测试账户`);
  },
};
