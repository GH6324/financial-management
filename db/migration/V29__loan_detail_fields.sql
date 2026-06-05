-- =========================================================
-- V29 · v0.6 · 负债明细轻量字段(2026-06-05)· FR-103
-- =========================================================
-- LOAN 账户可选填「负债类型」(房贷/消费贷/信用卡分期/借款)+「年利率」,
-- 喂资产负债表健康诊断:负债利率 vs 资产真实收益(提前还贷信号)。
--
-- backward-compat:纯 ADD COLUMN DEFAULT NULL · 老账户两列为空 → 该维度优雅降级
-- (只显负债额、不显利率对照)· 非 LOAN 账户两列恒空 · prod 0 风险。
-- =========================================================

ALTER TABLE account
  ADD COLUMN loan_kind VARCHAR(20) NULL AFTER risk_level_override,
  ADD COLUMN annual_rate_pct DECIMAL(6,3) NULL AFTER loan_kind;
