-- v1.16 FR-391 · 把「已经在飞」的账期一起对上(GitHub issue #15)
--
-- 为什么需要这条(tech-design/v1.16.md §1.3):
--   v1.16 起,开账时系统把上期末余额延续成本期快照的同时,会把同一行 snapshot_todo 标成 DONE
--   —— 「有数字」和「已填」从此是同一件事,填报页的 ✓、tab 徽标、自动关账读的是同一列。
--   但代码改动只影响**将来**开的账期。当前仍 OPEN 的那一期,快照早就写进去了、todo 还停在 PENDING,
--   徽标会继续挂着一个对不上的数字,直到下个月开账才自愈 —— 而用户报的就是当前这一期。
--
-- 三条红线:
--   ① 只动 status = 'OPEN' 的账期。已关账的历史一行不改 —— 关账那一刻的事实保持原样(v1.10 封板承诺)。
--   ② 只改状态列。不写、不改、不删任何一条 period_snapshot / cash_flow / transfer,金额一分不动。
--   ③ done_by_member_id 置 NULL = 「系统代填、没有人确认过」。存量的贷款账户升级后
--      趋势提示条照常出现,不会因为这次回填而丢掉一次决策机会(FR-392)。
--
-- JOIN period_snapshot 保证只对齐「确实已经有本期数字」的行;没有数字的照旧 PENDING,该催还是催。
-- 可重入:再跑一次时这些行已不是 PENDING,WHERE 直接筛掉,影响 0 行。

UPDATE snapshot_todo t
  JOIN period p          ON p.id = t.period_id
                        AND p.status = 'OPEN'
  JOIN period_snapshot s ON s.period_id = t.period_id
                        AND s.account_id = t.account_id
   SET t.status            = 'DONE',
       t.done_at           = COALESCE(t.done_at, NOW(3)),
       t.done_by_member_id = NULL
 WHERE t.status = 'PENDING';
