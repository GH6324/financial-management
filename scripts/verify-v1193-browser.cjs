/**
 * v1.19.3 · 信用卡支出 · 浏览器行为验证
 *
 * 为什么必须用真浏览器:前面的 curl/e2e 只能验「HTML 里有 data-liability 属性」,
 * 验不了**用户真正点的那个东西**。两个 select 都挂了 data-lsel,lens-select.js 会
 * 隐藏原生控件、另渲染一份自定义下拉(.lsel-panel li)—— 原生 select 里摘干净了,
 * 自定义下拉里没跟上的话,用户照样点得到那两个类目,然后撞服务端报错。
 *
 * 跑法:
 *   LD_LIBRARY_PATH=/tmp/xdmg/usr/lib/x86_64-linux-gnu node scripts/verify-v1193-browser.cjs
 */
const path = require('path');
const PW = process.env.PW_CORE || path.join(process.env.HOME, '.npm/_npx/9833c18b2d85bc59/node_modules/playwright-core');
const CHROME = process.env.PW_CHROME || path.join(process.env.HOME, '.cache/ms-playwright/chromium-1228/chrome-linux64/chrome');
const { chromium } = require(PW);
const BASE = process.env.V_BASE || 'http://127.0.0.1:20000';

let pass = 0, fail = 0; const failed = [];
const ok  = m => { pass++; console.log('  \x1b[32mPASS\x1b[0m ' + m); };
const bad = m => { fail++; failed.push(m); console.log('  \x1b[31mFAIL\x1b[0m ' + m); };

/**
 * 点自定义下拉里的一项 —— **用户的真实动作**。
 * 不能用 page.selectOption:原生 select 被 lens-select 用 CSS 隐藏了(那正是本次要验的机制),
 * Playwright 会一直等它「可见」直到超时。lens-select 监听的是 li 的 mousedown,不是 click。
 * 面板打开时可能被移到 document.body 下,所以选择器全局找 `.lsel-panel:not([hidden])`。
 */
async function pick(page, selAttr, value) {
  await page.$eval(`[${selAttr}]`, s => s.closest('.lsel').querySelector('.lsel-btn').click());
  await page.waitForTimeout(150);
  await page.$eval(`.lsel-panel:not([hidden]) li[data-v="${value}"]`,
    l => l.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true })));
  await page.waitForTimeout(250);
}

