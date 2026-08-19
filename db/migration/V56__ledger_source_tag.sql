-- =====================================================================
-- v1.18 · 流水来源标签(source_tag)
--
-- 背景:账户的「流水时间线」把手动填的、股价 API 自动估值的、券商同步来的
-- 混在一起显示 —— kind 只说明"是收入还是估值",说不清"这笔是谁写进来的"。
-- 用户看到一笔估值变动,分不出是定时拉价、还是富途同步、还是自己截图导入的。
--
-- 历史数据一律回填 UNKNOWN(维护者定 2026-08-19)。
-- 为什么不回填成 MANUAL:那等于**假装我们知道**。历史行里确实有一部分是自动
-- 同步来的(stock_valuation_event 的 trigger_kind 能佐证),但 cash_flow /
-- transfer 上没有任何依据可推断,写 MANUAL 会让统计得出"过去全是手填"的错误结论。
-- UNKNOWN 是诚实的:来源当时没记。
--
-- 向后兼容(prod 已上线):
--   · 四处都是 ADD COLUMN + DEFAULT,不改任何既有列语义、不动金额
--   · 老代码读新表:多一列不影响(MyBatis 按列名映射)
--   · 新代码读老数据:回填后没有 NULL,页面不会出现空标签
--   · 可回滚:回滚后新列被忽略,不影响老版本读写
-- =====================================================================

-- ① 估值事件:自动变动的主要来源。已有 trigger_kind(CRON/MANUAL/HOLDING_CHANGE/IMPORT),
--    但它说的是"什么动作触发",不是"价格/数据从哪来" —— 同一个 CRON 可能是股价 API 也可能是金属 API。
ALTER TABLE stock_valuation_event
  ADD COLUMN source_tag VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
    COMMENT 'v1.18 流水来源:SYNC_STOCK_API/SYNC_METAL_API/SYNC_CRYPTO_API/SYNC_BROKER_FUTU/SYNC_BROKER_TIGER/IMPORT_SCREENSHOT/MANUAL/UNKNOWN';

-- ② 收支流水:绝大多数是人手填的,但也有系统联动写的(股票买卖联动扣/加现金)
ALTER TABLE cash_flow
  ADD COLUMN source_tag VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
    COMMENT 'v1.18 流水来源:MANUAL/SYSTEM_ADJUST/IMPORT_SCREENSHOT/UNKNOWN';

-- ③ 划转
ALTER TABLE transfer
  ADD COLUMN source_tag VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
    COMMENT 'v1.18 流水来源:MANUAL/SYSTEM_ADJUST/UNKNOWN';

-- ④ 月末快照:时间线上的「月末校准」这一行就是它。
--    这张表有 5 个写入口(开账延续 / 用户填报 / 接受贷款趋势 / 余额派生 / 系统估值回写),
--    而 UNIQUE(period_id, account_id) 决定了它是 upsert —— 谁最后写谁说话。
--    以前只能靠 note 文案("开账自动延续上期末余额…" / "系统估值同步")倒着猜,
--    而 note 是用户可见文案、随时会改;把判据落成一列才不会再猜。
ALTER TABLE period_snapshot
  ADD COLUMN source_tag VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
    COMMENT 'v1.18 流水来源:MANUAL/CARRIED_FORWARD/SYSTEM_ADJUST/SYNC_*/UNKNOWN';

-- 回填:历史行的来源当时没记录,一律 UNKNOWN(不假装知道)。
-- DEFAULT 已经让新增列取 'UNKNOWN',这里显式再写一次是为了让意图留在迁移里、
-- 也覆盖将来有人手工插过 NULL 的情况。
UPDATE stock_valuation_event SET source_tag = 'UNKNOWN' WHERE source_tag IS NULL OR source_tag = '';
UPDATE cash_flow            SET source_tag = 'UNKNOWN' WHERE source_tag IS NULL OR source_tag = '';
UPDATE transfer             SET source_tag = 'UNKNOWN' WHERE source_tag IS NULL OR source_tag = '';
UPDATE period_snapshot      SET source_tag = 'UNKNOWN' WHERE source_tag IS NULL OR source_tag = '';
