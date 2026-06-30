# QA 用例过期审计 · v0.10.6 · 2026-06-30

> **过期 ≠ 失败**。本审计逐个 case 判断「用例的设计/假设/背景是否仍与当前代码/PRD 相符」——
> 一个 case 可能仍 PASS 却假设过时(靠注释/残留变量/巧合命中)。方法:6 个并行子代理按行段
> 逐 case 对照当前模板/控制器/PRD 核验;**所有被标记项再由主程亲自 grep 复核**(已抓出 2 个子代理误判)。

## 判断口径

- ✅ **符合** — 假设与当前代码/PRD 一致。
- ⚠️ **过期** — 仍 PASS,但背景假设已过时(引用改名概念/旧口径/已砍功能,靠注释或巧合续命)。
- 🔧 **需修** — 断言与现状不符/会误导。

## 总结(379 case)

逐个核验后,**确认 6 个过期**(均仍 PASS、靠注释/残留/巧合/旧阈值通过),已全部修复:

| Case | 过期原因(亲验) | 修法 |
|---|---|---|
| **FR13-1** | 第 5 KPI 卡 v0.4 FR-60a 已从「负债率」改「本月资产收益」(负债率搬 /checkup);锚词「负债率」只命中 `_region.html:132` 的 HTML **注释** → 靠注释假通过 | 锚词改「本月资产收益」 |
| **v02-FR40e-2** | 风险敞口**明细表** v0.4 FR-60b 已砍(改环形图,见 FR40e-3);`★≥3` 现靠「资产年化★」eyebrow + JS 注释里的 ★ 凑数,非风险表 | 去掉「表格」宣称,改星级标识渲染冒烟 ≥1 |
| **v02-FR38-1** | v0.4.2 起第 5 卡(本月资产收益)deep-link 指向 **/reports** 而非 /checkup;「5 张 KPI 均含 /checkup」不实,`n≥5` 靠账户行 /checkup 链接凑满 | 文案改「KPI+账户行含 ≥5 个 /checkup 深链」 |
| **v03-IND-6** | 桑基图 v0.4 FR-60b 已砍;`grep 'sankey'` 仅命中废弃的 `sankeyNodes/Links` JS **残留变量**(无图渲染),靠 `\|\| 净资产` 续命 | 去掉 sankey 期待,只验 backward-compat 核心内容「净资产」 |
| **v02-LEDGER-1** | `≥13` 硬编码耦合旧 demo 账户量(现 12 账户 × PC/移动双渲染 = 24,靠双倍计数过) | 解耦 → 渲染冒烟 ≥1 |
| **v02-FR30-7** | 同上,`≥13` 详情链接耦合 demo 量(实测 48) | 解耦 → 渲染冒烟 ≥1 |

**主程复核抓出的 2 个子代理误判**(实为 ✅,未改):

| Case | 子代理(错)判 | 复核结论 |
|---|---|---|
| **v04-VAL-2** | 🔧 `📈 估值` 已删 | ✅ 子代理只 grep 模板;`📈 估值` 由 EntryController **Java 拼 HTML** 渲染,运行时实测命中 3 处,case 正确。(旁注:`📈` 是 emoji,违 no-emoji 纪律,属独立代码问题,非 qa 过期) |
| **v04-VAL-3** | 🔧 `估值变动·手动刷价` 已改 `△ 估值` | ✅ 运行时 `/accounts/4` 实测**两串都在**(`△ 估值`×1 + `估值变动·手动刷价`×1),case 正确;子代理只 grep `detail.html` 漏了 fragment/Java 渲染 |

> 另两处「擦边但仍正确」(不到过期门槛,未改):v02-CCY-4 fallback grep「汇率未配置」是现文案「汇率尚未配置」的子串仍命中;v02-FR40E-3 grep 字面 `risk-section` 不存在(id 是 `sec-risk`)但 OR 分支「风险等级分布」命中。

其余 ~371 个 case 的设计/假设/背景均与当前代码/PRD 相符(✅)。下列各段为逐 case 明细。

---

## 段 1 · 认证 + FR-1..FR-22 + 静态(行 1–620)

唯一过期:**FR13-1**(已修)。其余 ✅。

