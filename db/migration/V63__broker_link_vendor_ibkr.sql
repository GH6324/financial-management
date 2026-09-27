-- ============================================================================
-- V63 · v1.26 · 券商只读同步加第三家:盈透 IBKR(issue #24)
--
-- broker_link.vendor 在 V39 里带了 CHECK (vendor IN ('FUTU','TIGER')) ——
-- 不放宽它,IBKR 的关联根本存不进去(INSERT 直接被约束拒绝)。
--
-- 只放宽,不改列:VARCHAR(8) 放得下 'IBKR';旧行一个不动。
-- 回滚:把约束改回两值即可 —— 但回滚前必须先解绑所有 IBKR 关联(否则 ADD CONSTRAINT 会失败,
--       而且老代码读到 vendor='IBKR' 的行也映射不回枚举)。见 tech-design/v1.26.md 五.1。
-- ============================================================================
ALTER TABLE broker_link
  DROP CHECK ck_broker_link_vendor,
  ADD CONSTRAINT ck_broker_link_vendor CHECK (vendor IN ('FUTU','TIGER','IBKR'));
