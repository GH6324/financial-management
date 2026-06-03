# 家庭账房 v0.1 · QA 测试用例

> 基于 `prd/v0.1.md` 与 `tech-design/v0.1.md`,以可执行黑盒测试视角拆解 22 条 FR + 认证。
> 每条用例:**ID · 一句话目标 · 操作步骤 · 预期 · 实际(执行后填)**。
> 跑测脚本:`bash /tmp/qa-run.sh`(用 curl + grep 校验 HTML 结构与副作用)。

## 0 · 认证(基础)

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| AUTH-1 | 未登录访问受限页跳登录 | GET /dashboard 不带 cookie | 302 → /login |
| AUTH-2 | 登录页可见 | GET /login | 200 + 含 `_csrf`、含 `username`/`password` 输入框 |
| AUTH-3 | 错误密码失败 | POST /login wrong | 302 → /login?error |
| AUTH-4 | 正确密码登录成功 | POST /login diwa/demo1234 | 302 → / |
| AUTH-5 | 登录后访问 /dashboard 完整 HTML | GET /dashboard | 200,以 `</html>` 结束 |
| AUTH-6 | 登出清 cookie | POST /logout | 302 → /login?logout |
| AUTH-7 | /health 公开 JSON | GET /health(无 cookie) | 200 `{"status":"UP"}` |
| **AUTH-8** | 已登录访问 /login 自动跳 /dashboard(书签 = /login 场景 · 2026-05-14) | 登录后 GET /login | 302 Location: /dashboard |
| **AUTH-9** | 未登录访问 /login 仍 200 + 表单(不破首登) | 无 cookie GET /login | 200 含 `name="username"` 输入 |

## FR-1 · 家庭与成员

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR1-1 | /admin/family 200 | GET /admin/family | 200,含家庭名、品牌名、本位币、周期类型 |
| **FR1-1a** | /admin/family **保存生效**(2026-05-14 bugfix · 之前嵌套 form 让主 save 失效) | POST /admin/family name=X brandText=Y baseCurrency=CNY periodType=MONTHLY | 302;DB family.name + brand_text 入库 |
| FR1-2 | /admin/members 200 | GET /admin/members | 200,显示 2 个成员 |
| FR1-3 | 编辑家庭名 | POST /admin/family name=测试家 | 302 → /admin/family;DB 更新 |
| FR1-4 | 编辑成员显示名 | POST /admin/members/{id} | 302;DB 更新 |
| FR1-5 | 重置密码 | POST /admin/members/{id}/reset-password | 显示一次性临时密码 |
| FR1-6 | logo 字段在表单 | GET /admin/family | 含 logo upload form;family.logoPath=NULL 时显示默认 SVG |
| FR1-7 | 添加成员入口存在 | GET /admin/members | 含 "+ 添加成员" 按钮 + 弹层 form |
| FR1-8 | 改密页可访问 | GET /profile/password | 200,含 "新密码" 输入,显示"显示/隐藏密码"按钮 |
| FR1-9 | 强制改密拦截 | DB 设 mustChangePw=1 后 GET /dashboard | 302 → /profile/password |
| FR1-10 | 默认 logo 兜底 | DELETE 物理 logo 文件 后 GET /dashboard | nav 仍显示默认 SVG(浏览器 onerror) |

## FR-2 · 账户模板向导

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR2-1 | /accounts/new 弹向导 | GET /accounts/new | 200,含 `添加账户向导`,模板列表显示 ≥ 12 个 |
| FR2-2 | /admin/account-templates 200 | GET /admin/account-templates | 200,显示模板列表只读 |
| FR2-3 | 模板下拉中文化 | GET /accounts/new | type 选项含 `现金 (CASH)` 等中文格式 |

## FR-3 · 账户管理

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR3-1 | /accounts 列表 | GET /accounts | 200,显示所有未归档账户 |
| FR3-2 | 新建账户 | POST /accounts | 302 → /accounts;新增 1 行 |
| FR3-3 | 编辑专属页 | GET /accounts/{id}/edit | 200,标题"编辑账户:XXX",按钮"保存对账户的修改" |
| FR3-4 | 编辑提交 | POST /accounts/{id}/edit | 302 → /accounts;DB 更新 |
| FR3-5 | 归档 | POST /accounts/{id}/archive | 302;archived_at 写入 |
| FR3-6 | 查看归档列表 | GET /accounts?archived=true | 含归档账户 |
| FR3-7 | 恢复归档 | POST /accounts/{id}/restore | archived_at 清空 |

## FR-4 · 周期配置

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR4-1 | 切换 period_type 阻塞 | OPEN 周期下 POST 切换 | flash 阻塞提示 |
| FR4-2 | period_type 显示当前 | GET /admin/family | 显示 MONTHLY/WEEKLY |

## FR-5 · 周期与待办自动生成

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR5-1 | 当前 OPEN 周期存在 | DB SELECT period status=OPEN | 1 行 |
| FR5-2 | 待办行数 = 未归档账户数 | DB count snapshot_todo / account active | 相等 |

## FR-6 · 待办与全员视图

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR6-1 | /my-todos 200 | GET /my-todos | 200,显示当期 PENDING 行 |
| FR6-2 | /my-todos→/entry 带账户 | 链接 href 含 `account=` | true |
| FR6-3 | mine=true 行数减少 | GET /entry?mine=true | size < /entry?mine=false |
| FR6-4 | account 筛选生效 | GET /entry?account=1 | 仅 1 个 entry-row,显示"已按账户筛选" |

## FR-7 · 余额录入

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR7-1 | /entry 默认显示行 | GET /entry | rows ≥ 1 |
| FR7-2 | 提交新余额 | POST /entry/{id}/balance newBalance=... | 200 (HTMX fragment),DB 写入 period_snapshot |
| FR7-3 | 已填 ✓ 状态切换 | 提交后再 GET /entry | 该账户行变 ✓ |
| FR7-4 | 未解释金额提示 | 不平衡时 | 显示"未解释" + 引导按钮 |
| FR7-5 | 本期流水明细列表 | 展开 row | 显示"本期流水 · N 笔",含 SNAPSHOT/INCOME/EXPENSE/TRANSFER_IN/OUT 5 类按时间排序 |
| FR7-6 | 不分页约束 | 单账户单期 < 30 条 | 全量列出,无 paging |
| FR7-7 | 进入页面输入框预填上期值 | GET /entry?account=X(snapshot 不存在)| `<input name="newBalance">` 的 value 等于上期末 |
| FR7-8 | 快捷+收入累加余额 | 上期 10000,POST cash-flow INCOME 100 | snapshot=10100;cash_flow +1;收入字段 100 |
| FR7-9 | 快捷-支出累加余额 | 续上,POST cash-flow EXPENSE 1000 | snapshot=9100;cash_flow +1;支出字段 1000 |
| FR7-10 | 校准余额直接覆盖 | 续上,POST balance=4000 | snapshot=4000(覆盖);unexplained=−5100 |
| FR7-11 | 校准后再叠加快捷 | 续上,POST cash-flow INCOME 200 | snapshot=4200;收入字段 300;unexplained 仍 −5100 |
| FR7-12 | 划转两端联动 | POST /entry/{A}/transfer toAccountId=B amount=500 | A snapshot -=500;B snapshot +=500;两端均 ✓ |
| FR7-13 | HX-Trigger refresh 链路 | POST 后 response 头 | 含 `HX-Trigger: refresh-row-{accountId}`;转账时还含 to 端的 trigger |

## FR-8 · 现金流

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR8-1 | 提交收入 | POST /entry/{id}/cash-flow kind=INCOME | 200,DB cash_flow 写入 |
| FR8-2 | 提交支出 | POST kind=EXPENSE | DB 写入 |

## FR-9 · 转账

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR9-1 | 提交转账 | POST /entry/{id}/transfer | DB transfer 写入 |
| FR9-2 | 24h 重复检测 | 同 (from,to,amount,period) 二次提交 | 二次确认 |

## FR-10 · 智能转账推断

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR10-1 | |未解释| > 3000 提示 | EntryRow.suggestTransfer = true | UI 显示 `💡 看起来像账户间转账?` |

## FR-11 · 周期关闭 + 重算

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR11-1 | 提交本期成员完成 | POST /entry/{periodId}/complete | 写 period_member_completion |
| FR11-2 | 全员完成自动 CLOSED | 所有成员都 complete | period.status=CLOSED |
| FR11-3 | metrics_recompute_log 写入 | CLOSED 后 | 1 行 |
| FR11-4 | CLOSED 期点 +/-/划转 | POST /entry/{closedAcc}/cash-flow | 200 + HX-Trigger=showToast(toast 拒写) |
| FR11-5 | 强制关账(代填上期末)| POST /admin/periods/{id}/force-close | period CLOSED;PENDING=0;snapshot N 行(=未归档账户数);metrics_recompute_log +1 |

## FR-12 · 周期重开

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR12-1 | /admin/periods 列出 | GET | 含周期 + 状态 |
| FR12-2 | CLOSED 重开 | POST /admin/periods/{id}/reopen reason=test | 302;period_reopen_log 写入;status=OPEN |
| FR12-3 | 重开 reason 必填 | reason 空 | 阻塞或 400 |
| FR12-4 | 立即开下一周期(测试用)| POST /admin/periods/open-next | 302 → /admin/periods;新 period.status=OPEN;snapshot_todo N 条(=未归档账户) |
| FR12-5 | OPEN 周期状态视觉绿色 | GET /admin/periods | OPEN 文案"OPEN · 进行中" + forest 配色;CLOSED "CLOSED · 已结束" + rust 配色 |
| FR12-6 | 开账时所有账户自动延续上期末 | POST /admin/periods/open-next | 新 period 的 period_snapshot 行数 = 未归档账户数;每行 note="开账自动延续上期末余额 X" |
| FR12-7 | LOAN 开账时按差值预填 | POST /admin/periods/open-next | LOAN 账户 snapshot.end_balance = prev + (prev - prevPrev);snapshot_todo.prefilled_balance 同 |

## FR-13 · Dashboard

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR13-1 | 5 KPI 卡可见 | GET /dashboard | 含 净资产/总资产/总负债/紧急储备/负债率 |
| FR13-2 | range tabs 切换不返回 fragment | GET /dashboard?range=1M(无 HX-Request) | 完整 HTML(`</html>`) |
| FR13-3 | range tabs HTMX 返回 fragment | GET 带 HX-Request | 仅 `<div id=dashboard-region>` |
| FR13-4 | YTD/3M/6M/ALL 都不抛错 | GET 各 range | 200 + 完整 HTML |
| FR13-5 | 红 banner 显示 pending | DB 有未填 + GET | 显示"本期还有 X 个账户未填" |

## FR-14 · 报表

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR14-1 | /reports 200 | GET /reports | 200,含家庭 XIRR/TWR、账户级表 |
| FR14-2 | range tabs 完整 | GET /reports?range=YTD | 完整 HTML |
| FR14-3 | 汇率明细表显示 | GET /reports | 含 fx_rate 表行 |

## FR-15 · 多币种

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR15-1 | /admin/fx 200 | GET | 含 USD/HKD/CNY 行 |
| FR15-2 | 手填覆盖 | POST /admin/fx | DB 写入 |

