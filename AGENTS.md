# AGENTS.md · 家庭账房 项目操作手册

> **这是每次迭代的单一入口。动代码前先读第 5 节(流程)+ 第 7 节(联动链)+ 第 8 节(护栏)。**
> 目的:把"项目是什么、怎么改、改了要同步什么、别再踩哪些坑"沉淀成一份随代码走的文档,让反复犯的错收敛掉。
> 维护规则:发现新坑 / 新联动 → **当场加一行**,能机器校验的**配一个 `scripts/qa-run.sh` 守护**。文档靠人记会漏,守护才是兜底。

---

## 1. 项目是什么(评估任何 FR 的标尺)

**定位**(用户拍板):**不是流水/记账 APP**,而是「中产家庭**摸清资产** + **辅助管理让钱增值不贬值**」的工具。填"账本类只到记录、没到决策辅助"的空白。

**两条产品轴** —— 任何 FR 先问"服务哪条轴?都不服务就砍":
1. **摸清** — 跨账户/类型/币种/产品 看清家庭资产全貌(净资产 + 配置 + 流动性 + 趋势)。
2. **增值不贬值** — 配置 vs 目标差 / 调仓建议 / 通胀对照 / 收益基准 / 提前还贷决策 / 应急金不闲置。

**硬约束**:夫妻每月**异步、10 分钟内**完成全部录入。任何"更全面但要更频繁录入"的功能都要拒。颗粒度**永远停在账户级月度快照**,不做单券持仓明细。

**刻意不做**(反例,别提议):个股持仓明细 / 逐笔流水账 / 定投提醒 / 预算包络 / 消费品类细化 / AA 账本 / 报销 / 券商 API 直连 / 银行账单 OCR / Docker 之外引入 K8s。

**恒等式(红线)**:`NetWorth(M) − NetWorth(M-1) = 外部收入(M) − 外部支出(M) + 投资损益(M)`,误差 > ¥0.01 抛 `DataInconsistencyException`。账户级 `PnL = ΔNW − 净流入 − 净划转`(外部流入被剔除 → 收入不该抬高收益率)。

用户:Java 工程师,有自己的服务器 + 域名;**妻子非技术** → UI 先保证她能独立完成。中国大陆环境。**对话一律中文**。

---

## 2. 环境拓扑(别搞错 · 致命级)

