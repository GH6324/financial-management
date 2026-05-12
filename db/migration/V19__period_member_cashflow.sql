-- =========================================================
-- V19 · v0.3 成员级月度收支聚合 · FR-51 修订(2026-05-13)
-- =========================================================
-- 原 V15 在 period 表上加 total_*_input 2 列(家庭级 · 1 期 1 行)
-- 用户反馈(2026-05-13):应该 by 成员隔离 · 各成员各自填报 · 家庭总额 = SUM
-- 本表替代 V15 那两列 · V15 加的列保留为占位(deprecated · 不再被代码引用 · prod 升级 0 影响)
--
-- 复合唯一键 (period_id, member_id) · 一个成员一期一行
-- upsert 走 ON DUPLICATE KEY UPDATE
-- =========================================================

CREATE TABLE period_member_cashflow (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  family_id    BIGINT       NOT NULL,
  period_id    BIGINT       NOT NULL,
  member_id    BIGINT       NOT NULL,
  total_income_input  DECIMAL(15,2) NULL
                       COMMENT '该成员本期总收入(选填 · 本位币)',
  total_expense_input DECIMAL(15,2) NULL
                       COMMENT '该成员本期总支出(选填 · 本位币)',
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                            ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_period_member (period_id, member_id),
  INDEX idx_family_period (family_id, period_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='成员级月度收支聚合 · v0.3 引入(2026-05-13 修订 by 成员隔离)';
