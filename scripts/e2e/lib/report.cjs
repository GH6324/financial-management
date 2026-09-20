/**
 * report.cjs · 结果汇总
 *
 * 失败要给三样东西:**哪个 flow、哪条断言、当时页面长什么样(截图路径)**。
 * 前两样 bash 的 e2e 也有;第三样是浏览器 e2e 独有的 —— 查一个红灯时
 * 不用靠复现去猜,直接看图。
 */
const C = {
  g: s => `\x1b[32m${s}\x1b[0m`,
  r: s => `\x1b[31m${s}\x1b[0m`,
  c: s => `\x1b[1;36m${s}\x1b[0m`,
  d: s => `\x1b[2m${s}\x1b[0m`,
};

class Report {
  constructor() { this.rows = []; this.skipped = []; }

  section(title) { console.log(`\n${C.c('════ ' + title + ' ════')}`); }
  info(msg)      { console.log(`  ${C.d(msg)}`); }

  pass(flow, label) {
    this.rows.push({ flow, label, ok: true });
    console.log(` ${C.g('PASS')}  ${label}`);
  }

  fail(flow, label, detail, shot) {
    this.rows.push({ flow, label, ok: false, detail, shot });
    console.log(` ${C.r('FAIL')}  ${label}${detail ? '  ::  ' + detail : ''}`);
    if (shot) console.log(`       ${C.d('现场截图 ' + shot)}`);
  }

  skip(flow, label, why) {
    this.skipped.push({ flow, label, why });
    console.log(` ${C.d('SKIP')}  ${label}  ::  ${why}`);
  }

  failCount() { return this.rows.filter(r => !r.ok).length; }
  passCount() { return this.rows.filter(r => r.ok).length; }

  summary() {
    const f = this.rows.filter(r => !r.ok);
    console.log('\n═══════════════════════════════════════');
    console.log(` e2e:PASS=${this.passCount()}  FAIL=${f.length}  SKIP=${this.skipped.length}`);
    console.log('═══════════════════════════════════════');
    if (f.length) {
      console.log('失败清单:');
      for (const r of f) {
        console.log(`  · [${r.flow}] ${r.label}${r.detail ? ' :: ' + r.detail : ''}`);
        if (r.shot) console.log(`      ${r.shot}`);
      }
    }
    return f.length;
  }
}

module.exports = { Report };
