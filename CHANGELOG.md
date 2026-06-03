# Changelog

按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格记录。每个版本详细需求见对应 [`prd/v0.X.md`](prd/),技术设计见 [`tech-design/v0.X.md`](tech-design/),QA case 见 [`docs/qa-cases.md`](docs/qa-cases.md)。

## [v0.5.4] · 2026-06-03

目标 AI 月报三处修复(无迁移)。

### Fixed

- **AI 月报脱敏未回写 → 出现「成员A与成员B」** · `GoalLlmService.generateMonthlyReport` / `generateAlertAdvice` 校验通过后**漏做反向映射**(v0.3 起 latent)· 月报里直接显代号 · 修:校验仍跑在代号 raw 上(不因真名误判泄露),通过后 `PromptBuilder.reverseMapping(raw, codenameToReal)` 把 成员A/成员B 还原回真名供阅读(与 checkup AI 诊断同口径)(v0.5.4)

### Changed

- **目标月报缓存语义对齐 checkup** · 月报本就持久化在 `goal_ai_report`(UNIQUE upsert)且详情页直接复用(加载不重算 = 缓存命中)· 本次补齐 UI:有月报时显「本期复用 · 渲染于…」+「重新生成」按钮(同 checkup ↻ force-refresh 语义),不再只有"尚未生成"态才有触发入口(v0.5.4)
- **目标月报内容保留换行** · `whitespace-pre-line` 渲染多段叙事(v0.5.4)

### Added

- **仪表盘目标条带「AI 阅读总结」小入口** · `goals/_progress-strip` 每个目标卡右侧加 inline-SVG 入口(book-open + AI)· 直达 `/goals/{id}#ai-report` 本期月报锚点 · detail 页 AI 月报段加 `id=ai-report` + `scroll-margin`(v0.5.4)
- **AI 月报段 emoji → inline SVG** · ✨ 换 Feather book-open(承 [[feedback_no_emoji]])(v0.5.4)

### Tests

- 单元 **228**(+2 `GoalLlmServiceTest`:代号→真名回写守护 + 无成员原样返回)· 锁死「月报展示前必反向映射」回归

## [v0.5.3] · 2026-06-03

计算指标透明化 —— ⓘ tooltip 从「只讲口径公式」升级为「口径 + 真实计算数值」(无迁移)。

### Changed

- **ⓘ tooltip 显示真实计算数值** · 全站 28 个计算型 KPI 的 ⓘ 面板,在口径文字下方加一条**真实实算行**(虚线分隔 + 等宽字体)· 例:紧急储备「流动资产 ¥X ÷ 月均支出 ¥Y = Z 月」、月结收入「近 12 月有填 N 个月 · 收入合计 ¥S ÷ N = ¥avg」、本月资产收益「(期末 ¥E − 期初 ¥B − 净流入 ¥I) ÷ 期初 ¥B = +x%」、钱赚 PnL「(期末 ¥E − 起始 ¥B) − 净流入 ¥I = ¥」· 数值与页面 KPI 同源同币种(dashboard/reports KPI 区 viewCurrency · checkup 与储蓄区本位币)(v0.5.3)
- **XIRR / TWR 诚实口径** · 迭代/几何解无法写成单条算式 → 只展示**真实输入端点 + 解得值**(如「期初净资产 −¥B → 期末 +¥E · N 期求解年化 = x%」),不伪造算术步骤(v0.5.3)

### Added

- **`_kpi-info` 片段升级 `i(text)` → `i(text, calc)`** · 第二参可 null(纯定义指标如账户级 XIRR 0% 成因仍只显口径)· `.kpi-info-calc` CSS(虚线分隔 · 等宽 · pre-line 多行分项)(v0.5.3)
- **`MetricExplainService`** · 纯展示层 · 把 FactView / HouseholdCashflow 已算好的中间量格式化成串 · 不做任何业务计算(数值必与页面 KPI 一致)· `KpiSnapshot` 加 4 个透明化中间量(liquidAssets / avgExpense / prevNetWorth / lastNetInflow · 原本算完即弃)(v0.5.3)

### Tests

- 单元 **226**(+8 `MetricExplainServiceTest`:格式化口径 / 三页 calc 自洽 / 钱赚分解恒等式 / 缺数据降级)· qa-run 加 v05-CALC-1~3(dashboard/reports/checkup ⓘ 含 `.kpi-info-calc` 真实数值守护 · 用恒有值的净资产/钱赚分解断言)

