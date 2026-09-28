-- ============================================================================
-- V64 · v1.27 · 分析范围 / 分析模板 / 分析偏好(issue #23 · tech-design/v1.27.md 三.6)
--
-- 只加不改:
--   ① account.analysis_excluded —— 「不参与配置分析」标记,默认 0 = 没标记 = v1.26 行为。
--      不进 period_account_attr(不定格):它是「怎么看」,不是「资产是什么」(PRD §13 ⑥)。
--   ② analysis_template —— 「我的模板」。内置 5 个随代码走,不进库;
--      这里存的是**完整副本**(来源 key + 来源版本只作记录),内置升级不会改动它(PRD §9 ⑥)。
--   ③ analysis_preference —— 家里人写给 AI 的几句话,只进提示词,不改任何数字。
--   ④ ask_message.ctx_note —— 这一轮带了什么分析上下文;托管模式下记百炼回显是否确认收到。
--
-- 回滚:回滚 jar 即可,老代码不读这些列与表,留着无害。
-- ============================================================================

ALTER TABLE account
  ADD COLUMN analysis_excluded TINYINT(1) NOT NULL DEFAULT 0
      COMMENT 'v1.27 · 不参与配置分析(只影响占比类分析,净资产照常计入)';

CREATE TABLE IF NOT EXISTS analysis_template (
  id                   BIGINT       NOT NULL AUTO_INCREMENT,
  family_id            BIGINT       NOT NULL,
  name                 VARCHAR(40)  NOT NULL,
  source_key           VARCHAR(40)  NULL     COMMENT '基于哪个模板复制:内置 key(如 STEADY)或 custom:<id>',
  source_version       INT          NULL     COMMENT '复制时来源模板的版本 · 仅记录,不联动',
  focus                VARCHAR(120) NOT NULL COMMENT '侧重维度 CSV(ALLOCATION,RISK,LIQUIDITY,RETURN,CONCENTRATION,DEBT)',
  stance               VARCHAR(16)  NOT NULL COMMENT 'FOLLOW / CONSERVATIVE / BALANCED / AGGRESSIVE',
  scope                VARCHAR(16)  NULL     COMMENT 'NULL = 跟随家里的默认范围',
  anchor               VARCHAR(32)  NULL     COMMENT 'NULL = 跟随家里的配置锚',
  extra                TEXT         NULL     COMMENT '补充要求 ≤ 500 字 · 会原样(经真名映射)发给大模型',
  version              INT          NOT NULL DEFAULT 1,
  updated_by_member_id BIGINT       NULL,
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_analysis_template_family (family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.27 · 我的分析模板(内置模板不进库)';

CREATE TABLE IF NOT EXISTS analysis_preference (
  id                   BIGINT       NOT NULL AUTO_INCREMENT,
  family_id            BIGINT       NOT NULL,
  content              VARCHAR(200) NOT NULL,
  enabled              TINYINT(1)   NOT NULL DEFAULT 1,
  source               VARCHAR(16)  NOT NULL DEFAULT 'SETTINGS' COMMENT 'SETTINGS 分析设置页 / AGENT 超级 Agent 对话里点「记住」',
  created_by_member_id BIGINT       NULL,
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_analysis_preference_family (family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.27 · 分析偏好(只进 AI 提示词)';

ALTER TABLE ask_message
  ADD COLUMN ctx_note VARCHAR(80) NULL
      COMMENT 'v1.27 · 这一轮带了什么分析上下文 · 托管模式记百炼回显是否确认';
