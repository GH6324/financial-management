-- =========================================================
-- V30 · v0.8 · 跨币种转账「到账金额」(PRD FR-147 / 决策 93)
-- =========================================================
-- transfer 原仅一个无币种 amount,跨币种转账(CNY→USD)时转入端用错币种数 → PnL/XIRR 被污染。
-- 加可空 to_amount(转入账户币种的到账金额);NULL = 同币种,沿用 amount。
-- 旧数据全部同币种 → 默认 NULL,FactMapper 用 COALESCE(to_amount, amount) 回落,零回填零影响。
-- =========================================================
ALTER TABLE transfer
  ADD COLUMN to_amount DECIMAL(18,2) NULL DEFAULT NULL
    COMMENT 'v0.8 · 转入账户币种的到账金额;NULL=同币种(沿用 amount)'
    AFTER amount;
