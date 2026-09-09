-- =====================================================================
-- v1.21 · 自定义支出分类 + 来源行 + 导入批次
--
-- 四张【全新】表,不改任何既有表 —— 老代码在新库上完全正常,回滚后空表无副作用。
-- `cash_flow_category`(全局表 · code 主键 · 无 family_id)一行不动。
--
-- 设计要点(详见 tech-design/v1.21.md §二):
--
--   · 分类是【月度总额的展开】,不是第三种记账方式。
--     Σ(expense_split) 同事务写回 period_member_cashflow.total_expense_input,
--     于是 ExpenseLedgerService(家庭支出唯一口径入口)【零改动】——
--     这不是靠测试保证的,是结构上必然的。
--
--   · expense_split 的键是【期 × 人 × 类目 × 来源】。
--     「来源」把手填与各渠道导入分开存,于是:
--       重导某渠道 = 按(期,人,渠道)DELETE+INSERT → 天然只动该渠道,
--       用户的手工修正永远不被导入冲掉(v1.21 FR-538)。
--     若只存合成额,重导时根本拆不出渠道份额。
--
--   · 「其他」是每家一条【真实行】(system_code='OTHER'),不是用 NULL 表示。
--     理由是硬的:MySQL 的 UNIQUE 对 NULL 不去重(NULL ≠ NULL),
--     用 category_id=NULL 表示「其他」会让同一格插出多条手填行,两级恒等式当场炸。
--
--   · 类目树用单表自引用,层级【封顶两层】(parent 的 parent 必为 NULL),
--     由应用层校验 + 护栏 v1210-TWO-LEVELS-MAX 共同保证。
--     不用物化路径:那样改名要级联改 path,而「名字是展示属性、不是数据身份」是本版明文要求。
-- =====================================================================

CREATE TABLE IF NOT EXISTS expense_category (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    family_id   BIGINT       NOT NULL,
    -- NULL = 一级(大类);非 NULL = 挂在该大类下的二级(细类)
    parent_id   BIGINT           NULL,
    name        VARCHAR(24)  NOT NULL,
    -- 'OTHER' = 每家一条兜底项,不可删 / 不可停用 / 不可改名
    system_code VARCHAR(16)      NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    archived_at DATETIME(3)      NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 同层同名拒绝。注意:parent_id 为 NULL 的行不受此约束保护(NULL 不去重),
    -- 所以【一级同名靠应用层校验】—— 这一点写在 ExpenseCategoryService 里,别指望 DB。
    UNIQUE KEY uk_expcat_name (family_id, parent_id, name),
    KEY idx_expcat_family (family_id),
    KEY idx_expcat_parent (parent_id),
    CONSTRAINT fk_expcat_family FOREIGN KEY (family_id) REFERENCES family (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.21 支出类目(一棵两层树 · 家庭级)';

CREATE TABLE IF NOT EXISTS expense_split (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    family_id   BIGINT        NOT NULL,
    period_id   BIGINT        NOT NULL,
    member_id   BIGINT        NOT NULL,
    category_id BIGINT        NOT NULL,
    -- MANUAL / ALIPAY / WECHAT / SHOT
    source      VARCHAR(16)   NOT NULL,
    -- 【只有 MANUAL 允许为负】—— 那是对导入值的冲正(FR-538)。
    -- 类目合成额(Σ来源行)不可为负,由服务层校验,不在这里约束。
    amount      DECIMAL(15,2) NOT NULL,
    batch_id    BIGINT            NULL,
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 【本迁移最重要的一行】一格一来源最多一行 —— 两级恒等式的地基
    UNIQUE KEY uk_split (period_id, member_id, category_id, source),
    KEY idx_split_period (period_id, member_id),
    KEY idx_split_batch (batch_id),
    KEY idx_split_cat (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.21 分类金额的来源行(期×人×类目×来源)';

CREATE TABLE IF NOT EXISTS expense_import_batch (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    family_id     BIGINT        NOT NULL,
    period_id     BIGINT        NOT NULL,
    member_id     BIGINT        NOT NULL,
    -- ALIPAY / WECHAT / SHOT
    channel       VARCHAR(16)   NOT NULL,
    row_count     INT           NOT NULL DEFAULT 0,
    total_amount  DECIMAL(15,2) NOT NULL DEFAULT 0,
    -- 被本批次替换掉的上一批(同期同人同渠道)· 形成可追溯的替换链
    replaced_id   BIGINT            NULL,
    imported_by   BIGINT            NULL,
    imported_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at    DATETIME(3)       NULL,
    PRIMARY KEY (id),
    KEY idx_batch_scope (family_id, period_id, member_id, channel),
    KEY idx_batch_replaced (replaced_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.21 导入批次(本身就是审计日志:渠道/行数/金额/替换链)';

CREATE TABLE IF NOT EXISTS expense_merchant_rule (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    family_id   BIGINT      NOT NULL,
    -- 商户名/商品说明里的关键字。微信账单【没有消费分类列】,只能靠这个映射。
    keyword     VARCHAR(40) NOT NULL,
    category_id BIGINT      NOT NULL,
    hit_count   INT         NOT NULL DEFAULT 0,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule (family_id, keyword),
    KEY idx_rule_family (family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.21 商户关键字 → 类目(配一次管一年)';