## [v0.5.2] · 2026-06-03

目标模块两个 bug 修复(无迁移)。

### Fixed

- **目标编辑页丢失支出口径** · `/goals/{id}/edit` 缺「固定值 / 自动适配月结支出」单选(create 表单有、edit 没有)→ 编辑退休目标时该选项消失。edit.html 补 `expenseMode` + 窗口 + 平滑,从已保存 params 回填(v0.5.2)
- **AI 综合月报永远"尚未生成"** · 月报原只在周期关闭时自动生成,新建目标 / 当前 OPEN 期无法触发。加 `GoalReportService.generateNow`(period_id=0 按需 · upsert 幂等)+ `POST /goals/{id}/report/generate` + 详情页「立即生成月报」按钮(v0.5.2)

### Tests

- qa-cases 加 bf-GOAL-EDIT-1~4 + bf-GOAL-RPT-1~4 回归 case

## [v0.5.1] · 2026-06-02

v0.5 上线后修复 + 体验完善。

### Fixed

- **比值类指标切币种被错算** · 紧急储备月数 / 本月资产收益% 等比值在切到非本位币时漂移 · 根因 `endBalanceBase` 实为 viewCurrency 口径而 PMC(月支出/净流入)按本位币原样存未换算 · 修:`FactViewServiceImpl.baseToViewFactor`(从 slice 本位币账户 fxToBase 自取)· PMC 路径 ×因子 · 比值币种无关(v0.5.1)
- **周期管理无分页** · beta 测试数据账期到 2032、当前期翻不到 · `/admin/periods` 加分页(24/页 倒序)(v0.5.1)
- **reports 净流入显示 0** · `anchorPeriod` 改优先 `findCurrentOpen`(原 `findLatest` 锚到未来测试期)(v0.5.1)

### Changed

- **CPI/M2 水位线文案** · 保命线→**购买力线** · 地位线→**社会财富线**(v0.5.1)
- **tooltip 补充** · 家庭 XIRR「vs 基准」来源说明(类目长期年化基准余额加权)· 人赚净流入口径 · 账户级 XIRR 0% 成因(v0.5.1)
- **reports 移动端浮动目录** · 右下角 FAB 不挡内容 · tap 跳转 section(v0.5.1)

### Tests

- 单元 **218**(+ BenchmarkAverage / WaterLevel / NetInflow 恒等式 / StockHoldingCashLink / FireAutoExpense / CurrencyInvariantRatio)· qa-run 319 PASS + v05-CCY-INV 黑盒守护

## [v0.5] · 2026-06-01

财富水位(资产 vs CPI/M2 双基准)+ 股票现金联动 + FIRE 支出自适应 + 净流入双源修复。详 [`prd/v0.5.md`](prd/v0.5.md) / [`tech-design/v0.5.md`](tech-design/v0.5.md)。

### Added

- **财富水位 · 并入 `/reports`**(FR-72/73/74)— 净资产 vs **CPI 保命线**(购买力)+ **M2 地位线**(社会财富份额)三线图 · 真实收益/相对社会收益 KPI · 人赚/钱赚分解诊断("水位靠收入硬撑")· 推导透明面板(三法均值)· 不新起 tab(v0.5)
- **宏观基准底座**(FR-70/71)— `macro_benchmark` 表 + 1990-2025 CPI/M2 历史 seed · 几何均值 / **剔极端值几何**(默认,防 1994=24.1% 失真)/ 近 10 年 三法推导 · `/admin/integrations` 宏观段可校正(v0.5)
- **收支趋势图**(FR-85)— `/reports` 储蓄区加收入线/支出线/储蓄填充 · 人赚引擎走势 · PMC 源(v0.5)
- **dashboard CPI 线升级 + M2 线**(FR-75)— 硬编码 2% → 真实剔极端 CPI · 加 M2 地位线参考(v0.5)
- **股票账户现金联动**(FR-78/79)— 录 AUTO 持仓可选「从账户现金划转买入」· 买入扣现金(FX 换算 · 成本必填 · 可负)· 归档按市价对称加回 · `cash_linked` 标记保向后兼容(v0.5)
- **FIRE 目标支出自适应**(FR-81/82/83)— 退休目标月支出可选「自动适配月结支出」· 周期关闭按近 N 月真实支出滚动重算(剔极端/中位/均值 · 空期排除 · 不足回退)· 派生值回写使下游零改动(v0.5)

