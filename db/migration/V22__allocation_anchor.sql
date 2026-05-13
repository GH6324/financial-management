-- =========================================================
-- V22 · v0.4 资产配置锚模板(2026-05-14)
-- =========================================================
-- 4 行静态预置 + 1 个 CUSTOM 由 family.allocation_anchor_custom JSON 承接
-- 对应 PRD/TDD § 3.2 · 决策 37
-- =========================================================

CREATE TABLE allocation_anchor (
  code          VARCHAR(32)  NOT NULL,
  display_name  VARCHAR(80)  NOT NULL,
  cash_pct      DECIMAL(5,2) NOT NULL  COMMENT '现金占比 %',
  invest_pct    DECIMAL(5,2) NOT NULL  COMMENT '投资(股+债+商品)占比 %',
  property_pct  DECIMAL(5,2) NOT NULL  COMMENT '房产占比 %',
  insurance_pct DECIMAL(5,2) NOT NULL DEFAULT 0
                              COMMENT '保险占比 % · v0.4 占位 · v0.5 实际承接',
  description   VARCHAR(255) NULL,
  display_order TINYINT      NOT NULL DEFAULT 100,
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='资产配置锚模板 · v0.4 引入 · admin 可微调';

INSERT INTO allocation_anchor (code, display_name, cash_pct, invest_pct, property_pct, insurance_pct, description, display_order) VALUES
  ('SP_4321',         '标普 4321 · 中产经典',     10, 30, 40, 20,
   '现金 10% · 投资 30% · 房产 40% · 保险 20% · 适合工作 10 年以上中产家庭', 10),
  ('XQ_CONSERVATIVE', '雪球三分法 · 稳健',        10, 90,  0,  0,
   '现金 10% · 投资 90%(其中债 60 / 股 30 / 商 10)· 适合子女教育金 5-10 年期', 20),
  ('XQ_AGGRESSIVE',   '雪球三分法 · 激进',        10, 90,  0,  0,
   '现金 10% · 投资 90%(其中股 70 / 债 15 / 商 15)· 适合成长期家庭 30-40 岁', 30),
  ('PERMANENT',       '永久投资组合',             25, 75,  0,  0,
   '4 类各 25%(股/债/金/现金)· 全经济周期对冲 · 适合熊市恐惧家庭', 40);
