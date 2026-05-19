-- =========================================================
-- V26 · v0.4.18 · 系统级配置沉淀管理页(2026-05-19)
-- =========================================================
-- 背景:LLM API key / 股票拉取开关 / cron 时段 / checkup 阈值 等运营参数
-- 之前散在 /etc/finance.env + application.yml + 代码常量,改了要重启。
-- 本迭代统一沉淀到本表 + 管理页可编辑 + 实时生效(动态 cron + 5s cache)。
--
-- 通用 K-V 表 · 单 family 当前模式下 family_id 全为 1。
-- 字段类型校验在 application service 层(FamilyConfigService),DB 不强校验。
--
-- backward-compat 红线:
--   · ADD COLUMN DEFAULT 类操作 0 项;只新建一张表 → 老 family 无行 = 业务读 env @Value fallback → 行为完全等价升级前
--   · LLM key 写入此表时是明文(同 family_notify_config 的 sms aksk 设计 · MySQL DB 权限严控)
--   · 该表 LLM key 字段绝不进 LLM prompt / audit_log 明文 / 前端明文回显
--
-- 详见 prd/v0.4.md §22。
-- =========================================================

CREATE TABLE family_runtime_config (
  family_id   BIGINT       NOT NULL,
  key_name    VARCHAR(80)  NOT NULL,
  value_text  VARCHAR(512) NOT NULL DEFAULT '',
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                            ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (family_id, key_name),
  CONSTRAINT fk_runtime_cfg_family FOREIGN KEY (family_id) REFERENCES family(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
