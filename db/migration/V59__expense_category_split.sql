-- =====================================================================
-- v1.21 · 自定义支出分类(第 2 稿)
--
-- 三张全新表 + 给 cash_flow 加三个可空列。
--
-- 中心决定(prd/v1.21.md §0.2):**分类依附在「一笔」上**,不独立成为填报动作。
--   第 1 稿曾建过一张 expense_split(期×人×类目×来源)按月汇总表,
--   配套的界面是填报页上一张 37 格的表单 —— 被否了,原因见 prd §14:
--   「把总数分配到类目里」是人做不来的运算,所有记账软件的分类都是记一笔时顺手选的。
--   那张表从未在 prod 存在过(v1.21 未发布),所以这里【直接改本迁移】而不是补一条 DROP。
--
-- 设计要点(详见 tech-design/v1.21.md §二):
--
--   · **性质 ≠ 消费分类,所以是加列不是改列。**
--     cash_flow.category_code(consumption / loan_payment / interest_paid / to_relatives)
--     是【性质】,决定储蓄率与负债口径(AGENTS.md 联动链 L1);
--     expense_category_id 是【钱花在哪】,只在 category_code='consumption' 时有意义。
--     合并两者会静默把储蓄率算错 —— 那类错误不抛异常,只是数字慢慢不对。
--
--   · **三个新列全部可空**,老 jar 见到它们照常跑(联动链 L7:回滚只回 jar 不回 DB)。
--     既有的 49 条 EXPENSE 流水 expense_category_id 为 NULL = 「未分类」,
--     不影响任何金额、不影响储蓄率。
--
--   · **去重靠 (family, channel, ext_tx_no) 而不是「来源行」。**
--     渠道账单每笔都带交易号,同一份文件导两次不会出双份,而且精确到笔 ——
--     比第 1 稿的「整条通道 DELETE+INSERT」既简单又准。
--     注意这里【故意不建 UNIQUE 约束】:交易号是外部数据,遇到渠道改版/补录/
--     同号复用时,硬约束会让整批导入 500 而不是跳过那一笔。去重在应用层做,
--     DB 只提供索引 —— 「外部标识不做唯一约束」是本项目一贯做法。
--
--   · 「其他」是每家一条【真实行】(system_code='OTHER'),不是用 NULL 表示。
--     理由是硬的:MySQL 的 UNIQUE 对 NULL 不去重(NULL ≠ NULL),
--     用 category_id=NULL 表示「其他」会让同名校验失效。
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

CREATE TABLE IF NOT EXISTS expense_import_batch (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    family_id     BIGINT        NOT NULL,
    period_id     BIGINT        NOT NULL,
    -- 整批落到同一个账户(FR-568)· 第一期不做多账户拆分
    account_id    BIGINT        NOT NULL,
    -- ALIPAY / WECHAT
    channel       VARCHAR(16)   NOT NULL,
    row_count     INT           NOT NULL DEFAULT 0,
    total_amount  DECIMAL(15,2) NOT NULL DEFAULT 0,
    -- 剔除/跳过的笔数,留作审计:「导入总额比账单少」时能解释清楚(FR-561)
    dropped_count INT           NOT NULL DEFAULT 0,
    skipped_count INT           NOT NULL DEFAULT 0,
    imported_by   BIGINT            NULL,
    imported_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    -- 整批撤销 = 软删该批次 + 软删它落的流水(FR-539)
    revoked_at    DATETIME(3)       NULL,
    PRIMARY KEY (id),
    KEY idx_batch_scope (family_id, period_id, channel),
    KEY idx_batch_period (period_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.21 导入批次(本身就是审计日志:渠道/笔数/金额/剔除数)';

CREATE TABLE IF NOT EXISTS expense_merchant_rule (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    family_id   BIGINT      NOT NULL,
    -- 商户名/商品说明里的关键字。微信账单【没有消费分类列】,只能靠这个映射;
    -- 用户在确认页改一笔并勾「记住」就写一条 —— 越用越准(FR-567)。
    keyword     VARCHAR(40) NOT NULL,
    category_id BIGINT      NOT NULL,
    hit_count   INT         NOT NULL DEFAULT 0,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule (family_id, keyword),
    KEY idx_rule_family (family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.21 商户关键字 → 类目(配一次管一年)';

CREATE TABLE IF NOT EXISTS expense_account_rule (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    family_id   BIGINT      NOT NULL,
    -- 账单里【资金来源】那一列的关键字:余额宝 / 花呗 / 招商银行储蓄卡(1234) ……
    -- 和商户规则分开存:一个回答「这笔是什么消费」,一个回答「这笔从哪个账户出的」,
    -- 键空间也不一样。塞一张表要加 kind 列,不如两张小表好读。
    keyword     VARCHAR(40) NOT NULL,
    account_id  BIGINT      NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_acct_rule (family_id, keyword),
    KEY idx_acct_rule_family (family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.21 资金来源关键字 → 账户(一份账单里可能有好几个账户)';

-- ── cash_flow 加三列 ────────────────────────────────────────────────
-- 全部可空 + 纯新增,既有 377 行一个字节不动。
--
-- 【为什么不加外键到 expense_category】:删类目时我们要自己控制搬家语义
-- (删二级 → 转父级;删一级 → 转「其他」,见 FR-505),外键的 RESTRICT/CASCADE
-- 两种行为都不是我们要的,而且会让删除路径在 DB 层报错而不是给出人话提示。

ALTER TABLE cash_flow
    ADD COLUMN expense_category_id BIGINT NULL COMMENT 'v1.21 消费分类(只在 category_code=consumption 时有意义;NULL=未分类)',
    ADD COLUMN import_batch_id     BIGINT NULL COMMENT 'v1.21 来自哪个导入批次;NULL=手工录入',
    ADD COLUMN ext_tx_no           VARCHAR(64) NULL COMMENT 'v1.21 渠道交易号,用于重复导入去重(不做唯一约束,见文件头)',
    -- v1.21 · 这笔要不要参与【该账户】的余额解释。默认 1 = 老行为,既有 377 行语义不变。
    --
    -- 为什么需要它:recordExpense 会调 applyDeltaToBalance,而那个方法【直接改写
    -- period_snapshot.end_balance】—— 也就是用户自己填的期末余额本身,不是什么预填值。
    -- 于是「先核对完余额、再导入账单」的人会被扣第二遍:余额已经反映了这次消费,
    -- 导入又扣一次。整批导入几百笔时这个错很大而且很难看出来。
    --
    -- 语义(两条口径分开走,别合并):
    --   affects_balance = 1 → 扣余额 + 参与该账户轧差 + 计入账户外部流出(口径 B · NAV/XIRR)
    --   affects_balance = 0 → 【只回答「家里花了多少、花在哪」】:
    --                         计入家庭消费(口径 A)与支出构成,
    --                         但不动余额、不参与轧差、不算该账户的资金流出。
    -- 后半句必须成立:余额没动却报一笔流出,NAV 会以为「钱是被取走的不是亏掉的」,
    -- 把账户收益率算高 —— 而这不会报错。
    ADD COLUMN affects_balance TINYINT(1) NOT NULL DEFAULT 1
        COMMENT 'v1.21 是否参与该账户的余额/轧差/外部流;0=只记家庭消费与构成';

-- 报表按分类聚合走这条(period + 分类),导入去重走 ext_tx_no 那条
CREATE INDEX idx_cf_expcat ON cash_flow (period_id, expense_category_id);
CREATE INDEX idx_cf_exttx  ON cash_flow (ext_tx_no);
CREATE INDEX idx_cf_batch  ON cash_flow (import_batch_id);
