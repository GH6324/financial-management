/**
 * fixture.cjs · 造前置状态
 *
 * ── 纪律 ──
 *
 * 能用页面造的,**一律去页面上点**(见各 flow 里的 `ui.*`)。
 * 这里只放两种东西:
 *
 *   1. **时间上无法自然到达的状态** —— 比如「账期已自然结束但还在宽限窗口里」,
 *      真实只出现在次月头几天。要么篡改系统时钟(会污染整台机器上别的东西),
 *      要么用应用自己的入口凑一个同构状态。选后者,并写明它与真实场景的**差异**。
 *   2. **清理** —— 每个 flow 跑完必须把现场还原。
 *
 * ── 还原要「声明终态」,不要「记住初态」 ──
 *
 * v1.23 的 e2e 踩过:还原写成 `if (跑之前是 CLOSED) 关回去`,
 * 连跑两次时第二次读到的已经是 OPEN,整段还原被跳过,beta 被留在双活跃状态里。
 * 正确写法是声明「我跑完之后必须只有一期 OPEN」,不管跑之前是什么样。
 */
const db = require('./db.cjs');

const FAM = Number(process.env.E2E_FAMILY || 1);

/** 当前 OPEN 期数。 */
function openCount() {
  return db.num(`SELECT COUNT(*) FROM period WHERE family_id=${FAM} AND status='OPEN'`);
}

/** 进行期 = 最新 OPEN 且尚未自然结束的那一期。 */
function currentPeriod() {
  return db.one(`SELECT id FROM period WHERE family_id=${FAM} AND status='OPEN'
                   AND period_end >= CURDATE() ORDER BY period_start DESC LIMIT 1`);
}

/**
 * 最近一个**已自然结束**的期。
 *
 * 不能写成 `ORDER BY period_start DESC LIMIT 1` —— beta 的账期表预建到了 2041,
 * 那样会取到未来期,而未来期不是补录期(period_end 还没到),
 * 双活跃的语义完全不成立。v1.23 的 e2e 在这里错过一次。
 */
function lastEndedPeriod() {
  return db.one(`SELECT id FROM period WHERE family_id=${FAM} AND period_end < CURDATE()
                   ORDER BY period_start DESC LIMIT 1`);
}

/**
 * 补回「全员已提交填报」。
 *
 * 只给 dualActive 造状态时用:应用的 `reopen` 会 `deleteByPeriod(completion)` ——
 * 那是「重开去改数据」的正确语义(数据要改了,之前的签收就不算数了)。
 * 但真实的**补录期**从没被 reopen 过,它的 completion 还在。
 * 不补回来的话,测的就不是「宽限期内的补录期」,而是「被重开的历史期」——
 * 后者收益类本来就该退回上上期,那不是 bug。
 */
function markAllMembersCompleted(periodId) {
  for (const m of db.col(`SELECT id FROM member WHERE family_id=${FAM} AND archived_at IS NULL`)) {
    db.raw(`INSERT IGNORE INTO period_member_completion(period_id, member_id) VALUES (${periodId}, ${m})`);
  }
}

/** 软删本次 e2e 造的流水(按 note 前缀标记)。 */
function purgeFlows(notePrefix) {
  db.raw(`UPDATE cash_flow SET deleted_at=NOW(3)
           WHERE note LIKE '${notePrefix}%' AND deleted_at IS NULL`);
  db.raw(`UPDATE transfer SET deleted_at=NOW(3)
           WHERE note LIKE '${notePrefix}%' AND deleted_at IS NULL`);
}

module.exports = { FAM, openCount, currentPeriod, lastEndedPeriod, markAllMembersCompleted, purgeFlows };