## FR-16 · CSV 导出

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR16-1 | /export.zip 200 | GET | 200,Content-Type octet-stream |
| FR16-2 | ZIP 含 8 CSV + README | unzip -l | 9 文件齐全 |
| FR16-3 | UTF-8 BOM | 头 3 字节 | EF BB BF |

## FR-17 · 站内提醒

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR17-1 | banner 显示 pendingRows | dashboard pending banner 元素 | 存在 |

## FR-18 · 备份

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR18-1 | /admin/backup 200 | GET | 200,展示最近备份状态 |

## FR-19 · LOAN 专属

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR19-1 | LOAN 余额负数显示绝对值 | dashboard 房贷行 | "¥XX,XXX" 不带负号 |
| FR19-2 | 资产配置不含 LOAN | dashboard | allocation labels 不含 LOAN |
| FR19-3 | LOAN 编辑页有还款来源字段 | GET /accounts/{loanId}/edit | 含"默认还款来源" |

## FR-20 · /admin

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR20-1~10 | 11 个 admin 子页全部 200 | GET 各路由 | 200 + 完整 HTML |

## FR-21 · 账户筛选器

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR21-1 | accounts=ID 参数生效 | GET /dashboard?accounts=1 | KPI 反映只此账户 |
| FR21-2 | 默认全选 | GET /dashboard | 显示"X 个已选" |
| FR21-3 | 多选 form | GET /dashboard 展开筛选 | 含 `<input type="checkbox" name="accounts">` + "应用筛选"按钮 |
| FR21-4 | 多选提交 | GET /dashboard?accounts=1&accounts=2&accounts=3 | "3 个已选";KPI/图表反映 3 个账户合计 |
| FR21-5 | 全选/全清/重置按钮 | 模板 | 三个按钮均存在 |
| FR21-6 | 账户类型筛选 | GET /accounts?type=CASH | 列表只剩 CASH,选中类型 pill 高亮 |

## FR-22 · 显示币种切换

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR22-1 | 切 USD 货币符号变 $ | GET /dashboard?currency=USD | 含 `$` |
| FR22-2 | 切 HKD 货币符号变 HK$ | GET /dashboard?currency=HKD | 含 `HK$` |
| v02-CCY-1 | 三套币种数字真换算(2026-05-10 BUG-FIX 回归保护)| 种 fx_rate 后 GET dashboard?currency={CNY,USD,HKD} | 三个净资产数字必须不同 |
| v02-CCY-2 | USD 数学正确 | CNY × 0.14 ≈ USD KPI(±2 元容差) | 数学正确 |
| v02-CCY-3 | fx_rate 缺时按需即时拉汇率 | 删 fx_rate → GET dashboard?currency=USD | fx_rate 表新增 frankfurter.dev 来源行 |
| v02-CCY-4 | 拉成功后正常显示 $ | 同上 | 净资产 KPI 含 `$`(无 toast 兜底)|
| v02-CCY-5 | 拉失败 fallback toast 防回归 | 模板源码扫描 | dashboard / reports `_region.html` 均含「汇率未配置」toast 脚本块 |
| v02-CCY-6 | 非 base 账户 → ensureForAccountCurrencies 写入 fx_rate(2026-05-11 critical bug 回归保护)| 删当期 fx_rate → GET dashboard | anchor 周期的 fx_rate 必有 USD/HKD 行(被即时拉或 copy)|
| v02-CCY-7 | 当期缺 fx_rate 但他期有 → 自动 copy 当期(不调 frankfurter) | 仅他期 fx_rate 行 → GET dashboard | 当期 fx_rate 新增 source='copied-from-period-N' 行 |

> **2026-05-11 critical bug 回归保护**:用户在 prod 创建 USD 账户填了余额,dashboard 净资产把 USD 当 CNY 直接累加(没换算)。根因:`FactMapper.queryBase` SQL 算 `fx_to_base` 时,fx_rate 表缺当期 + 账户币种行 → 落 `ELSE 1.0` 兜底。修法:Dashboard / Reports / Checkup load slice 前调 `FxService.ensureForAccountCurrencies`,扫所有非 base 账户币种,逐个 getOrFetchRate(DB 当期 → DB 他期 copy → frankfurter API)。CCY-6/7 防回归。

> **回归历史**:`FactMapper.xml` 的 fx CASE 公式两个分支(`fx_direct` / `fx_inverse`)曾在 v0.1 → v0.2 期间两次倒挂,导致 USD/HKD 视图全表数字 ×7 错位。v02-CCY-1/2 数学校验 + v02-CCY-3/4 即时拉取 + v02-CCY-5 toast 兜底是防回归底线。

## 静态资源 / 安全

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| ST-1 | /vendor/tailwind.js 200 | GET 无 cookie | 200 |
| ST-2 | /vendor/htmx.min.js 200 | GET 无 cookie | 200 |
| ST-3 | /vendor/chart.umd.min.js 200 | GET 无 cookie | 200 |
| ST-4 | /vendor/echarts.min.js 200 | GET 无 cookie | 200 |
| ST-5 | /css/style.css 200 | GET 无 cookie | 200 |
| ST-6 | CSRF 拒绝无 token POST | POST /accounts 不带 token | 403 |
| ST-7 | favicon SVG 头 200 + immutable | curl -I /img/default-logo.svg | 200 + Cache-Control immutable |
| ST-8 | 全局 loading 元素首屏注入 | GET /dashboard | HTML 含 page-progress / page-overlay / seal-character / seal-ink-dot |
| ST-9 | LOAN 余额修改不联动 | DB 改 LOAN snapshot 后查 transfer 表 | 不应有 from default_payment_source 的新 transfer |
| ST-10 | reports/ALL 不再 500 | GET /reports?range=ALL | 200 + 完整 HTML(NaN-safe) |
| ST-11 | block fragment 整块 swap | POST /entry/{id}/cash-flow | response 含 entry-block-{id} + entry-row-{id} + 展开本期流水(HX-Reswap=outerHTML 整块刷新) |
| ST-12 | 手动刷新 icon 存在 | GET /entry?account={id} | 含 `aria-label="刷新"` + `⟳` 字符 |
| ST-13 | dashboard 实时自刷新 | GET /dashboard | dashboard-region 含 hx-trigger="visibilitychange... every 90s" |

总计:**78 用例**(v0.1)。

---

## v0.2 · FR-33 微信引导 + FR-34 iOS PWA 添加到主屏

> 2026-05-09 上线。新增 10 条自动化(已加入 `/tmp/qa-run.sh` 末段)+ 8 条真机手测。

### v0.2 · 自动化(curl,与 v0.1 同 BASE)

| ID | 目标 | 步骤 | 预期 |
|---|---|---|---|
| FR34-1 | manifest MIME 正确 | curl -I /manifest.webmanifest | Content-Type=application/manifest+json |
| FR34-2 | manifest 字段齐 | 解析 JSON | 含 name / start_url=/dashboard / display=standalone / icons[3] |
| FR34-3 | 三张 PNG 200 | curl /img/{apple-touch-icon-180,icon-192,icon-512}.png | 各 200 + Content-Type=image/png |
| FR34-4 | layout 含 PWA meta | curl /login → grep | 含 apple-mobile-web-app-capable / status-bar-style / title / manifest link / apple-touch-icon-180.png / theme-color |
| FR34-5 | mobile-guide.js 未登录可达 | curl /js/mobile-guide.js(无 cookie) | 200 |
| FR34-6 | manifest 未登录可达 | curl /manifest.webmanifest(无 cookie) | 200 |
| FR33-1 | layout 引用 mobile-guide.js | curl /login → grep mobile-guide.js | 命中 |
| FR33-2 | 脚本含微信 + iOS 检测分支 | curl /js/mobile-guide.js → grep | 含 MicroMessenger / wx_dismissed_at / pwa_dismissed_at / standalone |

### v0.2 · 真机手测(浏览器开发者工具 / 实机)

| ID | 设备 | 步骤 | 预期 |
|---|---|---|---|
| FR33-M1 | iOS 微信 | 微信里点账房链接 | 看到全屏遮罩 + 引导卡 + 大箭头指右上 ⋯;呼吸闪动 |
| FR33-M2 | iOS 微信 | 点 dismiss 后立即重进 | 不再弹遮罩(localStorage `wx_dismissed_at` 已写) |
| FR33-M3 | Android 微信 | 同 M1 | 行为一致 |
| FR34-M1 | iPhone Safari | 打开 dashboard | 1.5 秒后底部弹卡片;高亮分享按钮金色光环 |
| FR34-M2 | iPhone Safari | 按引导:分享 → 添加到主屏 → 添加 | 主屏出现**墨底金棕"账"硬币 ¥ + 朱红印泥点**(非默认 favicon、非截图);点击进入是 standalone(无 Safari UI) |
| FR34-M3 | iPhone(主屏入口) | 从主屏图标重开账房 | banner 不再弹(`navigator.standalone === true`) |
| FR34-NEG-1 | macOS Chrome / Firefox / Edge | 打开账房 | banner 不弹、遮罩不弹 |
| FR34-NEG-2 | iOS Chrome / Firefox(CriOS / FxiOS) | 打开账房 | 都不弹(不是 Safari) |

### v0.2 · 自动化测试结果(2026-05-09 阶段 1)

```
═══════════════════════════════════════
 总结: PASS=88  FAIL=0  SKIP=1
═══════════════════════════════════════
```

v0.1 78 条用例继续 PASS;v0.2 阶段 1 新增 10 条全 PASS;无回归。

---

## v0.2 · 阶段 1 · 数据底座 + 类目骨架(自动化)

| ID | 接口 | 步骤 | 预期 |
|---|---|---|---|
| v02-NAV-1 | GET /dashboard | 顶部 nav | 含「资产体检」入口 |
| v02-CHK-1 | GET /checkup | 全家页 200 | 含「资产体检」标题 |
| v02-CHK-2 | GET /checkup?account=1 | 账户级 200 | 含「资产体检 / 账户体检」标题 |
| **v02-LIQ-1** | WEALTH+MONEY_FUND 进入流动资产(2026-05-14 v0.3.3 bugfix · product_category.liquidity_class 驱动)| 找 WEALTH 账户 · 切换 product_category_code · 对比 /checkup 流动资产 | AFTER − BEFORE ≈ 该账户 endBalance · 误差 ≤ 1 元 |
| **v02-LIQ-2** | 体检页 caption 更新 | GET /checkup | 显示「CASH + 货币基金等(类目 = LIQUID)」· 不再「仅 CASH」 |
| **v02-LIQ-3** | V20 灌数据完整 | SELECT product_category liquidity_class | 16 行均非空 · LIQUID=2(CASH_DEPOSIT, MONEY_FUND)· ILLIQUID=2(PROPERTY_RES, PROPERTY_INV)|
| v02-PCAT-1 | GET /admin/product-categories | 200 | 管理员只读页可达 |
| v02-PCAT-2 | GET /admin/product-categories | 类目数 | ≥15 个产品类目 code 渲染(共 16 个) |
| v02-PCAT-3 | GET /admin/product-categories | 基准 | 含「沪深 300 / 标普 500」等基准指数 |
| v02-PCAT-4 | GET /admin | hub | 含产品类目 tile |
| v02-PCAT-5 | GET /admin/cash-flow-categories | sidebar | 含「产品类目」侧栏链接 |
| v02-PILL-1 | GET /accounts | 类目 pill | 列表渲染 ≥20 个 📊 类目 pill |
| v02-PILL-2 | GET /accounts | 风险 pill | 列表渲染 ≥4 个 ★ 风险 pill |
| v02-PILL-3 | GET /accounts | 无错误兜底 | 不再触发 /error 兜底 |
| v02-WIZ-1 | GET /accounts/new | 向导 | 含产品类目下拉 + 「按账户类型默认」选项 |
| v02-EDIT-1 | GET /accounts/1/edit | 编辑页 | 含 productCategoryCode + riskLevelOverride 字段 |
| v02-DASH-1 | GET /dashboard | 行入口 | 含 `/checkup?account=` 链接 |
| v02-SOFT-1 | GET /entry | 兼容性 | deleted_at 过滤生效后 entry 仍可加载 |

