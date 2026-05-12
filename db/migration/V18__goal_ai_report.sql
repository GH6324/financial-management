-- =========================================================
-- V18 · v0.3 目标 AI 月报 + 偏离预警存储 · FR-53b/c
-- =========================================================
-- 周期关闭后异步生成月报 · 偏离预警条件命中时生成预警卡内容
-- 同 goal + 同 period 多次刷新走 ON DUPLICATE KEY 覆盖
-- 对应 PRD §FR-53b/c / TDD §3.5 决策 28
-- =========================================================

CREATE TABLE goal_ai_report (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  goal_id       BIGINT       NOT NULL,
  period_id     BIGINT       NOT NULL,
  report_type   VARCHAR(16)  NOT NULL
                             COMMENT 'MONTHLY 周期月报 | ALERT 偏离预警',
  content       TEXT         NOT NULL
                             COMMENT 'LLM 输出文本 · 已通过 OutputValidator',
  validator_status VARCHAR(16) NOT NULL DEFAULT 'PASS'
                             COMMENT 'PASS | FAIL',
  generated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  dismissed_at  TIMESTAMP    NULL
                             COMMENT 'ALERT 类型 · 用户 dismiss 时间',
  PRIMARY KEY (id),
  UNIQUE KEY uq_goal_period_type (goal_id, period_id, report_type),
  INDEX idx_recent (goal_id, report_type, generated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='目标 AI 月报 + 偏离预警 · v0.3 引入';