### Fixed

- **净流入(人赚的)双源 bug**(FR-84)— `principalVsReturnDecomposition` + `netInflowForPeriod` 改 **PMC 优先 · cash_flow 回退**(承 v0.4.3 B2)· 修 prod「人赚·净流入显示 0」· 钱赚改 ΔNW − 人赚 · 守 **人赚 + 钱赚 = ΔNetWorth** 恒等式(v0.5)

### Migration

- **V27** `macro_benchmark`(ADD TABLE + 1990-2025 seed)· **V28** `stock_holding.cash_linked`(ADD COLUMN DEFAULT 0)· 全向后兼容 0 风险(v0.5)

### Tests

- 单元 170 → **215**(+45):BenchmarkAverage / WaterLevelCalculator / NetInflowDecomposition(恒等式)/ StockHoldingCashLink / FireAutoExpense · qa-run **319 PASS**(同 v0.4 基线)

## [v0.4.23] · 2026-05-20

**admin hub 补全 + 集成改名** · v0.4 给管理后台加了 4 项新页(集成 / 提醒 v0.4.14 升级 / 数值阈值 v0.4.18 升级 / 各种 KPI),但 `/admin` 总览页(hub)tile 列表和文案没同步,这次一次性对齐。

### Changed

- **「集成」→「数据源接入」** · sidebar item + `/admin/integrations` 页标题 / eyebrow / h1 全部改名 · 让非技术家庭成员能看懂(原工程师术语「集成」)· URL `/admin/integrations` 路径不动(v0.4.23 · 见 [[feedback_user_friendly_naming]])
- **`/admin` hub 补「数据源接入」tile** · 之前 hub 12 个 tile 漏了这条,sidebar 14 项 hub 才 12 项,从 hub 进不去 v0.4.18 加的页(v0.4.23)
- **`/admin` hub 三处文案过时刷新**:
  - **提醒 tile**:「站内提醒条 · 每日触发时间」→「填报模板 · 阿里云短信 · 截止前强提醒 · 成员手机号」(v0.4.14 加的能力补齐)
  - **数值阈值 tile**:「智能提示 / LOAN 异常 / 自动归类」→「智能转账阈值 · 体检 4 阈值(集中度 / 高风险 / 流动性 / 应急金)· 会话有效期」(v0.4.18 加的 5 项补齐)
  - **备份 tile**:「最近 8 次备份状态」→「每周日自动备份 · 最近 8 次状态 · 一键恢复」(v0.4.20 fix 后周备份确实跑 · 文案补节奏)
- **`/admin` hub KPI · 汇率服务**:「exchangerate.host」→「frankfurter.dev」(实际用的就是这个 · 老文案错的)
- **hub 副标题列表补全**:原「家庭、成员、周期、提醒、汇率、备份」→「家庭、成员、周期、提醒、汇率、数据源接入、数值阈值、备份、审计」(v0.4.23)

## [v0.4.22] · 2026-05-20

**UX 改进** · /entry 顶部一键拉取股价按钮 · 让 v0.4.21 修好的 cron 链路也能被用户即时触发,不必再翻进 `/accounts/{id}/holdings` 才能 ↻ 刷新。

### Added

- **/entry 顶部「刷新股票估值」按钮**(Feather refresh-cw + loader 双 SVG · 旋转 indicator)· tab bar 右侧 · 仅家庭有 STOCK 账户时显示 · HTMX POST 不刷整页 · 顶部出现 toast「3 市场估值已刷新 · N 账户」(成功/check)、「仅 N/3 市场...」(警告/warn)、「3 市场均失败」(fail)、「操作太频繁」(clock)四态(6 秒自动渐隐)· 等同三个市场全 fetch + valuation refresh(MANUAL trigger · 写 stock_valuation_event 含触发用户)· 一律 inline SVG 不用 emoji(v0.4.22)
- **`EntryRefreshRateLimiter`** · per family 60s 滑动窗口 ≤3 次 · in-memory · 防点点点滥用 · 超频 toast「⟳ 操作太频繁 · 请 N 秒后再试」(v0.4.22)
- **`POST /entry/refresh-stocks`** · 新端点 · 三市场单独 try/catch · 单市场失败不阻断其他两个 · toast 区分「全部成功 / 部分成功(N/3) / 全部失败」三态(v0.4.22)
- **`EntryRefreshRateLimiterTest`** · 7 个单测 · 锁 3/60s 配额 + 跨 family 隔离 + 常量值不被无意改大(v0.4.22)

