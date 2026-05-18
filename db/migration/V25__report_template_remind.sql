-- =========================================================
-- V25 · v0.4.14 填报规范化 + DDL 强提醒(2026-05-18 · FR-63)
-- =========================================================
-- 背景:家庭记账靠"每月找一天 10 分钟全家填完",但没规范何时填什么 →
-- 成员忘填 / XIRR 时间精度差。本迁移加:
--   1. family.reporting_template   家庭级填报模板(3 选 1 · 默认 T1)
--   2. family.report_remind_lead_days  截止前几天开始强提醒(默认 2)
--   3. member.phone                成员手机号(私密 · 绝不进 LLM prompt)
--   4. family_notify_config        短信平台 aksk 等(私密 · access 受限 · 绝不进 prompt)
--   5. report_reminder_log         提醒发送审计 + 当天去重(UNIQUE)
--
-- backward-compat:全部 ADD COLUMN DEFAULT + 新表 · 0 破坏 · 老 family
-- 自动落 T1 默认模板 · prod 升级 0 风险。
-- 私密红线:member.phone / family_notify_config.* 任何字段绝不出现在
-- PromptBuilder / 任何 LLM prompt / audit_log 明文。
-- =========================================================

ALTER TABLE family
  ADD COLUMN reporting_template VARCHAR(40) NOT NULL DEFAULT 'T1_REALTIME_INCOME_MONTHEND_EXPENSE',
  ADD COLUMN report_remind_lead_days INT NOT NULL DEFAULT 2;

ALTER TABLE member
  ADD COLUMN phone VARCHAR(20) NULL;

CREATE TABLE family_notify_config (
  family_id              BIGINT       PRIMARY KEY,
  sms_enabled            TINYINT(1)   NOT NULL DEFAULT 0,
  sms_provider           VARCHAR(20)  NOT NULL DEFAULT 'aliyun',
  sms_access_key_id      VARCHAR(128) NULL,
  sms_access_key_secret  VARCHAR(128) NULL,
  sms_sign_name          VARCHAR(40)  NULL,
  sms_template_code      VARCHAR(40)  NULL,
  updated_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                      ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_notify_family FOREIGN KEY (family_id) REFERENCES family(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE report_reminder_log (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  family_id   BIGINT       NOT NULL,
  period_id   BIGINT       NOT NULL,
  member_id   BIGINT       NOT NULL,
  channel     VARCHAR(16)  NOT NULL,
  remind_date DATE         NOT NULL,
  status      VARCHAR(16)  NOT NULL,
  detail      VARCHAR(255) NULL,
  sent_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_dedup (family_id, period_id, member_id, channel, remind_date),
  KEY idx_family_period (family_id, period_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
