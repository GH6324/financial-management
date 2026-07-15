-- =========================================================
-- V45 · 资产透视(多维打标 + lens 看板)· v1.1
-- =========================================================
-- 目标:
--   1) account 加 3 个分析维度可空列(大类/平台/行业 · loanKind 先例的热字段直落)
--   2) stock_holding 加行业列(个股级细标 · 行业分析的"准"来源)
--   3) lens_board 用户自存透视看板(预设 5 块为代码常量,不落库)
--
-- backward-compat:
--   · 纯加可空列 + 新表;未打标 = 「未分类」照常可用,线上零影响
--   · asset_class / industry_tag 存 Java 枚举 name()(AssetClass / IndustryTag),DB 不加 CHECK(加值免迁移)
-- =========================================================

ALTER TABLE account
    ADD COLUMN asset_class  VARCHAR(20) NULL COMMENT 'AssetClass.name() · NULL=按类型派生默认(v1.1)' AFTER expected_return_pct,
    ADD COLUMN platform_tag VARCHAR(40) NULL COMMENT '平台/机构 · 自由文本 + 前端常用建议(v1.1)' AFTER asset_class,
    ADD COLUMN industry_tag VARCHAR(20) NULL COMMENT 'IndustryTag.name() · 账户级粗标 · 近似非成分穿透(v1.1)' AFTER platform_tag;

ALTER TABLE stock_holding
    ADD COLUMN industry_tag VARCHAR(20) NULL COMMENT 'IndustryTag.name() · 个股行业(v1.1)';

CREATE TABLE lens_board (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    family_id     BIGINT      NOT NULL,
    name          VARCHAR(40) NOT NULL,
    spec_json     TEXT        NOT NULL COMMENT 'LensQuery spec(rows/cols/measures/filters)',
    display_order INT         NOT NULL DEFAULT 100,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_board_family (family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='用户自存透视看板 · v1.1';
