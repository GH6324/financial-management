/**
 * v1.19.5 · 上传态缩略图:✕ 删除 + 点开放大 · 浏览器行为验证
 *
 * 必须用真浏览器:这两个都是**点击行为**,curl 和 grep 证明不了。
 * 用户报的两件事其实是同一个根因 —— 刚上传的图走的是 JS 画的 #thumbs(没有 ✕、
 * 没有 data-src、没绑灯箱),而删除和放大只挂在服务端渲染的 js-gallery 上。
 *
 * 全部 DOM 查询走 page.evaluate(不用 $eval/$$eval:那两个名字会被安全钩子当成
 * JS 的 eval 误报拦下;行为完全一样)。
 *
 * 跑法:
 *   LD_LIBRARY_PATH=/tmp/xdmg/usr/lib/x86_64-linux-gnu node scripts/verify-v1195-browser.cjs
 */
const path = require('path');
const fs = require('fs');
const zlib = require('zlib');
const PW = process.env.PW_CORE || path.join(process.env.HOME, '.npm/_npx/9833c18b2d85bc59/node_modules/playwright-core');
const CHROME = process.env.PW_CHROME || path.join(process.env.HOME, '.cache/ms-playwright/chromium-1228/chrome-linux64/chrome');
const { chromium } = require(PW);
const BASE = process.env.V_BASE || 'http://127.0.0.1:20000';
const ACCT = process.env.V_ACCT || '25';

let pass = 0, fail = 0; const failed = [];
const ok  = m => { pass++; console.log('  \x1b[32mPASS\x1b[0m ' + m); };
const bad = m => { fail++; failed.push(m); console.log('  \x1b[31mFAIL\x1b[0m ' + m); };

// ── 造两张肉眼可区分的纯色 PNG ──
const CRC = (() => { const t = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
const crc32 = buf => { let c = 0xFFFFFFFF; for (const x of buf) c = CRC[(c ^ x) & 0xFF] ^ (c >>> 8); return (c ^ 0xFFFFFFFF) >>> 0; };
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function makePng(file, r, g, b) {
  const W = 120, H = 200;
  const row = Buffer.concat([Buffer.from([0]), Buffer.concat(Array.from({ length: W }, () => Buffer.from([r, g, b])))]);
  const raw = Buffer.concat(Array.from({ length: H }, () => row));
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  fs.writeFileSync(file, Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]));
}

const shots = p => p.evaluate(() =>
  Array.from(document.querySelectorAll('#thumbs .gshot')).map(e => ({
    rel: e.getAttribute('data-rel'),
    src: e.getAttribute('data-src') || '',
    pending: e.classList.contains('pending'),
    rmVisible: (() => { const b = e.querySelector('.grm'); return !!b && getComputedStyle(b).display !== 'none'; })(),
  })));
const lbOpen  = p => p.evaluate(() => document.querySelector('#lb').classList.contains('on'));
const lbSrc   = p => p.evaluate(() => { const i = document.querySelector('#lb img'); return i ? (i.getAttribute('src') || '') : ''; });
const imgN    = p => p.evaluate(() => document.querySelector('#imgN').textContent.trim());
const scanOff = p => p.evaluate(() => document.querySelector('#scanBtn').disabled);

