-- =========================================================
-- V44 · INSURANCE 保险账户类型(储蓄/理财型)· issue #6 · v0.17
-- =========================================================
-- 目标:
--   1) 新增 INSURANCE 账户类型与内置模板(与 METAL V38 对称)
--   2) 保单登记旁表 account_insurance_policy(1:1 · 纯展示 · 全可空)
--   3) 新增 SAVINGS_INSURANCE 产品分类(流动性 SEMI_LIQUID)
--
-- backward-compat:
--   · 仅放宽 enum CHECK 约束 + 新增旁表/种子;不改老数据语义、不动 account 现有列
--   · 非保险账户不读旁表;旁表无行 = 保单未登记
--   · 现金价值 = 手填 period_snapshot(与 WEALTH/PROPERTY 同路,估值引擎零改动)
-- =========================================================

-- 1) 放宽两处 enum CHECK(DROP/ADD · V38 同法)
ALTER TABLE account_template DROP CHECK ck_account_template_type;
ALTER TABLE account_template
    ADD CONSTRAINT ck_account_template_type
        CHECK (type IN ('STOCK','CASH','WEALTH','CRYPTO','METAL','PROPERTY','LOAN','OTHER','INSURANCE'));

ALTER TABLE account DROP CHECK ck_account_type;
ALTER TABLE account
    ADD CONSTRAINT ck_account_type
        CHECK (type IN ('STOCK','CASH','WEALTH','CRYPTO','METAL','PROPERTY','LOAN','OTHER','INSURANCE'));

-- 2) 保单登记旁表 · 1:1 account · 全可空 · 纯展示(任何引擎都不读)
CREATE TABLE account_insurance_policy (
    account_id            BIGINT        NOT NULL,
    insurance_kind        VARCHAR(20)   NULL COMMENT 'InsuranceSubType.name() · 年金险/增额终身寿/…',
    insurer               VARCHAR(60)   NULL COMMENT '承保公司',
    policy_no             VARCHAR(60)   NULL COMMENT '保单号',
    policy_holder         VARCHAR(40)   NULL COMMENT '投保人(自填)',
    insured_person        VARCHAR(40)   NULL COMMENT '被保人(自填)',
    coverage_amount       DECIMAL(18,2) NULL COMMENT '保额',
    premium_amount        DECIMAL(18,2) NULL COMMENT '每期保费',
    premium_frequency     VARCHAR(12)   NULL COMMENT 'SINGLE 趸交 / ANNUAL 年缴 / MONTHLY 月缴',
    premium_terms_total   SMALLINT      NULL COMMENT '总缴费期数',
    premium_terms_paid    SMALLINT      NULL COMMENT '已缴期数',
    policy_effective_date DATE          NULL COMMENT '生效日',
    policy_maturity_date  DATE          NULL COMMENT '满期/领取日',
    CONSTRAINT pk_account_insurance_policy PRIMARY KEY (account_id),
    CONSTRAINT fk_ins_policy_account FOREIGN KEY (account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='保险保单登记 · 纯展示 · v0.17';

-- 3) 产品类目(供 AccountType→默认类目映射 + 风险/流动性对照)
INSERT INTO product_category (code, display_name, risk_level, liquidity_class, applicable_types, display_order)
VALUES ('SAVINGS_INSURANCE', '储蓄型保险', 2, 'SEMI_LIQUID', 'INSURANCE', 97);

-- 4) 内置模板 ×2(年金险 / 增额终身寿 · 子类型差异由 InsuranceSubType 下拉承接)
INSERT INTO account_template (code, display_name, type, default_currency, sort_order, is_custom_slot) VALUES
    ('annuity_insurance',    '年金险账户',     'INSURANCE', 'CNY', 15, 0),
    ('whole_life_insurance', '增额终身寿账户', 'INSURANCE', 'CNY', 16, 0);