| 环境 | 机器 | 访问 | 谁部署 |
|---|---|---|---|
| **beta** | **Claude 本机** · 公网 IP `[redacted-host]` · 本地 `127.0.0.1:20000` · systemd `finance` · jar `/opt/finance/app.jar` | `http://[redacted-host]/` · `https://beta.dixi-token.top`(经 prod nginx 反代) | Claude:`sudo /bin/cp target/app.jar /opt/finance/app.jar` + `sudo /bin/systemctl restart finance`(sudoers 白名单) |
| **prod** | **另一台机** `root@[redacted-host]` · 仓库 `/root/financial-management` | **`https://dixi-token.top`**(Let's Encrypt · 80→443) | 用户 `git pull && sudo bash deploy/deploy.sh` **或** `release-prod` skill |

- **`dixi-token.top` = prod,不是 beta**;beta 本机**无独立域名**(只 IP / beta 子域)。用户说「beta」=本机、说「dixi-token.top / 生产」=prod。
- prod 操作**仅限 `release-prod` skill 编排流程内**(用户 2026-05-15 授权边界)。
- beta DB:`mysql -ufinance -pfinance finance`(单家庭 `family_id=1` · 用户 `diwa`/`demo1234`)。

---

## 3. 技术栈 & 关键领域概念

**栈**:Spring Boot 3.3 + Java 21 + MyBatis 3 + MySQL 8;Thymeleaf + HTMX 1.9 + Chart.js 4 / ECharts;**无 SPA、无构建管线**;版本化 SQL 迁移 + sha256(无 Flyway)。UI = **晚清账册风**,**禁 emoji · 用 inline SVG**。

**必须先懂的领域概念**(改相关代码前务必对齐):

- **双轨收入**:`period_member_cashflow`(PMC「2框」· 家庭成员月度总收入/总支出 · 无账户关联)vs 账户级 `cash_flow`(每账户逐笔 · 驱动余额/XIRR/PnL)。**FR-142 起收入侧以 `cash_flow` 汇总为准**(历史 PMC 收入 >0 时兜底优先,防双计);支出侧仍 PMC 优先。
- **股票账户估值**:账户余额 = Σ 持仓估值。持仓 `ValuationMode`:**AUTO**(上市 · ticker+shares · 自动拉价)/ **MANUAL**(未上市如字节 · **v0.12.1 起 = 股数 × 单股手填估值**)/ **CASH**(券商现金 · 按币种)。估值刷新(`AccountValuationService.refreshAllForFamily`)会**重算并覆盖 `period_snapshot`** → 股票收入必须落 **CASH 行**(现金)或 **+持仓股数**(RSU),再 `applyDeltaToBalance` 立即入账,别直接改余额(会被刷新覆盖)。
- **`is_adjustment`(V33)**:手动改股票现金行的本金进出 → `cash_adjust`/`is_adjustment=1`,**剔出 PnL、不计家庭收入**;真实外部收入 `is_adjustment=0`。
- **币种三层**(极易错,见 L2/L3):
  - **账户币种** — `cash_flow.amount`、`period_snapshot.end_balance`、持仓 `manual_value` 都存这个。
  - **本位币**(`family.base_currency`,默认 CNY)— 家庭/跨账户**聚合**必须 `× fxToBase` 换到本位币,不能裸加。
  - **视图币种** — dashboard 镜头切换;**比值类 KPI 三币种必须完全相等**,金额按 fx 缩放。
- **fact 层**:`FactViewServiceImpl` + `FactMapper.queryBase` 产出 `AccountPeriodFact`(`incomeBase`/`expenseBase` 已换本位币);dashboard/reports/KPI 都从这层取。

---

## 4. 页面地图(顶栏 7 tab + 公开页)

| tab / 页 | 路由 | 干什么 | 关键文件 |
|---|---|---|---|
| 仪表盘 | `/dashboard` | 净资产/趋势(CPI+M2 线)/配置环/KPI 横条/**人赚vs钱赚拆解**/AI 洞察 | `dashboard/index.html` + `_region.html` · `DashboardController` · `CashflowSplitView` |
| 填报 | `/entry` | 每月录入:账户余额快照 + 收支 + 划转;**收入侧结构化**(现金/股票·联动持仓);退休目标折叠于此 | `entry/index.html` + `_row.html` + `_income-stock.html` · `EntryController`/`EntryService` |
| 账户 | `/accounts` | 6 类账户簿 · 按成员归集 · 划转/体检/账本/导出;股票账户 → 持仓管理 | `accounts/*` · `stock/holdings.html` · `StockHoldingController` |
| 报表 | `/reports` | 关账快照 + 指标全审计(比值→pp)+ 账期筛选 · **长文目录 TOC** | `reports/*` · `_toc` |
| 目标 | `/goals` | FIRE 退休 / 教育 / 应急金 · 三情景预测 | `goals/*` |
| 资产体检 | `/checkup` | 4 维诊断(配置/风险/流动性/收益)+ AI 调仓 · **长文目录 TOC** | `checkup/*` · `_toc` |
| 管理 | `/admin` | **所有运营参数热改**(品牌/成员/周期/提醒/汇率/数据源/阈值/aksk/key)· 改即生效不重启 | `admin/*` |
| 公开 | `/`(landing) `/login` `/onboarding` | 落地页(含工程数字带)/ 登录 / 首次引导 | `landing.html`(`data-stat`)· `auth/*` · `onboarding/*` |

---

## 5. 研发迭代全流程(功能级 = 硬 gate,逐步停等审)

判定量级:**新 FR / 新页面 / 新表 / 多文件 = 功能级**,走全流程;纯 bug fix / 文案 / 单文件小改可直接做(仍守第 7、8 节)。不确定先问。

```
1. PRD        prd/vX.Y.md(用户视角 FR · 目标/非目标/验收标准)
   + preview  preview/vX.Y/<feature>.html(静态 mockup · 复用 ../assets/style.css + 晚清账册骨架)
   → ★ 用户评审(停,等明确通过)
2. TDD        tech-design/vX.Y.md(每个关键决策:2-3 备选 + 取舍 + 选定理由 + 为什么不选)
   → ★ 用户评审(停,等明确通过)
3. 代码 + QA  实现 + 单测 + docs/qa-cases.md 用例 + scripts/qa-run.sh 守护 + scripts/e2e.sh 主线
4. 自测       mvn -o test(全绿) · bash scripts/qa-run.sh(静态守护) · bash scripts/e2e.sh(端到端真验收) · 无头截图视觉验收
5. 部署 beta  sudo cp jar + restart → 走用户真实点击路径复验(顶栏进入 · 手机视图 · 落地卡片)
   → ★ 用户在 beta review
6. 发布 prod  用户回精确串 release vX.Y.Z → release-prod skill(见第 10 节)
```

- **每个 gate 单独产出、单独等审**;不要一轮把 PRD/TDD/code 做完再给看(那不是 gate 是既成事实)。
- **达成一致后**:端到端做到完,中途**不擅自** checkpoint / 重排 / 问"要不要继续";工程量超预期也不停(逐块 commit + 自验推进)。合法的停只有上面的 ★ gate。
- 文档与代码**必须同步**(见 L1/L5):任何代码改动都同步 prd / tech-design / CHANGELOG / docs/qa-cases,别等用户提醒。

---

## 6. 关键文件 / 路径 & 用法速查

| 路径 | 用途 | 用法 |
|---|---|---|
| `prd/vX.Y.md` | 需求(用户视角 FR) | 新版本新建;v0.1 已封板别改 |
| `tech-design/vX.Y.md` | 技术方案(选型+取舍) | 实施权威源 |
| `preview/vX.Y/<f>.html` | PRD 阶段交互预览 | 复用 `preview/assets/style.css` + 4 字体 + `kpi/pill/paper-card/eyebrow/btn-ink` 类;**别用废弃 `preview/pages/`** |
| `db/migration/V<n>__*.sql` | schema 迁移 | 只增不改已发布的;全 backward-compat(见 L7)。跑:`DB_USER=finance DB_PASS=finance DB_NAME=finance bash db/apply.sh`(prod 读 `/etc/finance.env`) |
| `scripts/qa-run.sh` | 黑盒静态守护(广度) | `bash scripts/qa-run.sh` · 加守护参考 `v12-*` 写法 |
| `scripts/e2e.sh` | 端到端真验收(深度 · mysqldump 快照/还原) | `bash scripts/e2e.sh` · 断言用**增量**不用绝对值 · 不用 pipefail |
| `docs/qa-cases.md` | QA 用例登记 | 每功能加一段 |
| `CHANGELOG.md` | 版本记录 | 每版一段 |
| `src/main/resources/templates/landing.html` | 落地页**工程数字带** | `data-stat` version/tests/migrations/blackbox 必须与现状一致(release preflight 硬门) |
| `.claude/skills/release-prod/` | 发布 prod skill | 见第 10 节 |
| `deploy/deploy.sh` `rollback.sh` | prod 部署/回滚 | 幂等 + 失败自动回滚 |
| `AGENTS.md`(本文) | 项目操作手册 | 每次迭代必过 |
| memory `~/.claude/projects/-home-finance-financial-management/memory/` | Claude 跨会话记忆 | 一事一文件 + `MEMORY.md` 索引;详细规则见各 `feedback_*` |

**无头截图视觉验收**(排版类问题渲染 beta 实际看,见 `reference_headless_screenshot`):
- chromium:`~/.cache/ms-playwright/chromium-*/chrome-linux64/chrome`;playwright-core:`~/.npm/_npx/*/node_modules/playwright-core`(路径 hash/版本会变,用前 `find` 确认)。
- 跑脚本前 `LD_LIBRARY_PATH=/tmp/xdmg/usr/lib/x86_64-linux-gnu`(缺 `libXdamage.so.1` → `apt-get download libxdamage1` + `dpkg-deb -x /tmp/xdmg`,免 sudo;`/tmp` 被清则重解)。CJK 字体 wqy-zenhei 在 `~/.local/share/fonts/`。
- 脚本套路:login(`diwa`/`demo1234`)→ goto 页 → `waitForTimeout(2500)`(等 Chart 画完)→ screenshot。

---

## 7. 联动不变量登记表(改 X → 必须同步 Y,否则下游静默错)

| # | 触发 | 必须同步 | 守护 |
|---|---|---|---|
| L1 · 收支口径 | 改收入/支出**来源口径**(PMC↔`cash_flow`)、`is_adjustment` 语义 | `FactViewServiceImpl`(`netInflowIncome/Expense`/`cashflowBreakdown`/`familyXirr` 外部流入)· `HouseholdCashflowService`(`incomeBlend`/月均/储蓄率)· **dashboard `CashflowSplitView.empty()`**(空态别只看 PMC filledMembers)· `reports/_savings` 空态 · 收入列表展示/合计 | `v12-INCOME-KOUJING/EMPTY` · `CashflowSplitEmptyTest` · `CashflowBreakdownTest` |
| L2 · 账户币种 vs 本位币 | 任何**展示/汇总** `cash_flow.amount` / `period_snapshot.end_balance` | 存的是**账户币种**;单账户展示用账户币种符号(`MoneyFormat`);**家庭/跨账户聚合必须 ×`fxToBase`**,不裸加 ¥;缺直接汇率反向取倒数兜底(`EntryController.toBaseAmount`/`AccountValuationService.resolveFxRate`) | `v12-INCOME-FX` |
| L3 · 比值类 KPI 币种不变性 | 新增/改**比例类**指标 | 三视图币种下**比值完全相等**、金额按 fx 缩放;两比值相比用**相减(pp)**非相除 | `v05-CCY-INV-1` · `v08-CCY-INV-3` · `CurrencyInvarianceTest` |
| L4 · 长文目录 TOC | `reports`/`dashboard`/`checkup` 加/改/删/调序 section | 同步该页 `fragments/_toc` + `static/js/toc.js` + section `id` 锚点 | `v05-TOC-1/2/3` |
| L5 · 主页数字带 + 文档 | 出新版本 / 加迁移 / 改单测数或黑盒数 | `landing.html` `data-stat`(version=prd 个数 · migrations=`V*.sql` 个数 · tests/blackbox=README)+ README「N 单元 / N 黑盒」+ `prd`+`tech-design`+`CHANGELOG`+`docs/qa-cases` | release preflight(硬门)· `v09-LAND-6` · `v07-CLEAN-2` |
| L6 · 股票估值/持仓模型 | 改 `stock_holding` 字段 / `ValuationMode` / 计值 | `AccountValuationService.valuateInternal`(AUTO/MANUAL/CASH 三分支)· 迁移 backward-compat(prod 老数据折算总值不变)· 持仓管理 UI + 收入侧联动 | `v12-MANUAL-SHARES` · `v03-STOCK-*` |
| L7 · prod backward-compat | 任何 schema/代码/部署改动 | 先想对线上现有数据影响:迁移只 `ADD COLUMN NULL`/数据折算不破坏;**回滚只回 jar 不回 DB → 迁移须向前兼容老 jar** | release preflight 迁移提示 |
| L8 · UI 规范 | 新增 UI 文案/图标 | **禁 emoji**,用 inline SVG(Feather 24×24 `stroke=currentColor`);入口/按钮命名**避免技术词**(集成/API/接口)让非技术家庭成员看得懂 | `TODO: no-emoji grep 守护` |
| L9 · 运营参数 | 新增阈值/aksk/节奏/手机号等运营配置 | 走**管理页**配(DB > env > 代码默认 三层 fallback)· 不写服务器配置文件;涉及外部平台接入配一键测试入口 | 人工 |

**新链怎么加**:出现"改 A 漏了 B"事故 → 加一行(触发/必须同步/守护)+ `qa-run.sh` 加静态 grep 把它网住,下次它自己 fail。

---

## 8. 全局护栏 / 纪律(踩过的坑 · 收敛清单)

**流程 / 交付**
- **验收走用户真实点击路径**(顶栏 tab → 落地卡片 → 手机视图),别只测端点/单测/grep;护栏守用户实际入口非旁路。优先 `e2e.sh`(唤起 beta + 调接口 + DB 真值),UT/qa-run 静态守护只作补充。
- **commit 自主;tag/push/发 prod 须用户验收**,每个新版本重新确认(授权不顺延),用精确串 `release vX.Y.Z`。
- 选型对比表**只放用户能感知的维度**,别把"我写代码省不省事"伪装成用户价值。
- 每次代码改动**主动同步文档**(prd/tech-design/CHANGELOG/qa-cases),不等提醒。

**代码 / 技术**
- **LLM 严禁做数学**:所有计算类指标工程算好填进 prompt;SYSTEM 加禁数学约束;prompt 里数字先 read 验证;LLM 输出胡话先怀疑 prompt。LLM 校验失败先抓 prompt + raw output。
- **图表数字浮层**:Chart.js 图用 datalabels 把金额/百分比绘在扇片/柱顶/数据点上,hover tooltip 不算。
- **Thymeleaf**:`#xxx.yyy()` utility 必须在 `${}` 内;conditional render 必须 force-trigger 验证;诊断 prod 栈先找最早 ERROR。
- **RestTemplate 调签名 URL**:自己签名+pctEncode 过的 URL 传 `URI.create(s)`,别传 String(会被二次编码 → `SignatureDoesNotMatch`)。
- **跨账户转账走 `transfer` 表**,绝不合并进 `cash_flow`(账户级方案红线)。

**运维小纪律**
- 清空文件用 `:> foo`,别用 `rm`(触发审批)。所有编码自己做,不用 codex 插件。
- beta 账期常被 qa-run/e2e 滚动搞乱(无 OPEN 期 / 冒出未来期)→ 修复:`SET FOREIGN_KEY_CHECKS=0` 删未来期(period_snapshot/snapshot_todo/cash_flow/transfer/period_member_cashflow/stock_valuation_event + period)+ `UPDATE period SET status='OPEN' WHERE id=<当月>`。

---

## 9. 命令速查

```bash
# 编译 / 测试(离线)
mvn -o -q compile              # 只编译
mvn -o test                    # 全量单测(当前 311)
bash scripts/qa-run.sh         # 黑盒静态守护(当前 431)
bash scripts/e2e.sh            # 端到端真验收(7 主线 38 断言 · 快照还原不污染)

# 部署 beta(自测全绿后)
mvn -o -q package -DskipTests
DB_USER=finance DB_PASS=finance DB_NAME=finance bash db/apply.sh    # 应用新迁移
sudo -n /bin/cp /home/finance/financial-management/target/app.jar /opt/finance/app.jar
sudo -n /bin/systemctl restart finance
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:20000/health   # 等 200

# 发布 prod(用户回 release vX.Y.Z 后)
bash .claude/skills/release-prod/release.sh preflight vX.Y.Z   # 阶段0 预检
# → 停,等用户精确回 `release vX.Y.Z`
bash .claude/skills/release-prod/release.sh tag-push vX.Y.Z
bash .claude/skills/release-prod/release.sh deploy vX.Y.Z
bash .claude/skills/release-prod/release.sh verify
```

---

## 10. 发布 prod(release-prod skill · 唯一闸门)

触发词:用户说「发布 / 上线 / release / ship / 发版」。流程(逐步不跳):
1. **阶段 0 预检**:`release.sh preflight [版本]` — git 干净 / tag 不重 / 列 commit+diff / 扫迁移 / **README 联动 + 主页数字带 + 部署脚本 硬门** / `mvn test`。
2. **阶段 0.5 硬 gate**:把预检输出给用户,**停下**。用户回**严格等于 `release vX.Y.Z`**(版本一致)才继续;回别的/不回 = 中止,不打 tag、不碰 prod。
3. **阶段 1** `tag-push`:annotated tag + push origin(gitlab)+ github。
4. **阶段 2** `deploy`:ssh prod checkout tag + `deploy.sh`(mysqldump 备份 + 迁移 + 切 jar + 健康检查 + 失败自动回滚)。
5. **阶段 3** `verify`:loopback + 域名 `/health`+`/login` 200。
6. **阶段 4** 成功 `notify ok` / 失败 `rollback` + `notify rollback`。

版本号约定:精化/bugfix 走 patch(vX.Y.Z);prod 落后时可跳版本发(tag 含全部增量)。
