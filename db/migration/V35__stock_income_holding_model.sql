-- =========================================================
-- V35 · v0.12 精化 · 股票收入=持仓版 · 未上市模型升级(2026-07-07)
-- =========================================================
-- 三件事,全部 backward-compat:
-- 1) 未上市(MANUAL)持仓从「整笔市值」升级为「股数 × 单股估值」。
--    复用 shares 列 + manual_value 语义改为「单股估值」· 总市值 = shares × manual_value。
--    老数据按 1 股折算(单股 = 原整笔市值)→ 1 × manual_value = 原值,估值完全不变。
--    用户之后可在持仓页手动改成正确的股数 / 单股估值。
-- 2) 停用「卖出回款」收入类目(卖出回款是账户内减股+加现金的调整,不是外部收入,
--    记收入会重复计)。仅把 sort_order 沉到最后 + 前端下拉隐藏,不 DELETE(保历史 cash_flow 引用)。
-- 3) cash_flow 加两列 nullable,记「+股数收入」的冲回信息(删除时按 ref_shares 冲回股数)。
--    ADD COLUMN NULL → prod 老行全 NULL、现有 SELECT 不选取、0 影响。
-- =========================================================

-- 1) 未上市持仓升级:老数据按 1 股折算(总值不变)
UPDATE stock_holding SET shares = 1
 WHERE valuation_mode = 'MANUAL' AND (shares IS NULL OR shares = 0);

-- 2) 停用卖出回款收入类目(排序沉底 · 前端隐藏 · 不删行)
UPDATE cash_flow_category SET sort_order = 999 WHERE code = 'stock_sell';

-- 3) +股数收入的冲回信息(nullable)
ALTER TABLE cash_flow
  ADD COLUMN ref_holding_id BIGINT NULL
    COMMENT 'v0.12 股票+股数收入:关联持仓 id · 删除时按 ref_shares 冲回股数',
  ADD COLUMN ref_shares DECIMAL(15,4) NULL
    COMMENT 'v0.12 该笔收入新增的股数(+股数收入才有)';
