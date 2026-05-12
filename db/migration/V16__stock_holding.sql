-- =========================================================
-- V16 · v0.3 股票持仓表 · FR-52
-- =========================================================
-- 持仓级 AUTO/MANUAL 混合模式 · 一个 STOCK 账户内可同时存在两种持仓
-- AUTO 持仓:用户录入 ticker + 数量 · 系统每日 T+1 拉价 · 自动算市值
-- MANUAL 持仓:适合未上市/私募/拉不到价的持仓(如字节跳动期权)
-- 账户余额 = SUM(AUTO 持仓市值) + SUM(MANUAL 持仓 manual_value)
-- 写回 account_balance 表 · 下游 dashboard/XIRR/目标进度 自动反映
-- 对应 PRD §FR-52 / TDD §3.3 · 决策 25 / 26
-- =========================================================

CREATE TABLE stock_holding (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  account_id      BIGINT        NOT NULL,
  display_name    VARCHAR(64)   NOT NULL
                                COMMENT '显示名 · 阿里巴巴 / 字节跳动期权',
  valuation_mode  VARCHAR(8)    NOT NULL DEFAULT 'AUTO'
                                COMMENT 'AUTO 系统拉价 | MANUAL 用户手填市值',

  -- AUTO 模式字段
  ticker          VARCHAR(16)   NULL
                                COMMENT 'AUTO 时必填 · BABA / 600519 / 00700(港股 5 位前导零)',
  market          VARCHAR(8)    NULL
                                COMMENT 'AUTO 时必填 · US | CN | HK',
  shares          DECIMAL(15,4) NULL
                                COMMENT 'AUTO 时必填 · 持股数(小数 4 位 · 支持港股零碎)',
  cost_basis      DECIMAL(15,4) NULL
                                COMMENT '可选 · 平均买入成本(原币种)',
  currency        VARCHAR(8)    NULL
                                COMMENT 'AUTO 时必填 · USD / CNY / HKD',

  -- MANUAL 模式字段
  manual_value    DECIMAL(15,2) NULL
                                COMMENT 'MANUAL 时必填 · 本位币市值',
  manual_value_at TIMESTAMP     NULL
                                COMMENT 'MANUAL 上次更新时间 · UI 显示"X 天前更新"',

  -- 通用
  archived_at     TIMESTAMP     NULL
                                COMMENT '软删除 · 不参与账户余额计算',
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_account_active (account_id, archived_at),
  INDEX idx_auto_ticker (market, ticker, archived_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='股票账户持仓明细 · v0.3 引入 · 持仓级 AUTO/MANUAL 混合';