### Changed

- **/entry 账户折叠流水排序改为时间倒序(新→旧)** · 之前是升序(老的在最上)· 用户展开折叠看到的第一眼现在是最新流水 · null occurredAt 行留末尾 · 改动只在 `EntryService.java:438` 一处 sort comparator(v0.4.22)
- **UI 一律 inline SVG · 弃用 emoji**(`feedback_no_emoji` 纪律建立)· 新加按钮 / toast 都走项目自带 Feather-style 体系(stroke=currentColor · 24×24 viewBox)· 历史代码里的 emoji 改到相关页面顺手换 · 不专门 sweep(v0.4.22)

## [v0.4.21] · 2026-05-20

**hotfix** · 修自 v0.3 上线起的 latent bug · 自动拉价不触发账户估值刷新。

### Fixed

- **`StockPriceScheduler` cron 入口拉完价后未调 `AccountValuationService.refreshAllForFamily`** · 表象:每日 cron(US 06:05 / CN 16:10 / HK 16:30)都成功 fetch + 写 `stock_price_snapshot`,但 `stock_valuation_event` 表全是 MANUAL(用户在持仓页手点 ↻ 才有)· 账户余额永远停留在用户最后一次手动 ↻ 时的值 · 自 v0.3 上线就遗漏 wire · v0.4.18 重构调度时也只换了触发机制没补这个 wire。修复:`fetchUsStocks/CnStocks/HkStocks` 三个 cron 入口拉完价后接 `refreshAllForFamily(CRON, null)` · admin 手动入口的 `fetchMarket()` 不动(controller 自己接 MANUAL/HOLDING_CHANGE · 避免双跑)(v0.4.21)

### Added

- **`StockPriceSchedulerTest` · 13 个 wire 防回归单测** · 锁死「cron 入口必接 valuation 刷新」+「fetchMarket 直接调不带 refresh」+「TriggerKind=CRON 不污染 MANUAL/HOLDING_CHANGE 审计」三条铁律 · 后续谁删了 `refreshValuationsAfterCron` 编译期单测立挂(v0.4.21)

## [v0.4.20] · 2026-05-19

**hotfix** · 补 v0.4.19 漏修的 timer 行内注释 bug。

### Fixed

- **`finance-backup.timer` 行内注释致 `Persistent=true` 被静默忽略** · L15 `Persistent=true             # 错过...` —— systemd 不支持值后的行内注释,整行被忽略 = 关机错过周日不会自动补跑 · 改为注释独占一行 · `systemd-analyze verify` 通过(v0.4.20)

## [v0.4.19] · 2026-05-19

**hotfix** · 修周备份从 v0.1 起从未跑成功的 deploy 配置漏装问题。

### Fixed

- **`deploy.sh` 漏装 `backup.sh`** · `finance-backup.service` 的 `ExecStart=/opt/finance/deploy/backup.sh` 路径不存在 · 每周日 03:00 触发即 systemd `status=203/EXEC` 失败 · 周备份(`dump-YYYY-MM-DD.sql.gz` + `uploads-*.tar.gz`)从未成功跑过 · `pre-deploy-*` 应急备份不受影响(v0.4.19)
- **`deploy.sh` 自动装 backup unit + 启用 timer** · 之前 `finance-backup.{service,timer}` 需手动 cp 到 `/etc/systemd/system/` + 手动 `systemctl enable --now` · 现在 deploy.sh step 13 自动装两个 unit + step 14 自动 `enable --now finance-backup.timer`(幂等)(v0.4.19)
- **`/opt/finance/deploy/` 目录** · step 6 加入 mkdir 列表(v0.4.19)

## [v0.4.18] · 2026-05-19

系统级配置沉淀到管理页 · 9 项运营参数从 env/代码常量迁到 family_runtime_config 表 · 实时生效不重启。详 [`prd/v0.4.md`](prd/v0.4.md) §22。

### Added

