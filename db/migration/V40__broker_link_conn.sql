-- V40 · v0.15.x · 券商连接配置下沉到「关联」颗粒度
-- 背景:用户可能有多个富途账号(每个账号一个 OpenD 实例)→ 每条关联可各配 OpenD host:port。
-- NULL = 沿用全局默认(管理页 ⑥ 的 broker_futu_opend_host/port)→ 现有数据零迁移、向后兼容。
ALTER TABLE broker_link
  ADD COLUMN opend_host VARCHAR(255) NULL AFTER broker_account_id,
  ADD COLUMN opend_port INT NULL AFTER opend_host;
