-- =========================================================
-- V48 · v1.2 · 月度归因复盘 AI 缓存 + 再平衡执行计划(tech-design v1.2 §2/§3)
-- =========================================================
-- backward-compat:纯新增两张表,零改动存量;老版本代码不读不写这些表,回滚 jar 无影响。

-- AI 月度复盘缓存(D5 拍板落库:关账期结果不可变,长期回看;「重新解读」覆盖写)
CREATE TABLE IF NOT EXISTS review_ai_cache (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    family_id  BIGINT       NOT NULL,
    period_id  BIGINT       NOT NULL,
    dim        VARCHAR(20)  NOT NULL DEFAULT 'acct' COMMENT '归因维度 key(acct/assetClass/owner/platform/currency/type)',
    text       TEXT         NOT NULL,
    vendor     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_review (family_id, period_id, dim)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 再平衡计划(一个家庭至多 1 个 ACTIVE · 应用层保证;关账时归档)
CREATE TABLE IF NOT EXISTS rebalance_plan (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    family_id  BIGINT      NOT NULL,
    period_id  BIGINT      NOT NULL COMMENT '创建时所在账期',
    status     VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at  TIMESTAMP   NULL,
    KEY idx_plan_family (family_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 计划条目(仅 账户+金额;永不出现产品/标的 · FR-9 铁律)
CREATE TABLE IF NOT EXISTS rebalance_plan_item (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id              BIGINT        NOT NULL,
    from_account_id      BIGINT        NOT NULL,
    to_account_id        BIGINT        NOT NULL,
    amount_base          DECIMAL(15,2) NOT NULL COMMENT '本位币目标金额',
    note                 VARCHAR(120)  NULL,
    status               VARCHAR(12)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / EXECUTED / MANUAL_DONE',
    executed_transfer_id BIGINT        NULL COMMENT '划转核销回链(EXECUTED 时)',
    executed_at          TIMESTAMP     NULL,
    KEY idx_item_plan (plan_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