- **`/admin/integrations` 新页** · 集成中心 · 3 段:LLM(Qwen/DeepSeek key + max_tokens + timeout)/ 股票拉取(开关 + 3 市场 cron)/ FX 拉取 cron(v0.4.18)
- **`/admin/calc-tweaks` 升级为可编辑** · 原 3 项只读改可编辑 + 加 4 项体检阈值(集中度/高风险/LIQUID buffer/应急金月数)+ 1 项会话期(v0.4.18)
- **`FamilyConfigService` 三层 fallback** · DB > env(@Value)> 代码常量 · 5s TTL cache · 17 个 K_* 常量(v0.4.18)
- **`DynamicScheduleConfig` 动态 cron** · SchedulingConfigurer + TaskScheduler + CronTrigger · 5 受管 cron(stock 3 + fx + report-remind) · 配置改即 cancel+重排不重启(v0.4.18)
- **deploy.sh step 9.5 配置种子迁移** · 从 /etc/finance.env 一次性 UPSERT 进 family_runtime_config · 幂等 flag `/var/finance/.config-migrated-v0.4.18`(v0.4.18)
- **V26 migration** · 新建通用 K-V 表 `family_runtime_config` · ADD TABLE 0 破坏(v0.4.18)

### Changed

- **LLM client API key 改读 ConfigService** · QwenLlmClient / DeepSeekLlmClient 不再 @Value 直注入 · max_tokens 也动态(默认 2000)· timeout 仍构造期注入(v0.4.18)
- **股票拉取开关改读 DB** · StockPriceScheduler.isEnabled() 每次 schedule 触发都查 ConfigService(v0.4.18)
- **checkup 阈值 + LIQUID buffer 改读 DB** · FAM-RISK-1 / FAM-ALC-1 / LiquiditySurplus 接受 multiplier 参数 · DashboardController/CheckupController 注入 ConfigService(v0.4.18)
- **`/admin` sidebar 加"集成"入口** · 标 13 项 → 14 项(v0.4.18)

### Security

- **PrivacyIsolationTest 扩防 LLM key 泄露** · 新增 `promptBuilderNeverReferencesAnyPrivateAccessor` · 静态扫 PromptBuilder.java 不得引用 K_LLM_*_KEY / FamilyConfigService(只 LlmClient 可以读)· 与 SMS aksk 同纪律(v0.4.18)

## [v0.4.17] · 2026-05-20

520 一日限定爱情宣言彩蛋(详 prd/v0.4.md §21)。仅 5.20 当天 Asia/Shanghai 服务器时间触发,5.21 完全 dormant。

### Added

- **全屏像素彩蛋** · 任意已登录页拦截 · 跳动像素心 + 飘心粒子 + 烟花星空 + brass-deep + valentine 红 + ink 调色(v0.4.17)
- **文案库 19 条随机** · 单行祝福(纯爱意 · 与"账本/记账"无关)· 每次重开/换一句不与上一条重复(v0.4.17)
- **「换 一 句 ↻」** · 副标下方像素按钮 · 不关 overlay 换文案(v0.4.17)
- **右上 ♥520 常驻 pill** · 5.20 当天任意页可点重唤 · 每次重开换新文案(v0.4.17)
- **「叮」音效** · Web Audio API 合成(1318+2637Hz 双频 · 600ms 衰减)· 不增 jar 体积(v0.4.17)
- **localStorage flag** · `easter520_seen=YYYY-MM-DD` 当天首次自动弹 + 关闭后不再自动弹 · pill 仍常驻可手动唤回(v0.4.17)

## [v0.4.14] · 2026-05-18

填报规范化 + 截止前强提醒(FR-63)。详见 [`prd/v0.4.md`](prd/v0.4.md) §20。

### Added

