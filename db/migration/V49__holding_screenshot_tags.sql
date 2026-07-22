-- =========================================================
-- V49 · v1.4 持仓级资产类型/风险/流动性标签(2026-07-23)
-- =========================================================
-- 截图导入的基金/理财持仓需要逐支打标(一个大账户内各支持仓行业/风险/流动性各异)。
-- stock_holding 现只有 industry_tag(v1.1);补 asset_class / risk / liquidity 三个持仓级标签。
-- 全部可空 · 为空时装配层回落账户级同名标签 → 存量 STOCK 持仓(全空)行为完全不变。
-- sync_source 复用既有 VARCHAR(8) 列,新增取值 SCREENSHOT(截图导入),无需改结构。
-- 对应 prd/v1.4.md §7 · tech-design/v1.4.md §2①
-- =========================================================

ALTER TABLE stock_holding
  ADD COLUMN asset_class_tag VARCHAR(20) NULL
             COMMENT '资产类型(AssetClass.name · 空=回落账户级)' AFTER industry_tag,
  ADD COLUMN risk_tag        VARCHAR(16) NULL
             COMMENT '风险档(空=回落账户级)' AFTER asset_class_tag,
  ADD COLUMN liquidity_tag   VARCHAR(16) NULL
             COMMENT '流动性档(空=回落账户级)' AFTER risk_tag;

-- sync_source 原 VARCHAR(8) 装不下新值 SCREENSHOT(10 字符)· 加宽到 16(向后兼容,老值 FUTU/TIGER 不变)
ALTER TABLE stock_holding MODIFY COLUMN sync_source VARCHAR(16) NULL
      COMMENT '同步来源:FUTU / TIGER / SCREENSHOT(截图导入);手填为 null · reconcile 只动带标记的行';
