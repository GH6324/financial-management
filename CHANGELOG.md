# Changelog

按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格记录。每个版本详细需求见对应 [`prd/v0.X.md`](prd/),技术设计见 [`tech-design/v0.X.md`](tech-design/),QA case 见 [`docs/qa-cases.md`](docs/qa-cases.md)。

## [v0.6.2] · 2026-06-09

LLM 成本治理:Qwen 调用改「每次随机选模型」摊开流量 + 模型池精选。修根因「百炼免费额度**按模型各给一份**,而旧逻辑固定从 qwen-plus 起、只在 429 才切 → 全砸 qwen-plus,用超后账户**静默转计费**(不报 429)→ 出账单」。纯代码,0 schema。

### Changed

- **`QwenLlmClient` 随机选模型**:每次调用 `Collections.shuffle` 模型池、随机起一个(原固定 qwen-plus 起)→ 把流量均匀摊到各模型的**独立免费额度池**;失败/额度用尽仍沿随机序往后试 + 保留 per-model 6h 冷却。
- **默认模型池精选** → `qwen-plus,qwen-flash,qwen-max`(稳定别名 · 三档能力梯度 · 各一个独立免费额度池)· 丢掉 qwen-turbo / qwen2.5-7b 等偏弱(结构化 JSON 诊断易翻车)· 运营可在 `/admin/integrations` 调(`K_LLM_QWEN_MODELS`)。

### 运维(关键)

- **配合百炼控制台开「免费额度用完即停」**(默认关):开后某模型免费额度用尽返回 403 `AllocationQuota.FreeTierOnly` —— 本类 `classify()` 已识别为额度用尽→自动切下个模型,且**永不计费**。这是「不再出账单」的根本开关。
- 清理 beta 演示环境短信 aksk(公开 Demo 防误触发真发短信)。

## [v0.6.1] · 2026-06-08

iOS PWA 引导从「软建议」改「强引导」(FR-115)。原来是「你也可以这么用」,现在是
「iOS 上请务必装成 App」—— 整屏拦截 + 成果真机截图(看到网站能变 App)+ 想留在浏览器/
微信要被**两段挽留**才放行。纯前端,0 schema。

### Changed

- **`mobile-guide.js` 重写**:① iOS Safari → 进站 ~0.7s **整屏强引导**(标题「请把账房装成 App」+ 主屏成果截图 + 3 条价值点 +「看怎么装·4 步真机图」)② iOS 微信 → **整屏强引导转 Safari**(微信内核装不了 PWA · 大箭头指右上「⋯」→「在 Safari 中打开」+ 成果截图)
- **两段挽留(强阻挠)**:点「暂时用浏览器 / 继续在微信用」或 ✕ → 阻挠①(没图标/手输网址/不能全屏)→「仍要继续」→ 阻挠②(装一次 20 秒)→ 才放行 + 3 天内不再弹(原 Safari 7 天一键划走)
- **口吻**全面升「请 / 务必 / 强烈建议」;文案从可选改强推
- **emoji 全清** 📦📷✕✓ → 项目 inline SVG(承 `feedback_no_emoji`)
- 已装成 PWA(standalone)/ 非 iOS → 完全静默不打扰;`?reset_pwa=1 / ?reset_wx=1` dev 强触发保留;安卓微信本版静默(只强推 iOS · 安卓另议)

### Added

- **成果真机图** `img/safari-screen/home-screen.jpg`(主屏装好的账房图标 · 让用户直接看到「网站能像 App」)· 640px/q82 · 74KB

### Perf

- iOS PWA 引导 4 步截图压缩 1320→900px/q82 · 834KB→473KB(-44%)
- presets 品牌图标 32 位真彩→256 色板 PNG · 1370KB→83KB(-94%)· 已眼检无 banding

## [v0.6.0] · 2026-06-05

中国大陆中产视角的「AI 资产洞察」:把小红书/论坛/支付宝等主流讨论里反复出现的资产焦虑,
沉淀成 4 条可审计主线 —— ① 集中度(钱是否太挤在一处)② 资产负债表健康 ③ 再平衡 + 行为
④ 低利率·资产荒(真实购买力)。硬数据全部工程预算,LLM 只做中立解读(不预测涨跌 / 不择时 /
不荐产品)。主阵地资产体检页,dashboard 速览条 + reports 交叉入口。

