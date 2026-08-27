-- =====================================================================
-- v1.19 · 问一问(资产对话)
--
-- 七张【全新】表,不改任何既有表 —— 老代码在新库上完全正常,回滚后空表无副作用。
--
-- 设计要点(详见 tech-design/v1.19.md):
--   · 引用块单独建表,不把渲染好的 Markdown 存进正文 ——
--     存 Markdown 等于把口径冻结成一段文字,页面口径改了历史对话就对不上,
--     而且没人会发现。结构化存之后,口径文案在【渲染期】生成,与页面共用一份。
--   · 工具调用只存摘要,不存返回体 —— 返回体就是家底数据,存两份只增加泄漏面。
--   · 凭据只存 SHA-256 hash,明文永不入库。
--     为什么不是 bcrypt/argon2:慢哈希是为了拖慢【低熵口令】的暴力破解,
--     而这里是 32 字节 CSPRNG 随机数(256 bit 熵),拖库者要穷举 2^256 —— 慢哈希帮不上忙,
--     却让每次调用多花几百毫秒。这是个会被公网扫的端点,等于自带 DoS 放大器。
--     所以用 SHA-256 + 【hash 列唯一索引】,一次等值查找命中。
--     token_prefix 只用于审计展示与识别(以及被贴进公开仓库时的 secret scanning 告警)。
--   · 审计不记返回体、不记金额。
--
-- 向后兼容(prod 已上线):纯 CREATE TABLE,零风险。
-- =====================================================================