| Case | 断言/背景假设 | 现状核对 | 判断 |
|---|---|---|---|
| AUTH-1..9 | 登录/登出/health/已登录跳转 等 8 条 | SecurityConfig/AuthController/HealthController 一致 | ✅ |
| FR1-1/1a/2/7/8, FR1-6(skip) | 家庭/成员/改密/logo | admin/family·members·password 模板一致 | ✅ |
| FR2-1/2/3 | 账户向导 + 类型中文化 | _template-wizard 一致 | ✅ |
| FR3-1/3, FR5-1/2 | 账户列表/编辑/周期/待办 | 一致(依赖 seed) | ✅ |
| FR6-1..4 | 待办视图 + entry 筛选 banner | my-todos/entry 一致 | ✅ |
| FR7-2..11, FR8-1/1b, FR9-1/1b/2 | 余额/现金流/转账/校准/重复拦截 | EntryController/ToastErrorAdvice 一致 | ✅ |
| FR11-4/5, FR12-1/3 | 关账/强制关账/CLOSED 拒写/周期 | 一致 | ✅ |
| **FR13-1** | dashboard 5 KPI 含「负债率」 | 负债率搬 /checkup,第5卡=本月资产收益;原锚命中注释 | ⚠️→已修 |
| FR13-2(×6)/13-3 | range 参数 + HX fragment | 一致 | ✅ |
| FR14-1/14-2(×6), FR15-1, FR16-1/2/3 | 家庭XIRR/range/fx/CSV+BOM | 一致 | ✅ |
| FR17-1/18-1/19-3, FR20(×11), FR21-1, FR22-1/2 | banner/备份/LOAN/admin 子页/筛选/币种 | reminders 控制器迁移但 URL+HTML 不变 | ✅ |
| v02-CCY-1/2, v05-CCY-INV-1, v08-CCY-INV-2/3/4, v08-PILL-M | 多币种数字/比值币种无关/手机 pill | 属性级护栏 + fx 缩放一致 | ✅ |
| v05-CALC-1/2/3, v05-SNAP-1/2, v05-TOC-1/2/3 | ⓘ 实算/快照态/长文目录 | MetricExplainService + _toc 一致 | ✅ |
| v02-CCY-3..7, ST(×5), ERR-1 | fx 实时拉取/copy/静态资源/错误页 | FxService + 静态文件一致 | ✅ |

## 段 2 · v0.2 错误页/引导/体检/类目/账本(行 620–1200)

过期:**v02-LEDGER-1 / v02-FR30-7 / v02-FR40e-2 / v02-FR38-1**(均已修)。其余 ✅。

| Case | 判断 | 备注 |
|---|---|---|
| ERR-2/3/4, FR7-参考, FR34-1..7, FR33-1/2 | ✅ | 错误页/manifest/PWA/微信引导 一致 |
| v02-NAV-1, v02-CHK-1/2, v02-LIQ-1/2/3, v02-PCAT-1..5 | ✅ | 体检入口/流动性/产品类目 一致 |
| v02-PILL-1/2/3, v02-WIZ-1, v02-EDIT-1, v02-DASH-1, v02-SOFT-1 | ✅ | pill/向导/编辑/checkup 深链(PILL-1 v0.10.6 已 ≥1) |
| v02-DIAG-1..8(阶段2) | ✅ | CASH/STOCK/LOAN/PROPERTY 分卡正确(v0.10 移除的是账户**列表指标**,不波及 checkup 卡) |
| v02-ADV-1..7 | ✅ | 建议卡/健康态/AI placeholder 一致 |
| v02-DIAG-1..6(AI节), v02-LLM-LIVE-1 | ✅ | /checkup/diagnose 一致 |
| **v02-LEDGER-1** | ⚠️→已修 | ≥13 耦合 demo 量(checkup 深链);改 ≥1 |
| v02-LEDGER-2..6 | ✅ | LEDGER-2 v0.10.6 已 ≥1;CSV/BOM/跨家庭拒绝 一致 |
| v02-SOFT-DEL-2..8 | ✅ | 软删按钮(Java 拼)/CLOSED 拒删 一致 |
| v02-FR30-1..6 | ✅ | 账户详情页 一致 |
| **v02-FR30-7** | ⚠️→已修 | ≥13 耦合 demo 量(详情链接);改 ≥1 |
| v02-AUDIT-1, v02-DASH-ANCHOR | ✅ | 审计真名/dashboard 锚 一致 |
| v02-FR40e-1 | ✅ | 风险等级分布 + riskDistChart canvas 在 |
| **v02-FR40e-2** | ⚠️→已修 | 风险**表格**已砍;★ 靠 eyebrow+JS注释凑;改星级渲染冒烟 |
| v02-FR40e-3, v02-CHART-* | ✅ | 环形图保留;Chart.js+datalabels 注册 |
| **v02-FR38-1** | ⚠️→已修 | 第5卡链 /reports;改「KPI+账户行 ≥5 深链」 |
| v02-FR38-2/3 | ✅ | /checkup 锚点齐 |
| v02-UX-1..5, v02-FR40E-1/2/3 | ✅ | entry 输入/分布图/风险段(FR40E-3 用 OR,见擦边注) |
| v02-LOGO-1/2/3 | ✅ | 16 预设 PNG/manifest/favicon 默认 icon2 |

