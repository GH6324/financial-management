/**
 * flow · issue #22 · 体检「值得做的事」的「不适用」按钮
 *
 * 提交者原话:「如果有提醒,不适用按钮按下没有反应」。它从加上那天起就调用一个不存在的前端函数。
 *
 * 全程从页面发起:顶部导航进体检 → 点某张卡的「不适用」→ 看它消失、看「已隐藏 N 条」→ 刷新 →
 * 另开一个手机尺寸的会话(家里另一个人)看 → 点「全部恢复」;
 * 再从「账户」页的「资产体检」链接进账户体检页,同样点一遍,并确认只影响这个账户。
 *
 * 真值层:家庭配置 `checkup_advice_dismissed` 里确实存了「规则|账户」。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');
const { BASE } = require('../lib/browser.cjs');

const KEY = 'checkup_advice_dismissed';
const state = {};

const cfg = () => db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${KEY}'`);
const rules = (ui) => ui.page.locator('#checkup-advice article.advice-card')
  .evaluateAll(els => els.map(a => a.getAttribute('data-rule'))).catch(() => []);

/** 点某张卡上的「不适用」(表单提交,整页跳回 #checkup-advice) */
async function dismiss(ui, rule, label) {
  const btn = `#checkup-advice article.advice-card[data-rule="${rule}"] button:has-text("不适用")`;
  await Promise.all([
    ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 30000 }).catch(() => {}),
    ui.click(btn, label),
  ]);
}
/** 从「账户」页进某个账户的体检:点那一行的「⋯ 更多操作」→ 点「资产体检」(电脑上它收在这个菜单里) */
async function openAccountCheckup(ui, id) {
  await ui.goto('/accounts');
  const more = `details.row-more:has(a[href="/checkup?account=${id}"])`;
  await ui.page.locator(`${more} > summary`).first().click({ timeout: 12000 });
  await Promise.all([
    ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 30000 }).catch(() => {}),
    ui.page.locator(`${more} a[href="/checkup?account=${id}"]`).first().click({ timeout: 12000 }),
  ]);
}
async function restoreAll(ui, label) {
  await Promise.all([
    ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 30000 }).catch(() => {}),
    ui.click('#checkup-advice button:has-text("全部恢复")', label),
  ]);
}

