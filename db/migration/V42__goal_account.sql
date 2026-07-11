-- v0.16 · 目标绑定账户(0..N)· 纯新增表,0 行 = 全家口径(存量目标不写此表 → 行为不变)
CREATE TABLE goal_account (
    goal_id    BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    PRIMARY KEY (goal_id, account_id),
    INDEX idx_goal (goal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '目标↔账户 多对多绑定(v0.16)';
