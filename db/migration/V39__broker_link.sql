-- =========================================================
-- V39 · 券商只读同步(富途/老虎)· 账户↔券商绑定 + 持仓同步来源
-- =========================================================
-- 目标:
--   1) broker_link:一个账房账户 ↔ 一个券商交易账户(1:1)+ 同步元数据
--   2) stock_holding.sync_source:标记持仓来自哪家券商同步(手填为 NULL)
--      → reconcile 只动带标记的行,绝不碰用户手填持仓
--
-- backward-compat:纯新增表 + 新增可空列,对线上现有数据零影响
-- 只读红线:本表只记"绑定与同步状态",不存任何交易凭据(凭据走 family_runtime_config 私密)
-- =========================================================

CREATE TABLE broker_link (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    account_id        BIGINT       NOT NULL,
    vendor            VARCHAR(8)   NOT NULL,               -- FUTU / TIGER
    broker_account_id VARCHAR(64)  NULL,                   -- 券商侧交易账户号(多账户时定位)
    enabled           TINYINT(1)   NOT NULL DEFAULT 1,
    last_synced_at    DATETIME(3)  NULL,
    last_status       VARCHAR(255) NULL,                   -- 上次同步结果摘要
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_broker_link PRIMARY KEY (id),
    CONSTRAINT uq_broker_link_account UNIQUE (account_id),
    CONSTRAINT ck_broker_link_vendor CHECK (vendor IN ('FUTU','TIGER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE stock_holding
    ADD COLUMN sync_source VARCHAR(8) NULL
        COMMENT '券商同步来源:FUTU/TIGER;手填持仓为 NULL(v0.15)';
