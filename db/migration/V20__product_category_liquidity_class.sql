-- =========================================================
-- V20 · 产品类目流动性分级(2026-05-14)
-- =========================================================
-- 背景:v0.2 引入 product_category(V11)· 但流动性(LIQUID / SEMI_LIQUID /
-- ILLIQUID / NA)一直按 AccountType 一刀切(Account.getLiquidity 内 switch)。
-- 用户实例:WEALTH 账户里的「货币基金」(MONEY_FUND · 余额宝 / 零钱通 / 招商招招金)
-- 实际 T+0/T+1 赎回 · 是 LIQUID · 但被误判为 SEMI_LIQUID → FactView / 体检
-- 流动资产 / 应急月数 / AccountRules LIQ-1 / LLM prompt 数字全错。
--
-- 修:product_category 加 liquidity_class 列 · 按 16 行类目精细化标注。
-- FactMapper.xml LEFT JOIN pc 透传 · FactProjector 优先 PC · NULL 时 fallback AccountType。
-- =========================================================

ALTER TABLE product_category
  ADD COLUMN liquidity_class VARCHAR(16) NOT NULL DEFAULT 'NA'
                              COMMENT 'LIQUID / SEMI_LIQUID / ILLIQUID / NA · v0.3.3'
  AFTER risk_level;

UPDATE product_category SET liquidity_class = 'LIQUID'      WHERE code IN ('CASH_DEPOSIT', 'MONEY_FUND');
UPDATE product_category SET liquidity_class = 'SEMI_LIQUID' WHERE code IN ('BANK_WEALTH', 'SHORT_BOND', 'LONG_BOND', 'MIXED_FUND', 'A_STOCK', 'US_STOCK', 'HK_STOCK', 'GOLD', 'CRYPTO', 'FUTURES');
UPDATE product_category SET liquidity_class = 'ILLIQUID'    WHERE code IN ('PROPERTY_RES', 'PROPERTY_INV');
UPDATE product_category SET liquidity_class = 'NA'          WHERE code IN ('LIABILITY', 'OTHER');
