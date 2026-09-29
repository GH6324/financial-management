-- ============================================================================
-- V65 · v1.28 · 看 AI 收到了什么(tech-design/v1.28.md 三.4)
--
-- 只加不改:
--   ① llm_system_prompt —— 「规矩」那一段(system prompt)按 (家庭, sha256) 去重,每种只存一份。
--      带 family_id:本机模式的超级 Agent 会把分析上下文(含家里写的偏好)拼进系统提示词,不是纯代码常量。
--   ② llm_prompt_record —— 一次调用真正发出去的内容(user 原文 + 规矩的 hash)、用的模型、结果。
--      只在 LlmRouter 的调用循环 / 超级 Agent 发出那一刻写,页面只读它,不重拼(PRD FR-909)。
--   ③ 四张结果表各加 prompt_record_id(可空):空 = 本版之前生成的老结果,页面照实说「那时还没开始记录」。
--
-- 回滚:回滚 jar 即可,老代码不读这些列与表,留着无害。
-- ============================================================================

CREATE TABLE IF NOT EXISTS llm_system_prompt (
  family_id   BIGINT      NOT NULL,
  hash        CHAR(64)    NOT NULL,
  text        MEDIUMTEXT  NOT NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (family_id, hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='v1.28 · 发给 AI 的「规矩」原文 · 按内容去重';

CREATE TABLE IF NOT EXISTS llm_prompt_record (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  family_id      BIGINT       NOT NULL,
  surface        VARCHAR(32)  NOT NULL COMMENT '用在哪:DIAGNOSE_FAMILY / REBALANCE / ASK_TURN …',
  system_hash    CHAR(64)     NOT NULL COMMENT '→ llm_system_prompt.hash',
  user_text      MEDIUMTEXT   NOT NULL COMMENT '这一次发出去的「你家的数据」原文(已换代号)',
  vendor         VARCHAR(80)  NULL     COMMENT '实际用的模型(如 deepseek · deepseek-chat)',
  outcome        VARCHAR(16)  NOT NULL COMMENT 'OK / FAILED / REJECTED / SENT',
  outcome_note   VARCHAR(500) NULL     COMMENT '失败的上游原话 / 没采用的原因',
  settings_note  VARCHAR(200) NULL     COMMENT '用了哪个模板 / 范围 / 几条偏好',
  legend_json    TEXT         NULL     COMMENT '代号 → 真名(只给本家庭看,不发给 AI)',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_lpr_fam_created (family_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='v1.28 · 一次 AI 调用真正发出去的内容';

ALTER TABLE rebalance_advice_cache ADD COLUMN prompt_record_id BIGINT NULL COMMENT 'v1.28 · 生成这份建议的那次调用';
ALTER TABLE review_ai_cache        ADD COLUMN prompt_record_id BIGINT NULL COMMENT 'v1.28 · 生成这份复盘的那次调用';
ALTER TABLE goal_ai_report         ADD COLUMN prompt_record_id BIGINT NULL COMMENT 'v1.28 · 生成这份报告的那次调用';
ALTER TABLE ask_message            ADD COLUMN prompt_record_id BIGINT NULL COMMENT 'v1.28 · 这一问发出去的内容(记在提问那条上)';
