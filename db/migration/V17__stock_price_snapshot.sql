-- =========================================================
-- V17 · v0.3 股票价格快照表 · FR-52a
-- =========================================================
-- 每日 cron 拉取的股价缓存表 · 一行 = ticker + market + trade_date 的当日收盘价
-- 拉价失败时,fallback 到此表最近行(UI 标"价格陈旧 N 天")
-- 复合主键 · idx_recent 支持 ORDER BY trade_date DESC LIMIT 1 高效查询
-- 对应 PRD §FR-52a / TDD §3.4 · 决策 24(数据源选型)
-- =========================================================

CREATE TABLE stock_price_snapshot (
  ticker      VARCHAR(16)   NOT NULL
                            COMMENT 'BABA / 600519 / 00700',
  market      VARCHAR(8)    NOT NULL
                            COMMENT 'US | CN | HK',
  trade_date  DATE          NOT NULL
                            COMMENT '交易日(非节假日)',
  close_price DECIMAL(15,4) NOT NULL
                            COMMENT '当日收盘价 · 原币种',
  currency    VARCHAR(8)    NOT NULL
                            COMMENT 'USD / CNY / HKD',
  source      VARCHAR(16)   NOT NULL
                            COMMENT 'sina | tencent · 拉价数据源',
  fetched_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
                            COMMENT '本行写入时间 · 用于审计',
  PRIMARY KEY (ticker, market, trade_date),
  INDEX idx_recent (ticker, market, trade_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='股票价格快照 · v0.3 引入 · 每日 cron 拉取写入';