### Added

- **calc/ 4 纯函数**(无 Spring · 可单测)· `ConcentrationCalculator`(房产/单一账户/单一币种占比 + 风险线)/ `BalanceSheetHealth`(金融盘 vs 不动产 · 负债率分级 · 提前还贷信号)/ `RebalanceDrift`(4 桶偏离方向 OVER/UNDER/OK)/ `BehaviorHeuristics`(顺周期加仓 / 集中度持续走高 · 历史<6 期静默)
- **`AssetInsightService`** · 从 FactView / 账户负债字段 / 配置 diff / 财富水位取数 → 调 4 纯函数 → 组装可审计「硬数据」`AssetInsight`(只读 · 永不抛 · 数据缺失局部降级)
- **AI 资产洞察 LLM 层** · `InsightPromptBuilder`(硬数据铺成 prompt · **不含任何人名/账户名**)+ `LlmDiagnoseService.diagnoseAssetInsight`(复用 client 轮转 + 1h cache + audit · scope=ASSET_INSIGHT)· 结构化 JSON(总评 + 4 维卡 + 纪律性提醒)
- **资产体检页「AI 资产洞察」section**(`checkup/_ai-insight.html`)· 上半第一层硬数据 4 卡 + 下半第二层 AI 解读 · 全 inline SVG(无 emoji)· TOC 新增「AI 资产洞察」项
- **dashboard 资产洞察速览条**(`dashboard/_insight-strip.html`)· 房产占比 / 负债 band / 加速偿还 / 真实收益承压 / 行为提醒 chip + 「查看完整洞察」· 仅硬数据不调 LLM(保持仪表盘轻快)
- **reports 配置对照交叉入口** → `/checkup#checkup-insight`
- **Qwen 多模型免费额度兜底**(FR-108)· `K_LLM_QWEN_MODELS` 有序模型列表(≤10 · 运营可配)· 某模型免费额度用尽(429 `Throttling.AllocationQuota`/`insufficient_quota` · 403 `AllocationQuota.FreeTierOnly`)自动切下一模型并冷却 6h · 账户欠费/账单过期(400 `Arrearage` · `*BillOverdue`)立刻 failover DeepSeek
- **`OutputValidator.checkInsight`** · 在合规底线之上额外拒绝预测涨跌 / 择时 / 买卖时点话术(仅 ASSET_INSIGHT scope · 既有综合诊断 `check` 行为不变)
- **账户负债明细字段** · `account.loan_kind` / `annual_rate_pct`(V29 · 喂提前还贷信号)· 编辑页 LOAN 类型可选填

### Schema

- **V29** · `ALTER TABLE account ADD COLUMN loan_kind VARCHAR(20) NULL, ADD COLUMN annual_rate_pct DECIMAL(6,3) NULL` · 纯 ADD COLUMN DEFAULT NULL · prod 0 风险 · 老账户该维度优雅降级

### Tests

- +`AssetInsightCalcTest`(6)+ `QwenInsightComplianceTest`(6)· 单元 **244** 全绿 · qa-run +v06-INSIGHT-1~6 / v06-LLM-LIVE / v06-COMPLIANCE / v06-PRIV / v06-MODELS / v06-MIGRATION

### 红线

- LLM 严禁算术(数字全工程预算 · prompt 明示只引用)· 不预测涨跌 · 不择时 · 不荐产品 · 中立诊断
- 隐私 by construction:洞察 prompt 不含成员/账户名 · `checkInsight` 防御深度仍扫
- 全部只读取数 · 不写表 · 既有 `/checkup/diagnose` 与 `check()` 行为不变

## [v0.5.7] · 2026-06-04

长文目录推广到所有长 tab 页 + 沉淀为可复用件(无迁移 · 纯前端 · 0 schema)。

### Added