module.exports = {
  name: '24-advice-dismiss',
  title: 'issue #22 · 「不适用」真的能用:存下来、全家一致、可恢复、按账户隔离',

  async run(ui, report) {
    ui.flow = this.name;
    // 体检页一打开就会自动请求「AI 综合诊断」「AI 资产洞察」—— 这条 flow 不测 AI,
    //   就地返回空片段:不花 token,也不让 networkidle 等十几秒的模型响应
    await ui.page.route(/\/checkup\/(diagnose|insight)/, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));
    state.before = cfg();
    db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${KEY}'`);

    // ── 1 · 家庭体检 ─────────────────────────────────────────────────
    report.section('1 · 家庭体检:点「不适用」');
    await ui.goto('/');
    await ui.click('nav a:has-text("资产体检")', '顶部导航点「资产体检」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.rendered('体检页');
    const fam = await rules(ui);
    if (!fam.length) {
      report.skip(this.name, '家庭体检', '当前 beta 数据下家庭体检一条提醒都没命中');
    } else {
      const target = fam[0];
      await dismiss(ui, target, `点「${target}」那张卡的「不适用」`);
      await ui.assert(!(await rules(ui)).includes(target), `「${target}」这张卡消失了`);
      await ui.seesText('已隐藏 1 条标成「不适用」的提醒', '出现「已隐藏 1 条 · 全部恢复」');
      const top = await ui.page.locator('#checkup-advice').evaluate(e => Math.round(e.getBoundingClientRect().top)).catch(() => null);
      await ui.assert(top !== null && top > -5 && top < 300, '提交后停在「值得做的事」这一块,不用重新往下找', `top=${top}`);
      await ui.assert((cfg() || '').split('\n').includes(`${target}|*`), '真值层:家庭配置里存了「规则|全家」', `存的是 [${cfg()}]`);

      await ui.page.reload({ waitUntil: 'networkidle' });
      await ui.assert(!(await rules(ui)).includes(target), '刷新之后仍然隐藏(存下来了,不是前端藏一下)');

      // 家里另一个人、另一台设备:同一个浏览器里另开一个手机尺寸的会话
      const ctx2 = await ui.page.context().browser().newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
      await ctx2.route(/\/checkup\/(diagnose|insight)/, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));
      try {
        const p2 = await ctx2.newPage();
        await p2.goto(BASE + '/login', { waitUntil: 'networkidle', timeout: 90000 });
        await p2.fill('input[name=username]', process.env.E2E_USER || 'diwa');
        await p2.fill('input[name=password]', process.env.E2E_PASS || 'demo1234');
        await Promise.all([p2.waitForNavigation({ waitUntil: 'networkidle', timeout: 90000 }).catch(() => {}),
                           p2.click('button[type=submit]')]);
        await p2.goto(BASE + '/checkup', { waitUntil: 'networkidle', timeout: 90000 });
        const r2 = await p2.locator('#checkup-advice article.advice-card').evaluateAll(els => els.map(a => a.getAttribute('data-rule')));
        await ui.assert(!r2.includes(target), '另一个会话(手机尺寸)里也看不到这条', r2.join(','));
        const ov = await p2.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
        await ui.assert(ov <= 2, '手机尺寸无横向溢出', `溢出 ${ov}px`);
        const restoreVisible = await p2.isVisible('#checkup-advice button:has-text("全部恢复")');
        await ui.assert(restoreVisible, '手机上「全部恢复」也点得到');
      } finally {
        await ctx2.close();
      }

      await restoreAll(ui, '点「全部恢复」');
      await ui.assert((await rules(ui)).includes(target), `「${target}」回来了`);
      await ui.notSeesText('已隐藏', '「已隐藏」提示消失');
      await ui.assert(!(cfg() || '').includes('|*'), '真值层:家庭级的记录清掉了', `存的是 [${cfg()}]`);
      await ui.noConsoleErrors('家庭体检页控制台无报错');
    }

    // ── 2 · 账户体检:从「账户」页进去 ─────────────────────────────
    report.section('2 · 账户体检:从「账户」页的「资产体检」链接进去');
    await ui.goto('/accounts');
    const ids = await ui.page.locator('details.row-more a[href^="/checkup?account="]')
      .evaluateAll(as => [...new Set(as.map(a => a.getAttribute('href').match(/account=(\d+)/)[1]))]);
    // 先把每个账户都点进去看一遍,优先挑「同一条规则在两个账户上都命中」的,好验按账户隔离
    const hits = {};
    for (const id of ids) {
      await openAccountCheckup(ui, id);
      const r = await rules(ui);
      if (r.length) hits[id] = r;
    }
    const hitIds = Object.keys(hits);
    if (!hitIds.length) {
      report.skip(this.name, '账户体检', `${ids.length} 个账户逐个点进去看过,没有一个命中提醒`);
      return;
    }
    let acct = hitIds[0], rule = hits[acct][0], other = null;
    for (const a of hitIds) {
      for (const r of hits[a]) {
        const o = hitIds.find(b => b !== a && hits[b].includes(r));
        if (o) { acct = a; rule = r; other = o; break; }
      }
      if (other) break;
    }
    report.info(`命中提醒的账户:${hitIds.map(a => `${a}(${hits[a].join('/')})`).join(' · ')}`);

    await openAccountCheckup(ui, acct);
    await dismiss(ui, rule, `在账户 ${acct} 上点「${rule}」的「不适用」`);
    await ui.assert(new URL(ui.page.url()).searchParams.get('account') === acct, '提交后回到的还是这个账户的体检页', ui.page.url());
    await ui.assert(!(await rules(ui)).includes(rule), `「${rule}」在这个账户上消失了`);
    await ui.seesText('已隐藏 1 条标成「不适用」的提醒', '账户页也出现「已隐藏 1 条 · 全部恢复」');
    await ui.assert((cfg() || '').split('\n').includes(`${rule}|${acct}`), '真值层:存的是「规则|这个账户」,不是全家', `存的是 [${cfg()}]`);

    // 按账户隔离:别的账户上同一条规则照常显示
    if (other) {
      await openAccountCheckup(ui, other);
      await ui.assert((await rules(ui)).includes(rule), `账户 ${other} 上同一条「${rule}」照常显示(按账户记,不是全家一刀切)`);
    } else {
      report.info(`没有两个账户命中同一条规则,按账户隔离这一点由单测守`);
    }

    await openAccountCheckup(ui, acct);
    await restoreAll(ui, '回到这个账户,点「全部恢复」');
    await ui.assert((await rules(ui)).includes(rule), `「${rule}」回来了`);
    await ui.noConsoleErrors('账户体检页控制台无报错');
  },

  async cleanup(ui, report) {
    await ui.page.unroute(/\/checkup\/(diagnose|insight)/).catch(() => {});
    db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${KEY}'`);
    if (state.before !== null && state.before !== undefined) {
      db.raw(`INSERT INTO family_runtime_config (family_id, key_name, value_text)
              VALUES (${fx.FAM}, '${KEY}', '${String(state.before).replace(/'/g, "''")}')`);
    }
    report.info(`还原:${KEY} = [${cfg() || ''}](跑之前是 [${state.before || ''}])`);
  },
};