- **填报模板** · 家庭级 3 选 1(实时收入·月末支出 / 月末一次清 / 每周滚动)· 默认 T1 · 统一全家填报节奏(v0.4.14)
- **填报页推荐方案提示** · `/entry` banner 显示当前模板建议 + 距本期截止天数 · ≤2 天红色强提醒(v0.4.14)
- **截止前强提醒调度** · `@Scheduled` 每天 10:00/20:00 · 截止前 N 天(默认 2)对未完成填报成员触达(v0.4.14)
- **渠道抽象** · `NotificationChannel` 可插拔 · 短信为主(阿里云 dysmsapi)+ 站内 banner 强化兜底 · 当天去重(v0.4.14)
- **提醒设置页** · `/admin/reminders` 升级为可配:模板 / 提前天数 / 短信 aksk·签名·模板 / 各成员手机号 / 手动触发(v0.4.14)
- **短信文案 4 变量** · `${brand}/${period}/${days}/${progress}` · 模板 67 字内塞尽量多信息(v0.4.14)
- **填报页三栏富信息 banner** · 左节奏 + 中周期/截止日/家庭进度 N/M + 我已填徽标 + 右距截止 pill 3 状态(v0.4.14)
- **⊕ 一键测试短信** · 配完 aksk 立刻验证链路 · 测试专用文案 · 限流 3次/分 · 走 audit_log 不污染调度日志(v0.4.14)
- **阿里云接入指引** · [`docs/aliyun-sms-setup.md`](docs/aliyun-sms-setup.md) · 9 步从注册到一键测试 · 含错误码对照表(v0.4.14)
- **提醒发送日志** · `/admin/reminders` ⑥ 段 · 真实调度的每次触达 · 分页 20/页 · 列含发送时间/截止日/成员/渠道/状态/详情(v0.4.14)
- **测试短信用真实账期 + days=-1 标识** · 不再用 2099-12-31 假日期 · 运营商日志一眼可辨"非真实窗口测试"(v0.4.14)

### Security

- **私密红线** · `member.phone` / 短信 aksk 绝不进 LLM prompt / audit_log 明文 / 前端明文回显 · `PrivacyIsolationTest` + `qa-run v04-PRIV-1` 双重防回归(v0.4.14)

## [v0.4.11] · 2026-05-14

v0.4 主线 + .1-.11 一系列迭代封板。

详见 [`prd/v0.4.md`](prd/v0.4.md)。

### Added

- **报表大整顿** · 砍 8 张流水视角图(瀑布 / 桑基 / 月度对比 / 风险敞口表 / 汇率表 ...) · dashboard / reports / checkup 三页职责重切(v0.4)
- **CPI 对照线** · 净资产趋势加虚线对比 · 默认 2% 可切换(v0.4)
- **账户级基准对照** · `product_category.benchmark_pct` · 跑赢 / 跑输 pill(v0.4)
- **AI 调仓建议** · 4 桶配置 diff(现金 / 投资 / 房产 / 保险)· LLM JSON 输出 · 30 天复用 + ↻ 刷新(v0.4 + v0.4.6/.8)
- **提前还贷决策器** · `/reports/refinance` · NPV 18 年视角(v0.4)
- **应急金不闲置提示** · LIQUID 超额 6 月时建议挪 50% 至理财(v0.4)
- **「人赚 vs 钱赚」二分收益指标** · 含收入 XIRR / 剔除收入资产年化 · 真实资产回报(v0.4.2)
- **股票估值事件 ledger** · 拉价后 ledger 显示 📈 估值行(v0.4.1)
- **AI 综合诊断 JSON 结构化** · 总评 + 4 维度卡(配置 / 风险 / 流动性 / 收益)+ 优先行动 · verdict 染色(v0.4.9)
- **/checkup 风险敞口饼图化** · doughnut + datalabels(v0.4.5)
- **AI 缓存 + ↻ 刷新按钮** · checkup 综合诊断 + rebalance 调仓建议 · 真跳过缓存(v0.4.8)

### Fixed

- **B1** period_snapshot 漏填账户 NULL → fact_view 续值 ≤ 当期最近一笔非空(读时 COALESCE · 不写库)(v0.4.3)
- **B2** dashboard 紧急储备 vs reports 储蓄能力双源问题 → PMC 优先 + cash_flow 回退(v0.4.3)
- **B4** YTD 复用 caller range slice 漂移 → 独立 load 1 月-今天 slice(v0.4.3)
- **prod 应急 hotfix** · dashboard/_region L157/L158 Thymeleaf 表达式 `#numbers.xxx()` 在 `${}` 外的语法错(beta 不触发 / prod 应急金超额触发)+ layout / nav `_csrf` null-safe 兜底(v0.4.4-hotfix × 3)
- **OutputValidator 账户名白名单** · 用户自家「支付宝-余额宝」被 PRODUCT_NAME_PATTERN 误杀 → 加白名单 + 3 态前端反馈(v0.4.6)
- **OutputValidator 放宽** · 删古典中式词 + 删过度客套 + 真名 length≥3 + rebalance caller 跳过真名扫描(v0.4.7)
- **LLM max_tokens 750→2000 + 截断检测** · JSON 输出被中途截断显示半截 JSON → 提到 2000 + finish_reason 日志 + 截断友好错误(v0.4.10)
- **`pct1(ratio)` 没 ×100** · prompt 给 LLM 占比 0.4%(实际 44.2%)误导 LLM → 新增 `pctFromRatio` ×100 + SYSTEM_DIAGNOSE 加「⚠⚠⚠ 100% 禁四则运算」防 LLM 二次瞎算(v0.4.11)