## 段 3 · v0.2 品牌图标 + v0.3 + 早期 v0.4(行 1200–1925)

过期:**v03-IND-6**(已修)。子代理误判 **v04-VAL-2/VAL-3**(经复核实为 ✅)。其余 ✅。

| Case | 判断 | 备注 |
|---|---|---|
| v02-LOGO-1..10 | ✅ | 预设/manifest/favicon/上传 webp/非法拒写;现带 `?v=` 缓存戳子串仍命中 |
| v03-GOAL-1..12 | ✅ | 目标空态/创建/三情景/归档/nav;空态负向串已不存在故恒满足 |
| v03-IND-1..5, IND-7..12 | ✅ | 收支录入/储蓄区/FR-51 顺序/KPI 搬迁(注释已同步,OR 双串兼容) |
| **v03-IND-6** | ⚠️→已修 | 桑基图 v0.4 砍,grep 'sankey' 仅命中 JS 残留变量;改只验「净资产」 |
| v03-STOCK-1..15 | ✅ | 持仓 MANUAL/AUTO/CASH/FX/估值/归档;note 改名已 OR 兼容 |
| v03-AI-1..6 | ✅ | 目标 AI 建议端点 一致 |
| v04-RPT-1..5, v04-CPI-1/2, v04-BMK-1, v04-DIFF-1/2/3 | ✅ | dashboard 5KPI/cpi 切换/砍图为0/配置 diff |
| v04-REFI-1..4, v04-AI-REBALANCE-1, v04-VAL-1 | ✅ | 提前还贷/调仓/估值事件 |
| **v04-VAL-2** | ✅(子代理误判🔧) | `📈 估值` 由 EntryController Java 渲染,运行时命中 3 处;case 正确(旁注 emoji 违纪) |
| **v04-VAL-3** | ✅(子代理误判🔧) | `/accounts/4` 运行时 `△ 估值` + `估值变动·手动刷价` 两串都在;case 正确 |
| v04-RET-1..4 | ✅ | 本月资产收益/双口径/资产年化★/单测文件在 |

## 段 4 · v0.4.3 兼容 / v0.4.4 文案 / v0.4.14 提醒 / v0.4.17 彩蛋(行 1925–2340)

全部 ✅(50 case)。

| Case | 判断 | 备注 |
|---|---|---|
| v04-FIX-1..7 | ✅ | 续值 SQL/PMC 优先/YTD slice/漏填渲染/factview 目录 |
| v04-UX-1..9 | ✅ | 去迁移文案/配置卡/无 enum 泄露/死模板已删/riskChart |
| v04-AI-REBALANCE-2..7 | ✅ | advise/cache/反馈条/fromCache 日志/刷新按钮(集成脆弱但设计假设有效) |
| v04-AI-DIAGNOSE-1..8 | ✅ | panel/4维度+4图标(LLM 契约在 PromptBuilder)/截断告警/禁数学约束 |
| v04-RPT-TMPL-1, RPT-REMIND-1..5 | ✅ | 模板枚举/cron 注册/渠道抽象/V25 去重(v0.10.1 off-by-one 改窗口判定,REMIND-3 只验 cron 注册不受影响) |
| v04-RPT-BANNER-1/2, RPT-MSG-1, RPT-TEST-1..3, RPT-LOG-1..3 | ✅ | 填报 banner/短信变量/测试限流/日志分页 |
| v04-520-1..4 | ✅ | 彩蛋日期 gate/footer 注入/hooks/非当天 dormant |

