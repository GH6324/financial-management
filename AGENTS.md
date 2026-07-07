# AGENTS.md · 联动不变量登记表(改代码前必读)

> 目的:本项目有若干**横切联动链** —— 改了 A,必须同步改 B/C/D,否则某个下游模块静默出错(历史上反复踩:收入口径、币种、长文目录、主页数字带…)。
> **规则**:
> 1. 动某功能前,先在下表找**相关链**,按"必须同步"列**一次改全**。
> 2. 完成前**回扫本表**确认没漏。发现新链 → **加一行**。
> 3. 能机器校验的,**必须配 `scripts/qa-run.sh` 守护**(guard id 见"守护"列)。文档靠人记会漏,**守护才是兜底**;没 guard 的标 `TODO`,是待补的洞。
> 4. 面向用户的验收走**用户点击路径 + `scripts/e2e.sh`**(唤起 beta 调接口 + DB 真值),别只 UT/grep。

## 联动链

| # | 触发(改了什么) | 必须同步(否则下游静默错) | 守护 |
|---|---|---|---|
| L1 · 收入/支出口径 | 改收入或支出的**来源口径**(PMC「2框」↔ account `cash_flow` 汇总)、`is_adjustment` 语义 | `FactViewServiceImpl`(`netInflowIncome`/`netInflowExpense`/`cashflowBreakdown`/`familyXirr` 外部流入)· `HouseholdCashflowService`(`incomeBlend`/月均/储蓄率)· **dashboard `CashflowSplitView.empty()`**(空态判定,别只看 PMC filledMembers)· `reports/_savings` 空态 · 收入列表展示/合计 | `v12-INCOME-KOUJING` · `v12-INCOME-EMPTY` · `CashflowSplitEmptyTest` · `CashflowBreakdownTest` |
| L2 · 币种:账户币种 vs 本位币 | 任何**展示/汇总** `cash_flow.amount` 或 `period_snapshot.end_balance` 的地方 | 这两者存的是**账户币种**;单账户展示用账户币种符号(`MoneyFormat`);**家庭/跨账户聚合必须 ×`fxToBase` 换本位币**,不能裸加 ¥;缺直接汇率时反向取倒数兜底(见 `EntryController.toBaseAmount`/`AccountValuationService.resolveFxRate`) | `v12-INCOME-FX` |
| L3 · 比值类 KPI 币种不变性 | 新增/改**比例类**指标(XIRR、储蓄率、占比…) | 三视图币种下**比值必须完全相等**、金额按 fx 缩放;比值相比要用**相减(pp)**不是相除 | `v05-CCY-INV-1` · `v08-CCY-INV-3` · `CurrencyInvarianceTest` |
| L4 · 长文目录 TOC | `reports`/`dashboard`/`checkup` 加/改/删/调序 **section** | 同步该页 `fragments/_toc` + `static/js/toc.js` + section 的 `id` 锚点,否则锚点失效/漏节 | `v05-TOC-1/2/3` |
| L5 · 主页数字带 + 文档 | 出新版本 / 加迁移 / 改单测数或黑盒数 | `landing.html` 的 `data-stat`(version=prd 个数 · migrations=`V*.sql` 个数 · tests/blackbox=README)+ README「N 单元 / N 黑盒」+ `prd/`+`tech-design/`+`CHANGELOG`+`docs/qa-cases` | release preflight(landing/README 硬门)· `v09-LAND-6` · `v07-CLEAN-2` |
| L6 · 股票估值/持仓模型 | 改 `stock_holding` 字段/`ValuationMode`/持仓计值 | `AccountValuationService.valuateInternal`(AUTO/MANUAL/CASH 分支)· 迁移 backward-compat(prod 老数据折算总值不变)· 持仓管理 UI + 收入侧联动 | `v12-MANUAL-SHARES` · `v03-STOCK-*` |
| L7 · prod 已上线 backward-compat | 任何 schema/代码/部署改动 | 先想对线上现有数据影响:迁移只 `ADD COLUMN NULL`/数据折算不破坏;回滚只回 jar 不回 DB → 迁移须向前兼容老 jar | release preflight 迁移提示 |
| L8 · UI 规范 | 新增任何 UI 文案/图标 | **不用 emoji**,用 inline SVG(Feather 24×24 stroke=currentColor);入口/按钮命名**避免技术词**(集成/API/接口),让非技术家庭成员看得懂 | `no-emoji` grep(TODO 补 guard) |
| L9 · 功能级流程 | 做**新功能** | PRD+preview → 用户评审 → TDD → 用户评审 → 才写代码+QA;每步硬 gate 停等审;tag/push/发 prod 须用户 `release vX.Y.Z` 精确确认 | 人工 gate |

## 怎么加新链
发现"改 A 时漏了 B"这类事故 → 在上表加一行(触发/必须同步/守护),并在 `scripts/qa-run.sh` 加一个静态 grep 守护把它网住(参考现有 `v12-*` 写法),这样下次它会自己 fail。
