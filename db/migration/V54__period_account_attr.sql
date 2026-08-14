-- v1.12 FR-350 · 关账时把账户的「分类属性」一并定格
--
-- 为什么要这张表(tech-design/v1.12.md §1):
--   金额侧的事实早就按期落库了(period_snapshot / period_member_cashflow / fx_rate / stock_price_snapshot),
--   但分类侧不是 —— FactMapper.queryBase 是拿**当前**的 account 行和 product_category 行去 join 每一期。
--   于是今天在管理页把一个账户从「货币基金」改成「债基」,去年 12 月的集中度 / 流动性分层 / 大类分布
--   当场跟着变,而 v1.10 对报表页的产品承诺是「已关账的月份是封板快照,不再变」。
--
-- 为什么不加列到 period_snapshot(否决方案 A):
--   那张表只有「用户填了余额」才有行。queryBase 里 COALESCE(ps.end_balance, 沿用最近一期) 就是
--   专门为**没有行**的账户准备的(v0.4.3 B1)。往那张表加列 = 只给填过余额的账户定格,
--   靠沿用出现在报表里的账户照漏 —— 而且漏的恰好是最不常动的账户,最难发现。
--
-- 兼容红线(memory feedback_prod_backward_compat):
--   · 纯新增表 + INSERT,不 ALTER / UPDATE / DROP 任何既有表 → 回滚到 v1.11.3 的 jar 仍可运行
--     (旧代码不认识这张表,忽略即可;表留着不影响任何既有查询)
--   · 列宽与来源列逐一对齐:account.type varchar(10) / account.product_category_code varchar(40) /
--     product_category.display_name varchar(80) / liquidity_class varchar(16) / benchmark_pct decimal(5,2)
--   · 不建外键到 product_category(code):类目可被删,不该连带删掉历史定格行、也不该被历史行挡住删除

CREATE TABLE IF NOT EXISTS period_account_attr (
  period_id             BIGINT       NOT NULL,
  account_id            BIGINT       NOT NULL,
  account_type          VARCHAR(10)  NOT NULL                COMMENT '关账那一刻的 account.type',
  product_category_code VARCHAR(40)  DEFAULT NULL            COMMENT '关账那一刻的 account.product_category_code',
  product_category_name VARCHAR(80)  DEFAULT NULL            COMMENT '当时的类目显示名 · v0.14.1 修过「裸露 code」,类目被删后仍要能显示中文名',
  liquidity_class       VARCHAR(16)  DEFAULT NULL            COMMENT '当时的 product_category.liquidity_class(解析后的值,不是只存 code —— 改类目自己的档位同样会改写历史)',
  benchmark_pct         DECIMAL(5,2) DEFAULT NULL            COMMENT '当时的 product_category.benchmark_pct',
  expected_return_pct   DECIMAL(5,2) DEFAULT NULL            COMMENT '当时的 account.expected_return_pct(账户级预期覆盖)· 见下方「第 5 个属性」',
  source                VARCHAR(8)   NOT NULL                COMMENT 'CLOSE=关账时真定格 / BACKFILL=v1.12 上线时按当天属性回填(不是真定格,诚实标注)',
  sealed_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (period_id, account_id),
  KEY idx_paa_account (account_id),
  CONSTRAINT fk_paa_period  FOREIGN KEY (period_id)  REFERENCES period(id),
  CONSTRAINT fk_paa_account FOREIGN KEY (account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT 'v1.12 · 已关账期的账户分类属性定格(金额早已按期落库,这里补上分类侧)';

-- 「第 5 个属性」expected_return_pct —— 设计时漏了,施工中被正向漂移测试逼出来的。
--
-- PRD FR-350 原本只列 4 个属性(账户类型 / 类目 / 流动性档 / 基准 %),因为设计时只找到两条漂移入口:
--   ① FactMapper.queryBase 裸 join 当前 account / product_category → 封板二区
--   ② ReportsController 的 benchmarkPctByPcCode → 三区「vs 基准」列
-- 施工时把 GOLD 的 benchmark_pct 改成 99 做正向测试,报表页 3M/6M/YTD × 3 币种**仍然在动** ——
-- 差的那两行是三区的**「预实」列**,它根本不吃 ②那张 map,走的是
--   FactViewServiceImpl.expectedReturnByAccount → AccountPerformance.planActualDiffPct
-- 这条第三入口:先读**当前** account.expected_return_pct 覆盖,回落**当前**类目 benchmark_pct。
--
-- 所以定格必须连账户级覆盖一起定格。只定格类目侧 → 用户改一次账户的「预期年化」,
-- 历史每一期的预实当场重算,承诺照样破,而且破在相邻的一列上(基准列不动、预实列动),更难解释。
--
-- 存**原始覆盖值**而不是存解析后的「最终预期 %」:定格的是**输入**,不是**输出**。
-- 解析规则(覆盖优先、回落类目基准)留在 Java 一处,读侧对定格值和当前值用同一套规则 ——
-- 规则本身以后要改,历史不会因为「当年存的是旧规则的结果」而钉死。

-- 回填 = 今天的属性(同下)。列在建表语句后单独 INSERT,列顺序与上面的 CREATE 一致。

-- 回填全部已关账期。
--
-- 这里必须如实说清:回填**只能用今天的属性** —— 「当时的属性是什么」这个信息从来没被记录过。
-- 所以回填出来的历史 = 今天算出来的历史,与现状完全一致。
-- 这一版兑现的是「从此不再变」,不是「修正过去」。source='BACKFILL' 把这件事在数据里标出来。
--
-- 副作用(而且是本版最强的验收手段):既然回填 = 现状,那么建表 + 回填 + 读侧改造之后,
-- 报表页与仪表盘的渲染**必须逐字节不变**。任何差异都是 bug,不是「口径改进」。
-- 见 scripts/metric-baseline.sh 与 tech-design/v1.12.md §7 分水岭。
--
-- INSERT IGNORE + 主键 (period_id, account_id) → 幂等,重跑无副作用。
--
-- 归档过滤 `a.archived_at IS NULL OR a.archived_at > p.period_end` 必须与 FactMapper.queryBase
-- 的写法**逐字一致**(v1.10 的 v110-ARCHIVED-TIME)。两边不一致 → 回填的行集 ≠ 读取的行集
-- → 零差异当场破。护栏 v112-ATTR-BACKFILL-SCOPE 钉住这一条。
INSERT IGNORE INTO period_account_attr
  (period_id, account_id, account_type, product_category_code, product_category_name,
   liquidity_class, benchmark_pct, expected_return_pct, source)
SELECT p.id, a.id, a.type, a.product_category_code, pc.display_name,
       pc.liquidity_class, pc.benchmark_pct, a.expected_return_pct, 'BACKFILL'
  FROM account a
  JOIN period p
    ON p.family_id = a.family_id
   AND p.status = 'CLOSED'
  LEFT JOIN product_category pc
    ON pc.code = a.product_category_code
 WHERE a.archived_at IS NULL OR a.archived_at > p.period_end;
