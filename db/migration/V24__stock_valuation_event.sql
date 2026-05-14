-- =========================================================
-- V24 · v0.4.1 股票估值事件审计 + ledger 显示(2026-05-14)
-- =========================================================
-- 背景:v0.3 FR-52 股票账户自动估值更新 period_snapshot.end_balance · 但
-- 不写任何 cash_flow / ledger 行 · 用户在 /entry · /accounts/{id} 看不到
-- "为什么本期余额从 X 变到 Y" · 估值 PnL 隐式只在 dashboard / reports 算出来。
--
-- 这张表记录每次估值变动 · 当余额变 |Δ| > ¥0.01 时写一行 · ledger view 把它
-- 作为第 4 种"流水"(VALUATION)与 cash_flow / transfer 并列显示。
--
-- 对应 FR-52f · 决策 33-bis(v0.4.1 增量)
-- =========================================================

CREATE TABLE stock_valuation_event (
  id                   BIGINT       NOT NULL AUTO_INCREMENT,
  family_id            BIGINT       NOT NULL,
  account_id           BIGINT       NOT NULL,
  period_id            BIGINT       NOT NULL,
  prev_balance         DECIMAL(15,2) NULL
                                    COMMENT '本次估值前的账户余额(账户币种)· null = 首次估值',
  new_balance          DECIMAL(15,2) NOT NULL
                                    COMMENT '本次估值后的账户余额(账户币种)',
  delta                DECIMAL(15,2) NOT NULL
                                    COMMENT 'new_balance - prev_balance · 正 = 增值 · 负 = 减值',
  trigger_kind         VARCHAR(16)  NOT NULL
                                    COMMENT 'CRON · MANUAL · HOLDING_CHANGE(增删改持仓后自动 refresh)',
  triggered_by_member_id BIGINT     NULL
                                    COMMENT 'MANUAL 时记录用户 ID · CRON null',
  note                 VARCHAR(255) NULL
                                    COMMENT '可选附加说明 · 自动生成「BABA +5% / 腾讯 -2%」摘要',
  triggered_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_account_period (account_id, period_id),
  INDEX idx_family_time (family_id, triggered_at),
  INDEX idx_period_account (period_id, account_id, triggered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='股票账户估值变动审计 · 自动估值时写 · ledger view 当流水显示 · v0.4.1';
