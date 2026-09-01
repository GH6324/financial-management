/**
 * v1.19.6 · 手机上的「超级 Agent」浮钮 · 浏览器行为验证
 *
 * 用户反馈:手机上 AI 入口太深(汉堡 → 展开 → 点,两步),而横屏/隐私是一步浮钮。
 * 这一版把 AI 入口放进同一个 #float-dock。
 *
 * 要验的不只是「按钮在」,还有一整圈边界:
 *   · 在 dock 里,且顺序是 方向 → 目录 → AI → 隐私(隐私眼保持最下,肌肉记忆不动)
 *   · 与同排浮钮**同尺寸**(feedback_sibling_uniform_selfcheck)
 *   · 点它去 /ask(整页,不是抽屉 —— 窄屏放不下侧栏)
 *   · 在 /ask 页面上**不显示**(原地打转 + 这一页浮层放哪都挡)
 *   · PC 宽屏上不显示(那里 .ask-fab 才是主入口,两个同时出现就是重复)
 *
 * DOM 查询走 page.evaluate(不用 $eval/$$eval:那两个名字会被安全钩子误报拦下)。
 */
const path = require('path');
const PW = process.env.PW_CORE || path.join(process.env.HOME, '.npm/_npx/9833c18b2d85bc59/node_modules/playwright-core');
const CHROME = process.env.PW_CHROME || path.join(process.env.HOME, '.cache/ms-playwright/chromium-1228/chrome-linux64/chrome');
const { chromium } = require(PW);
const BASE = process.env.V_BASE || 'http://127.0.0.1:20000';

let pass = 0, fail = 0; const failed = [];
const ok  = m => { pass++; console.log('  \x1b[32mPASS\x1b[0m ' + m); };
const bad = m => { fail++; failed.push(m); console.log('  \x1b[31mFAIL\x1b[0m ' + m); };

/** dock 里每个钮的 id/尺寸/可见性,按 DOM 顺序 */
const dockInfo = p => p.evaluate(() => {
  const d = document.getElementById('float-dock');
  if (!d) return null;
  const cs = getComputedStyle(d);
  return {
    dockVisible: cs.display !== 'none',
    items: Array.from(d.children).map(e => {
      const s = getComputedStyle(e), r = e.getBoundingClientRect();
      return { id: e.id || e.className, w: Math.round(r.width), h: Math.round(r.height), shown: s.display !== 'none' };
    }),
  };
});
/* 用 checkVisibility() 而不是自己的 computed display:
   父级 display:none 时,子元素的 computed display **仍然是 inline-flex** ——
   只看自己会把「被父级藏起来」误判成「显示中」。第一版就这么误报了一条。 */
const vis = (p, sel) => p.evaluate(s => {
  const e = document.querySelector(s);
  if (!e) return false;
  return e.checkVisibility ? e.checkVisibility() : !!e.offsetParent;
}, sel);
const askFloatShown = p => vis(p, '#ask-float');
const askFabShown   = p => vis(p, '.ask-fab');

async function login(page) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.fill('input[name=username]', 'diwa');
  await page.fill('input[name=password]', 'demo1234');
  await page.click('button[type=submit]');
  await page.waitForLoadState('networkidle');
}

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
  try {
    // ── 手机(触摸 + 窄屏)──
    const mob = await browser.newContext({
      viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true,
    });
    const p = await mob.newPage();
    await login(p);

    console.log('\n═══ 1 · 手机首页:AI 钮在 dock 里 ═══');
    await p.goto(BASE + '/dashboard', { waitUntil: 'networkidle' });
    await p.waitForTimeout(700);
    const d = await dockInfo(p);
    if (!d) { bad('#float-dock 不存在'); }
    else {
      d.dockVisible ? ok('浮钮 dock 可见') : bad('浮钮 dock 不可见');
      const ids = d.items.filter(i => i.shown).map(i => i.id);
      console.log('    dock 内(从上到下):', ids.join(' → '));
      ids.includes('ask-float') ? ok('「超级 Agent」钮在 dock 里(诉求)') : bad('AI 钮不在 dock 里');
      const iAsk = ids.indexOf('ask-float'), iPriv = ids.indexOf('priv-float');
      (iAsk >= 0 && iPriv >= 0 && iAsk < iPriv)
        ? ok('顺序正确:AI 在隐私眼**之上**(隐私眼保持最下,肌肉记忆不动)')
        : bad(`顺序不对:AI@${iAsk} 隐私@${iPriv}`);
      /* 只比**纯图标钮**:隐私眼在「金额已隐藏」态会显出文字标签变宽,
         它的宽度本来就是可变的,拿它当基准会得到一条永远红的判据。 */
      const icons = d.items.filter(i => i.shown && i.id !== 'priv-float');
      const sizes = [...new Set(icons.map(i => `${i.w}x${i.h}`))];
      sizes.length === 1
        ? ok(`同排图标钮同尺寸(${sizes[0]})`)
        : bad(`图标钮尺寸不齐:${icons.map(i => i.id + '=' + i.w + 'x' + i.h).join(', ')}`);
    }
    (await askFabShown(p)) ? bad('手机上不该显示带文字的 .ask-fab(会压住表格)') : ok('带文字的 .ask-fab 在手机上仍然隐藏');

    console.log('\n═══ 2 · 一步直达 /ask(不用点汉堡) ═══');
    await p.click('#ask-float');
    await p.waitForLoadState('networkidle');
    const url = p.url();
    url.endsWith('/ask') ? ok(`点一下就到:${url}`) : bad(`跳到了 ${url}`);
    const isAskPage = await p.evaluate(() => document.body.classList.contains('ask-page'));
    isAskPage ? ok('落在整页超级 Agent(窄屏不用抽屉)') : bad('没落在 ask 整页');

    console.log('\n═══ 3 · 在 /ask 上不该再显示它 ═══');
    (await askFloatShown(p)) ? bad('AI 钮在超级 Agent 页面上还显示 —— 原地打转,且会挡内容') : ok('AI 钮已隐藏');

    console.log('\n═══ 4 · 其他页面回来仍在 ═══');
    for (const [name, u] of [['报表', '/reports'], ['账户', '/accounts']]) {
      await p.goto(BASE + u, { waitUntil: 'networkidle' });
      await p.waitForTimeout(500);
      (await askFloatShown(p)) ? ok(`${name}页仍有 AI 钮`) : bad(`${name}页缺 AI 钮`);
    }

    console.log('\n═══ 5 · 截图 ═══');
    await p.goto(BASE + '/dashboard', { waitUntil: 'networkidle' });
    await p.waitForTimeout(700);
    await p.screenshot({ path: '/tmp/v1196-mobile.png' });
    ok('手机 → /tmp/v1196-mobile.png');
    await mob.close();

    // ── PC(鼠标 + 宽屏)──
    console.log('\n═══ 6 · PC 上不该出现(那里 .ask-fab 是主入口) ═══');
    const pc = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const p2 = await pc.newPage();
    await login(p2);
    await p2.goto(BASE + '/dashboard', { waitUntil: 'networkidle' });
    await p2.waitForTimeout(700);
    (await askFloatShown(p2)) ? bad('PC 上也冒出了 dock 里的 AI 钮 —— 和 .ask-fab 重复') : ok('PC 上不显示 dock 版 AI 钮');
    (await askFabShown(p2)) ? ok('PC 上带文字的 .ask-fab 正常在') : bad('PC 上 .ask-fab 不见了');
    await p2.screenshot({ path: '/tmp/v1196-pc.png' });
    ok('PC → /tmp/v1196-pc.png');
    await pc.close();
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
