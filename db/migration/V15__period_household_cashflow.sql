-- =========================================================
-- V15 · v0.3 周期级家庭月度收支聚合 · FR-51
-- =========================================================
-- period 加 2 NULL 列 · 用户在 /entry 填报时选填 · 用于储蓄率指标 + 目标月供反推
-- 与 cash_flow 表口径独立(那张是账户级轧账 · 这是家庭级月度聚合)
-- 不强制一致 · NULL 安全(老周期保持 NULL · 新周期由用户填)
-- 对应 PRD §4.1 / TDD §3.2 · 决策 27(为何不新建表)
-- =========================================================

ALTER TABLE period
  ADD COLUMN total_income_input  DECIMAL(15,2) NULL
    COMMENT '本月家庭总收入(选填 · 本位币 · 来源用户家庭账单口径)'
    AFTER closed_at,
  ADD COLUMN total_expense_input DECIMAL(15,2) NULL
    COMMENT '本月家庭总支出(选填 · 本位币)'
    AFTER total_income_input;
