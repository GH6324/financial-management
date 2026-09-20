/**
 * db.cjs · 真值层
 *
 * ── 为什么 e2e 仍然要查库 ──
 *
 * 「真 e2e 就不该碰数据库」是个误解。真 e2e 要改的是**动作从哪里发起**
 * (必须是页面,不能是 curl),不是「不许核对数据」。
 *
 * 只看页面会漏掉一整类问题:**显示对了但没存进去**。
 * 页面回显的经常是你刚提交的表单值,而不是库里读回来的 —— 那种情况下
 * 「页面上看到 10500」什么也证明不了。
 *
 * 所以这一层专职回答一件事:**用户那串点击,最后在库里留下了什么**。
 *
 * ── 只读 ──
 *
 * 这里刻意不提供写入方法。要造数据就去页面上点 —— 直接 INSERT 出来的状态
 * 可能是应用永远产生不了的,拿它测出来的绿灯没有意义。
 * 唯一例外见 fixture.cjs(造「时间上无法自然到达」的状态,且必须写明理由)。
 */
const { execFileSync } = require('child_process');

const HOST = process.env.E2E_DB_HOST || '127.0.0.1';
const PORT = process.env.E2E_DB_PORT || '3306';
const USER = process.env.E2E_DB_USER || 'finance';
const PASS = process.env.E2E_DB_PASS || 'finance';
const NAME = process.env.E2E_DB_NAME || 'finance';

function raw(sql) {
  try {
    return execFileSync('mysql', [
      `-h${HOST}`, `-P${PORT}`, `-u${USER}`, `-p${PASS}`, NAME, '-sN', '-e', sql,
    ], { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
  } catch (e) {
    throw new Error(`SQL 失败: ${sql.slice(0, 80)} · ${String(e.stderr || e.message).slice(0, 200)}`);
  }
}

/** 取单值(第一行第一列)。没有行时返回 null,不返回空串 —— 两者含义不同。 */
function one(sql) {
  const r = raw(sql);
  return r === '' ? null : r;
}

/** 取数字。拿不到值返回 null,让调用方显式处理,不要悄悄当成 0。 */
function num(sql) {
  const r = one(sql);
  return r === null ? null : Number(r);
}

/** 取一列。 */
function col(sql) {
  const r = raw(sql);
  return r === '' ? [] : r.split('\n').map(s => s.trim());
}

module.exports = { one, num, col, raw };
