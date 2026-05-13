-- =========================================================
-- V21 · v0.4 家庭决策辅助字段(2026-05-14)
-- =========================================================
-- FR-61a · cpi_assumption 通胀对照线假设值(默认 2.0%)
-- FR-62a · allocation_anchor + custom 配置锚 + 自定义
-- FR-62b · risk_appetite 家庭风险偏好(LLM 调仓 prompt 输入)
-- 全部 NOT NULL DEFAULT · 0 风险升级(prod 现有 family 行自动填充默认)
-- 对应 PRD/TDD § 3.1 · 决策 33/37/38
-- =========================================================

ALTER TABLE family
  ADD COLUMN cpi_assumption DECIMAL(5,2) NOT NULL DEFAULT 2.00
    COMMENT 'FR-61a · 通胀对照线假设值(% · 默认 2.00 = 2%)· 用户可改 1.5/2/3/5'
    AFTER period_type,
  ADD COLUMN allocation_anchor VARCHAR(32) NOT NULL DEFAULT 'SP_4321'
    COMMENT 'FR-62a · 配置锚 code · 关联 allocation_anchor.code'
    AFTER cpi_assumption,
  ADD COLUMN allocation_anchor_custom JSON NULL
    COMMENT 'FR-62a · 自定义锚 JSON {"cash":10,"invest":30,"property":40,"insurance":20}'
    AFTER allocation_anchor,
  ADD COLUMN risk_appetite VARCHAR(16) NOT NULL DEFAULT 'MODERATE'
    COMMENT 'FR-62b · 家庭风险偏好 · CONSERVATIVE / MODERATE / AGGRESSIVE · LLM 调仓 prompt 输入'
    AFTER allocation_anchor_custom;
