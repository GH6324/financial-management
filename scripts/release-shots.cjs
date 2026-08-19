#!/usr/bin/env node
/**
 * release-shots.cjs · 发版真机截图(beta)· v0.15 起随 release-prod skill 使用
 *
 * 用法:
 *   LD_LIBRARY_PATH=/tmp/xdmg/usr/lib/x86_64-linux-gnu \
 *   node scripts/release-shots.cjs <输出目录> [spec.json 路径]
 *
 * - 登录 beta(127.0.0.1:20000 · diwa/demo1234)后按 spec 截图
 * - PC 1440x900 + 移动 390x844 双视口;JPEG quality=82(压缩且保清晰)
 * - spec 默认 scripts/release-shots.spec.json —— 每次发版按本次核心能力改清单
 * - spec 条目可选 eval:"<js>" —— 截图前在页面里跑一段(展开折叠块 / 截短长列表)
 * - chromium 路径按本机 playwright 缓存(见 memory reference_headless_screenshot)
 */
const path = require('path');
const fs = require('fs');

const PW = process.env.PW_CORE || path.join(process.env.HOME, '.npm/_npx/9833c18b2d85bc59/node_modules/playwright-core');
const CHROME = process.env.PW_CHROME || path.join(process.env.HOME, '.cache/ms-playwright/chromium-1228/chrome-linux64/chrome');
const BASE = process.env.SHOT_BASE || 'http://127.0.0.1:20000';
const USER = process.env.SHOT_USER || 'diwa';
const PASS = process.env.SHOT_PASS || 'demo1234';

const outDir = process.argv[2] || '/tmp/release-shots';
const specFile = process.argv[3] || path.join(__dirname, 'release-shots.spec.json');
const spec = JSON.parse(fs.readFileSync(specFile, 'utf8'));
fs.mkdirSync(outDir, { recursive: true });

const { chromium } = require(PW);

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

  // 隐私模式默认开启(SHOT_PRIVACY=0 关):data-priv 金额高斯模糊,截图可安全上公开 Release
  async function armPrivacy(ctx) {
    if (process.env.SHOT_PRIVACY === '0') return;
    await ctx.addInitScript(() => { try { sessionStorage.setItem('privacy', '1'); } catch (e) {} });
  }

  async function login(ctx) {
    const p = await ctx.newPage();
    await p.goto(BASE + '/login', { waitUntil: 'networkidle' });
    await p.fill('input[name="username"]', USER);
    await p.fill('input[name="password"]', PASS);
    await Promise.all([p.waitForNavigation({ waitUntil: 'networkidle' }), p.click('button[type="submit"]')]);
    await p.close();
  }

  async function shoot(ctx, list, prefix) {
    const p = await ctx.newPage();
    for (const s of list) {
      try {
        await p.goto(BASE + s.path, { waitUntil: 'networkidle' });
        if (s.waitFor) { await p.waitForSelector(s.waitFor, { timeout: 15000 }); }  // 等内容真渲染出来(治 loading 页截早)
        // v1.18 · 截图前在页面里跑一段(展开指定的折叠块 / 截掉过长列表 / 关掉动画)。
        // 加它是因为流水时间线默认只展开【最新】那一组,而 beta 的测试数据里最新是 2041 年那批;
        // 想拍本次能力就得先把目标那一组打开。用页面自己的 DOM 操作,不改被拍的页面。
        if (s.eval) { await p.evaluate(s.eval); await p.waitForTimeout(300); }
        await p.waitForTimeout(s.wait || 1200);   // 图表/字体/轮询首帧
        const f = path.join(outDir, `${prefix}_${s.name}.jpg`);
        if (s.selector) {
          // 聚焦局部:只截该元素(裁掉导航/留白/无关区域)
          await p.locator(s.selector).first().screenshot({ path: f, type: 'jpeg', quality: 82 });
        } else {
          await p.screenshot({ path: f, type: 'jpeg', quality: 82, fullPage: s.full !== false });
        }
        console.log('shot', f, Math.round(fs.statSync(f).size / 1024) + 'KB');
      } catch (e) { console.error('FAIL', s.path, e.message); }
    }
    await p.close();
  }

  const pc = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
  await armPrivacy(pc);
  await login(pc);
  await shoot(pc, spec.pc || [], 'pc');
  await pc.close();

  const mob = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  await armPrivacy(mob);
  await login(mob);
  await shoot(mob, spec.mobile || [], 'mobile');
  await mob.close();

  await browser.close();
})();