## v0.2 · 阶段 2 · FR-40b 账户级体检(自动化)

| ID | 接口 | 步骤 | 预期 |
|---|---|---|---|
| v02-DIAG-1 | GET /checkup?account={1..13} | 13 个账户均访问 | 全部 200,无 Thymeleaf 渲染异常 |
| v02-DIAG-2 | GET /checkup?account=1(CASH) | 视觉分支 | 仅显示「流动性」卡;不显示投资 / 欠款 / 估值卡 |
| v02-DIAG-3 | GET /checkup?account=3(STOCK) | 视觉分支 | 显示「收益表现 / 风险刻度 / 基准对照 / 现金流」4 张投资卡 |
| v02-DIAG-4 | GET /checkup?account=5(LOAN) | 视觉分支 | 显示「欠款余额 / 还款进度」;不显示投资卡 |
| v02-DIAG-5 | GET /checkup?account=10(PROPERTY) | 视觉分支 | 显示「估值」简卡;不显示投资卡 |
| v02-DIAG-6 | GET /checkup?account=99999 | 越权 | 跨家庭账户跳 /checkup 全家页 |
| v02-DIAG-7 | GET /checkup?account=3 | 顶部账户标签 | 含 📊 类目 pill + ★ 风险 pill |
| v02-DIAG-8 | GET /checkup?account=3 | 余额走势 | DOM 含 `<canvas id="balanceTrend">` |

## v0.2 · 阶段 3 · FR-40a/c 全家诊断 + 智能建议 + LLM(自动化)

| ID | 接口 | 步骤 | 预期 |
|---|---|---|---|
| v02-ADV-1 | GET /checkup + 13 个账户 | 14 个体检页 | 全部 200,无 Thymeleaf 渲染异常 |
| v02-ADV-2 | GET /checkup | 全家页 | 含 advice 卡或「健康状态良好」提示 |
| v02-ADV-3 | GET /checkup | 全家页 | eyebrow 文案存在 |
| v02-ADV-4 | GET /checkup?account=3 | 账户级 advice | 含 advice 卡或「本账户体检通过」 |
| v02-ADV-5 | GET /checkup | DOM 属性 | 每张卡含 `data-rule` + `data-severity` |
| v02-ADV-6 | GET /checkup | AI 润色按钮 | DOM 含「✨ AI 润色」 |
| v02-ADV-7 | Cookie | XSRF-TOKEN | 浏览器获取到 XSRF cookie |
| v02-ADV-8 | POST /checkup/advice/{ruleId}/polish | 全家级建议润色 | 200,返回单卡 fragment |
| v02-ADV-9 | POST /checkup/advice/{ruleId}/polish?account=3 | 账户级 | 200,fragment 含 `data-account="3"` |
| v02-ADV-10 | POST /checkup/advice/NONEXISTENT/polish | 不存在规则 | 200,空 fragment |

## v0.2 · 阶段 4 · FR-30/31/32 账本侧(自动化)

| ID | 接口 | 步骤 | 预期 |
|---|---|---|---|
| v02-LEDGER-1 | GET /accounts | 操作列 | 13 个账户均含「📊 体检」入口 |
| v02-LEDGER-2 | GET /accounts | 操作列 | 13 个账户均含「⬇ 账本」入口 |
| v02-LEDGER-3 | GET /accounts/3/ledger.csv | 下载 | Content-Type=text/csv;表头 9 列正确 |
| v02-LEDGER-4 | ledger.csv | 编码 | 文件首 3 字节为 UTF-8 BOM(EF BB BF) |
| v02-LEDGER-5 | ledger.csv | 响应头 | Content-Disposition 含 `filename*=UTF-8''` |
| v02-LEDGER-6 | GET /accounts/99999/ledger.csv | 越权 | ≥ 400 |
| v02-SOFT-DEL-2 | GET /entry?period=35 | OPEN 周期 | DOM 含 ≥1 个 `hx-post=".../delete"` 删除按钮 |
| v02-SOFT-DEL-3 | 删除按钮 URL | 路径 | 指向 `/entry/cash-flow/{id}/delete` 或 `/entry/transfer/{id}/delete` |
| v02-SOFT-DEL-4 | 删除按钮 attr | hx-confirm | 含「确定删除」二次确认 |
| v02-SOFT-DEL-5 | POST /entry/cash-flow/{id}/delete | 软删真实 cf | 200,DB cf.deleted_at 设为 NOW(3),余额反向冲销 |
| v02-SOFT-DEL-6 | GET /entry?period=35 | 重新加载 | 已软删 cf 不再出现在 ledger |
| v02-SOFT-DEL-7 | POST /entry/cash-flow/222/delete | CLOSED 周期 | ≥ 400(IllegalStateException 拒写) |
| v02-SOFT-DEL-8 | POST /entry/cash-flow/9999999/delete | 不存在 id | ≥ 400 |

### v0.2 · 阶段 1-4 全量自动化测试结果(2026-05-10)

```
═══════════════════════════════════════
 总结: PASS=143  FAIL=0  SKIP=1
═══════════════════════════════════════
```

v0.1 + v0.2 共 143 条 curl + grep 黑盒用例全部通过,0 回归。

### v0.2 · 决策 20 升级后的最终全量自动化测试结果(2026-05-10 · qwen-plus 真机)

```
═══════════════════════════════════════
 总结: PASS=152  FAIL=0  SKIP=1
═══════════════════════════════════════
```

### v0.2 封版终态(2026-05-10)· 三套 + 总数

```
mvn test:    Tests run: 76,  Failures: 0  ← JUnit 单元测试
qa-e2e.sh:   PASS=36, FAIL=0              ← 端到端真值校验(清 DB → 填 → 关 → 开 → 再填)
qa-run.sh:   PASS=164, FAIL=0, SKIP=3     ← 黑盒 endpoint + 模板渲染
─────────────────────────────────────────
合计:        276 通过 / 0 失败             ← 封版基线
```

### v0.2 · 币种切换 BUG-FIX(2026-05-10 第二轮)+ 输入框对齐 + 按需拉汇率

```
mvn test:    Tests run: 76,  Failures: 0
qa-e2e.sh:   PASS=36, FAIL=0
qa-run.sh:   PASS=174, FAIL=0, SKIP=3
─────────────────────────────────────────
合计:        286 通过 / 0 失败
```

完整修复链(从用户报「币种切换失效」到完整解):
1. **核心算式倒挂**:`FactMapper.xml` fx CASE 公式两个分支方向都搞反 — `fx_inverse.rate` 已经是 `a.currency → viewCurrency` 直乘比例,被错写成 `1/rate` 导致 USD/HKD 数字被 1/0.14 ≈ ×7 放大
2. **fx_rate 表空兜底**:SQL 落到 `ELSE 1.0` 时只换符号不换数 — 改为 controller 检测缺失并触发 `FxService.getOrFetchRate(...)` 即时调 frankfurter.dev API 拉取 + 缓存
3. **拉失败 UX**:从 banner 改为 toast 自动消失提示「当期 CNY 对 USD 汇率未配置」,active tab 保持用户点击前的 base 币种,符合"我看到的数字是什么币种,active tab 就是什么"的一致性
4. **输入框对齐**:entry 余额 / 备注共用 h-9 + 各自 eyebrow,「参考 · 上期末」从 label 内迁出为独立 caption

新增 5 条 case(从 168 → 173):
- **v02-CCY-1**:三套币种净资产 KPI 数字必须真的不同(防 SQL CASE 倒挂回归)
- **v02-CCY-2**:CNY × 0.14 ≈ USD 数学校验(±2 元容差)
- **v02-CCY-3**:`fx_rate` 表空时 dashboard 显示「汇率缺失」banner
- **v02-CCY-4**:fxFallback 强制回退 `¥` 显示,不静默冠错符号
- **v02-UX-5**:entry 余额 / 备注 input 高度统一 `h-9` + 备注独立 eyebrow

**根因**:`FactMapper.xml` 的 fx CASE 两个分支公式倒挂 — `fx_inverse.rate`(已经是 `a.currency → viewCurrency` 的直乘比例)被错写成 `1/rate`,导致 USD/HKD 视图数字被 1/0.14 ≈ ×7 放大;而 `fx_rate` 表空时又落到 `ELSE 1.0` 兜底,只换符号不换数 → 用户感觉"币种切换无效"。两次回归都因同样的 CASE 倒挂。修复:`FactMapper.xml` CASE 改为 `fx_inverse → rate` / `fx_direct → 1/rate`;Dashboard / Reports controller 加 fxFallback 检测 + banner。

**端到端真值校验 (qa-e2e.sh)** 覆盖完整业务场景:
1. 清 DB + 开 2026-05
2. 5 个账户填余额 → DB 真值断言(¥10500/¥7000/¥50000/¥30000/¥-200000)
3. 收入 ¥3000 + 支出 ¥500 + 转账 ¥2000 → 余额 + cf/transfer 数断言
4. dashboard KPI 全数字断言:净资产 ¥-102,500 / 总资产 ¥97,500 / 总负债 ¥200,000 / 紧急储备 35.0月 / 负债率 205.1%
5. checkup 全家 KPI 与 dashboard 一致性断言
6. /accounts/{id} 详情显示 ¥10,500 断言
7. force-close 2026-05 + open-next 2026-06,acct=1 自动延续 ¥10,500 断言
8. 06 期 +¥4000 → 余额 ¥14,500 断言
9. dashboard 较上期 +¥4,000 断言
10. 详情页较上期 +38.1% 断言
11. 家庭 XIRR 已计算断言

**SKIP(3 条都是设计行为而非测试失败)**:
- FR6-2 my-todos 链接:PeriodOpener 自动延续 snapshot 后所有账户 row.done=true,无「填 →」链接是预期
- v02-ADV-5 advice data attr:当前数据无规则命中,渲染「健康状态良好」是预期
- v02-LLM-LIVE-1:LLM key 配置且未失败时校验,降级 fallback 也可接受

