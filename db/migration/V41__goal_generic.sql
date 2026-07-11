-- v0.16 · 目标模块重构:family_goal 通用化(加列 · 纯加列可空/带默认 · 存量行零影响)
-- goal_type 复用为 kind(值域加 CUSTOM);新增 追踪指标 / 达标方向 / 时间模式。
-- 存量三类回填:退休/教育 → AMOUNT_TOTAL(全家净资产)、应急 → CASH_TOTAL(全家现金);
-- comparator 默认 GTE、time_mode 默认 OPEN(=旧长期口径,行为完全等价迁移前)。

ALTER TABLE family_goal
    ADD COLUMN metric     VARCHAR(32) NULL         COMMENT '追踪指标 GoalMetric(NULL 视为 AMOUNT_TOTAL)' AFTER goal_type,
    ADD COLUMN comparator VARCHAR(4)  NOT NULL DEFAULT 'GTE' COMMENT 'GTE 达到 | LTE 降到' AFTER metric,
    ADD COLUMN time_mode  VARCHAR(8)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 长期 | DEADLINE 截止型' AFTER comparator;

-- 回填存量目标的追踪指标(与旧进度口径等价)
UPDATE family_goal
   SET metric = CASE goal_type WHEN 'EMERGENCY' THEN 'CASH_TOTAL' ELSE 'AMOUNT_TOTAL' END
 WHERE metric IS NULL;
