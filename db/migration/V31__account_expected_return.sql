-- =========================================================
-- V31 · v0.8 · 账户级预期收益率(PRD FR-152 / 决策 98 · 预实分析)
-- =========================================================
-- 预实分析的「预期」默认取账户所属品类的 product_category.benchmark_pct;
-- 本列允许对单个账户覆盖自己的预期年化 %。NULL = 未设 → 回落品类基准。
-- 旧账户默认 NULL,零回填零影响。
-- =========================================================
ALTER TABLE account
  ADD COLUMN expected_return_pct DECIMAL(5,2) NULL DEFAULT NULL
    COMMENT 'v0.8 · 账户预期年化收益率 %;NULL=回落品类 benchmark_pct'
    AFTER risk_level_override;
