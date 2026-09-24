/**
 * flow · 报表「钱花在哪了」→ 点一行看逐笔(2026-09-24 整套回归时查到)
 *
 * v1.24 把 _expense-mix.html 并进了 _expense-section.html,控制器却还返回旧文件名。
 * 本机与 prod 都在长期存在的工作区里 `mvn package`(不 clean),target/ 里的旧文件把它掩盖了;
 * Docker 镜像是 clean 构建 → 每个 Docker 用户点这一行都是整页错误。
 * 之前的 flow 只断言了「这一块渲染出来」,从没点过那一行 —— 这里补上点击。
 *
 * 全程从页面发起:进报表 → 在「明细 · 点一行看逐笔」里点第一行 → 看抽屉里出来的东西。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

module.exports = {
  name: '26-expense-drawer',
  title: '报表 · 支出构成点一行看逐笔:抽屉真的能打开',

  async run(ui, report) {
    ui.flow = this.name;
    await ui.goto('/reports');
    await ui.rendered('报表页');
    await ui.seesText('点一行看逐笔', '支出构成的明细表在');

    const btn = 'button[hx-get*="/reports/expense-mix/detail"]';
    const n = await ui.page.locator(btn).count();
    if (!n) { report.skip(this.name, '点一行', '当前窗口里没有任何消费分组'); return; }
    const target = await ui.page.locator(btn).first().getAttribute('hx-target');
    const [resp] = await Promise.all([
      ui.page.waitForResponse(r => r.url().includes('/reports/expense-mix/detail'), { timeout: 30000 }).catch(() => null),
      ui.click(`${btn} >> nth=0`, '点第一行'),
    ]);
    await ui.page.waitForTimeout(600);
    await ui.assert(!!resp && resp.status() === 200, '抽屉请求返回 200(修复前是整页错误)', resp ? `HTTP ${resp.status()}` : '没发出请求');

    const drawer = await ui.page.evaluate((sel) => {
      const el = document.querySelector(sel);
      return el ? { text: el.innerText.replace(/\s+/g, ' ').slice(0, 300), rows: el.querySelectorAll('div.border-b').length } : null;
    }, target);
    await ui.assert(!!drawer && !/出错了/.test(drawer.text), '抽屉里不是错误页', drawer ? drawer.text : '(抽屉容器不存在)');

    // 这一行自己写着「N 笔」—— N > 0 时抽屉里就该列出逐笔,而不是空态
    const items = Number(((await ui.page.locator(btn).first().innerText()).match(/(\d+)\s*笔/) || [])[1] || 0);
    await ui.assert(items > 0 ? !!drawer && drawer.rows > 0 : true,
                    `这一行写着 ${items} 笔,抽屉里列出了逐笔`, `抽屉 ${drawer ? drawer.rows : 0} 行`);
    // 真值层(弱):这一行说有笔数时,库里确实有支出笔 —— 这条 flow 守的是「抽屉打得开」,逐笔进不进得去由 regression-data 主线 12 守
    const cnt = db.num(`SELECT COUNT(*) FROM cash_flow cf JOIN account a ON a.id = cf.account_id
                         WHERE a.family_id = ${fx.FAM} AND cf.kind = 'EXPENSE' AND cf.deleted_at IS NULL`);
    await ui.assert(items === 0 || cnt > 0, '真值层:库里确实有支出笔', `库里 ${cnt} 笔`);
    await ui.noConsoleErrors('报表页控制台无报错');
  },

  async cleanup() { /* 只读 flow:没有改任何数据 */ },
};
