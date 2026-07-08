-- =========================================================
-- V37 · 价格/估值精度放宽(issue #3 · 精度截断)
-- =========================================================
-- 背景:
--   社区 issue #3 反馈「未上市持仓手填单股估值 15.678 被截成 15.68」。
--   根因是列精度:
--     · stock_holding.manual_value  DECIMAL(15,2) —— MANUAL 模式此列 = 单股估值,
--       2 位小数放不下 15.678 / 2.3456,估值必然失真。
--     · stock_holding.cost_basis    DECIMAL(15,4) —— 每股成本,4 位对高价股/汇率换算仍偏紧。
--     · stock_price_snapshot.close_price DECIMAL(15,4) —— 收盘价,加密货币/高精度场景 4 位偏紧。
--   统一放宽到 DECIMAL(20,6):14 位整数 + 6 位小数,覆盖家庭所有持仓价与估值。
--
-- backward-compat:
--   · 纯拓宽(widening)精度,不改语义、不动老数据数值 —— 老值 x.xx 原样保留为 x.xx0000。
--   · manual_value 在 CASH 模式仍是现金金额(Service 按 2 位落库),拓宽不影响其行为。
--   · 列可空性保持不变(manual_value / cost_basis 可空;close_price 非空)。
-- =========================================================

ALTER TABLE stock_holding
    MODIFY COLUMN manual_value DECIMAL(20,6) NULL
        COMMENT 'MANUAL 时=单股估值(账户币种)· CASH 时=现金金额;总市值见 AccountValuationService',
    MODIFY COLUMN cost_basis DECIMAL(20,6) NULL
        COMMENT '可选 · 平均买入成本 / 每股成本(原币种)';

ALTER TABLE stock_price_snapshot
    MODIFY COLUMN close_price DECIMAL(20,6) NOT NULL
        COMMENT '当日收盘价 · 原币种(6 位小数)';
