-- =========================================================
-- V36 · CRYPTO 账户类型 + public crypto quote 估值
-- =========================================================
-- 目标:
--   1) 新增 CRYPTO 账户类型与内置模板
--   2) 允许 stock_holding 复用 AUTO/MANUAL/CASH 持仓模型记录加密货币
--   3) 扩大 shares 精度,支持 BTC/ETH 等小数数量
--
-- backward-compat:
--   · 仅放宽 enum CHECK 约束与 DECIMAL 精度,不改老数据语义
--   · 新模板 / 新 product_category 适用范围只影响新建账户选择
-- =========================================================

ALTER TABLE account_template DROP CHECK ck_account_template_type;
ALTER TABLE account_template
    ADD CONSTRAINT ck_account_template_type
        CHECK (type IN ('STOCK','CASH','WEALTH','CRYPTO','PROPERTY','LOAN','OTHER'));

ALTER TABLE account DROP CHECK ck_account_type;
ALTER TABLE account
    ADD CONSTRAINT ck_account_type
        CHECK (type IN ('STOCK','CASH','WEALTH','CRYPTO','PROPERTY','LOAN','OTHER'));

ALTER TABLE stock_holding
    MODIFY COLUMN shares DECIMAL(24,8) NULL
        COMMENT 'AUTO 时必填 · 持仓数量(支持股票小数股与加密货币 8 位小数)';

UPDATE product_category
   SET applicable_types = 'CRYPTO,OTHER'
 WHERE code = 'CRYPTO';

INSERT INTO account_template (code, display_name, type, default_currency, sort_order, is_custom_slot)
VALUES ('crypto_exchange', '加密货币账户', 'CRYPTO', 'USD', 13, 0);