### Changed

- **用户面文案专业化** · 13 模板 ~30 处 · 删内部 routing 暴露(「已搬到 /dashboard」)+ 清版本号 / FR-XX 代号 + 中文化 AUTO / MANUAL / CASH enum + `/entry` / `/admin/fx` 路径文字改友好中文(v0.4.4)

---

## [v0.3] · 2026-05-13

详见 [`prd/v0.3.md`](prd/v0.3.md)。

### Added

- **财务目标体系** · `/goals` 一级页 + dashboard 进度条带
  - 退休 / FIRE(通胀 PV + 4% 提取率)
  - 子女教育金(通胀公式)
  - 应急储备(CASH 类账户口径 · target = 月支出 × 倍数)
  - 三情景预测(乐观 8% / 中性 5% / 悲观 2%)+ 二分反推达成日
- **AI 4 处介入** · 目标设定向导推荐参数 / 周期关闭后异步月报 / 偏离预警(90 天节流)/ 体检页 prompt 扩展
- **股票账户自动估值** · `/accounts/{id}/holdings` 混合模式
  - 自动拉价(录 ticker + 数量 · 新浪主 + 腾讯备 · 圆形熔断 · 美 / A / 港 三市场)
  - 手填市值(未上市 / 私募)
  - 账户内现金(IBKR / 富途 · FX 链式)
- **储蓄能力指标** · `/entry` 成员级月度收支 +2 框 · `/reports` 月度收支双柱图 + 月均 KPI
- **macOS 一键部署** · `deploy/deploy.sh` 顶部 OS 探测 · Darwin 自动转 `deploy-macos.sh`(brew · 无 sudo)

---

## [v0.2] · 2026-05-10

详见 [`prd/v0.2.md`](prd/v0.2.md)。

### Added

- **资产体检模块** · 全家诊断(配置 / 风险敞口 / 流动性 / 收益质量)+ 账户级诊断(类型差异化)
- **智能建议规则引擎** · 16 条规则(余额负数 / 久未填报 / 单类目集中度过高 等)
- **LLM 综合诊断** · 主 Qwen-Plus + 备 DeepSeek-Chat · 真名脱敏 · 圆形熔断
- **产品类目 + 6 级风险评级体系**
- **单账户详情页** · 账本视角(余额时序 + 月分组流水)+ 单账户 CSV
- **iOS PWA** · 添加到主屏(apple-touch-icon + manifest)
- **微信内浏览器引导** · 打开系统浏览器登录
- **4 套预设品牌图标** · 默认 icon2 · 可自定义 WebP 上传
- **dashboard "按账户分布"横向 bar** + "按成员分布"饼图

---

## [v0.1] · MVP

详见 [`prd/v0.1.md`](prd/v0.1.md)。

### Added

- **6 种账户类型** · 现金 / 股票 / 理财 / 房产 / 负债 / 其他 · 13 个内置账户模板
- **月度 / 周度周期可切** · 每周期自动生成"填余额"待办
- **余额录入 + 现金流 + 跨账户转账** · 轧差自动建议未解释金额怎么分类
- **周期自动关闭 + 指标重算** · 净资产 / 总资产 / 总负债 / 趋势 / 配置
- **账户级 XIRR + 家庭级 XIRR / TWR**
- **多币种** · 本位币 CNY / USD / HKD · 自动拉汇率
- **CSV 一键导出** · 避免被工具锁死
- **移动端响应式**
- **11 个 admin 子页** · logo / 品牌名 / 周期类型 / 汇率覆盖 / 成员 / 账户模板 / 类目 / 周期 / 提醒 / 备份 / 审计
