-- =========================================================
-- V32 · v0.8 · 可配置指标集(PRD FR-149 / 决策 102)
-- =========================================================
-- 用户在管理页勾选「我关心的指标」(家庭级 / 账户级两组),dashboard 与 reports 共用此配置。
-- 存 family 级 JSON:{"family":["net_worth",...],"account":["current_value",...]};
-- NULL = 用代码默认集(FR-150)。仿 allocation_anchor_custom JSON 先例。
-- 旧家庭默认 NULL,零回填零影响。
-- =========================================================
ALTER TABLE family
  ADD COLUMN metric_prefs JSON NULL DEFAULT NULL
    COMMENT 'v0.8 · 指标勾选配置 {"family":[...],"account":[...]};NULL=代码默认集';