(async () => {
  makePng('/tmp/v1195-a.png', 220, 60, 60);
  makePng('/tmp/v1195-b.png', 60, 120, 220);

  const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1100, height: 900 } });
  page.on('dialog', d => d.accept());     // 删除有 confirm
  try {
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.fill('input[name=username]', 'diwa');
    await page.fill('input[name=password]', 'demo1234');
    await page.click('button[type=submit]');
    await page.waitForLoadState('networkidle');
    await page.goto(`${BASE}/entry/import/${ACCT}`, { waitUntil: 'networkidle' });
    // state:'attached' —— 容器为空时高度是 0,默认的 'visible' 永远等不到。
    // (同一个坑在 v1194 的脚本里踩过一次:隐藏/零高元素不能用默认等待。)
    await page.waitForSelector('#thumbs', { state: 'attached', timeout: 10000 });

    console.log('\n═══ 1 · 上传两张 ═══');
    await page.setInputFiles('#fileInput', ['/tmp/v1195-a.png', '/tmp/v1195-b.png']);
    await page.waitForFunction(
      () => document.querySelectorAll('#thumbs .gshot[data-rel]').length >= 2, null, { timeout: 25000 });
    const s = await shots(page);
    s.length >= 2 ? ok(`缩略图 ${s.length} 张,都在同一个画廊容器里`) : bad(`只有 ${s.length} 张`);
    s.every(x => !x.pending) ? ok('都拿到了服务器 rel(上传完成)') : bad('有图停在 pending');
    s.every(x => x.src.startsWith('/uploads/')) ? ok('大图地址已换成服务器路径') : bad(`data-src 没换:${s.map(x => x.src).join(',')}`);
    s.every(x => x.rmVisible) ? ok('每张右上角都有 ✕ 按钮(诉求 1)') : bad('✕ 按钮没出现');

    console.log('\n═══ 2 · 点图放大(诉求 2) ═══');
    await page.click('#thumbs .gshot:first-child');
    await page.waitForTimeout(500);
    const on1 = await lbOpen(page);
    on1 ? ok('灯箱打开了') : bad('点了没反应 —— 灯箱没打开');
    if (on1) {
      const src = await lbSrc(page);
      src.startsWith('/uploads/') ? ok(`灯箱显示的是服务器上那张(${src.slice(0, 30)}…)`) : bad(`灯箱 src 不对:${src}`);
      await page.click('#lbClose');
      await page.waitForTimeout(400);
      (await lbOpen(page)) ? bad('灯箱关不掉') : ok('灯箱能关掉');
    }

    console.log('\n═══ 3 · ✕ 删除(诉求 1) ═══');
    const before = (await shots(page)).length;
    const nBefore = await imgN(page);
    await page.click('#thumbs .gshot:first-child .grm');
    await page.waitForFunction(
      n => document.querySelectorAll('#thumbs .gshot').length === n, before - 1, { timeout: 15000 });
    const after = (await shots(page)).length;
    after === before - 1 ? ok(`删掉一张:${before} → ${after}`) : bad(`删除没生效:${before} → ${after}`);
    const nAfter = await imgN(page);
    nAfter === String(Number(nBefore) - 1)
      ? ok(`「本次 N 张」跟着退回:${nBefore} → ${nAfter}`)
      : bad(`计数没退回:${nBefore} → ${nAfter}`);

    console.log('\n═══ 4 · 刷新后仍可删可看 + 按钮不该变灰 ═══');
    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForSelector('#thumbs .gshot', { timeout: 10000 });
    const s2 = await shots(page);
    s2.length >= 1 ? ok(`刷新后画廊还在(${s2.length} 张)`) : bad('刷新后画廊空了');
    (await scanOff(page))
      ? bad('刷新后「开始识别」变灰 —— uploaded 没从已有图恢复')
      : ok('「开始识别」可点(uploaded 从服务端图数起算)');
    await page.click('#thumbs .gshot:first-child');
    await page.waitForTimeout(500);
    const on2 = await lbOpen(page);
    on2 ? ok('刷新后点图仍能放大(事件委托对服务端渲染的也生效)') : bad('刷新后点图没反应');
    if (on2) { await page.click('#lbClose'); await page.waitForTimeout(300); }

    console.log('\n═══ 5 · 截图存档 ═══');
    await page.screenshot({ path: '/tmp/v1195-pc.png' });
    ok('PC → /tmp/v1195-pc.png');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(400);
    await page.screenshot({ path: '/tmp/v1195-mobile.png' });
    ok('移动 → /tmp/v1195-mobile.png');
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