## 段 5 · v0.4.18 配置 / v0.6 洞察 / v0.6.1 PWA / v0.7 Docker / v0.8 指标(行 2340–2790)

全部 ✅(51 case)。重点核对用户担心的指标重构,均无过期:

| Case | 判断 | 备注 |
|---|---|---|
| v04-PRIV-1, v04-CFG-1..10, v08-NAV-1 | ✅ | 隐私红线/runtime config/受管调度/集成页/指标设置入口 |
| v06-INSIGHT-1..6, v06-LLM-LIVE/COMPLIANCE/PRIV/MODELS/MIGRATION | ✅ | 资产洞察/合规红线/隐私/多模型池/迁移 backward-compat |
| v061-PWA-1..5 | ✅ | mobile-guide/强引导文案/无emoji/成果图 |
| v07-DOCKER-1..8, v07-CN-1/2, v07-CLEAN-1/2 | ✅ | Docker 编排/国内镜像源/清演示数据;README 283/405 实测精确吻合 |
| **v08-1**(指标端出/sparkline/排序) | ✅ | 只查通用 hook(cumPnl/sparkPoints/排序属性),**不触及** v0.10.4 移除的 twr/yoy/risk |
| v08-2/3/4/6 | ✅ | 跨币种转账/现金调整剔PnL/筛选as-of+MoM/预实分析 字段在 |
| **v08-5**(可配置指标集) | ✅ | 查 acctMetrics.contains/接线,不查被删 key |
| **v08-7**(famMetrics) | ✅ | `period_return` 是**家庭级** key(label本月资产收益),与账户级改名「本期损益」无关 |
| v08-8 | ✅ | emoji 红线(★/↔↺✕ 保留) |

## 段 6 · v0.7 二三批 / v0.9 落地页 / v0.10 全部(行 2790–3105)

全部 ✅(33 case)——最新代码,本就该全符合。

| Case | 判断 | 备注 |
|---|---|---|
| v07-CFG-1..5, v07-ONB-1/2, v07-FIX-1 | ✅ | 配置文档/LLM 测试脱敏/引导路由/登出 |
| v09-LAND-1..6 | ✅ | 落地页文案/匿名 200/已登录跳转/数字带与 release 同口径(10/283/33/405) |
| v09-UX-1..3, v09-CPI-1, v09-FORM-1..6 | ✅ | toast 层级/必填/CPI 购买力线/条件必填助手 |
| v10-CASHFLOW-1..4 | ✅ | 人赚vs钱赚 section/三态/趋势图/钱赚=ΔNW−人赚 |
| v10-CCY-LENS-1/2 | ✅ | 收支/净资产趋势同汇率缩放(运行时条件断言,数据不足则 SKIP) |
| v10-TOC-SYNC-1/2 | ✅ | 长文目录无遗漏/无死链 |
| v10-NOMINAL-1, v10-ACCT-COLS-1, v10-WINDOW-1 | ✅ | 名义口径/账户补列(MetricPrefsService 无 twr)/同窗口对比 |

---

## 修复落点(scripts/qa-run.sh)

- `FR13-1`:`grep 负债率` → `grep 本月资产收益`
- `v02-LEDGER-1` / `v02-FR30-7`:`≥13` → `≥1`(解耦 demo 账户量,渲染冒烟)
- `v02-FR40e-2`:去「风险表格」宣称 → 星级标识 `★` 渲染冒烟 ≥1(风险明细表 v0.4 已砍,环形图见 FR40e-3)
- `v02-FR38-1`:文案 →「KPI 4 张 + 账户行含 ≥5 个 /checkup 深链」(第5卡链 /reports)
- `v03-IND-6`:去 `sankey` 期待 → 只验「净资产」(桑基图 v0.4 已砍,grep 仅命中 JS 残留变量)

case 数不变(仅改阈值/锚词/文案),黑盒计数 405 不变。
