-- =========================================================
-- V38 · METAL 贵金属账户类型 + 持仓计价单位(issue #4)
-- =========================================================
-- 目标:
--   1) 新增 METAL 账户类型与内置模板(与 CRYPTO V36 对称)
--   2) stock_holding 加 unit 列:METAL 持仓按 克/盎司 计价
--   3) 新增 PRECIOUS_METAL 产品分类(流动性 SEMI_LIQUID)
--
-- backward-compat:
--   · 仅放宽 enum CHECK 约束 + 新增可空列 + 加种子;不改老数据语义
--   · 现有持仓 unit=NULL,非 METAL 类型不读该列
-- =========================================================

ALTER TABLE account_template DROP CHECK ck_account_template_type;
ALTER TABLE account_template
    ADD CONSTRAINT ck_account_template_type
        CHECK (type IN ('STOCK','CASH','WEALTH','CRYPTO','METAL','PROPERTY','LOAN','OTHER'));

ALTER TABLE account DROP CHECK ck_account_type;
ALTER TABLE account
    ADD CONSTRAINT ck_account_type
        CHECK (type IN ('STOCK','CASH','WEALTH','CRYPTO','METAL','PROPERTY','LOAN','OTHER'));

ALTER TABLE stock_holding
    ADD COLUMN unit VARCHAR(8) NULL
        COMMENT 'METAL 持仓计价单位:GRAM/OUNCE;其余类型 NULL(v0.14)';

INSERT INTO product_category (code, display_name, risk_level, liquidity_class, applicable_types, display_order)
VALUES ('PRECIOUS_METAL', '贵金属', 4, 'SEMI_LIQUID', 'METAL,OTHER', 96);

INSERT INTO account_template (code, display_name, type, default_currency, sort_order, is_custom_slot)
VALUES ('metal_account', '贵金属账户', 'METAL', 'CNY', 14, 0);