-- ① 一段对话
CREATE TABLE ask_conversation (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    family_id       BIGINT        NOT NULL,
    title           VARCHAR(64)   NOT NULL                COMMENT '取首条提问前 20 字',
    provider_ref    VARCHAR(128)  NULL                    COMMENT '云端 session / response id',
    ctx_period_id   BIGINT        NULL                    COMMENT '上下文账期',
    ctx_currency    VARCHAR(8)    NULL                    COMMENT '上下文视图币种',
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    archived_at     DATETIME(3)   NULL,
    CONSTRAINT pk_ask_conversation PRIMARY KEY (id),
    KEY idx_ask_conv_fam_created (family_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='v1.19 问一问 · 会话';

-- ② 一条消息(正文含 {{cite:id}} 标记,不含渲染后的数字)
CREATE TABLE ask_message (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id  BIGINT       NOT NULL,
    role             VARCHAR(16)  NOT NULL                COMMENT 'user | assistant | system_note',
    content_text     MEDIUMTEXT   NOT NULL                COMMENT '含 {{cite:xx}} 标记',
    seq              INT          NOT NULL                COMMENT '会话内序号',
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_ask_message PRIMARY KEY (id),
    KEY idx_ask_msg_conv_seq (conversation_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='v1.19 问一问 · 消息';

-- ③ 引用块 —— 本设计的关键表:模型不写数字,只写标记,数值在这里
CREATE TABLE ask_citation (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    message_id   BIGINT       NOT NULL,
    cite_key     VARCHAR(16)  NOT NULL                    COMMENT '与正文 {{cite:c1}} 对应',
    metric_key   VARCHAR(64)  NOT NULL                    COMMENT '口径标识,渲染期据此取文案',
    -- label 必须存:它常常是**数据派生**的(「支付宝 · 总资产」里的行名来自用户自己的账户名),
    -- 从 metric_key 推不出来。不存的话,重新打开这段对话就只剩 lens.pivot.value 这种技术串。
    label        VARCHAR(64)  NULL                        COMMENT '给用户看的名字(数据派生,推不出来)',
    period_id    BIGINT       NULL                        COMMENT '这个数字属于哪一期',
    in_progress  TINYINT(1)   NOT NULL DEFAULT 0          COMMENT '该期是否进行中',
    value_text   VARCHAR(32)  NOT NULL                    COMMENT '工具返回的原值(已格式化)',
    currency     VARCHAR(8)   NULL,
    target_href  VARCHAR(255) NULL                        COMMENT '点回哪一页',
    CONSTRAINT pk_ask_citation PRIMARY KEY (id),
    KEY idx_ask_cite_msg (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='v1.19 问一问 · 引用块(数字保真的载体)';

-- ④ 工具调用摘要(不存返回体)
CREATE TABLE ask_tool_call (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    message_id   BIGINT        NOT NULL,
    tool_name    VARCHAR(64)   NOT NULL,
    args_json    VARCHAR(1024) NULL,
    duration_ms  INT           NOT NULL DEFAULT 0,
    ok           TINYINT(1)    NOT NULL DEFAULT 1,
    CONSTRAINT pk_ask_tool_call PRIMARY KEY (id),
    KEY idx_ask_tool_msg (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='v1.19 问一问 · 工具调用摘要';

-- ⑤ 接入凭据
--    access_point_id:同一接入点的多把密钥指向同一个 id(换绑期间该 id 下有两行)
--    superseded_by  :旧密钥指向新密钥;新密钥首次被使用时据此吊销旧的
CREATE TABLE ask_access_token (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    family_id        BIGINT       NOT NULL,
    access_point_id  BIGINT       NOT NULL                COMMENT '接入点 id;换绑期间同一 id 下有两行',
    name             VARCHAR(64)  NOT NULL                COMMENT '如「百炼-家庭助手」',
    token_hash       VARCHAR(64)  NOT NULL                COMMENT 'SHA-256 hex;明文永不入库',
    token_prefix     VARCHAR(16)  NOT NULL                COMMENT 'fmk_ + 8 位;命中候选行与审计展示',
    scope            VARCHAR(16)  NOT NULL DEFAULT 'aggregate' COMMENT 'aggregate | detail',
    expires_at       DATETIME(3)  NOT NULL,
    superseded_by    BIGINT       NULL                    COMMENT '旧密钥指向新密钥',
    revoked_at       DATETIME(3)  NULL,
    last_used_at     DATETIME(3)  NULL                    COMMENT '异步节流更新',
    first_used_at    DATETIME(3)  NULL                    COMMENT '首次使用即通知,只通知一次',
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_ask_access_token PRIMARY KEY (id),
    CONSTRAINT uk_ask_token_hash   UNIQUE (token_hash),
    CONSTRAINT uk_ask_token_prefix UNIQUE (token_prefix),
    KEY idx_ask_token_fam (family_id),
    KEY idx_ask_token_point (access_point_id),
    KEY idx_ask_token_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='v1.19 问一问 · 接入凭据(只存 hash)';

-- ⑥ 调用审计(不记返回体、不记金额)
CREATE TABLE ask_access_audit (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    family_id     BIGINT       NOT NULL,
    token_prefix  VARCHAR(16)  NOT NULL                   COMMENT '不存 token 本身',
    tool_name     VARCHAR(64)  NULL,
    result        VARCHAR(16)  NOT NULL                   COMMENT 'OK|OK_NEW|OK_OLD|INVALID|EXPIRED|REVOKED|SCOPE|RATE',
    src_ip        VARCHAR(64)  NULL,
    user_agent    VARCHAR(128) NULL,
    duration_ms   INT          NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_ask_access_audit PRIMARY KEY (id),
    KEY idx_ask_audit_prefix_time (token_prefix, created_at),
    KEY idx_ask_audit_fam_time (family_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='v1.19 问一问 · 接口调用审计';

-- ⑦ agent 够不着时的反馈 —— 变成下一版加接口的产品输入
CREATE TABLE ask_unmet_need (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    family_id   BIGINT       NOT NULL,
    question    VARCHAR(512) NOT NULL                     COMMENT '用户原问题',
    needed      VARCHAR(512) NULL                         COMMENT 'agent 说它需要什么能力',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_ask_unmet_need PRIMARY KEY (id),
    KEY idx_ask_unmet_fam (family_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='v1.19 问一问 · 能力缺口反馈';