- **长文目录共用件** · `fragments/_toc.html`(`rail(items)` + `mobile(items)` 两片段,items 经 `th:with` 内联 map 传入)+ `static/js/toc.js`(页面无关 scrollspy + 底部 sheet · 自动接 `[data-toc-nav]`)· `style.css` 布局类泛化 `.reports-cols`→`.toc-cols`
- **dashboard 接入目录**(概览 / 净资产趋势 / 按成员分布 / 按账户分布 / 账户列表)· `_region` 加 `#dash-trend/#dash-member/#dash-dist/#dash-list` 锚点
- **checkup 接入目录**(概览 / 资产配置 / 风险敞口 / 流动性 / 收益质量 / 智能建议 / AI 综合诊断 / 单账户体检)· 复用既有 `#allocation/#risk/#liquidity/#return` + 加 `#checkup-overview/#checkup-advice/#checkup-ai/#checkup-accounts`(advice 互斥两态同 id 保锚点恒在)

### Changed

- **reports 目录改用共用件** · 移除原内联脚本/rail/sheet markup · 行为不变(左侧常驻树状 + scrollspy + 手机 sheet)
- **不做目录的页**(明确):填报 / 账户 / 目标列表 / 管理 —— 表单/列表/管理页,非长文阅读

### Tests

- qa-run +v05-TOC-2/3(dashboard/checkup 含目录 + 锚点)· 单元 **232** 不变(纯前端)· 0 schema · 迭代须同步目录(memory `feedback_toc_sync`)

## [v0.5.6] · 2026-06-03

报表两件事(无迁移):① 收益口径回归「已关账快照」(原拟 v0.5.5,不单独发,并入此版)② 长文目录(PC 右栏树状大纲 + scrollspy · 手机左上唤醒钮 + 底部 sheet)。

### Fixed

- **报表四指标锚在进行中账期 → 人赚常年 0 / XIRR·TWR 用半填净值失真**(自 v0.5.1 `findCurrentOpen` 优先引入)· 报表本应是"已关账快照",却跟着月中还空着的 OPEN 期走 · 年轻家庭里唯一填了数的已关账月恰好当了"被排除的基准",真正计入的只剩空 OPEN 期 → #3=0(FR-94)

### Changed

- **报表锚定改「最近已关账(≤今天)账期」· dashboard 不动(实时)** · 四指标终点永远是填满的已关账月;`period_start ≤ 今天` 顺带干净挡掉测试/误建的未来账期(2032),不必再靠 OPEN 兜底 · 新增只读 `PeriodMapper.findLatestClosedAsOf` + 纯函数 `ReportsAnchorResolver`(FR-94)
- **#3 人赚 ⓘ 文案** · 点明「区间逐期累计 · 非单月 · 只统计已关账」(去掉过时的"当前账期没填→0"措辞)(FR-96)
- **报表目录升级**(FR-98)· 原 v0.5.1 右下角低调小 FAB → 业界长文交互:**PC 右侧常驻树状大纲**(竖线/树枝 · 支持多层嵌套)+ `IntersectionObserver`/scroll scrollspy 高亮当前节(`aria-current` a11y 钩子);**手机左上角唤醒钮 → 底部 sheet**(拖拽手柄 + × + Esc · 触控 ≥44px · 点击跳转后收起)· HTMX 换 range/币种后重新 spy

### Added

- **报表页头「已关账」朱印章 + 说明行透出**(FR-97)· 标题旁朱印红横排小印(纯 CSS · `.report-seal` · 无 emoji)+「本页为已关账账期的稳定快照 · 数据截至 X · 进行中的本月请看 仪表盘→」· 让用户一眼分清报表(快照)与仪表盘(实时)
- **已关账账期 <2 的诚实空态**(FR-95)· 四 banner 显「—」+「需 ≥2 个已关账账期」,不再显误导性 0;0 个已关账期 → 引导空态(不盖空印章)
- **章节锚点**(FR-98)· `_region` 加 `#sec-decompose` / `#sec-risk` / `#sec-accounts`(配合 `#reports-region` / `#sec-wealth` / `#sec-savings` / `#allocation-diff`)· `scroll-margin-top` 处理 sticky-nav 偏移

### Tests

- 单元 **232**(+4 `ReportsAnchorResolverTest`:快照/退 OPEN/退 latest/无账期抛错)· qa-run +v05-SNAP-1/2(快照透出 · dashboard 实时)+v05-TOC-1(右栏树 + 锚点 + 手机 sheet)· **0 schema 改动** · dashboard 行为不变 · 目录纯前端

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
