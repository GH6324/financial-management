-- =========================================================
-- V46 · 资产透视「用途」维度 + 行业清单扩充(v1.1 评审修订)
-- =========================================================
-- account 加 purpose_tag 可空列(PurposeTag.name() · 应急金/教育金/养老/购房/增值/日常)
-- 行业枚举 12→17(电商零售/半导体/汽车出行/游戏传媒/能源化工)为 Java 枚举,免迁移
-- backward-compat:纯加可空列,未打标=未分类,零影响

ALTER TABLE account
    ADD COLUMN purpose_tag VARCHAR(20) NULL COMMENT 'PurposeTag.name() · 资金用途 · NULL=未分类(v1.1)' AFTER industry_tag;