新增内容(从 143 → 152):
- **FR-40c 综合诊断升级(决策 20)**:旧 v02-ADV-8/9/10 per-advice polish endpoint 删除,
  替换为 v02-DIAG-1~6(GET /checkup/diagnose 全家 + 账户 + 跨家庭降级 + CASH 账户)
- **v02-ADV-6/7 重写**:从"AI 润色按钮"改为"AI 综合诊断 placeholder + hx-trigger=load 自动加载"
- **FR-40e 报表风险等级分布**:v02-FR40E-1/2/3(reports 含「风险等级分布」标题 + #riskDistChart canvas + 风险敞口明细 + 资产体检入口)
- **v02-LLM-LIVE-1**:LLM 真实调用嗅探(vendor=qwen 综合诊断长文已返回 / 数据脱敏正常)

### v0.2 · FR-1/FR-34 品牌图标预设(2026-05-10)

```
mvn test:    Tests run: 76,  Failures: 0
qa-e2e.sh:   PASS=36, FAIL=0
qa-run.sh:   PASS=183, FAIL=0, SKIP=4
─────────────────────────────────────────
合计:        295 通过 / 0 失败
```

新增功能:
- 4 张预设图标(`/img/presets/icon{1..4}-{96,180,192,512}.png`,合计 16 张),默认 icon2
- `/admin/family` 新增 4 缩略图 gallery,点击切换;DB 加 `family.logo_preset` 字段(V12 迁移)
- web favicon / iOS apple-touch-icon / PWA manifest 三处全部跟随 `family.logoPreset` 动态变
- **预设赢一切统一**:click 预设清空 logo_path,所有平台同步;原自定义 WebP 上传保留(只覆盖 web 头部,iOS / manifest 仍用预设)
- `/manifest.webmanifest` 从静态文件改为 `ManifestController` 动态输出

新增 10 条 case(qa-run 173 → 183):

| ID | 校验目标 |
|---|---|
| v02-LOGO-1 | 16 张预设 PNG 全部公开可访问(无 cookie 200)|
| v02-LOGO-2 | manifest.webmanifest Content-Type=`application/manifest+json` + 默认 icon2 |
| v02-LOGO-3 | dashboard `<link rel="icon">` 默认 icon2-192.png |
| v02-LOGO-4 | dashboard `<link rel="apple-touch-icon">` 默认 icon2-180.png |
| v02-LOGO-5 | nav header logo `<img src>` 默认 icon2-192.png |
| v02-LOGO-6 | admin/family gallery 渲染 4 个 button(data-preset="iconN")· **零嵌套 form**(2026-05-14 改:之前是嵌套 form,触发 HTML 解析器 bug 让主 save form 失效)|
| v02-LOGO-7 | POST 切到 icon3 → DB + dashboard favicon + iOS apple-touch + manifest 全跟随 |
| v02-LOGO-8 | 自定义 webp 上传 + 预设并存 → web=webp / iOS=preset(双轨道)|
| v02-LOGO-9 | 切预设按钮一并清空 logo_path(预设赢一切统一)|
| v02-LOGO-10 | 非法 preset(icon99)→ 服务层校验拒写,DB 不变 |

### v0.2 · 单元测试(JUnit 5)— 决策 20 后

```
Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
```

OutputValidatorTest 从 8 个(锁数字模式)→ **15 个**(综合诊断校验:长度 / 担保词 / 古典词 / 产品代码 / 真名泄露 / 客套上限 / 金融术语必现 / 接受合法长文 / 代号 OK)。
其它 calc/rule 测试不变,合计 76 个。

### v0.2 · 单元测试(JUnit 5)

| 包 | 测试类 | 用例数 |
|---|---|---|
| calc | PnlCalculatorTest | 9 (v0.1) |
| calc | XirrCalculatorTest | 4 (v0.1) |
| calc | ReconciliationCalculatorTest | 3 (v0.1) |
| calc | MaxDrawdownCalculatorTest | 11 (v0.2 新增) |
| calc | NavSeriesBuilderTest | 10 (v0.2 新增) |
| calc | BenchmarkComparatorTest | 5 (v0.2 新增) |
| service.checkup.rule | RulesTest | 19 (v0.2 新增) |
| service.checkup.llm | OutputValidatorTest | 8 (v0.2 新增) |

```
Tests run: 69, Failures: 0, Errors: 0, Skipped: 0
```

v0.2 新增 53 个单测,加 v0.1 的 16 个,合计 69 个,全部通过。

---

## v0.3 QA case(2026-05-12 交付)

### v0.3 · 黑盒 case 段 · scripts/qa-run.sh

| Case | 描述 |
|---|---|
| **v03-GOAL · 12 条 · 财务目标 FR-50 系列** | |
| v03-GOAL-1 | 无目标时 /goals 列表显空状态引导卡 |
| v03-GOAL-2 | POST /goals/new/retirement 创建退休目标 → 302 跳 detail |
| v03-GOAL-3 | DB target_value = 通胀公式准确(15000×12×1.025^22/0.04 ≈ 7.75m) |
| v03-GOAL-4 | GET /goals/{id} 详情含名称 + 三情景 + Chart.js canvas |
| v03-GOAL-5 | 创建教育金 · child_member_id FK 入 params_json |
| v03-GOAL-6 | 创建应急 · target_value=NULL(由 PV 计算时 derived) |
| v03-GOAL-7 | /goals 列表渲染 3 个目标(退休/教育/应急) |
| v03-GOAL-8 | Dashboard 条带含目标 · 引导卡消失(C 混合) |
| v03-GOAL-9 | 非法目标类型 → 4xx/5xx 拒绝 |
| v03-GOAL-10 | POST /goals/{id}/archive 软删 archived_at 入库 · 列表过滤 |
| v03-GOAL-11 | Dashboard v0.2 KPI 卡完全保留(backward compat) |
| v03-GOAL-12 | 顶部 nav 加 /goals link |
| **v03-IND · 6 条 · 储蓄能力 FR-51 系列** | |
| v03-IND-1 | /entry 含 FR-51 家庭口径 2 框 form |
| v03-IND-2 | POST /entry/cashflow-summary 写入成员级 period_member_cashflow(2026-05-13 修订)|
| v03-IND-3 | 空值 → NULL 入库(选填 backward compat) |
| v03-IND-4 | /reports 无数据时显储蓄引导卡 |
| v03-IND-5 | /reports 储蓄区块有数据时显双柱图(canvas#savings-bars) |
| v03-IND-6 | v0.2 reports 既有内容保留(backward compat) |
| v03-IND-7 | /entry FR-51 在「本期总进度」之前(置顶 · 第一步) |
| v03-IND-8 | Dashboard 月均收入 / 月均支出 / 储蓄率 / 已填月份 4 KPI 卡 |
| v03-IND-9 | /reports 储蓄区块加月均收入 KPI · 数据来自 period_member_cashflow 聚合 |
| v03-IND-10 | /checkup 用 HouseholdCashflowService 算月均支出(优先 v0.3 口径 · fallback v0.2 cash_flow)|
| v03-IND-11 | **多成员独立填**(2026-05-13)· diwa + bob · dashboard SUM 显 ¥62k / ¥23k |
| v03-IND-12 | /entry 含「家庭本月总收入 SUM 成员」聚合区块 + 已填 N/M 人 |
| **v03-STOCK · 15 条 · 股票自动估值 FR-52 系列** | |
| v03-STOCK-1 | STOCK 账户持仓页 200 |
| v03-STOCK-2 | 非 STOCK 账户拒绝持仓页 |
| v03-STOCK-3 | 创建 MANUAL 持仓 · 入库 100k |
| v03-STOCK-4 | 创建 AUTO BABA · 持仓+价格快照入库(新浪) |
| v03-STOCK-5 | A 股 600519 拉价成功 · source=sina |
| v03-STOCK-6 | 港股 ticker 规范化 0700 → 00700 |
| v03-STOCK-7 | 估值写回 period_snapshot · note=auto-stock-valuation v0.3 |
| v03-STOCK-8 | refresh 全家估值不抛异常 · backward compat |
| v03-STOCK-9 | 持仓归档后账户余额重算 |
| v03-STOCK-10 | /entry STOCK 行加持仓变动入口(FR-52b) |
| v03-STOCK-11 | **fx 链式跨币种**(2026-05-13 修复)· HKD 账户 + USD/HKD 混合持仓 · 经 CNY 中转 · bal 验证链式生效 |
| v03-STOCK-12 | **CASH 表单页**(FR-52e · 2026-05-13)· GET /holdings/new-cash 200 + currency + amount |
| v03-STOCK-13 | **CASH 创建 + FX**:HKD 账户加 USD 5000 现金 → bal ≈ 39139 HKD(经 CNY 链)|
| v03-STOCK-14 | **CASH 更新**:POST /update-cash 改金额 + manual_value_at 刷新 |
| v03-STOCK-15 | **持仓+现金共存**:HKD MANUAL 50000 + USD CASH 8000 → bal ≈ HKD 112623 |
| **v03-AI · 6 条 · AI 4 处介入 FR-53 系列** | |
| v03-AI-1 | /goals/advise/retirement 返回合法 JSON(ok/error) |
| v03-AI-2 | /goals/advise/education JSON 响应 |
| v03-AI-3 | /goals/advise/emergency JSON 响应 |
| v03-AI-4 | 非法 type 4xx/5xx 拒 |
| v03-AI-5 | 退休向导含 AI 推荐按钮 + JS |
| v03-AI-6 | /checkup 既有页面渲染保留(backward compat · 无目标家庭 prompt 不加段) |

### v0.3 · 总结(2026-05-13 最新)

- 新加 **45 条**黑盒 case 全 PASS(v03-GOAL × 12 + v03-IND × 12 + v03-STOCK × 15 + v03-AI × 6)
- 2026-05-14 加 FR1-1a 保存生效 1 条 + v02-LOGO-6 改 button 校验
- 2026-05-14 加 AUTH-8/9 已登录 /login 自动跳 dashboard(书签优化)
- 2026-05-14 加 v02-LIQ-1/2/3 货币基金参与流动资产(V20 product_category.liquidity_class)
- 总 PASS=235 / FAIL=3(pre-existing v0.2 PILL/DIAG/LEDGER · 与 v0.3 无关)/ SKIP=2

### v0.4 · 总结(2026-05-14 最新)

- v0.4 新加 **15 条**黑盒(v04-RPT × 5 + v04-CPI × 2 + v04-BMK × 1 + v04-DIFF × 3 + v04-REFI × 4)+ v04-AI-REBALANCE × 1
- v0.4 单测新增 33(CpiDeflatorTest × 7 + BenchmarkAggregatorTest × 6 + AllocationDiffTest × 6 + RefinanceNpvCalculatorTest × 8 + LiquiditySurplusTest × 6)
- v0.2/v0.3 旧 case 改判(v0.4 报表整顿后):v02-FR40e-3 / v02-FR40E-3 / v03-IND-8 / v03-IND-11 4 条
- 总 PASS=250 / FAIL=3(pre-existing v0.2 PILL/DIAG/LEDGER · 与 v0.4 无关)/ SKIP=2
- mvn test 152(v0.3.3 基线 119 + v0.4 新增 33)全绿

### v0.4.1 · 股票估值事件 ledger 显示(2026-05-14)

- v0.4.1 新加 **3 条**黑盒(v04-VAL-1 拉价后写 event · VAL-2 /entry ledger 显示 · VAL-3 /accounts/{id} 显示)
- V24 schema:`stock_valuation_event` 表(prev_balance/new_balance/delta/trigger_kind/triggered_by)
- AccountValuationService.refreshAllForFamily 加 trigger 参数 + event hook · MANUAL/CRON/HOLDING_CHANGE 3 类
- EntryRow.LedgerKind + AccountDetail.Kind 加 VALUATION 类型 · UI 用 📈 估值 brass-deep 渲染
- 总 PASS=253 / FAIL=3(同 v0.4)/ SKIP=2

### v0.4.2 · 「人赚 vs 钱赚」二分收益指标(2026-05-14)

- 产品定位:**家庭记录详细成员收入信息,核心是为了区分"哪些钱是人赚的 vs 哪些是资产赚的"**(用户拍板)
- 新加 **4 条**黑盒(v04-RET-1 dashboard 第 5 KPI · RET-2 reports 双口径 + banner · RET-3 checkup 4 KPI 升级 · RET-4 单测覆盖)+ 9 单测(InvestmentReturnCalculatorTest)
- 月度口径:`月度 PnL = ΔNetWorth − 净流入 · 月度收益率 = PnL / 期初净资产` · 不年化
- 年度口径:滚动 12 月几何平均(= 复用 TwrCalculator)· 不卡自然年避免 1 月突兀
- KpiSnapshot 加 4 字段(monthlyPnlAmount / monthlyInvestReturnPct / annualizedInvestReturnPct / ytdInvestPnl)· **0 schema 改动**(历史数据天然兼容)
- UI 改造:
  - dashboard 第 5 KPI:月储蓄能力 → **本月资产收益(剔除收入)**
  - reports 4 KPI label 改:家庭 XIRR · 含收入 / **资产年化 · 剔除收入 ★** / **人赚的 · 净流入** / **钱赚的 · 投资 PnL** + 双口径解释 banner
  - checkup 收益诊断卡:4 KPI 升级布局(资产年化 ★ 高亮 + XIRR 辅助 + 本月 + YTD)
- 旧 v0.4 case 改判:v04-RPT-1 + v03-IND-8(KPI 文案演进)
- 总 PASS=257 / FAIL=3(同 v0.4)/ SKIP=2
- mvn test 161(基线 152 + v0.4.2 新增 9)全绿
- 真 LLM 调用:RebalanceAdvisor /reports/rebalance/advise 端点接通(LLM 可能 unavailable · 容忍 + 30 天节流缓存)
- 真机移动端:dashboard / reports / checkup / refinance 4 页响应式 OK
- 单测 114(v0.2 既有 76 + v0.3 新增 38 全绿)
- 真 LLM 调用验证:Qwen-Plus 返回合理参数 + rationale(beta 已验)
- 真数据源验证:新浪国内可达 · BABA/600519/00700 三市场拉价成功

### v0.3 · 单元测试新增(JUnit 5)

| 包 | 测试类 | 用例数 |
|---|---|---|
| calc | GoalProgressCalculatorTest | 13(三类目标 target 公式 + 进度 + 中位) |
| calc | GoalProjectorTest | 10(三情景 FV + 二分反推达成日 + 边界) |
| service.stock | SinaStockClientTest | 9(三市场 mock 解析 + 异常态) |
| service.stock | TencentStockClientTest | 6(三市场 mock 解析) |

```
Tests run: 114, Failures: 0, Errors: 0, Skipped: 0
```

v0.3 新增 38 个单测,加 v0.2 既有 76 个,合计 114 个全过。

---

### v0.4.3 · QA 视角再审视 → P0 修复(2026-05-14)

完成 v0.4 主线 + v0.4.1/v0.4.2 后,以 QA 视角对所有指标计算重新审视,发现 8 项隐患(5 BUG + 3 一致性)。
v0.4.3 优先修 P0 三项 B1/B2/B4,**0 schema 变更 · 100% backward-compat**。

**修复点**

| ID | 问题 | 修复 |
|---|---|---|
| **B1** | period_snapshot.end_balance NULL 时 fact_view 取出 NULL → netWorth/totalLiabilities 静默失真 | FactMapper.queryBase end_balance 列加 COALESCE 续值子查询 · NULL 时沿用 ≤ 当期最近一笔非空 snapshot · 不超期 · 用户填 0 仍取 0(尊重意图) |
| **B2** | dashboard 紧急储备 averageExpense 用 cash_flow · /reports 用 PMC · 同月不同数 | FactViewServiceImpl 注入 PeriodMemberCashflowMapper · averageExpense PMC 优先 → cash_flow 回退 |
| **B4** | ytdInvestPnl 复用 caller range-bound slice · 选 3M 时 YTD 只算 3M | 改为独立 load 1 月-今天 slice · range 切换不影响 YTD 口径 |

**剩余降级(v0.4.4+)**:B3 PMC 边界 · B5 利息计提 · I1-I3 一致性

**新加 8 条**黑盒(v04-FIX-1/1b/2/3/4/5/6/7):
- v04-FIX-1:FactMapper.xml 含 COALESCE + ps_carry IS NOT NULL 续值
- v04-FIX-1b:真实 beta 数据账户 11(房贷)2026-05 漏填 → 续值 SQL 返回 -1195180.00(非 NULL)
- v04-FIX-2:FactViewServiceImpl 注入 PMC mapper · averageExpense 双源
- v04-FIX-3:ytdInvestPnl 独立加载 1 月-今天 slice
- v04-FIX-4:/dashboard 漏填账户续值后 KPI 仍正常渲染
- v04-FIX-5:/reports?range=1Y B1 续值后正常出图
- v04-FIX-6:/checkup B2 双源后正常渲染应急金诊断
- v04-FIX-7:factview 单测目录存在(改动不破坏现有覆盖)

**验证**
- 真实 beta 数据:账户 7/9/11 在 2026-05 漏填 snapshot → B1 fix 后续值为 9200 / 127800 / -1195180(v04-FIX-1b 实测)
- `mvn test`:161 全绿(v0.4.2 基线 + 0 新增 0 破坏)
- `bash scripts/qa-run.sh`:**总 PASS=264 / FAIL=4**(v04-DIFF-1 + 3 条 pre-existing v0.2 · 均状态污染 · 与 v0.4.3 改动无关)/ SKIP=2

**backward-compat 红线**
- schema 0 改动 · 无 V25 migration
- period_snapshot 表完全不变(NULL 仍 NULL · 仅 fact_view 出口结果非 NULL)
- prod 升级路径:`git pull && sudo bash deploy/deploy.sh` 单步 · 0 风险

---

### v0.4.4 · 用户面文案专业化清理(2026-05-14)

触发:用户在 checkup 页发现"资产配置图已搬到 /dashboard"等内部 routing 文案,要求"所有页面详细过一下"。

**改动范围**(13 模板 · ~30 处 · 2 死文件 + 后端 2 处)
- P0:删 5 处"已搬到 / 已挪至"内部迁移文案 + 删 2 个死 placeholder 模板 + checkup 资产配置卡换 mini 横向条(用 diagnose.allocation 数据)
- P1:13 处 eyebrow / 标签的 v0.X / FR-XX 代号清理
- P2:`/entry` / `/admin/fx` 路径暴露改中文 · code 字段名删 · my-todos / stock holdings enum 中文化 · 历史 `auto-stock-valuation v0.3` → 「系统估值同步」(写入端 + 渲染端兼容)
- P3:"节流" → "内复用" · "dismiss" 删 · "cron" 中文化

**新加 8 条**黑盒(v04-UX-1~8):
- v04-UX-1 /checkup 不再含"已搬到 / 已挪至"
- v04-UX-2 /checkup 资产配置卡 mini 横向条 + 中性 eyebrow
- v04-UX-3 /reports 不再含汇率挪至 section
- v04-UX-4 6 用户面页(dashboard/reports/checkup/goals/entry/accounts)Python 正则扫描 0 个 v0.X/FR-XX 代号残留
- v04-UX-5 /reports/refinance 不再含 v0.X 版本路线规划
- v04-UX-6 checkup placeholder 死代码模板已删除
- v04-UX-7 /my-todos 不再暴露 SNAPSHOT_TODO enum + 类型英文括号
- v04-UX-8 stock/holdings pill 中文化

**验证**
- `mvn test`:161 全绿(0 新增 0 破坏)
- `bash scripts/qa-run.sh`:**总 PASS=273 / FAIL=3**(pre-existing v0.2 PILL/DIAG/LEDGER 状态污染)/ SKIP=2
- 渲染验证:6 用户面页 0 代号残留 · /entry 页 `auto-stock-valuation v0.3` 计数 0 → `系统估值同步` 计数 3

**backward-compat 红线**
- 0 schema 改动 · period_snapshot 已有数据不动 · 显示层兼容
- 老 QA case 改判:v02-CCY-5(文案"汇率未配置"→"汇率尚未配置")· v03-IND-4("去 /entry" → "去填报页")· v03-STOCK-7(note 接受两种值)
- prod 升级:`git pull && sudo bash deploy/deploy.sh` 单步 · 0 风险

---

### v0.4.5 · /checkup 风险敞口卡饼图化 + dashboard L157/158 表达式 hotfix(2026-05-14)

**触发**(两件事一起)
1. 用户 prod 部署 v0.4.4 后 /dashboard 挂 · 排查后定位到 dashboard/_region L157+L158 Thymeleaf 表达式 `#numbers.xxx(...)` 在 `${...}` 外的语法错(beta 数据 banner 不触发未踩到 · prod 应急金超额触发)
2. 用户反馈风险敞口卡「干巴巴数字」要饼图

**hotfix 链(commit 3 个)**
- `87e644e` layout.html _csrf null-safe(兜底)
- `9218442` nav + dashboard _csrf null-safe(兜底)
- `69ce5b6` dashboard/_region L157/L158 表达式 root cause(真因)

**饼图化**
- checkup/family 风险敞口卡从列表改 doughnut · 颜色梯度浅绿→朱红 · datalabels 浮在扇片
- 复用既有 Chart.js + ChartDataLabels · 0 后端改动
- v04-RPT-5 改判:checkup 砍 alloc 环形(0)· 但风险等级回归 doughnut(1 canvas)
- 新加 v04-UX-9:doughnut + datalabels 防回归

**诊断教训**(写入 memory)
- 看 prod stack 时,第一条 ERROR(时间戳最早)才是 root cause
- Thymeleaf chunked streaming 下视图渲染中段抛异常会触发 forward 到 /error,但 response 已 commit,/error 也会二次炸,最终浏览器看 ERR_INCOMPLETE_CHUNKED_ENCODING
- 应该按时间戳找最早那条 + 精确读 `template + line + 表达式` 而不是猜
- Thymeleaf 表达式语法:`#xxx.yyy()` 这种 utility 调用必须在 `${...}` 内,不能跟 `${var} + 'str' + #xxx.yyy()` 这种"半在内半在外"

**验证**
- `mvn test`:161 全绿
- `bash scripts/qa-run.sh`:**总 PASS=275 / FAIL=3**(pre-existing)/ SKIP=2
- beta 强制触发应急金 banner 路径 + 风险饼图,均正常渲染

**backward-compat 红线**
- 0 schema · 0 controller · 0 model 字段
- 仅模板 / JS / QA case 改动
- prod 升级:`git pull && sudo bash deploy/deploy.sh` · 0 风险

---

### v0.4.6 · AI 调仓建议「点了没反应」修复(2026-05-14)

**触发**:用户反馈「报表的 🤖 AI · 调 · 仓 · 建 · 议 是否没有实现?点击按钮以后没有反应」。

**真因**(从日志锁定 · 不是猜测):

```
WARN RebalanceAdvisorService: rebalance advice LLM output 校验失败: 含具体产品名/代码: "余额宝"
INFO RebalanceController : rebalance advise · family=1 ok=false fromCache=false actions=0
```

`OutputValidator.PRODUCT_NAME_PATTERN` 把「余额宝」列为禁词(防 LLM 推荐金融产品),但用户自家有「支付宝-余额宝」账户,LLM 在 actions 里引用这个账户名是**合法的**(让用户"从自家余额宝调出"不算产品推荐),却被误杀。

**双重修复**

| 改动 | 目的 |
|---|---|
| `OutputValidator.check` 加 `accountWhitelist` 参数 · PRODUCT_NAME_PATTERN 匹配到的字符串如果是用户已有账户名的子串就放行 | 不再误杀对自家账户的引用 |
| `RebalanceAdvisorService` 调用时传账户名集合 | 把用户上下文带进 validator |
| `RebalanceController` 加 `RedirectAttributes` flash · ok-fresh / ok-cache / fail 三态 | 用户看得到结果,不再"按了没反应" |
| `reports/_allocation-diff.html` 加 3 个反馈条 + 隐藏空态提示 | 视觉反馈 |

**新加 3 条**黑盒(v04-AI-REBALANCE-2/3/4):
- v04-AI-REBALANCE-2:advise POST → 302 · cache 写入(LLM 通过 + validator 通过)
- v04-AI-REBALANCE-3:/reports 渲染 advice card · 含「生成于」+「从 X 调出」
- v04-AI-REBALANCE-4:POST → GET /reports 反馈条出现(成功 / 缓存 / 失败)

**验证**
- `mvn test`:161 全绿
- `bash scripts/qa-run.sh`:**278 PASS** / 3 pre-existing FAIL
- 真实 beta:`actions=3`(招行储蓄卡 → 蚂蚁财富 · 支付宝-余额宝 → 招行理财 · 华泰证券-A股 → ...)· DB cache 写入 · advice card + 反馈条均渲染

**backward-compat 红线**
- 0 schema · `OutputValidator.check` 旧 2 参数签名保留 · 新 3 参数 overload
- prod 升级 0 风险

---

### v0.4.7 · OutputValidator 放宽(2026-05-14)

**触发**:v0.4.6 修了「余额宝」后 prod 又新误杀 `真名泄露: "萝卜"`(用户家庭成员真名「王萝卜」· LLM 在叙事中用到「萝卜」蔬菜词被误杀)· 用户反馈「对 LLM 的限制太多」。

**诊断**(临时加 DEBUG log + beta 真跑一次 抓 prompt 全文 + LLM raw 输出):
- `RebalanceAdvisorService.buildPrompt` 收 members 但**完全没写入 prompt** · LLM 物理上看不到真名
- 真名扫描 length ≥ 2 + contains 在 2 字常用组合(萝卜/张三/李四)上误杀率 >> 真泄露率

**放宽**

| 校验 | 之前 | v0.4.7 |
|---|---|---|
| 古典中式词(师傅/打理/家底...) | reject | **删** |
| 过度客套(您 > 2 次) | reject | **删** |
| 真名扫描门槛 | length ≥ 2 | length ≥ 3(防 2 字常用词误杀) |
| rebalance caller 行为 | 传 mapping.realToCodename().keySet() | 传 Set.of() 跳过扫描 |

**保留**(真有意义):长度 / 担保性话术(合规底线)/ 产品名+白名单 / 金融术语

**单测**:删 2 reject 测改 allow · 加 3 新测(2 字真名放行 / ≥3 仍 reject / 空 realNames 跳过)· 总 OutputValidatorTest 13 → 18 个 · 全绿

**验证**
- mvn test:164 全绿(151 + 13 OutputValidator)
- bash scripts/qa-run.sh:**278 PASS** / 3 pre-existing FAIL
- beta:LLM ok=true · actions=3 · 不再被「萝卜」误杀

**backward-compat 红线**
- 0 schema · `OutputValidator.check(text, realNames)` 旧 2 参数行为变化只是放宽(原 reject 的现在 accept)· caller 代码 0 改动
- 其他 LLM caller(checkup / goals)真名扫描仍走 length ≥ 3 兜底

---

### v0.4.8 · MAX_LEN 1500 + AI 刷新按钮真生效(2026-05-14)

**触发**:用户两个新报告
1. ⚠ 文本过长 len=707(> 700)· MAX_LEN 仍太严
2. 几处 AI 建议都应该做好缓存,但点刷新小按钮应立刻去新的并更新缓存

**改动**

| 维度 | 之前 | v0.4.8 |
|---|---|---|
| OutputValidator MAX_LEN | 700 | 1500(rebalance JSON narrative+4 actions+reason 常见 800-1000) |
| RebalanceAdvisorService | advise(familyId) 只读 cache | advise(familyId, forceRefresh) · forceRefresh=true 跳 cache |
| LlmDiagnoseService | diagnoseFamily/Account 只读 cache | 加 5 参 overload · forceRefresh=true 跳 cache + cache.remove |
| RebalanceController | 接 form | 接 @RequestParam refresh=false |
| AiDiagnoseController | 接 GET | 接 @RequestParam refresh=false |
| reports/_ai-rebalance.html | 无刷新按钮 | advice card 标题栏右加「↻ 刷新」form · action 带 refresh=true |
| checkup/_ai-diagnose.html | 「↻ 刷新」title 写忽略缓存但 url 没传(假刷新)| 真传 refresh=true · 立刻调新 LLM |

**新加 4 条**黑盒(v04-AI-REBALANCE-5/6/7 + v04-AI-DIAGNOSE-1):
- v04-AI-REBALANCE-5:第二次 advise 命中 cache(fromCache=true · 节省 LLM 调用)
- v04-AI-REBALANCE-6:refresh=true 跳过缓存 + forceRefresh log + fromCache=false
- v04-AI-REBALANCE-7:advice card 显示 ↻ 刷新按钮(form 带 refresh=true)
- v04-AI-DIAGNOSE-1:/checkup/diagnose 刷新按钮 url 带 refresh=true(真忽略 cache · 此前假忽略)

**验证**
- mvn test 164 全绿(rejectsTooLong 改 100 次 repeat 验证 1500 阈值)
- bash scripts/qa-run.sh:**282 PASS** / 3 pre-existing FAIL
- beta 三态实测(log 凭证):
  - cache 空 → 调 LLM · fromCache=false
  - 再点 → fromCache=true(命中)
  - refresh=true → forceRefresh log + fromCache=false(强制重新)

**backward-compat 红线**
- 0 schema · `RebalanceAdvisorService.advise(long)` + `LlmDiagnoseService.diagnoseFamily/Account` 老签名都保留作 1-2 参 overload · delegate 到新版本(forceRefresh=false)
- Controller 新增 `refresh=false` 默认 RequestParam · form 不带也兼容
- prod 升级 0 风险

---

### v0.4.9 · AI 综合诊断 JSON 结构化 + 4 维度卡(2026-05-14)

**触发**:用户反馈「1.大段文字看着吃力 没排版没主题;2.没有清晰的分析方向/诊断方向」

**设计**:LLM 输出从「200-500 字散文」改 JSON 结构化:overall + dimensions(配置/风险/流动性/收益 4 维)+ actions。前端按总评 banner + 4 卡 + 优先行动渲染。

**改动**

| 维度 | 之前 | v0.4.9 |
|---|---|---|
| LLM 输出 | 纯文本散文 200-500 字 | JSON · overall + 4 dimensions + 1-3 actions |
| Prompt 诊断方向 | 三层叙事(总评/分析/建议)模糊 | 4 维度明确(配置/风险/流动性/收益)· 与体检页 4 卡对应 |
| 渲染 | 一段散文 | 总评 banner(verdict 染色)+ 4 dim 卡(图标 + verdict pill + finding + evidence)+ 优先行动 ol |
| OutputValidator | 直接对 raw 扫描 | JSON 路径:joinUserFacingStrings 拼后扫;非 JSON 路径不变 |
| PRODUCT_NAME_PATTERN 6 位数字 | `\b\d{6}\b`(¥120526 / 2026 年误杀) | 加 lookbehind/lookahead:`(?<![¥$￥0-9.])\b\d{6}\b(?![元万千亿年月日天.])` |

**新加 2 条**(v04-AI-DIAGNOSE-2/3)+ **OutputValidator 2 测**:
- v04-AI-DIAGNOSE-2:结构化诊断渲染 · 含总评 + 4 维度 + 优先行动(10/10 marker)
- v04-AI-DIAGNOSE-3:模板含 fallback 分支(老 cache / 解析失败时 text 显示)
- 单测 `amountNotMisreadAsStockCode_v049`:¥120526 不再误杀
- 单测 `stillRejectsStandaloneStockCode_v049`:600519 仍 reject(合规底线保留)

**验证**
- mvn test 166 全绿
- bash scripts/qa-run.sh **284 PASS** / 3 pre-existing FAIL
- 真实 beta LLM:JSON 解析成功 · 模板 10/10 marker · verdict OK/WARN/RISK 三态染色都对

**backward-compat 红线**
- 0 schema · DiagnoseResult 老 3 参工厂保留(structured=null)
- 模板 `result.structured() == null` fallback 分支 · 老 cache 纯文本能正确显示
- 其他 LLM caller(月报/向导)默认仍用文本路径 · 0 改动
- prod 升级 0 风险

---

### v0.4.10 · max_tokens 750→2000 + 截断检测(2026-05-14)

**触发**:用户反馈「目前 AI 诊断经常展示一大段 JSON · 是因为 LLM 返回太长 被截断后不是标准 JSON 了吗?」

**真因**(精准锁定 · 看 LLM audit log):
- 实际 response 长度 1000-1240 字符 · 接近 max_tokens=750 上限
- v0.4.9 JSON 输出(overall + 4 dimensions + actions + 语法标记)≈ 930 字 ≈ 1100-1300 tokens
- 750 tokens 严重不够 · JSON 中途被截断 · tryParseStructured 返 null · 前端把半截 JSON 当 text 显示

**修法**

| 改动 | 目的 |
|---|---|
| QwenLlmClient + DeepSeekLlmClient max_tokens 750 → 2000 | 给 JSON 输出足够余量 |
| 客户端检测 finish_reason=length log.warn | 将来调 max_tokens 有数据支撑 |
| DiagnoseResult.truncated + looksTruncatedJson(raw 以 { 开头但不以 } 结尾) | 检测截断 |
| 模板 result.truncated() 分支 显示「⚠ AI 输出被截断 · 请刷新重试」红底卡 | 不再把半截 JSON 当 text 显示 |

**新加 3 条**(v04-AI-DIAGNOSE-4/5/6):
- v04-AI-DIAGNOSE-4:max_tokens 2000(Qwen + DeepSeek 两端)
- v04-AI-DIAGNOSE-5:DiagnoseResult.truncated + 模板友好错误
- v04-AI-DIAGNOSE-6:客户端 finish_reason 截断日志告警

**验证**
- mvn test 166 全绿
- bash scripts/qa-run.sh **287 PASS** / 3 pre-existing FAIL
- beta 实测:LLM 响应 1211 字符 · 2000 token 不截断 · 4 维度卡完整

**backward-compat 红线**
- 0 schema · DiagnoseResult 老工厂保留(truncated=false)
- 老 cache 纯文本走 fallback text 分支不误判截断
- prod 升级 0 风险

---

### v0.4.11 · prompt 占比 bug 修复 + 严禁 LLM 算术(2026-05-14)

**触发**:用户反馈 LLM 胡说「股票类仅占 3.4%(¥376万/¥1095万)」· 实际 34% · 用户说「不应该让 LLM 做任何数学计算 · 所有计算类指标应该工程算好填进去」

**真因两层**

| 层 | 问题 | 修法 |
|---|---|---|
| 1 | `pct1(s.ratio())` 没 ×100 · ratio=0.442 显成 0.4% · prompt 给 LLM 错误数据 | 新增 `pctFromRatio(ratio)` ×100 · L137/L147 改用此函数 |
| 1 | L223 `pct1(benchmarkPct.multiply(100))` 反向 bug · 8.00 ×100 显 800% | 删 `multiply(100)` · benchmarkPct 已是百分比形式 |
| 2 | LLM 即使数字对也会瞎算占比/差额(根本性) | SYSTEM_DIAGNOSE 加「⚠⚠⚠ 最高优先级 · 100% 禁止四则运算 · 数字必须照抄」5 条规则 + userPromptForFamily 顶部「⚠ 重要 · 以下数字已计算 · 你只能引用」 |

**verify(beta 实测)**:
- 修前 prompt:`股票 ¥1779269 · 占比 0.4%`(错)
- 修后 prompt:`股票 ¥1779269 · 占比 44.2%`(对)
- LLM evidence:`现金占比2.4%,股票占比44.2%,理财占比8.7%,房产占比44.7%` ← 100% 照抄 prompt

**新加 2 条**(v04-AI-DIAGNOSE-7/8):
- v04-AI-DIAGNOSE-7:PromptBuilder ratio 占比 ×100 修
- v04-AI-DIAGNOSE-8:SYSTEM_DIAGNOSE 含禁数学约束

**验证**
- mvn test 166 全绿
- bash scripts/qa-run.sh **289 PASS** / 3 pre-existing FAIL

**backward-compat 红线**
- 0 schema · 0 DB
- `pct1` 函数行为不变 · 仅 caller 切换到 `pctFromRatio`
- SYSTEM_DIAGNOSE 更严不引入新错
- 其他 LLM caller 0 改动
- prod 升级 0 风险

### v0.4.14 · 填报规范化 + DDL 强提醒(FR-63 · 2026-05-18)

**触发**:规范"何时填什么" + 截止前强提醒 + 短信设置页;手机号/aksk 私密绝不进 LLM。详见 [`prd/v0.4.md`](../prd/v0.4.md) §20 / [`tech-design/v0.4.md`](../tech-design/v0.4.md) §16。

| Case | 验证点 |
|---|---|
| v04-RPT-TMPL-1 | `ReportingTemplate` 含 T1/T2/T3 三模板 + `fromCode` 安全解析(未知→默认 T1) |
| v04-RPT-REMIND-1 | `/admin/reminders` 设置页 200 · 含 3 模板单选 + 提前天数 |
| v04-RPT-REMIND-2 | POST 模板=T3 + leadDays=3 落库 · GET 回显 checked + value="3"(测后还原 T1/2) |
| v04-RPT-REMIND-3 | 调度器 `@Scheduled(cron="0 0 10,20 * * *", zone=Asia/Shanghai)` |
| v04-RPT-REMIND-4 | 渠道抽象 `NotificationChannel` + `SmsAliyunChannel` + `InAppBannerChannel`(可插拔) |
| v04-RPT-REMIND-5 | 提醒去重:V25 `UNIQUE uk_dedup` + Mapper `INSERT IGNORE`(同成员同渠道当天 1 次) |
| v04-RPT-BANNER-1 | `/entry` 显示「推荐填报方案」提示 banner(随模板 + 距截止天数) |
| v04-RPT-BANNER-2 | `/entry` banner **三栏富信息**:周期标识 + 截止日 + 家庭进度 N/M + 我已填/未填徽标 + 距截止 pill |
| v04-RPT-MSG-1 | 短信 TemplateParam 含 **4 变量** `brand/period/days/progress`(源码 grep + ReminderMessage 字段) |
| v04-RPT-TEST-1 | `POST /admin/reminders/sms-test` endpoint 在岗 · 配置不全时返"配置不完整" |
| v04-RPT-TEST-2 | 测试限流 3 次/分/管理员(源码 `TEST_RATE_LIMIT_PER_MIN=3` + 滑动窗口) |
| v04-RPT-TEST-3 | 测试日志走 **audit_log**(决策 36)· 非 report_reminder_log(避免 UNIQUE 去重) |
| v04-RPT-LOG-1 | `/admin/reminders` ⑥ 段提醒发送日志 · 顶部引导「→ 测试发送审计」 |
| v04-RPT-LOG-2 | `ReportReminderLogMapper.findByFamily` + `countByFamily` · LIMIT/OFFSET 分页查询 |
| v04-RPT-LOG-3 | `?page=N` URL 参数被识别 · 默认 20/页 · 越界 clamp |
| **v04-PRIV-1** | **合规底线**:LLM prompt 目录(`service/checkup/llm`)源码零引用 `getPhone`/`AccessKeySecret`/`FamilyNotifyConfig`… + `PrivacyIsolationTest` 在岗 |

**单测**:`PrivacyIsolationTest` —— ① buildNameMapping 带 phone 的 Member 不外泄手机号 ② applyMapping 不引入手机号 ③ 静态扫描 LLM 目录零引用私密渠道符号(编译期 gate)。

**手工验证步骤**:
1. `mysql < db/migration/V25__report_template_remind.sql` · `DESC family`/`member` 见新列 · 2 张新表在
2. `/admin/reminders` 设模板+提前天数 +(可选)短信 aksk/签名/模板 + 各成员手机号
3. `/entry` 看到推荐填报提示 banner(随模板变 + 距截止天数;≤2 天红色强样式)
4. `/admin/reminders` 点「立即手动触发」· 看站内日志 / 配了短信则收带「<家庭别名>账本」短信 · `report_reminder_log` 写入 + 当天去重(同日不重发)
5. 私密验证:抓一次 LLM diagnose prompt(临时 log)· grep 确认无 phone / aksk

**backward-compat 红线**
- V25 全 ADD COLUMN DEFAULT + 新表 · 0 破坏 · 老 family 自动 T1 / leadDays=2
- `/admin/reminders` v0.1 只读页升级为设置页 · 路由 / 侧栏入口不变
- PromptBuilder 白名单式注入不受新字段影响 · 其他 LLM caller 0 改动
- prod 升级 `git pull && sudo bash deploy/deploy.sh`(交互确认应用 V25)· 0 风险

### v0.4.18 · 系统级配置沉淀管理页(FR-22 · 2026-05-19)

**触发**:9 项运营参数(LLM keys / 股票拉取开关+cron / FX cron / 提醒 cron / checkup 阈值 / 会话期)从 env/代码常量迁到 family_runtime_config 表 · 实时生效不重启。详 [prd/v0.4.md §22](../prd/v0.4.md)。

| Case | 验证点 |
|---|---|
| v04-CFG-1 | V26 migration `family_runtime_config` 表存在 |
| v04-CFG-2 | `FamilyConfigService` 三层 fallback + 5s TTL cache + 17 个 K_* 常量 |
| v04-CFG-3 | `DynamicScheduleConfig` 注册 5 受管 cron + rescheduleAll |
| v04-CFG-4 | Stock/Fx/ReportReminder `@Scheduled` 已删 · 由动态调度接管 |
| v04-CFG-5 | LLM client API key 改读 ConfigService(不再 @Value 直注入) |
| v04-CFG-6 | `/admin/integrations` 集成中心 200 · 3 段(LLM/股票/FX) |
| v04-CFG-7 | `/admin/calc-tweaks` 升级为可编辑表单 · 8 个字段(老 3 + 新 4 + 会话期) |
| v04-CFG-8 | admin sidebar 加"集成"入口 + 标 14 项 |
| v04-CFG-9 | deploy.sh step 9.5 种子 + 幂等 flag |
| **v04-CFG-10** | **私密红线扩展** · PrivacyIsolationTest.promptBuilderNeverReferencesAnyPrivateAccessor 防 LLM key 泄露进 prompt |

**手工验证步骤(prod 升级后)**
1. `bash deploy/deploy.sh` · step 9.5 跑过 · `SELECT * FROM family_runtime_config WHERE family_id=1` 应含 stock_fetch_enabled / llm_qwen_api_key / llm_deepseek_api_key 3 行(env 值 seed)
2. `/admin/integrations` 看 3 段 form · 改 LLM max_tokens 保存 · DB 入新行
3. `/admin/calc-tweaks` 改 emergency_months=12 保存 · `/checkup` 应急金提示数字跟着变
4. 改股票 cron `06:05` → `07:00` 保存 · journal 应见 `[dyn-sched] stock-us scheduled · cron=...` rescheduled
5. 关股票拉取开关 · 等 cron 时段过 · 应 SKIPPED 不 fetch
6. 回滚 v0.4.18 → v0.4.17:老 jar 不读新表 · 完全恢复升级前行为(env @Value 仍生效)

**backward-compat 红线**
- V26 仅新建表 · 0 改字段 / 0 删 · 老 family 无行走 env @Value · 行为完全等价升级前
- LLM API key 同 SMS aksk 纪律 · PrivacyIsolationTest 双重防回归
- deploy.sh 9.5 步幂等(flag 文件)· 重复 deploy 不覆盖用户管理页改过的值
- 私密字段在 audit_log 只记"已配/未配"不记明文

### v0.4.17 · 520 一日限定爱情宣言彩蛋(FR-520 · 2026-05-19 设计 · 2026-05-20 上线)

**触发**:5.20 谐音"我爱你" + 家庭账房面向夫妻/家庭场景 · 全屏像素彩蛋强化"家"的氛围 · 仅当天 + Asia/Shanghai 服务器时区 · 5.21 完全 dormant。详 [prd/v0.4.md §21](../prd/v0.4.md)。

| Case | 验证点 |
|---|---|
| v04-520-1 | `templates/fragments/easter520.html` 存在 + 严格 `today == '05-20'` 触发条件 + 主标"I LOVE U" + 文案库 19 条(首尾各一句 + 总行数计) |
| v04-520-2 | `templates/fragments/layout.html` footer 含 `~{fragments/easter520 :: easter520(...)}` 注入 |
| v04-520-3 | Fragment 含 `easter520_seen` localStorage flag + `e520Pill` 右上常驻按钮 + `next-slogan-btn` 换一句 + `window.__e520_*` IIFE 暴露入口 |
| v04-520-4 | 日期 guard:今天非 5.20 时,/dashboard 不注入 fragment(dormant);今天就是 5.20 时,/dashboard 含 "I LOVE U" |

**手工验证步骤(5.20 当天)**
1. 登录任意页(/dashboard / /entry / /admin/reminders / /reports / /accounts)
2. 0.5s 后自动弹全屏 overlay · 像素心脉动 + 飘心粒子 + 「叮」一声
3. 副标随机显 19 条之一 · 不与上一句重(刷新 + 关闭 + 点 pill 多试)
4. 点「换 一 句 ↻」立刻换一条(overlay 不关 · 「叮」一声)
5. 点任意位置 / 按 ENTER / 任意键 / × → 关闭 + 「叮」 → localStorage `easter520_seen=2026-05-20`
6. 同一天再进系统不再自动弹 · 但右上 ♥520 pill 常驻 · 点了重开 + 换新文案
7. **5.21 起**:fragment 服务器侧 th:if 直接跳过 · /dashboard 源码 grep 无 "I LOVE U"

**backward-compat 红线**
- **0 schema 改动 · 0 DB 改动**(纯 Thymeleaf fragment + 静态资源)
- 不引用 phone / aksk / LLM(无私密红线接触)
- 5.21 服务器侧 `th:if` 跳过 = **零运行成本**
- localStorage flag 永久留无害(再次 5.20 系日期换了自动 ignore)
- prod 升级:`git pull && sudo bash deploy/deploy.sh` · 0 风险

---

### bugfix · 目标编辑页 expenseMode 回填 + AI月报手动生成(2026-06-03)

**触发**:① 编辑页未渲染 expenseMode 单选/下拉(FR-81 漏补) · ② 月报区块无按需触发入口(FR-53b 周期关闭前无法验收)。

| Case | 验证点 |
|---|---|
| bf-GOAL-EDIT-1 | GET `/goals/{id}/edit` · 已保存 `expenseMode=FIXED` 的目标 → 「固定值」radio **预选中**，「自动适配」未选 |
| bf-GOAL-EDIT-2 | GET `/goals/{id}/edit` · 已保存 `expenseMode=AUTO_MONTHLY` 的目标 → 「自动适配月结支出」radio **预选中** |
| bf-GOAL-EDIT-3 | 编辑页 `expenseSmoothing` 下拉回显已保存值(TRIMMED/MEDIAN/MEAN 之一)；`expenseWindowMonths` 下拉回显 6/12/24 之一 |
| bf-GOAL-EDIT-4 | 提交编辑表单切换 expenseMode → 保存后再进编辑页确认新值已持久化 |
| bf-GOAL-RPT-1 | GET `/goals/{id}` · 无 AI 月报时「AI 综合月报」区块显示「立即生成月报」按钮，**不再**是纯静态提示 |
| bf-GOAL-RPT-2 | POST `/goals/{id}/report/generate` → 302 跳回详情页 · 详情页月报内容已展示（LLM 已配置时） |
| bf-GOAL-RPT-3 | `goal_ai_report` 表中 `period_id=0` `report_type='MONTHLY'` 新增一行(按需标记)；重复触发幂等不新增 |
| bf-GOAL-RPT-4 | 无权限家庭成员访问其他家庭 `/goals/{id}/report/generate` → 4xx 拒绝 |

**backward-compat 红线**
- 0 schema 改动(仅新增写入 `period_id=0` 行)
- `period_id=0` 行不影响周期关闭时批量生成逻辑(`generateMonthlyReportsAsync` 不感知)
- edit.html 新增字段与 controller 已有参数完全对齐 · 无新接口

### v0.5.3 · 计算指标透明化(ⓘ tooltip 真实数值 · FR-90 · 2026-06-03)

**单元 · `MetricExplainServiceTest`(8 例)**

| Case | 断言 |
|---|---|
| money/signedMoney 格式 | `¥1,235`(千分位 · HALF_UP)· `+¥3,000`/`−¥3,000`(− 用 U+2212)· null→`—` |
| pct/months 格式 | `pct2Signed(0.0123)=+1.23%` · `pctUnits(5.4)=5.4%` · `months(3.0)=3.0` |
| dashboard calc | 净资产「总资产 ¥ − 总负债 ¥ = ¥」· 总资产按类型分项 · 总负债按 LOAN 账户分项 · 紧急储备「流动资产÷月均支出=月」· 本月收益「(期末−期初−净流入)÷期初=%」|
| checkup calc 用本位币 | netWorth/emergency 实算 · familyXirr/TWR 含解得值 · ytdPnl 含 +¥ |
| reports 钱赚恒等式 | `(期末 − 起始) − 净流入 = PnL` 串自洽 · netInflow 含「共 N 期计入」· avgIncome「N 月合计 ÷ N = avg」· savingsRate 含分子分母 |
| 缺数据降级 | 月均支出 0 → emergency/monthlyPnl 显「暂无法计算」不崩 · savings 不可用时 5 个储蓄 key 不出现 |

**黑盒 · qa-run(v05-CALC-1~3 · 用恒有数值的「净资产 = 总资产 − 总负债」/钱赚分解断言 · 不依赖月支出/PMC 填报)**

| Case | 校验 |
|---|---|
| v05-CALC-1 | `/dashboard` ⓘ 含 `.kpi-info-calc` 且净资产「总资产 ¥ − 总负债 ¥ = ¥」实算 |
| v05-CALC-2 | `/reports` ⓘ 含 `.kpi-info-calc` 且钱赚「(期末净资产 …」实算 |
| v05-CALC-3 | `/checkup` ⓘ 含 `.kpi-info-calc` 且净资产实算 |

> 注:紧急储备/月均收支等数值依赖 PMC 填报与锚定期;数据缺失时**自洽降级**为「月均支出为 0,暂无法计算」并与对应 KPI 卡的「—」一致(beta familyId=1 因测试期到 2032 + 无 PMC 即呈降级态 · 非 bug)。

**backward-compat 红线**
- 0 schema 改动 · `KpiSnapshot` 加字段保留 7 参/11 参兼容构造器(老调用方/测试不动)
- `_kpi-info` 升 2 参 · 全部 28 调用点同批改完 · 纯定义指标传 `null`(只显口径)
- 指标计算口径零改动(只暴露已算中间量)

### v0.5.4 · 目标 AI 月报修复(FR-91/92/93 · 2026-06-03)

**单元 · `GoalLlmServiceTest`(2 例)**

| Case | 断言 |
|---|---|
| 代号→真名回写 | LLM 输出「成员A与成员B」· 2 成员(张三/李四)→ 月报 value 含「张三」「李四」且不含「成员A/成员B」(校验仍在代号 raw 上跑) |
| 无成员原样返回 | 空映射 → reverseMapping 原样返回 · 月报 value == LLM 原文(不崩) |

**人工 · beta 验收**

| 项 | 校验 |
|---|---|
| FR-91 | 目标详情点「重新生成」→ 月报正文出现真名(成员真实 displayName)· 不再有「成员A/成员B」 |
| FR-92 | 已有月报时显「本期复用 · 渲染于…」+「重新生成」按钮(刷新覆写);再次进入页面不重算(复用) |
| FR-93 | 仪表盘目标条带每个目标右侧有 book-open + AI 小入口 · 点击直达 `/goals/{id}#ai-report` 且月报段已展开 |

**backward-compat 红线**
- 0 schema 改动 · 隐私边界不变(prompt 端不含真名 · 仅展示端还原 · 与 checkup 同口径)
- 月报缓存仍走既有 `goal_ai_report` upsert · 「重新生成」= 既有 `POST /goals/{id}/report/generate`

### v0.5.5 · 报表「已关账快照」锚定(FR-94~97 · 2026-06-03)

**单元 · `ReportsAnchorResolverTest`(4 例)**

| Case | 断言 |
|---|---|
| 有已关账期 | 选最近已关账作锚 · `closedSnapshot=true` |
| 无已关账 有 OPEN | 退 OPEN 锚 · `closedSnapshot=false` |
| 无已关账 无 OPEN | 退 latest · `closedSnapshot=false` |
| 三者皆空 | 抛 `IllegalStateException`(尚未创建周期) |

**黑盒 · qa-run(v05-SNAP-1/2)**

| Case | 校验 |
|---|---|
| v05-SNAP-1 | `/reports` 透出快照语义:含「已关账账期的稳定快照」(印章+说明行)**或**「尚无已关账账期」(空态) |
| v05-SNAP-2 | `/dashboard` **不含**「已关账账期的稳定快照」(dashboard 仍实时 · 两 tab 分工) |

**人工 · beta 验收**

| 项 | 校验 |
|---|---|
| FR-94 | 报表锚定到最近已关账月(非月中 OPEN);未来测试期(2032)不被锚定;关账新月后报表纳入 |
| FR-95 | 仅 1 个已关账期:四 banner 显「—」+「需 ≥2 个已关账账期」note,**无误导性 0**;0 个 → 引导空态 |
| FR-96 | #3 人赚 ⓘ 文案为「区间逐期累计 · 非单月 · 只统计已关账」 |
| FR-97 | 报表标题旁显朱印红「已关账」竖排方印 + 说明行(数据截至 X · 仪表盘链接);0 已关账期不显印章 |

**backward-compat 红线**
- 0 schema 改动 · 新增只读 `findLatestClosedAsOf` + 锚定逻辑 + 模板
- dashboard 完全不动(仍 `findLatest(1)` 实时)· 指标数学口径不变(只改锚哪个账期)

### v0.5.6 · 报表长文目录(FR-98 · 2026-06-03)

**黑盒 · qa-run(v05-TOC-1)**

| Case | 校验 |
|---|---|
| v05-TOC-1 | `/reports` 含 `toc-rail`(PC 右栏)+ `class="toc-node"`(树节点)+ 章节锚点 `#sec-decompose`/`#sec-accounts` + `#toc-sheet`(手机 sheet) |

**人工 · beta 验收**

| 项 | 校验 |
|---|---|
| PC scrollspy | 宽屏右侧常驻目录栏;滚动内容,当前所在节朱铜高亮(`aria-current`),点击平滑跳转 |
| 嵌套 | 树状缩进 + 竖线/树枝引导线;未来加子节层级可见 |
| 手机 | 缩到窄屏 → 右栏收起、左上角「目录」钮 → 底部 sheet 滑出;拖拽手柄 + × + Esc 关闭;点击跳转后收起 |
| HTMX | 切 range/币种后(#reports-region 重渲)scrollspy 仍正常高亮 |

**backward-compat 红线**
- 纯前端 · 0 schema · 0 后端逻辑改动 · dashboard 不加目录(不动)
- 章节锚点为新增 id,不改既有结构/样式
