-- =========================================================
-- V23 · v0.4 AI 调仓建议缓存(2026-05-14)
-- =========================================================
-- FR-62b · 30 天节流 · 复用 v0.3 LlmOrchestrator
-- 对应 PRD/TDD § 3.3 · 决策 38/39
-- =========================================================

CREATE TABLE rebalance_advice_cache (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  family_id    BIGINT       NOT NULL,
  anchor_code  VARCHAR(32)  NOT NULL
                            COMMENT '生成时用的锚 · 切换锚会触发新生成',
  content_json JSON         NOT NULL
                            COMMENT 'AI 返回 {actions:[...], narrative}',
  prompt_hash  CHAR(64)     NULL
                            COMMENT 'sha256(prompt) · 数据未变可命中缓存',
  generated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_family_anchor (family_id, anchor_code),
  INDEX idx_family_time (family_id, generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='FR-62b · AI 调仓建议缓存 · 30 天 TTL';