/** 原生 select 里可见的类目 value */
const nativeCats = page => page.$$eval('[data-expense-cat] option', os => os.map(o => o.value));
/** 自定义下拉面板里可点的类目 value —— 这才是用户实际点的东西 */
async function panelCats(page) {
  await page.$eval('[data-expense-cat]', s => s.closest('.lsel').querySelector('.lsel-btn').click());
  await page.waitForTimeout(150);
  const vals = await page.$$eval('.lsel-panel:not([hidden]) li[data-v]', ls => ls.map(l => l.getAttribute('data-v')));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(100);
  return vals;
}
const hintShown = page => page.$eval('[data-expense-liability-hint]', e => !e.hidden);

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.fill('input[name=username]', 'diwa');
    await page.fill('input[name=password]', 'demo1234');
    await page.click('button[type=submit]');
    await page.waitForLoadState('networkidle');

    await page.goto(BASE + '/entry', { waitUntil: 'networkidle' });
    // state:'attached' —— 默认的 'visible' 在这里永远等不到:lens-select 初始化后
    // 原生 select 就被隐藏了。上一版能跑通只是抢在它初始化之前,属于时序侥幸。
    await page.waitForSelector('[data-expense-acct]', { state: 'attached', timeout: 10000 });
    await page.waitForSelector('.lsel-btn', { timeout: 10000 });   // 等自定义下拉真的建出来

    // 账户选项:找一个负债的、一个非负债的
    const accts = await page.$$eval('[data-expense-acct] option',
      os => os.map(o => ({ v: o.value, liab: o.getAttribute('data-liability') === 'true', t: o.textContent.trim() })));
    const card = accts.find(a => a.liab);
    const cash = accts.find(a => !a.liab);
    if (!card) { bad('支出账户下拉里没有任何负债账户 —— 核心诉求没达成'); throw new Error('no liability account'); }
    ok(`支出账户下拉里有负债账户:${card.t}`);

    console.log('\n═══ 1 · 选非负债账户 → 四个类目都在,提示隐藏 ═══');
    await pick(page, 'data-expense-acct', cash.v);
    let n = await nativeCats(page), p = await panelCats(page);
    n.length === 4 ? ok(`原生 select 有 4 个类目 [${n.join(', ')}]`) : bad(`原生 select 类目数 ${n.length},应为 4`);
    p.includes('loan_payment') && p.includes('interest_paid')
      ? ok(`自定义下拉里能点到还贷/利息 [${p.join(', ')}]`) : bad(`自定义下拉缺类目 [${p.join(', ')}]`);
    (await hintShown(page)) ? bad('非负债账户却显示了负债提示') : ok('负债提示条隐藏');

    console.log('\n═══ 2 · 切到信用卡 → 摘掉两个类目 + 提示出现 ═══');
    await pick(page, 'data-expense-acct', card.v);
    await page.waitForTimeout(250);
    n = await nativeCats(page); p = await panelCats(page);
    (!n.includes('loan_payment') && !n.includes('interest_paid'))
      ? ok(`原生 select 已摘掉还贷/利息 [${n.join(', ')}]`) : bad(`原生 select 仍有还贷/利息 [${n.join(', ')}]`);
    (!p.includes('loan_payment') && !p.includes('interest_paid'))
      ? ok(`自定义下拉也同步摘掉了(用户点不到)[${p.join(', ')}]`)
      : bad(`自定义下拉没同步!用户仍能点到 [${p.join(', ')}] —— lens-select 没跟上`);
    n.includes('consumption') ? ok('「消费」仍在(信用卡刷卡的正常类目)') : bad('「消费」被误摘');
    (await hintShown(page)) ? ok('负债提示条已显示(解释了为什么少两项)') : bad('摘了类目却不说原因,用户会以为下拉坏了');

    console.log('\n═══ 3 · 选中还贷时切到信用卡 → 自动落回「消费」,不留空值 ═══');
    await pick(page, 'data-expense-acct', cash.v);
    await page.waitForTimeout(200);
    await pick(page, 'data-expense-cat', 'loan_payment');
    await page.waitForTimeout(120);
    await pick(page, 'data-expense-acct', card.v);
    await page.waitForTimeout(250);
    const cur = await page.$eval('[data-expense-cat]', s => s.value);
    cur === 'consumption' ? ok('类目自动落回 consumption(空值提交会被服务端报「类目不存在」)')
                          : bad(`类目落到了 "${cur}",应为 consumption`);
    const btnTxt = await page.$eval('[data-expense-cat]', s => {
      const w = s.closest('.lsel'); return w ? w.querySelector('.lsel-btn').textContent.trim() : '(无)';
    });
    btnTxt.includes('日常开支') || btnTxt.includes('消费')
      ? ok(`自定义下拉按钮文案同步为「${btnTxt}」`)
      : bad(`按钮文案是「${btnTxt}」,和实际值 ${cur} 对不上 —— 用户看到的和会提交的不是一回事`);

    console.log('\n═══ 4 · 切回非负债 → 两个类目按原顺序恢复 ═══');
    await pick(page, 'data-expense-acct', cash.v);
    await page.waitForTimeout(250);
    n = await nativeCats(page); p = await panelCats(page);
    n.length === 4 ? ok(`类目恢复为 4 个 [${n.join(', ')}]`) : bad(`恢复后类目数 ${n.length},应为 4`);
    n[1] === 'loan_payment' && n[2] === 'interest_paid'
      ? ok('恢复后顺序正确(insertBefore 按原下标插回,不是 append)')
      : bad(`恢复后顺序错了 [${n.join(', ')}] —— append 会把它们堆到末尾`);
    p.includes('loan_payment') ? ok('自定义下拉也恢复了') : bad('自定义下拉没恢复');
    (await hintShown(page)) ? bad('切回非负债账户后提示条没收起') : ok('负债提示条已收起');

    console.log('\n═══ 5 · 截图存档 ═══');
    await pick(page, 'data-expense-acct', card.v);
    await page.waitForTimeout(250);
    const box = await page.$('[data-expense-liability-hint]');
    const sec = await page.evaluateHandle(() => document.querySelector('[data-expense-acct]').closest('.border-t'));
    await sec.asElement().screenshot({ path: '/tmp/v1193-pc.png' });
    ok('PC 截图 → /tmp/v1193-pc.png');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(300);
    const sec2 = await page.evaluateHandle(() => document.querySelector('[data-expense-acct]').closest('.border-t'));
    await sec2.asElement().screenshot({ path: '/tmp/v1193-mobile.png' });
    ok('移动截图 → /tmp/v1193-mobile.png');
    void box;
  } catch (e) {
    bad('异常:' + e.message);
  } finally {
    await browser.close();
  }
  console.log('\n═══════════════════════════════════════');
  console.log(` 总结: PASS=${pass}  FAIL=${fail}`);
  console.log('═══════════════════════════════════════');
  if (fail) { failed.forEach(f => console.log('  · ' + f)); process.exit(1); }
})();
