-- =========================================================
-- V33 · v0.8 · 现金调整流水标记(PRD 决策 97-B / 105 · Problem B)
-- =========================================================
-- 手动改股票账户内部现金行(updateCashAmount)会改余额 → 被恒等式当投资损益污染 XIRR。
-- 修法:把这类「主动调现金」记一笔 cash_flow(kind INCOME/EXPENSE),从 PnL 剔除(当本金进出);
-- 但它不是家庭真实收支 → is_adjustment=1 标记,使家庭级储蓄率/月均收支聚合排除它。
-- 账户级净外部流入仍计入(才能把它剔出 PnL)。旧数据默认 0,零影响。
-- =========================================================
ALTER TABLE cash_flow
  ADD COLUMN is_adjustment TINYINT(1) NOT NULL DEFAULT 0
    COMMENT 'v0.8 · 1=账户内部现金调整(剔出投资损益,但不计入家庭真实收支统计)';

-- 调整流水专用类目(kind BOTH,可作 INCOME/EXPENSE);手动改股票现金行时自动记账用
INSERT INTO cash_flow_category (code, display_name, kind, sort_order) VALUES
  ('cash_adjust', '现金调整', 'BOTH', 900)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);
