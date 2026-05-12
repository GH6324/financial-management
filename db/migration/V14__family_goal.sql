-- =========================================================
-- V14 · v0.3 家庭财务目标 · FR-50 系列
-- =========================================================
-- 主表 family_goal · 用单表 + params_json(JSON 字段)承载三类目标
-- 类型差异(RETIREMENT / EDUCATION / EMERGENCY)由 goal_type + params_json 表达
-- 对应 PRD §3.4 / TDD §3.1 · 决策 21(为何不用分表)
-- =========================================================

CREATE TABLE family_goal (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  family_id    BIGINT       NOT NULL,
  goal_type    VARCHAR(16)  NOT NULL
                            COMMENT 'RETIREMENT | EDUCATION | EMERGENCY',
  name         VARCHAR(64)  NOT NULL
                            COMMENT '显示名 · 默认按 type 生成可改',
  target_value DECIMAL(15,2) NULL
                            COMMENT '目标本位币金额 · EMERGENCY NULL(由 params derived)',
  target_date  DATE         NULL
                            COMMENT '目标日期 · EMERGENCY NULL',
  params_json  JSON         NOT NULL
                            COMMENT '类型特定参数 · RETIREMENT:{retire_age,current_age,monthly_expense,inflation_rate,withdrawal_rate} · EDUCATION:{child_member_id,child_birth_year,target_year_offset,target_amount,inflation_rate} · EMERGENCY:{months_target,auto_baseline,fixed_baseline}',
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                            ON UPDATE CURRENT_TIMESTAMP,
  archived_at  TIMESTAMP    NULL
                            COMMENT '软删 · 沿用 v0.2 风格 · 不真删行',
  PRIMARY KEY (id),
  INDEX idx_family_active (family_id, archived_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='家庭财务目标 · v0.3 引入(退休/教育金/应急储备)';
