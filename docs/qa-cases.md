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
| FR6-1 | /my-todos 已退休 | GET /my-todos | 302 重定向(v0.11.7 折叠进填报) |
| FR6-2 | /my-todos→填报页 | 跟随重定向 | 落 `/entry?mine=true` · 页面含「保存我的本月收支/应填账户」 |
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
| v02-PILL-1 | GET /accounts | 类目 pill | 类目 pill 渲染冒烟(≥1 · v0.10.6 解耦旧 demo 量级,数量随数据浮动)|
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
| v02-LEDGER-2 | GET /accounts | 操作列 | 账户行均含「账本」入口渲染冒烟(≥1 · v0.10.6 解耦旧 demo 量级)|
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
| **v03-STOCK · 18 条 · 持仓自动估值 FR-52 系列** | |
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
| v03-STOCK-16 | **CRYPTO 账户模板**:新建加密货币账户 · 默认 USD · product_category=CRYPTO |
| v03-STOCK-17 | **CRYPTO 自动估值**:创建 AUTO BTC · ticker 规范化 btc-usd → BTC · Binance 主源 / CoinGecko / Coinbase 备源写入 price_snapshot |
| v03-STOCK-18 | **CRYPTO cron 写回**:stock_cron_crypto 触发后 refreshAllForFamily(CRON,null),CRYPTO 账户余额写回 period_snapshot |
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
| service.stock | CoinGeckoCryptoClientTest | 1(免 key symbol price 解析) |
| service.stock | BinanceCryptoClientTest | 2(免 key ticker price 解析 + 地区限制响应降级) |
| service.stock | CoinbaseCryptoClientTest | 3(免 key spot price 解析 + ticker 规范化) |

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

### v0.5.7 · 长文目录推广到长 tab 页(FR-99 · 2026-06-04)

**黑盒 · qa-run(v05-TOC-2/3)**

| Case | 校验 |
|---|---|
| v05-TOC-2 | `/dashboard` 含 `class="toc-rail"` + `js/toc.js` + 锚点 `#dash-trend` |
| v05-TOC-3 | `/checkup` 含 `class="toc-rail"` + `js/toc.js` + 锚点 `#checkup-ai` |

**人工 · beta 验收**

| 页 | 校验 |
|---|---|
| dashboard | 宽屏左侧目录(概览/净资产趋势/按成员/按账户/账户列表)滚动高亮;手机左上钮→sheet |
| checkup | 左侧目录(概览/资产配置/风险/流动性/收益/智能建议/AI/账户体检)· advice 有无两态锚点都在 |
| reports | 改用共用件后行为不变 |

**backward-compat 红线**
- 纯前端 · 0 schema · 不动指标/数据 · dashboard region HTMX 90s 自刷后 scrollspy 经 htmx:afterSettle 重算
- 不做目录的页(entry/accounts/goals 列表/admin)不受影响

### v0.6 · AI 资产洞察(FR-100~110 · 2026-06-05)

中国大陆中产视角的 4 主线资产洞察:① 集中度 ② 资产负债表健康 ③ 再平衡 + 行为 ④ 低利率·资产荒。
硬数据全部工程预算(calc/ 4 纯函数),LLM 只中立解读(不预测涨跌 / 不择时 / 不荐产品)。
主阵地资产体检页,dashboard 速览条 + reports 交叉入口。Qwen 免费额度按模型独立计量,
用尽自动切下一模型(≤10),账户欠费立刻 failover DeepSeek。

**单元(`mvn test`)**

| Case | 校验 |
|---|---|
| AssetInsightCalcTest(6) | 集中度 `pct/topPct/line` · 资产负债表 band(HEALTHY/ELEVATED/ALERT)+ prepay 信号 · 再平衡 OVER/UNDER/OK · 行为 PRO_CYCLICAL+CONCENTRATION_RISING · 历史<6 期静默 |
| QwenInsightComplianceTest(6) | Qwen 故障分类:免费额度用尽→QUOTA_EXHAUSTED(切模型)/ 欠费·账单过期→ARREARAGE(failover)/ 其它→TRANSIENT · `checkInsight` 放行中立文本、拒绝预测涨跌/择时/担保/产品名 |

**黑盒 · qa-run(v06-*)**

| Case | 校验 |
|---|---|
| v06-INSIGHT-1 | `GET /checkup/insight` → 200(无 LLM key 时降级仍 200) |
| v06-INSIGHT-2 | fragment 含 `data-vendor/available` + 「AI · 资产洞察」标题 |
| v06-INSIGHT-3 | fragment 含第一层硬数据(集中度等维度名)或降级占位 |
| v06-INSIGHT-4 | `/checkup` 含 `#checkup-insight` section + `ai-insight-panel` placeholder + TOC 项 |
| v06-INSIGHT-5 | `/reports` 配置对照尾部含「查看完整资产洞察」→ `/checkup#checkup-insight` |
| v06-INSIGHT-6 | `/dashboard` 速览条 `#dash-insight`(有数据渲染 · 无数据 SKIP) |
| v06-LLM-LIVE | 嗅探 `/checkup/insight` 真 LLM 成功 vendor=qwen/deepseek(无 key 降级 SKIP) |
| v06-COMPLIANCE | 渲染输出绝不含 会涨/会跌/牛市/抄底/高抛低吸/余额宝/茅台 等(中立红线 · 防御深度) |
| v06-PRIV | `InsightPromptBuilder` 源码不引用 `getDisplayName/getName()/topAccountLabel`(prompt 无人名 by construction) |
| v06-MODELS | `QwenLlmClient` 含 `K_LLM_QWEN_MODELS` + `QUOTA_EXHAUSTED/ARREARAGE` + `modelExhaustedUntil` |
| v06-MIGRATION | `V29` 纯 `ADD COLUMN ... NULL`(loan_kind / annual_rate_pct · prod 0 风险) |

**人工 · beta 验收**

| 页 | 校验 |
|---|---|
| checkup | 「AI 资产洞察」section:上半 4 维硬数据卡(集中度/资产负债表/再平衡·行为/低利率,真实 %/pp 数字),下半 AI 解读(总评 + 4 维卡 + 纪律性提醒);图标全 SVG 无 emoji;「重新生成」忽略缓存 |
| checkup | 左侧目录新增「AI 资产洞察」项,滚动高亮 |
| dashboard | 顶部速览条:房产占比 / 负债 band / (可能)加速偿还 / 真实收益承压 / 行为提醒 chip + 「查看完整洞察 →」 |
| reports | 配置对照尾部「→ 查看完整资产洞察」跳 checkup 锚点 |
| 合规 | AI 解读不预测涨跌、不给买卖时点、不提具体产品/代码;成员/账户名不进 prompt |

**backward-compat 红线**
- V29 纯 `ADD COLUMN DEFAULT NULL` · 老账户两列空 → 资产负债表「负债利率对照」优雅降级(只显负债额)· prod 历史程序 0 影响
- `AssetInsightService.compute` 只读不写任何表 · 任一数据缺失局部字段 null 降级 · 永不抛
- 既有 `/checkup/diagnose`(AI 综合诊断)与 `OutputValidator.check` 行为不变;`checkInsight` 为新增更严路径,仅 ASSET_INSIGHT scope 走
- Qwen 单模型语义保留(默认列表首位 qwen-plus)· 仅在额度用尽时才切换

### v0.6.1 · iOS PWA 强引导(FR-115 · 2026-06-08)

iOS PWA 引导从软建议改强引导:整屏拦截 + 成果真机截图 + 想留浏览器/微信要两段挽留。纯前端 0 schema。

**黑盒 · qa-run(v061-PWA-*)**

| Case | 校验 |
|---|---|
| v061-PWA-1 | `/js/mobile-guide.js` 200 · 含 `showIosPwaInterstitial` + `showWxGuide` + `twoStepLeave` |
| v061-PWA-2 | JS 含强口吻文案(`强烈建议` · `装成 App`) |
| v061-PWA-3 | JS 无 emoji(📦📷✕✓ 等 · 全 inline SVG · 承 `feedback_no_emoji`) |
| v061-PWA-4 | 成果图 `home-screen.jpg` 200(主屏装好样子) |
| v061-PWA-5 | 4 步真机截图 `step1-4.jpg` 全部 200(压缩后) |

**人工 · beta 真机验收(必须真 iPhone · UA 分支)**

| 场景 | 触发 | 校验 |
|---|---|---|
| iOS Safari | 真机 Safari 开 beta(`?reset_pwa=1` 强触发) | ~0.7s 整屏引导「请把账房装成 App」+ 成果截图 + 价值点;「看怎么装」→ 4 步真机图 modal |
| 两段挽留 | 点「暂时用浏览器」或 ✕ | 阻挠①(没图标/手输网址/不能全屏)→「仍要继续」→ 阻挠②(20 秒)→「就用浏览器」才放行 · 3 天不再弹 |
| iOS 微信 | 真机微信开 beta(`?reset_wx=1`) | 整屏「微信里装不了主屏 App · 先在 Safari 打开」+ 大箭头指右上「⋯」+ 成果图;「继续在微信用」同样两段挽留 |
| 已装 PWA | 主屏图标进入(standalone) | 完全静默不弹 |
| 桌面 / 安卓微信 | PC 浏览器 / 安卓微信 | 静默(本版只强推 iOS) |

**backward-compat 红线**
- 纯前端 · 0 schema · 仅改 `mobile-guide.js` + 加 1 张成果图 · 非引导链路零影响
- 已装成 PWA / 非 iOS 一律静默;snooze 仅在两段挽留全拒后才写(`localStorage` · 隐私模式降级静默)

### v0.7 · 一键 Docker + 兼容存量(2026-06-12)

Docker 化部署 + systemd/macOS 存量零丢迁移。**真机冒烟(docker build/up、迁移演练)留待 Mac + Ubuntu 分别跑**(beta 是 Linux 未装 Docker);qa-run 这层做**静态守护**(文件/结构/语法/防泄密)。

**黑盒 · qa-run(v07-DOCKER-* · 静态)**

| Case | 校验 |
|---|---|
| v07-DOCKER-1 | Docker 9 文件齐:`Dockerfile`/`docker-compose.yml`/`.env.example`/`.dockerignore`/`docker/entrypoint.sh`/`docker/backup.sh`/`deploy/docker-up.sh`(唯一 Docker 入口)/`deploy/migrate-to-docker.sh`/`.github/workflows/docker-publish.yml` |
| v07-DOCKER-2 | Dockerfile 多阶段(2 个 FROM);compose 含 `app`+`db`+`backup` 三服务 + `db-data`/`uploads`/`backups` 三卷 |
| v07-DOCKER-3 | entrypoint 复用 `db/apply.sh`(与 systemd 共用迁移 → 防重放) |
| v07-DOCKER-4 | 全部 Docker shell(4 个)`bash -n` 通过 |
| v07-DOCKER-5 | `.env` 在 `.gitignore`(密钥不入库);`.env.example` 不含真实密钥(只占位) |
| v07-DOCKER-6 | migrate-to-docker.sh 同时识别 `/etc/finance.env`(systemd)与 `~/.finance/finance.env`(macOS) |
| v07-DOCKER-7 | `docker-up.sh` 一键自检:探测 `docker info`(引擎)/`docker compose version`(V2)/`docker-compose --short`(拒老 V1)+ 验 `/health` |
| v07-DOCKER-8 | 种子账号 prod 引导:`ProdSeedRunner`(`@Profile("prod")`)调 `findSeedPlaceholders`+`updatePasswordHash` 设临时密码(`seed.admin-password`),修 Docker 首登死锁;`.env.example` 有 `SEED_ADMIN_PASSWORD`;`docker-up.sh` 打印「首次登录」账号 |
| v07-DOCKER-9 | 安装入口收敛:**Docker 只有 `docker-up.sh` 一个入口**(`docker-init.sh` 已删、`.env` 随机密钥生成内联为 `ensure_env`,含 `openssl rand`/`REMEMBER_ME_KEY`);**直装只有 `deploy.sh` 一个入口**(macOS 自动 `exec` 到内部实现 `_deploy-macos.sh`,`deploy-macos.sh` 已改名)。Windows 走 WSL2 复用 `docker-up.sh`(不另写脚本)。**落地页 `landing.html` 快速开始 + `.env.example` 注释均引用 `docker-up.sh`、不得再出现 `docker-init`**(防页面内嵌命令回归)。修真实案例:非技术 Mac 用户跑旧 `docker-init.sh` 无 docker 却被带去装孤立 compose 插件而卡死 → 现单入口 `docker-up.sh` 逐项自检 + 按平台给可复制修复命令 |
| v07-DOCKER-10 | 单一构建:`docker-compose.yml` **只允许一个服务带 `build:`**(仅 `app`);`backup` 复用同一 `image:` tag、**不写 `build:`**。修真实案例:`app` 与 `backup` 都 `image: <同 tag>` + `build: .`,在 **classic builder(非 BuildKit)**下同名镜像被 build 两遍,第二遍打 tag 撞 `AlreadyExists: image already exists` 而 `docker compose up --build` 失败(BuildKit 会去重不报,但不能依赖) |

**人工 · 真机验收(Mac + Ubuntu 分别)**

| 场景 | 校验 |
|---|---|
| 全新机一键起 | `bash deploy/docker-up.sh`(自检环境 → 起 → 验 /health)→ 登录/填报/AI 体检全通;`down && up` 数据不丢。Mac 各装法(Docker Desktop / OrbStack / colima)均跑通 |
| systemd → Docker 迁移 | `sudo bash deploy/migrate-to-docker.sh` → 账户/周期/uploads 零丢、schema_history 不重放、/health 通;`down`+`systemctl start finance` 可回滚 |
| macOS → Docker 迁移 | 同上,脚本提示先停前台 java;数据零丢 |
| Apple Silicon | `docker compose build` 原生 arm64 起得来 |
| GHCR | 打 tag 后 Actions 出 amd64+arm64 镜像,`docker compose pull` 可用 |

### v0.7 第二批 · 外部服务配置引导(2026-06-16)

**黑盒 · qa-run(v07-CFG-* · 静态)**

| Case | 校验 |
|---|---|
| v07-CFG-1 | `docs/configuration.md` 存在 + README 有入口链接 |
| v07-CFG-2 | LLM 配置页:可选 banner + 「如何获取 Qwen Key」折叠 + `form="llm-test-qwen"` 测试按钮 + `id="llm-test-qwen/deepseek"` sibling 表单齐 |
| v07-CFG-3 | `IntegrationsController` 有 `/llm/test` 端点 + `classifyLlmError` 脱敏 + `isPrivateKeyConfigured` 未配短路 |
| v07-CFG-4 | 私密红线:`testLlm` 方法体不读/不拼/不回显 key 明文(awk 抽方法体 grep 无 qwenKey/getString.*KEY/.token) |
| v07-CFG-5 | 短信页有「阿里云短信接入」文档链 |

**人工 · 真机验收(beta)**

| 场景 | 校验 |
|---|---|
| 折叠指引 | `/admin/integrations` LLM 卡顶部见「可选 + 解锁什么」;每 key 下「如何获取?」点开见 3-4 步 + 控制台直链 + 配置指南链 |
| 测试连接 · 未配 | 没配 key 点「测试连接」→ 顶部红 flash「未配置 Key · 请先填好并保存」 |
| 测试连接 · 配对 | 填对的 key 保存后点测试 → 绿 flash「Qwen 测试连接成功 · 可用」 |
| 测试连接 · 配错 | 填错 key 保存后点测试 → 红 flash「测试失败 · Key 无效或无权限」(**不回显 key**) |
| 私密 | 审计 `/admin/audit` 测试事件只记 vendor + 成功/失败归类,无 key 明文;`PrivacyIsolationTest` 绿 |
| 文档入口 | README「文档」「配置项」「首次登录」三处都能跳到 `docs/configuration.md` |

### v0.7 第三批 · 系统内首次引导(2026-06-16)

**黑盒 · qa-run(v07-ONB-* · 静态)**

| Case | 校验 |
|---|---|
| v07-ONB-1 | `HomeController` `/` 智能路由(零周期/零账户→`onboarding/index`,有数据→`redirect:/dashboard`)+ `DashboardController` 零周期兜底 `redirect:/`(修首登 500);`onboarding/index.html` 存在 |
| v07-ONB-2 | 引导页含「加账户 / 开本期周期」起步步骤;`/entry` 顶部有「周期流程」说明;`OnboardingRoutingTest` 在 |
| v16-EMPTY-1 | 全新部署空账期兜底:`/entry`(原 `orElseThrow` 找不到周期)、`/reports`(原 `ReportsAnchorResolver` 抛「尚未创建周期」)、`/checkup` 在零周期时**不再 500**,统一 `redirect:/?needs=period` 回引导页;引导页显**朱红醒目横幅**(`needs=='period'`),第②步「去开周期」需 `hasAccount`、第③步「去填报」需 `hasPeriod` 才是活链接(否则灰禁用 + 提示),按序解锁。修真实案例:测试用户全新部署点「记账」直接 500 |

**单元 · OnboardingRoutingTest**:零周期/零账户→onboarding;有账户无周期→onboarding;有周期无账户→onboarding;两者齐→redirect dashboard;`needs=period`→引导页横幅标志置位。

**空态全页巡检(2026-07-14 · beta 临时零账期家庭真机)**:两种空态逐页打状态码找 500 ——
① 纯空(0 账户 0 账期):`/ /dashboard /entry /reports /checkup /accounts /accounts/new /goals /goals/new /goals/new/custom /my-todos /profile/password` + 全部 `/admin/*` = 200 或优雅 302(entry/reports/checkup→`/?needs=period`,dashboard→`/`,my-todos→`/entry`→引导);
② 有账户+目标、零账期:`/accounts/{id}`(CASH/STOCK)`/accounts/{id}/edit` `/accounts/{id}/holdings` `holdings/new-auto|new-manual` `/accounts/{id}/broker` `/goals/{id}` = 全 200。
**结论:除已修的 entry/reports/checkup 外,无其它空态 500;详情页/持仓/目标详情均空态安全。** 测试数据用后清零(残留=0)。

**人工 · 真机验收(全新装)**

| 场景 | 校验 |
|---|---|
| 首登不崩 | 全新部署(零周期零账户)首次登录 → **不再 500**,落到引导页 |
| 引导可用 | 引导页见「开→填→关→出报告」一句话流程 + 3 步直达按钮;加账户/开周期后对应步骤打勾 |
| 完成即隐 | 加好账户 + 开好周期后,`/` 自动 redirect `/dashboard` |
| entry 说明 | `/entry` 顶部见「周期流程:开→填(本页)→关→出报告」 |

### v0.7.3 hotfix · 改密死循环(issue #1 · 2026-06-22)

**黑盒 · qa-run(静态)**

| Case | 校验 |
|---|---|
| v07-FIX-1 | `ProfileController` 改密后用 `SecurityContextLogoutHandler.logout(request, response, …)` 真作废 session(不再只 `clearContext()`);`ProfilePasswordChangeTest` 在 |

**单元 · ProfilePasswordChangeTest**:改密成功 → `session.invalidate()` 被调 + `updatePasswordHash(…,false)` + 跳 `/login?passwordChanged` + context 清空;原密码错 → 返回表单、不动 session。

**人工 · 真机验收(全新装 / 强制改密)**

| 场景 | 校验 |
|---|---|
| 首登改密不死循环 | 种子账号(must_change_pw=1)首登 → 被强制改密 → 改完**跳到登录页(给表单)**、用新密码登入 → 进 dashboard,**不再被弹回改密页** |
| 旧密码失效 | 改密后旧密码登录失败,新密码成功 |

**backward-compat 红线**
- 旧 `deploy.sh`(systemd 直装/迭代)路径不动,存量(含 prod/beta)零破坏
- 迁移前强制 mysqldump、全程不删旧部署、可回滚;共用 schema_history 防重放
- 密钥不进镜像/日志/git;`SERVER_ADDRESS=0.0.0.0`(容器内)+ 默认仅 loopback 发布

---

## v0.7.4 · 国内 Docker 部署网络阻断引导(FR-136~138)

**背景**:prod 隔离真机验证(2026-06-22)证明 compose 链路通(整栈 ~730MB),但大陆 Docker Hub 被墙 → 拉 `mysql:8.0` 卡死;GHCR(app 镜像)直连 OK。`docker-up.sh` 据此归因 + 引导镜像源。

**黑盒 · qa-run(静态 + 桩)**

| Case | 校验 |
|---|---|
| v07-CN-1 | `docker-up.sh` 含归因/引导逻辑:`pull_one mysql:8.0` 单独探 Docker Hub + `cn_hub_blocked_guide` + `registry-mirrors` + `docker.m.daocloud.io` + 已存在 `daemon.json` 不覆盖(`[[ ! -e`)+ `bash -n` 通过 |
| v07-CN-2 | 文档守护:`deploy/README.md` / `README.md` / `docs/faq.md` 三处均含「大陆 / mysql / registry-mirrors / daocloud」,且不再出现「`docker compose build` 可替代/绕过」误导措辞 |

**桩(stub)模拟验证(无需真 Docker)**:伪造 `docker`/`systemctl`/`sudo`/`curl` 入临时 PATH,`docker pull mysql:8.0` 按目标 `daemon.json` 是否存在切换成败(模拟"配了镜像源就能拉")。断言:
| 场景 | 校验 |
|---|---|
| 无 daemon.json · 非自动(Linux) | 打印含 `docker.m.daocloud.io` 的镜像源指引,最终因仍拉不到而 die(指向修复) |
| `FINANCE_ASSUME_YES=1`(Linux) | 自动写入 `$FINANCE_DAEMON_JSON`(内容含 daocloud + 1ms)+ 调 `$FINANCE_DOCKER_RESTART` 钩子 + 重试 `pull` 成功 → `up` |
| 预置 daemon.json(Linux) | 跑完该文件内容**保持不变**(不覆盖既有 docker 配置) |
| macOS(stub `uname`→Darwin + 有 colima/orb) | 走 `_cn_guide_mac`:打印 colima(`~/.colima/default/colima.yaml`)/ OrbStack(`orb config docker`)/ Docker Desktop 三种精确步骤;**不**写 `daemon.json`、**不**触发自动写(Mac CN 同样撞墙,网络层) |

**实测依据**:prod 写 `registry-mirrors` 后 `mysql:8.0` 实拉成功(2026-06-22);桩中"配了镜像源即可拉"的假设有真机背书。

**backward-compat 红线**
- 纯脚本 + 文档,0 Java / 0 schema / 0 镜像/编排变更;存量(prod/beta、已部署 Docker)零影响
- 自动写 `daemon.json`:用户同意 + 文件不存在 + 告知重启,三重前置缺一不写;公共镜像免登录(不硬编码阿里云专属地址)

---

## v0.7.5 · 新用户无痛苦收口(FR-139~141)

**背景**:全新用户视角审视 README + 部署,修 `<your-org>` clone 失败 + 全新 Docker 清成空态(与 systemd 一致,触发 onboarding)+ 文档订正。

**黑盒 · qa-run(静态)**

| Case | 校验 |
|---|---|
| v07-CLEAN-1 | 全新 Docker 清演示数据接线齐:`docker/clean-dev-data.sh` 存在且含互锁(`member.*id > 2`)+ `FINANCE_KEEP_DEMO` + 与 step10 同表集(`TRUNCATE TABLE period`/`account`);`entrypoint.sh` 迁移前判 `schema_history`(`FRESH_DB`)且仅 FRESH 时调清理;`Dockerfile` COPY 该脚本;全 docker shell `bash -n` |
| v07-CLEAN-2 | README 无 `<your-org>` 残留;测试数自洽(250 单元 / 367 黑盒,无旧的 244/319/338) |

**真机 · beta 隔离测试库(不碰线上 `finance` 库)**

| 场景 | 校验 |
|---|---|
| 全新库判定 | 空库 `information_schema` 查 `schema_history` = 0(FRESH);`db/apply.sh` 后 = 1(非 FRESH) |
| 全新库清理 | apply(含演示数据)后跑 `clean-dev-data.sh` → `period`/`account` 行数 = 0;`member` = 2、`family` = 1、`account_template` 保留 |
| 真实数据互锁 | 注入 `member(id=3)` 后跑 → 跳过、`account` 行数原样保留(不清) |
| 保留开关 | `FINANCE_KEEP_DEMO=1` → 跳过、演示数据原样 |

**backward-compat 红线**
- 删数据三重防线:① 迁移前无 `schema_history` 才清(migrate-to-docker 灌 dump 自带该表 → 永不触发;升级库已有该表 → 老用户零风险)② 真实数据互锁 ③ `FINANCE_KEEP_DEMO`
- 只 TRUNCATE 演示性表,保留 family/member/模板/runtime_config;0 schema、不动 systemd step10、存量零影响

## v0.8 · 「我关心的指标」管理页(FR-149/150 · 决策 102)

**背景**:`/admin/metrics` 勾选页接线——两组 checklist(家庭级 KPI / 账户级指标),勾选序列化进 `family.metric_prefs` JSON;dashboard 与 reports 共用此集。后端 `MetricPrefsService`(目录 + enabled + serialize)已就绪,本切片只接 controller + 模板 + 侧栏入口。

**黑盒 · qa-run(v08-METRICS-*)**

| Case | 校验 |
|---|---|
| v08-METRICS-1 | `GET /admin/metrics` 200 · 含侧栏「指标设置」高亮(`active=='metrics'`)· 两组 checklist 渲染(FAMILY 8 项 / ACCOUNT 15 项)· 当前启用项 `checked` · `mandatory`(`net_worth`/`current_value`)`disabled` 且 `checked` |
| v08-METRICS-2 | `POST /admin/metrics`(family=net_worth & account=current_value,xirr)→ 302 回 `/admin/metrics` · `family.metric_prefs` 写入 JSON `{"family":[...],"account":[...]}` · flash「已保存」· 审计 +1(`family_metric_prefs`) |
| v08-METRICS-3 | 必选项兜底:POST 不带任何 `family`/`account`(全空) → 落库仍含 `net_worth` / `current_value`(后端 `enabled()` 强制纳入 mandatory) |

**beta 验收批修复 + 指标计算正确性(v0.8.1)**

| Case | 校验 |
|---|---|
| 列表类型标签 | **PC + 手机**账户列表:类型 pill 在账户名**前**、固定 `min-width:3.4em` 对齐(类型字长不一也齐)。手机卡片(`sm:hidden`)2026-06-23 补齐,`qa-run v08-PILL-M` 源级防回归到「名后 ml-1」 |
| 默认观察账期 | as-of 默认 = **当前 OPEN 账期**(与主页一致),不取 max(period_start)(避免锚到 dev/未来 stray 期如 2034-01) |
| 收益率口径标签 | dashboard 列头「收益率」(非「年化收益率」)+ tooltip:满 12 期为年化 XIRR,不足显累计、不做单期年化外推 |
| 家庭指标控豆腐块 | `/admin/metrics` 勾选家庭指标真正控制 dashboard 5 个 KPI 豆腐块 + 头部储蓄率/MoM/YoY 显隐(FAMILY 目录精简到 dashboard 真有的 8 项,1:1)。beta 实测往返:只留 净资产+总资产 → dashboard 只剩这 2 块 → 还原全集恢复 |
| 指标设置入口可点达(v08-NAV-1)| 「管理」tab 落到 `/admin`,该落地页卡片网格须含「指标设置」→ `/admin/metrics`。**2026-06-23 漏修**:v0.8 只加进子页 `_sidebar`、没加进 `admin/index` 卡片网格 → 用户从管理首页看不到入口。`qa-run v08-NAV-1` 源级+渲染双查,防回归(原 `v04-CFG-8` 只查侧边栏、放过了这个洞)|
| 账户详情无 emoji | `/accounts/{id}`(STOCK):持仓管理用 inline SVG、估值标签「△ 估值」、dashboard 应急金 banner 用 SVG —— 无 💡/📦/📈 等 pictographic emoji(★ 风险星、↔↺✕ 排版符保留) |

**指标计算正确性 · `FactViewMetricsCalcTest`(单测 · periodPnl 走真实 PnlCalculator)**

| 场景 | 校验 |
|---|---|
| 单月无流水 | cumPnl=0(首期无损益)· netPrincipal=0 · monthsHeld=1 · momAmount=null · xirr=null(<2期)· sharePct=100%(唯一账户) |
| 多月纯估值 | 10000→10200→10404 · cumPnl=404(Σ各期损益)· netPrincipal=0 · momAmount=204 · xirr 非空(<12期=累计) |
| 带外部流入 | 10000→15000 含 income 4000 · cumPnl=1000(剔除工资)· netPrincipal=4000 · momAmount=5000 |
| 带转账(转出)| 10000→7000 转出 3000 · **cumPnl=0(转账无幽灵损益)** · netPrincipal=−3000 |
| 带转账(转入)| 5000→8000 转入 3000 · **cumPnl=0** · netPrincipal=+3000 |
| 占比 | A=7000 / B=8000 · sharePct 46.67% / 53.33%(÷家庭净资产 15000);家庭层面转账净零、两端 cumPnl 均 0 |

**backward-compat 红线**
- `family.metric_prefs` 为 v0.8 新增可空列(决策 102);NULL → 代码默认集,存量家庭零影响
- 只动 `web/admin` controller + `admin/metrics.html` + `_sidebar.html`;不碰 dashboard/_region、FactView、EntryService、calc/factview/service 既有逻辑
- 前端 `mandatory` 项 `disabled`(不提交),POST 端用 `MetricPrefsService.enabled` 兜底强制纳入,双保险

**跨币种不变性根治(v08-CCY-INV · 决策 107 · beta 验收暴露)**

> **背景**:切币种(CNY→HKD)后「本月资产收益率」乱漂(CNY −18% / HKD −9% / USD −88%)。**双重根因**:① v0.8 筛选器重做让 MoM/YoY/趋势/TWR/本月收益率吃**多期** `endBalanceBase`,但 `ensure` 只覆盖 anchor 一期 → 上期/窗口期缺汇率落 `1.0` 未换算,末期减上期=垃圾;② `FactMapper` 只认「一端=视图币种」的**直连**汇率行,视图币种为第三币种(USD 账户在 HKD 视图)缺三角换算 → 落 1.0。**修**:`ensure` 扩到 ≤anchor 全窗口(dashboard/reports/checkup)+ 视图币种全期补 `base→view` + `FactMapper` 经本位币三角换算 `fx(acct→view)=rate(base→view)/rate(base→acct)`(base 视图与旧实现完全一致 → 向后兼容,无 schema 改动)。**语义锁定:视图币种=显示镜头 → 比值类币种无关、金额类按 fx 精确缩放**。

`CurrencyInvarianceTest`(单测 · 同一套 orig 经济事实按因子 k 构造各视图)

| 场景 | 校验 |
|---|---|
| 比值类币种无关 | 紧急储备月数 / 负债率 / 净资产环比% / **本月资产收益率** 在 CNY(k=1)/USD(k=6.774)/HKD(k=0.14761)视图下**完全相等** |
| 金额类按因子缩放 | 净资产 / 总资产 / 总负债 / 本月收益额 / 月均支出 = 本位币值 × k(精确) |
| 账户级 | `accountPerformance` 占比 sharePct 币种无关;账户现值按 k 缩放 |

`qa-run.sh`(黑盒 · 先给 family#1 全账期播一致汇率,使不变式可严格断言)

| Case | 校验 |
|---|---|
| v08-CCY-INV-2 | 本月资产收益率(用户实际踩雷点)CNY=USD=HKD 完全相等(beta 实测 −27.68%) |
| v08-CCY-INV-3 | **属性级** · dashboard 所有含 `%`/「月」的比值类 KPI 三币种逐条相等(网住未来任何新增比值指标) |
| v08-CCY-INV-4 | 净资产金额按 fx 精确缩放(USD/CNY≈0.14 · HKD/CNY≈1.09 · 容 0.5% 舍入) |

> **回归保护**:这是币种切换第三次出问题(v0.2 CASE 倒挂 → v0.5 PMC 未换算 → v0.8 跨期/三角换算)。前两次都是逐个指标点检,这次加**属性级**护栏(`-3` 逐条扫所有比值 KPI)+ 单测口径双保险,从「补单点」升级为「网住整类」。

---

## v0.9 · 根路径公开落地页(FR-160/161/162 · 决策 108-111)

> 背景:Chrome 把整域(prod `/`+`/login`、beta 多页)误判「Deceptive pages」。服务器/证书均干净,触发特征是「`.top` 域 + token + 首屏裸登录框」。v0.9 给根路径一个公开介绍页消除该特征,并作对外门面。

| Case | 校验 |
|---|---|
| v09-LAND-1 | 匿名 `GET /` = 200 公开落地页,含定位文案(家庭账房 / 资产全局图)+ GitHub 全 URL + 功能总览截图引用 |
| v09-LAND-2 | 匿名 `GET /` 直接 200、**不再 302 `/login`**(裸登录触发特征消除 = 降钓鱼信号核心) |
| v09-LAND-3 | 已登录 `GET /` → 302 `/dashboard`(沿用既有分流,老用户无感;新家庭仍走 onboarding) |
| v09-LAND-4 | 回归:匿名 `GET /dashboard` 仍被拦去 `/login`(permitAll 只加了精确根 `/`,没放过头) |
| v09-LAND-5 | v0.9.1 精修元素都在:GitHub 角标(`github-corner`/`octo-arm` 挥手)+ 真实 4 步命令块(`git clone`…`docker compose up -d`)+「它解决什么」四问 + 数字带(`data-stat`) |
| v09-LAND-6 | 主页数字带**联动一致**:`data-stat` 的 version/tests/migrations/blackbox = 版本(`prd/v0.*.md` 个数)/ 单测(README「N 单元」)/ 迁移(`db/migration/V*.sql` 个数)/ 黑盒(README「N 黑盒回归」);过时即红(与 release skill 同口径) |

> **v0.9.1 精修(8 小巧思 · 居中单列参考 brew/ohmyzsh)**:GitHub 角标挥手 / GitHub-Star 按钮 / 进场错落 / CTA 下划线 / 实时 star 数 / 纸张颗粒 / 卡片轻抬 / 数字滚动;朱印评审去掉。命令改真实 4 步(不假装一键)。**数字带 4 个数字走「发版联动」**:`release-prod` skill preflight 加硬门(版本/迁移自动算、单测/黑盒随 README),landing 过时则 `die`;`qa-run v09-LAND-6` 同口径日常守护。

**实现要点 / 防回归**
- 复用既有 `common.HomeController`(本就 `@GetMapping("/")`)加匿名分支 `me==null → "landing"`;**不新建同名控制器**(2026-06-25 曾误新建 `web.HomeController` → bean 名冲突致 beta 启动崩溃,见 tech-design 决策 108)。
- `landing.html` 复用 `fragments/layout :: head`(自托管 tailwind/字体/css,零外部 CDN);截图落 `static/img/feature_summary_total.jpg` 不外链;全 inline SVG、无 emoji。
- `SecurityConfig` permitAll 加精确 `"/"`(非 `/**`);`/login`、会话、登录成功跳转均不变;零 schema。

---

## v0.9.3 · 表单缺项前置拦截(全量审计 · FR-164 · 决策 113)

> 背景:承接 v0.9.2 划转空字段拦截,扫遍全站写表单,把「缺必填项 → 发请求 → 拿 400 / 存脏数据」统一改成**客户端前置拦截**。必填挂原生 `required`;仅在某控件命中时才必填的,用新的 `data-require-when` 通用助手声明式挂载(命中外自动摘除,避免对隐藏/不适用字段误挂 required 卡死提交)。

| Case | 校验 |
|---|---|
| v09-FORM-1 | entry 收入 + 支出金额均挂 `required`(空字段前置拦截 · 三个 cash-flow 表单各自独立、互不阻塞) |
| v09-FORM-2 | 通用条件必填助手 `data-require-when` 就位于 layout footer(全站注入 · `curVal` 取 radio/checkbox/select 当前值,命中才 `el.required=true`) |
| v09-FORM-3 | 应急金「手填基线」`fixedBaseline` 条件必填(`data-require-when="autoBaseline=false"`)· 新建 + 编辑两页一致;选「自动基线」时不挡 |
| v09-FORM-4 | 自选股「从现金划转买入」`costBasis` 条件必填(`data-require-when="deductCash=true"`)· UI 早已写「划转买入时必填」,补上强制 |
| v09-FORM-5 | 宏观基准录入 CPI/M2 挂 `required`(空值无意义) |
| v09-FORM-6 | 成员编辑「显示名」挂 `required maxlength=40`(原仅新增有校验、编辑可清空提交) |

> **刻意保持可选(审计后确认,不挂 required)**:entry 期初汇总收支(占位明写「留空=未填」)· 短信 AccessKey/密钥(「留空=不修改」增量更新设计)· 划转到账额 `toAmount`(仅跨币种填)· 角色标签 / 备注 / 成员手机号 / 各类带默认值的运营参数。把「故意可选」与「漏挂必填」区分清楚,是这次审计的核心结论。

**实现要点 / 防回归**
- `data-require-when="控件名=值"`:同表单内名为「控件名」的控件,radio 取 `:checked` 的 value、checkbox 勾选→其 value(默认 `'true'`)未勾→`'false'`、其它取 `.value`;== 指定值则该字段 `required`,否则摘除。`change` 时实时同步,HTMX `htmx:load` 再绑(本站条件字段均为整页表单,绑定是冗余保险)。
- 原生 `required` 由浏览器 + HTMX 共同拦截提交(HTMX 尊重 HTML5 校验);`data-searchable` 下拉因 `display:none` 不可挂 required(会「not focusable」),靠默认选中首项保证非空。
- 纯模板 + 1 处全站脚本,零 schema、向后兼容。

---

## v0.10 · 仪表盘「人赚 vs 钱赚」实时拆解 + 实时收支趋势(FR-165~167 · 决策 114-120)

> 背景:实时仪表盘此前只显「钱赚」侧(投资收益/财富水位),「人赚」侧(收入/支出/净流入)在 v0.4 被搬去 `/reports` 储蓄区(已关账快照,不含本月)。本版把 `ΔNW = 人赚 + 钱赚` 的人赚那一刻拆解 + 实时收支趋势补回首页。三个核心数复用现成 `KpiSnapshot`,零新增计算、零 schema。

| Case | 校验 |
|---|---|
| v10-CASHFLOW-1 | 新 `<section id="dash-cashflow">` 在,且 dashboard 长文目录 `tocItems` 同步了 `#dash-cashflow` 锚点(改 section 必同步目录的纪律) |
| v10-CASHFLOW-2 | 三态文案钩子在:空态 CTA「本期还没填收支」+ 半填诚实「收支可能不全」+ 首期分支 + 有符号双向条(`renBarStyle`) |
| v10-CASHFLOW-3 | 实时收支趋势 canvas(`cashflowTrendChart`)+ 序列注入(`cashflowSeries`)+ datalabels(数值浮于柱顶/数据点,非 hover) |
| v10-CASHFLOW-4 | 控制器装配 `cashflowSplit` + 钱赚 = ΔNW − 人赚(`deltaNetWorth.subtract(ren)`)卡内恒等,不与「本月资产收益」打架 |

**单测(JUnit · 12 个)**
- `CashflowSplitViewTest`(7):四象限符号 `(+,+)/(+,−)/(−,+)/(−,−)` 文案与正负、首期只显人赚、空/半填三态、双向条宽度比例。
- `CashflowBreakdownTest`(3):PMC 优先盖过 cash_flow、PMC 空回退 cash_flow、null 期返回 0。
- `CurrencyInvarianceTest`(+2):三视图币种下 `人赚+钱赚==ΔNW` 恒等、比例与条宽币种无关、金额按 fx 缩放、`收入−支出==人赚` 同源;实时序列 live 标记 + 与 breakdown 一致。

> **有符号双向条(回应评审)**:人赚、钱赚各自可正可负,零基线居中、正右(绿)负左(赭),长度 ∝ |值|÷三者最大绝对值;四象限统一一套画法、不特判;一句话文案随象限自适应;首期(无上期 → ΔNW/钱赚不可算)只显人赚。

> **完整度诚实**:收支选填,PMC 成员级 `已填 N/M`;空态(0 填)引导填报但投资侧(钱赚)仍显(不依赖收支);半填挂琥珀 pill +「人赚是下限」。

**实现要点 / 防回归**
- `cashflowBreakdown` 与 `pmcFirstNetInflow`(人赚口径)**同源同分支**(PMC 优先 ×`baseToViewFactor` → view;空回退 account cash_flow),保证「收入−支出==人赚」、与 KPI 同口径。
- **不挂 metric-pref 开关**(决策 119):dashboard section 本就不受指标开关控制;且 `enabled` 的 `defaultOn` 仅整份 prefs 为空时生效,挂门会让老家庭升级后看不到 → section 无条件渲染、零兼容坑。
- 趋势复用既有 `chartjs-plugin-datalabels`;含进行中本月(最右浅色)= 区别于 `/reports` 已关账快照。
- 纯展示 + 两个只读 service 方法 + 视图模型,零 schema、向后兼容。

---

## v0.10.1 · 缺陷修复(币种单一镜头 + 提醒窗口 · 决策 121-122)

> 币种切换第 4 次复发 + 短信多发 1 天。这次不再逐点补,改**根因 + 真端到端护栏**。

| Case | 校验 |
|---|---|
| v10-CCY-LENS-1 | 【真端到端】登录后请求真 `/dashboard?currency=CNY` 与 `=USD`,解析**实时收支趋势**各期 netInflow,断言逐期比值全相等(同一汇率均匀缩放)。修前多币种家庭某期会漂(0.15 vs 0.1471)→ DRIFT 红 |
| v10-CCY-LENS-2 | 【真端到端】同上,解析**净资产趋势**各期值(始终存在 · 正是出 bug 的核心量),断言逐期切币种按同一汇率缩放 |
| v10-REMIND-1 | `ReportReminderScheduler.inReminderWindow(daysLeft, leadDays)`:lead=N 恰好 N 个提醒日([0,N-1]),过期(负)不发(单测 `ReportReminderWindowTest` 5 例) |

**根因 & 修复**
- 净资产换算原 `FactMapper` fx 三角换算 join `period_id = p.id`(**每期历史汇率**)→ 单期金额对,但跨期差额(ΔNW)被减数/减数用不同月汇率,多币种大额家庭切币种偏 ~17%。改为**取锚点期(≤rangeEnd 最新一期)单一汇率换算所有期** → 金额/差额/比值三币种按同一汇率均匀缩放。view==base 时因子恒 1 → 本位币视图**完全不变**(向后兼容),零 schema。
- 提醒窗口 `daysLeft ≤ leadDays`([0,lead] 共 lead+1 天,多发 1 天)→ 改 `daysLeft < leadDays`([0,lead-1] 共 lead 天)。

> **教训(为什么单元测试没网住)**:`CurrencyInvarianceTest` 是单元 + **单一 mock 汇率**(所有期同一个 k),恰好把"多期不同历史汇率"这个真实触发场景**抹平**了 → 永远绿。币种这类「跨期/跨账户口径」缺陷,**必须端到端**(真 HTTP + 真 SQL + 多期不同汇率 + 多币种账户)才网得住。v10-CCY-LENS-1/2 即此。属性级单测 + 端到端缺一不可。

---

## v0.10.2 · 长文目录漏维护守护(TOC-SYNC)

> 长文目录(reports/dashboard/checkup 三页)的 `tocItems` 是手工内联列表,新增 section 易忘加 → 目录漏节。把它变成 CI 闸门。

| Case | 校验 |
|---|---|
| v10-TOC-SYNC-1 | 任何带 `scroll-margin-top` 的 `dash-`/`checkup-`/`sec-` 前缀 section,必须出现在对应页(dashboard/checkup/reports)的 `tocItems`。加 section 漏加目录条目 → 红 |
| v10-TOC-SYNC-2 | 每个 `tocItems` 锚点 `href:'#x'` 必须有真实 `id="x"`(section 被删/改名留下死链)→ 红 |

> 约定:新增 TOC section 用 `dash-`/`checkup-`/`sec-` 前缀命名 + 挂 `scroll-margin-top:80px`,守护即强制补目录。新开带 TOC 的页面(新前缀)时,把新前缀加进守护的 case 分支。2026-06-29 全量审计:三页均已同步,无漏项。

---

## v0.10.3 · 收益名义口径 + 目录补漏(决策 123)

| Case | 校验 |
|---|---|
| v10-NOMINAL-1 | dashboard 速览 / checkup 体检 / reports 财富水位 的收益数用 `nominalGrowthPct`(名义);dashboard 速览无「跑输通胀」残留;reports 财富水位 CPI 购买力线**保留**(对比线不删,只是不从收益里扣通胀)|
| (v10-TOC-SYNC-1 复用)| dash-cashflow-trend(实时收支趋势)补独立锚点后,被守护纳管并要求在 tocItems(已加「收支趋势」)|

> 口径澄清:① 环比 MoM=净资产总变化(含人赚)② 本月资产收益=纯投资(剔人赚)③ 之前的「真实收益」=扣CPI——三者不同。v0.10.3 起洞察/体检/水位**统一用名义**(净资产名义增长),通胀只作 CPI 购买力线/M2 社会财富线**参照**,不替用户从收益里扣。守护 `v10-NOMINAL-1`。
> 守护提取 bug 教训:`[a-z0-9-]+$` 提 `href:'#x'`/`id="x"`(结尾引号)在 GNU grep 下失效 → 假绿;一律用 sed 捕获组提取。

---

## v0.10.4 · 账户列表补全列 + 指标筛选/横滑(决策 124)

| Case | 校验 |
|---|---|
| v10-ACCT-COLS-1 | dashboard 账户表补 6 列(net_principal/period_return/return_base/max_drawdown/months_held/plan_actual · data-mcol)+ 内联指标 chips(data-mchip · localStorage)+ 账户名 acct-sticky + 列多横滑;MetricPrefsService 账户目录移除无数据的 twr/yoy/risk(不超卖)|

> 背景:账户级指标目录 15 项,但全站唯一消费方(dashboard 账户列表)只渲染 6 列 → 勾了其余 9 项不显示(v0.8 扩了数据+目录、模板列没补完)。修:有数据的 6 项补成列;无 per-account 数据的 twr/yoy/risk 移除目录。
> 列多展示:账户名 sticky 左固定;指标列 > 最佳数(7)时容器横滑;内联 chips 即时切列显隐(localStorage 记住,默认=指标设置勾选集)。手机端卡片不变。

---

## v0.10.5 · 收益对比同窗口口径(决策 125)

| Case | 校验 |
|---|---|
| v10-WINDOW-1 | `BenchmarkAggregator.windowDiffPercentPoints` + `beatStatusWindow` 就位;预实(`FactViewServiceImpl`)、reports vs基准(`ReportsController`)都改用窗口缩放;reports 不再 `diffPercentPoints(ap.xirr())`(累计减年化的旧错口径) |
| BenchmarkAggregatorTest(+3) | `expectedOverWindowPct`(8%→1月≈0.64%、12月=8%);**月度2% vs 年化8% → 跑赢(非跑输6pp)**;阈值随窗口缩放(12月回到±2pp) |

> 根因:账户 `xirr=annualizedOrCumulative`——满12期年化、不足累计;而预期/基准恒为年化。短账户「几个月累计」减「年化」→ 错判。修:实际累计 vs 预期(年化缩放到持有月数)同窗口比;阈值同步缩放;<12期「年化」列动态标「累」、预实标「近N月」。checkup 本就 gate≥12,本次让 dashboard/reports 与之一致(且短账户也能正确比)。

---

## v0.11 · 隐私模式(决策 126–131)

| Case | 校验 |
|---|---|
| v11-PRIVACY-1 | layout FOUC(`sessionStorage.getItem('privacy')`)+ `togglePrivacy` + `#priv-float` 浮动控件 + `html.privacy [data-priv]` CSS;nav `priv-eye` 眼睛 |
| v11-PRIVACY-2 | dashboard/reports/checkup/accounts/entry 渲染后**均含 `data-priv` 金额标记** + 双入口(`togglePrivacy` + `#priv-float`)——无页面整页漏标/漏入口 |
| v11-PRIVACY-4 | layout 含 `priv-peek` CSS 覆盖 + `pointerdown` 事件委托 → 隐私态按住 `[data-priv]` 去模糊(松开复原);覆盖 HTMX 片段 |
| v11-PRIVACY-3 | 紧急储备(月)/本月收益率(%)源码**不带 `data-priv`**(比例不误遮);dashboard 图表 `fmtMoney` 含 `isPrivacy()` 守卫(金额隐藏 · 曲线形状保留) |

> 纯前端叠加(0 schema/接口):绝对金额 `data-priv` → `html.privacy` 下高斯模糊 + 不可选中复制;比例/%/月数/形状保留。会话级(sessionStorage),重开默认显示,FOUC 防闪。双入口(nav 眼睛 + 左下常驻浮动 chip)零 JS 同步。手验:隐私态从顶层 tab 走 dashboard→reports→checkup→accounts→entry(PC+移动),逐屏确认无裸金额、比例仍在、浮动 chip 可随处恢复。威胁模型 = 肩窥/截图(非取证级,DOM 仍有真值)。

---

## v0.11.2 · 账期滚动修复(切月两 bug)

| Case | 校验 |
|---|---|
| v11-ROLLOVER-1 | `PeriodOpener.closePriorOpenPeriods` + `forceClose`(bug1 开新期即关旧期)、`PeriodMapper.findOpenBefore`、`predictLoanBalance` + `signum()>0?ZERO`(bug2 LOAN 夹零≤0)均在 |
| PeriodOpenerLoanPrefillTest(+6) | LOAN 预填夹零:**税务欠款 -72000→0 预测 0(非+72000)**、房贷 -1000000→-990000 外推 -980000、增债 -150000、单期沿用、已平维持 0、跨零夹 0 |

> bug1:滚动 cron 原只开新期不关旧期 → 06 悬挂 OPEN;修为开新期前 force-close 早于新期的 OPEN 旧期(自动 openIfDue + 管理员 openNextNow 同口径)。bug2:LOAN 趋势外推越过 0 变正 → 夹到 ≤0。现网历史数据需手动补救(关 06 + 07 税务欠款重填 0),修复不追溯。

---

## v0.11.2 补 · 报表标签修复 + 储蓄区口径

| Case | 校验 |
|---|---|
| v11-REPORTS-1 | `ReportsController` 的 `labels` 用 `debtTrend`(全期 N)非 `decomposition`(N-1)→ 负债曲线画 N 点、本金vs损益分解图 `labels.slice(1)` 对齐 N-1 柱(修「2 关账期时负债 1 点/分解 0 柱」) |

> bug3:labels 错接 decomposition(N-1)→ 负债曲线(用 labels+N 个 debtValues)少 1 点、分解图(labels.slice(1))再少 1 → 2 期时负债 1 点、分解 0 柱。改用全期标签后对齐。储蓄区(双柱/收支趋势/储蓄率)口径确认:只统计家庭月度「2 框」(period_member_cashflow),账户 cash_flow 流水不计入;不做回退,引导卡文案讲清(决策 B)。

---

## v0.11.3 · 储蓄区图表 fragment 边界修

| Case | 校验 |
|---|---|
| v11-REPORTS-2 | `reports/_savings.html` 图表 `<script>` 在 `th:fragment="section"` 内(`</script>` 后紧跟 `</section>`)→ reports 用 `:: section` 引入时脚本不被丢,双柱/收支趋势 canvas 可渲染 |

> bug:图表脚本原写在 fragment 的 `</section>` 之后 → `:: section` 引入只拿 section、脚本丢失 → 双柱/收支趋势 canvas 空(KPI 在 section 内正常)。修:`</section>` 挪到 `</script>` 之后。口径不变(决策 B):仍只统计家庭月度「2 框」PMC。

---

## v0.11.4 · 报表账户表补全指标 + vs基准口径修(决策 135/136)

| Case | 校验 |
|---|---|
| v11-REPORTS-METRICS | `ReportsController` 注入 `acctMetrics=metricPrefsService.enabled(family.metricPrefs,"account")` + 全字段 `accountRows`;`reports/_region.html` 第四表按 `acctMetrics.contains(...)` 门控 `data-mcol` 指标列(与仪表盘同源)· e2e 实测账户表出现 ≥3 种 data-mcol |
| v11-REPORTS-PP | 家庭卡 + 账户行 + 预实 pill 单位一律 `pp`(基准值仍 `%`);模板不再有 `${familyBenchmarkDiff\|row.diffPct} + '%'` · e2e 实测 pp≥1 且误用 % 计数=0 |
| v10-WINDOW-1(改) | vs基准/预实 实际 = 显示的那个 xirr(`displayedDiffPercentPoints`:<12 期累计、≥12 期年化),基准同基(<12 期 `expectedOverWindowPct` 缩放);三处调用(家庭/账户/FactView 预实)已切换,不再用 `cumPnl/净投入` 当实际 |
| (e2e) 报表-vs基准无爆值 | `/reports` 渲染后不出现 `|pp|>1000` 的爆值(修 v0.10.5:净投入极小 → +19497pp) |
| (UT) BenchmarkAggregatorTest | `displayedDiffPercentPoints`:8.30% vs 4.61%(≥12 期)=+3.69pp→BEAT;1 月累计 2% vs 年化 8%(缩到窗口)≈+1.36pp→BEAT;xirr/基准 null / months≤0 → null;`beatStatusDisplayed` null/0 月 → NA |

> bug:v0.10.5 把 diff「实际」改成 `cumPnl÷净投入`,净投入极小的账户爆成 `+19497pp`,且与卡片头条显示的 XIRR 脱节(头条 8.30% 却「跑输 -243%」);单位也错标 `%`(比例减比例应是 pp)。修:实际取「显示的那个 xirr 本身」(同 `annualizedOrCumulative` 口径),基准同基对齐,单位 pp。同源修仪表盘/报表「预实」列。第四表另补全指标列:复用 `/admin/metrics` 账户级配置(与仪表盘同一套开关 + 共享 `acctHiddenCols` 隐藏集)。

---

## v0.11.5 · 比例相比口径审计 + 报表观察账期(决策 137/138)

| Case | 校验 |
|---|---|
| v11-AUDIT-PP | 全系统「两比例相比」一律相减+pp:配置对照 超配/欠配(`_allocation-diff.html` `dif` = 当前−模板)显示 `pp` 不显 `%`;财富水位 真实/相对社会收益(`WaterLevelCalculator.realReturnPct`)用 `nominalGrowthPct.subtract(benchmarkCumulativePct)` 相减(不再 Fisher `(1+n)/(1+b)−1`),`_wealth-level.html` 显 `pp` |
| v11-REPORTS-ASOF | 报表观察账期筛选器:`ReportsController` 收 `asof` + 注入 `periods`(CLOSED)/`asof`;`reports/_region.html` 有账期下拉(`th:each p:${periods}` + `onchange` 带 `asof=`)· e2e/手测:15 个已关账 option、默认选中最近已关账、`asof=2025-09` → 数据截至 2025 年 9 月 |
| (口径清单) 保持 % | 收益率 / XIRR / TWR / 占比 / 最大回撤 / 负债率 / 储蓄率 / 配置份额(cur/tgt)/ 风险敞口 / 目标进度 / 环比同比增长 —— 单一比率或增长率,非「相比」,保持 `%` |
| (审计-已正确) | vs基准(家庭/账户)、预实、体检账户基准对照(`BenchmarkComparator`)、体检 RET-2/3 —— 本已是「相减 pp」,不动 |

> 说明:审计规则 = 「分子分母都是比例、结果表达『相比差多少』」→ 相减取 pp;而「一个量对另一个量的增长率 / 单一占比 / Fisher 前的名义率」是率,保持 %。财富水位从 Fisher 精确改简单相减,是用户口径(统一 + 直观)压倒精确性的取舍。观察账期下拉上界取「默认锚」而非 `LocalDate.now()`,避 JVM/DB 日期偏差把当月挤出下拉。

---

## v0.11.6 · dashboard 首屏层级修正 + 收支趋势空态

| Case | 校验 |
|---|---|
| v11-DASH-LAYOUT | 目标进度 + AI洞察 从 `dashboard/index.html` 顶部下移到 `_region.html`「KPI 总览之后」:`index.html` 不再含 `_insight-strip :: strip` / `_progress-strip :: emptyHint`,`_region.html` 含之(位于 KPI grid 之后、`#dash-cashflow` 之前);`DashboardController` 有 `cashflowSeriesHasData`,`_region.html` 有 `th:unless="${cashflowSeriesHasData}"` 空态细条 |
| (无头渲染核对) | PC(1366)+ 移动(390)首屏顺序 = 标题 → 账户范围 → KPI 总览 → 财务目标 → AI洞察 → 人赚vs钱赚/收支趋势 → 图表 → 账户列表;收支趋势有非零数据时出图(canvas 有绘制),全零时显空态 |

> bug:`目标 + AI洞察` 两条挂在 region 外顶部,喧宾夺主(净资产/KPI 主角被挤到下方)。修:下移进 region、置于 KPI 总览之后(`insight`/`goalsProgress` 本在 `populateModel`,HTMX 刷新也在)。附:收支趋势近月全零时不再留空白大卡,改空态细条。

---

## v0.11.7 · 「待办」页退休折叠进「填报」

| Case | 校验 |
|---|---|
| v11-TODO-RETIRE | 导航 `nav.html` 不再含 `@{/my-todos}`;「填报」项承接 `state.pendingCount > 0` 的「·N」角标;`MyTodosController` 为 `redirect:/entry?mine=true`;`my-todos.html` 模板已删 |
| v11-SUN-RINGCOLOR | 旭日环级配色(2026-07-16 两轮评审):`lens.js` 含 `PALETTE_PLANS` 五套方案(A 飞书原味/B 外环原版/C 色相错位/D 莫兰迪默认/E 国风),`LENS_META.palette` 由 `FamilyConfigService.K_LENS_PALETTE` 驱动(`/admin/calc-tweaks` ②.5 区块 radio+色卡可配,白名单 A-E 脏值回落 D);`colorMapFor(values, ring)` 环内字典序防撞;信息可见性:扇区常显 名称+占比(`≥28°` 且非隐私加 `fmtShort` 短金额),中心信息盘 `#sunCenter` 默认合计 hover 显 名称/金额/占比(金额 data-priv),隐私切换 MutationObserver 重绘(canvas 金额不受 CSS blur 管辖);**小扇区引导线**(角度<14° 且占比≥0.1%):PC `renderLeaders` graphic 自绘折线到圆外(色点+名称+占比 · 左右分侧 · 纵向避让 每侧≤8 · 外半径收窄 76% 腾空间),移动(<480px)退化为图下 `#sunSmallNotes` 补注清单 |
| v11-DIM-REV2 | 维值修订三(2026-07-16 TUI 拍板):`AssetClass` 平民化 label(股票股权/债券理财/现金活钱/房产/黄金加密,无「权益」残留);`IndustryTag` 17→18(+MONEY_CASH 货币现金 · FINANCE_ESTATE 拆 FINANCE 银行券商保险 + ESTATE_CONSTRUCTION 地产建筑 · 删 OVERSEAS);`V47__industry_revision.sql` 存量迁移(FINANCE_ESTATE→FINANCE,OVERSEAS→NULL);「行业集中」看板筛选与 LENS-CON-1 改「股票股权」;AI prompt 余额宝→MONEY_CASH(实测真调 LLM:萝卜-余额宝 → CASH_EQ + MONEY_CASH) |
| v11-LSEL | 自研搜索下拉:`lens-select.js` 渐进增强 `select[data-lsel]`(原生控件隐藏保留表单语义,无 JS 降级);面板搜索三路匹配 中文子串/全拼连写(`data-py` 来自枚举 `getPinyin()`)/首字母;键盘 ↑↓ 回车 Esc;动态 options MutationObserver 自动重建;覆盖 打标页 4 类下拉 + 透视 8 个选择器(下一层按/行/列/度量/构建器×4);实测:输入 hb→货币现金、gp→股票股权;**移动端(≤640px)**:面板改贴底 bottom sheet(fixed·z 10050·open 时 portal 到 body 逃出卡片层叠上下文,不被隐私/目录浮钮遮挡),搜索框 `16px !important`(iOS Safari 对 <16px input 聚焦自动放大整页——用户主诉"点击被放大";打标页 `.tags-table td input` 会把它压回 12px 故须 important),选项 15px 大触控目标 |
| v11-ROUND3 | 2026-07-17 第三轮评审 9 项:①打标页控件统一 32px 等高(lsel按钮/input/AI钮同高,双行 AI 钮同款同宽)+ 账户 meta 行(主理人·币种);②行业 18→20(混合配置=固收+/FOF · 红利公用=长电/中证红利;货币现金→「货币基金/存款」);③预设看板 6→10(全维度)+「夫妻结构」→「成员结构」;④切看板自动收起「+自定义」构建器;⑤打标页顶部说明 风险/流动性/账户类型/主理人/币种/地域 来自账户资料;⑥应急金 banner 3 处金额 data-priv;⑦POST /lens/insight AI 解读(PivotEngine 工程算好事实 · LLM 禁算只解读 · 成员真名→成员A/B;实测输出要点行合规);⑧交叉透视行/列各 2 维(两层列头 colspan + 行头 rowspan,groupStable 保证同父值连续);⑨引导线根治:内环小块改图下补注,仅外环拉线 · 外半径 76→82%。**第四轮(同日)**:①引导线标签同侧按质心 18px 等距散开(不再向下堆挤);②打标页列盒模型统一(acct/hold 行除第一列外 padding 一致,缩进只落第一列;实测双行 AI 钮 x/宽 完全相等);③洞察按视图键缓存(切走隐藏/切回恢复/收起记忆),卡头显示模型+时间+「重新解读」强刷;④洞察重做=工程先判异常信号(过度集中 vs 管理页阈值/打标缺口≥30%%/高风险超标/过度分散/碎片化≥3 块)→ LLM 按信号出洞察+一条最优先动作,无信号如实说(实测:「未分类 60.1%…尽快打标」)。**第五轮(2026-07-17)**:①pivot 宽度自适配(数值列 min-width 92px · padding 9/12 · 铺满容器);②「度量」→「指标」pills 多选(默认 金额+占比,至少留 1,单元格多行第一指标为热力基准);③看板按用户关心度重排(资产类型/风险/成员/平台/行业/用途/流动性/币种/地域/账户类型),默认打开「资产类型」;④打标页每账户行「改资料 →」直达 /accounts/{id}/edit(风险/主理人/币种/流动性),持仓账户另有「持仓 →」直达详情改市场地域(实测 15+6 个入口);⑤AI 洞察与 AI 打标均按管理页 K_LLM_PRIMARY_VENDOR 排序取 client(实测主选 deepseek 时 vendor=deepseek) |
| v11-CASHROW | 券商/交易账户的现金部分(valuationMode=CASH 的 holding 行)此前双重遗漏:①透视头寸 industry 硬置 null 落未分类、assetClass/risk 继承账户级(被算成 股票股权·高风险);②打标树直接过滤,用户不可见无从知晓去向。修复:头寸层 cashRow 语义定死(现金活钱 · 货币基金/存款 · 低风险 · 灵活取用 · region null);打标页保留为只读子行「货币基金/存款 · 系统归类,不可改」,AI 不碰。e2e 实测:股票类账户按行业出现 货币基金/存款=28458.76;该头寸 资产类型×风险=现金活钱·低风险 |
| v11-ENTRY-UX | 填报页(2026-07-17 评审):①全家可见确认保留(默认 mineOnly=false + 仅我切换,v0.4.15 起即有,账户行 avatar 已有);②收入记录行新增 **账户主理人头像**(avatar-N 圆标,ownerColorMap 同款)+ **填报日期**(submitted_at → MM-dd,title 含完整时刻);投影 `IncomeEntryRow` 扩 ownerName/submittedAt(LEFT JOIN member);SQL 实测 迪娃/07-01 正确(页面级渲染需 OPEN 期,beta 当前 CLOSED 由模板同构保证);③UED:_row 右列操作区重排两段式(快捷支出/账户间划转 各带 eyebrow,控件统一 h-8,划转 select 全宽+金额/到账/按钮一行),收入表单控件统一 h-9 |
| v11-UED8 | 2026-07-18 八项 UED 细节:①支出输入框与「保存本月总支出」同 items-end 流(参考行移出,统一 h-11);②「现金收入/股票收入」tab 不再独占一行,嵌入表单行首与金额/类目同行(h-9 同高,id→class 双处绑定);③移动端「刷新持仓估值/+新账户」whitespace-nowrap 不再折字;④自研下拉移动端打开不自动聚焦搜索框(弹键盘会挡选项列表,PC 保留直打搜索);⑤目标条带移动单列(原两列 150px 格 + nowrap 底行必溢出框体);⑥AI 资产洞察卡标题与信号 pills 分层(原混排换行参差);⑦「本期 xx-xx/收入已录入」pills 移动横排一行(原 flex-col 竖占);⑧净资产趋势图例窄屏短标签(CPI 购买力/M2 财富线)+9px 小色块一行放下,顶距 40 防 datalabel 蹭图例。实测断言:#3 单行 true/#7 同行 true/#4 focus false/#8 截图一行 |
| v11-R6 | 2026-07-19 六项:①目标条带移动回两列提密度(外层 px-3 py-3 内卡 px-2 py-1.5 gap-1.5,底行 flex-wrap 防溢出;实测两列/无溢出);②「本期/已填」pills 强制同行(flex-nowrap + 移动 10px;实测 sameRow=true 右缘 228<360);③净资产趋势移动端**自绘 HTML 图例**(#nwLegendM 一行永不换,Chart.js 原生 legend 窄屏隐藏——上轮短标签方案在真机字体宽度下仍折行,根治);④流水行账本式两行(行1 类型 w-16|摘要 truncate|金额右对齐成列,行2 时间/备注 pl-72 对齐摘要);⑤账户页顶部 tile 中文为主(现金 CASH);⑥目标百分比 setScale(2)(实测 78.33%/43.52%)。验证用临时目标(TOTAL_ASSETS/DEADLINE)插删干净 |
| v11-R7 | 2026-07-19 五项:①目标条带 ⓘ 从 span+title 换 `_kpi-info` 组件(kpi-info.js 已带 preventDefault+stopPropagation → 点击弹描述、不再误跳目标详情;实测 panel 弹出且留在 dashboard);②小图标热区:`.kpi-info-btn::after/.tap::after inset:-12px`(视觉 14px → 热区 ~38px,elementFromPoint -8px 处仍 HIT),流水删除 ✕ 加 .tap;③移动自绘图例找回原生 toggle(点击项 setDatasetVisibility 显隐曲线 + 40% 透明删除线;实测 true→false→true);④README 旭日截图重拍为「成员结构」看板(内=主理人 外=风险,1600×880,?v=3);⑤docker-publish 失败根因=aliyun maven 镜像 502(瞬态,rerun success);加固:Dockerfile ARG CN_MIRROR(默认 1 本地走 aliyun,CI 传 0 直连 central,去单点) |
| FR6-1(改) | GET `/my-todos` → 302(退休重定向,保老书签) |
| FR6-2(改) | 跟随 `/my-todos` 重定向落到 `/entry?mine=true`(含「保存我的本月收支 / 应填账户」) |
| v04-UX-7(改) | `/entry?mine=true`(承接待办)不暴露 `SNAPSHOT_TODO` enum / 类型英文括号 |

> 决策:待办页早已是 `/entry?mine=true` 的只读子集(列表 + 「填 →」跳填报),而填报页能内联填 + 「我未填」标记 + 进度 + 自动关账;仪表盘/提醒也都指向填报。三处重叠 + 导航双入口对「10 分钟/月、非技术家属」是噪音。故退休 /my-todos、角标并入「填报」、保 302 重定向。FR6-3(mine 过滤行数)不变。

---

## v0.12 · 收支填报「收入侧」升级(结构化 · 关联账户 · 直接入账)

| Case | 校验 |
|---|---|
| v12-INCOME-CAT | `V34` 迁移:`cash_flow_category` 加 `account_type` 列 + 新增 stock_salary/dividend/stock_sell(股票类)· `CashFlowCategory` 域含 accountType |
| v12-INCOME-ENDPOINT | `POST /entry/income` + `EntryService.recordIncome`;服务端红线校验「类目.account_type == 目标账户.type」(NULL 不限),错配抛异常拒绝 |
| v12-INCOME-STOCK | 股票账户收入走 `creditAccountBalance → StockHoldingService.adjustAccountCash`(落 CASH 现金行,扛估值刷新)+ applyDeltaToBalance(立即入快照);记 `is_adjustment=0` 真实外部流入 |
| v12-INCOME-KOUJING | `FactViewServiceImpl.netInflowIncome/netInflowExpense`:收入侧 PMC 手填(历史)优先否则 cash_flow 汇总(新账期),支出侧不变;按期各取其一不叠加(防双计);币种走本位币保不变性 |
| v12-INCOME-UI | `entry/index.html` 收入侧**类型优先**(`tab-cash`/`tab-stock` + `income-cash-block` + `stock-holdings-target` + `/entry/income`);`_row.html` 移除硬编码 `+收入`(无 `name="kind" value="INCOME"`) |
| (UT) CashflowBreakdownTest +2 | PMC 收入缺(totalIncome=null)→ 收入取 cash_flow(8000)不被低估为 0;PMC 收入 9000 存在时不与 cash_flow 8000 叠加成 17000(防双计) |

### v0.12.1 精化 · 股票收入 = 持仓版(未上市模型升级 · +股数入账)

| Case | 校验 |
|---|---|
| v12-MANUAL-SHARES | 未上市持仓 = `股数 × 单股估值`:`V35` 迁移 `UPDATE ... valuation_mode='MANUAL' SET shares=1`(老数据总值不变)· `AccountValuationService` MANUAL 分支 `manualBase += shares × manualValue`(`multiply(sh)`)· `StockHoldingService.createManual(displayName,shares,unitValue)` + `addShares` + `currentUnitValueInAccountCcy` |
| v12-STOCK-SHARE-INCOME | 股票 +股数入账:`EntryService.recordStockIncomeExistingHolding/NewAuto/NewManual`(改建持仓 → applyDelta(+value) → cash_flow `is_adjustment=0` + `ref_holding_id/ref_shares`)· 端点 `/entry/income/stock/{holding,new-auto,new-manual}` · `CashFlowMapper` insert/findById 带 ref 列 · 联动持仓 fragment `entry/_income-stock.html :: holdings` |
| v12-STOCK-SELL-HIDDEN | 卖出回款不算收入:`CashFlowCategoryMapper.listIncomeOrdered` `WHERE code <> 'stock_sell'` + `V35` `stock_sell` sort_order 沉底 |
| (UT) StockManualSharesTest ×8 | createManual 存 shares+单股 · addShares 增减(冲回不为负)· currentUnitValue(MANUAL=单股 / AUTO=价×fx / 无价=null)· convertToManual 保 shares 且总值守恒(单股=整笔÷股数) |
| (UT) ManualHoldingValuationTest ×2 | MANUAL 估值 = 2000×240=480000 · 老数据 shares=null 兜底 1 股 → 总值不变 |
| (UT) EntryStockIncomeTest ×3 | +股数记外部流入(is_adjustment=0 + ref_holding_id/ref_shares + amount=股数×单股)· 无价拒绝且不写库 · 删除按 ref_shares 冲回股数(不走现金行) |
| (e2e) 主线7 持仓版 | 现金股息 +4200(承 v0.12.0)· 未上市建仓 +100 股 ×50=5000(snapshot+5000 · flow ref)· 已有持仓 +50 股=2500(股数 100→150 · snapshot+2500)· 删除 +股数 冲回(150→100 · snapshot 回落)· 删建仓笔 股数→0;工资→股票账户拒错配 |

> 决策(承 prd/tech-design v0.12):收入=外部流入(不进 PnL)· 股票收入按持仓入账(+股数上市按市价/未上市按手填单股估值 · +现金落 CASH 行)· 未上市升级为股数×单股估值(V35 老数据 1 股折算总值不变)· 卖出回款不算收入 · +股数删除按 ref 列精确冲回股数 · 收入侧与账户明细同一批 cash_flow 两视图。

---

## v0.13 · 开账基线 + 社区 issue #3 修复

### v0.13.0 · 新账户「开账基线」不计入当期收益

| Case | 校验 |
|---|---|
| v13-OPENING | 开账基线口径:`SnapshotMapper.firstAppearingAccountIds`(账户首次出现期)· `FactViewServiceImpl.openingBaseline` + `netWorthTrendExOpening`(收益指标/财富水位剔除存量本金)· `CashflowSplitView` 三分 `ΔNW = 人赚 + 钱赚 + 开账基线`(`subtract(ob)`)· dashboard `_region.html` 第三行「开账基线」 |
| (UT) CashflowSplitOpeningTest | 三分自洽:人赚 + 钱赚 + 开账基线 = ΔNW;无新账户期该项为 0,与现状一致 |
| (e2e) 主线9 开账基线 | 新账户仅最新期首现快照 ¥176,543 → dashboard 出现「开账基线」行 + 金额 · 不计入钱赚 |

### v0.13.1 · 社区 issue #3(@BetterQx)· 估值精度 + A 股拉价

| Case | 校验 |
|---|---|
| v13.1-ISSUE3-PREC | 精度放宽:`V37` 把 `stock_holding.manual_value` / `cost_basis` / `stock_price_snapshot.close_price` → `DECIMAL(20,6)`;表单 `holding-new-manual` 的 `unitValue` + `holding-new-auto` 的 `costBasis` `step="0.000001"`(单股估值 15.678 不再被截成 15.68) |
| v13.1-ISSUE3-CN | A 股交易所前缀集中到 `AShareTicker`(沪 = 首位 5/6/9,深 = 其余);`SinaStockClient` / `TencentStockClient` 均 `AShareTicker.withExchange` 复用,无 `startsWith("6")` 残留(上交所 ETF 513180 不再误判 sz → 熔断) |
| v03-STOCK-3(改) | 创建 MANUAL 持仓走 `shares` + `unitValue`;单股估值 `15.678` 原样落库(`manual_value=15.678` → 1),验证 (20,6) 精度 |
| v03-STOCK-5b | 上交所 ETF `513180`(market=CN)自动拉价成功、写 `stock_price_snapshot`(前缀判 `sh513180`) |
| (UT) AShareTickerTest ×5 | 沪:513180/510300/600519/688981/900901→sh;深:000001/002594/300750/159915/200011→sz;withExchange 拼前缀;空值兜底 sh;去空白 |
| (UT) SinaStockClientTest / TencentStockClientTest +1 各 | CN symbol:ETF/科创/B 股→sh,创业板/深 ETF→sz(旧 startsWith("6") 会漏) |
| (UT) StockManualSharesTest +2 | createManual / updateManual 单股估值 15.678 / 2.3456 原样落库,服务层不四舍五入(scale 保留 3/4) |
| (e2e) 主线7 精度 | 未上市建仓 `unitValue=15.678` → `manual_value=15.678` 原样落库(非 15.68) |

> 决策(承 prd/tech-design v0.13 § v0.13.1):精度纯拓宽(widening)向后兼容,老数据零影响;A 股前缀规则集中一处消除两 client 重复且错误的判断 —— 一处网住整类(沪市 ETF/科创/B 股),防止再漂。

---

## v0.14 · 贵金属账户 + 自动金价 · LLM 供应商自选

| Case | 校验 |
|---|---|
| v14-METAL | `V38` 迁移(`stock_holding.unit` 列 + `METAL` 类型 CHECK + `metal_account` 模板)· `AccountType.METAL`/`Market.METAL` · `MetalUnit`(GRAMS_PER_TROY_OUNCE + 每克归一)· `MetalPriceClient`(source=sina-metal)· `StockPriceFetcher` METAL 路由(`fetchMetalAndPersist`)· `StockHoldingService.createMetal` · `holding-new-metal.html` |
| v14-METAL-PD-SGE | 钯金无上海盘:`MetalUnit.tickerFor("PD","sge")→null`(UI 提示改选国际) |
| v14-LLM-VENDOR | LLM 主选供应商 / 温度 / 模型级联:`K_LLM_PRIMARY_VENDOR/TEMPERATURE/MODEL` · `LlmDiagnoseService.orderByPrimaryVendor` · `QwenLlmClient.currentTemperature/pinnedModel` · `IntegrationsController.normalizeLlmModel` · 管理页 `primaryVendor`+`llmModelSel`+`syncLlmModels` |
| (UT) MetalUnitTest ×8 | tickerFor(品种×源,钯 SGE→null)· gds_/hf_ symbol 映射 · currency/默认单位随源 · normalizeToPerGram(SGE 金不变 / 银÷1000 / 国际÷31.1035)· perHoldingUnit(盎司×31.1035)· 盎司往返 |
| (UT) MetalPriceClientTest ×5 | 解析 SGE 金(元/克不变)/ SGE 银(÷1000)/ 国际金(每克归一 · USD)· 混源批量 · 空 payload 跳过 |
| (UT) LlmVendorOrderingTest ×3 | 主选 Qwen→Qwen 先;主选 DeepSeek→DeepSeek 先;未知/null→保持原序 |
| (e2e) 主线10 贵金属 | 建 METAL 账户 → SGE 金 892/克×200克=¥178,400、盈亏(892−500)×200=+¥78,400 · 国际金 10/克×31.1035×3盎司≈USD 933(oz→g 因子)· 持仓页渲染 AU9999/XAU |

> 决策(承 prd/tech-design v0.14):贵金属复用持仓/估值机器(METAL 类型对称加密)· ticker 编码品种×源、currency 定源币种 · 单位存用户原样、快照归一每克、估值层一次换算 · 全局价格源仅作新建默认、已建持仓各记各源 · LLM 主选/温度/模型走接入源页(模型级联下拉·越权回落 auto),业务 prompt 不动。

---

## v0.15 · 券商自动同步(富途 / 老虎 · 只读)

| Case | 校验 |
|---|---|
| v15-RO-1(黑盒+UT) | **只读铁律**:`service/broker/` 全部源码无 `unlockTrade(` / `placeOrder` / `modifyOrder` / `cancelOrder` / `replaceOrder` 调用;`BrokerReadOnlyGuardTest` 去注释后静态扫,一处网住整类 |
| v15-MAP-1 | 对账 `reconcile` 只动 `sync_source=本 vendor` 的持仓行(用户手填持仓绝不碰);券商有我方无→建 AUTO、都有→更 shares/cost、我方有券商无→软归档;现金按币种 upsert;期权/期货 `skippedNonEquity` 计数 |
| v15-LINK-1 | 关联(替换接管)高危不可自动回退:关联前先 `AuditLogType.BROKER_LINK` 落持仓快照 → 软归档现有持仓 → 建绑定;两步确认硬门(`!confirmed || !acknowledged` 即拒) |
| v15-CRON-1 | `broker-sync` 纳入 `DynamicScheduleConfig`(cron 可配 `K_BROKER_SYNC_CRON`、默认工作日 16:45、无 enabled 关联时空跑)· 手动同步走持仓页 / 关联页 |
| v15-CFG-1 | 管理页 ⑥ 券商段:老虎(tiger_id/RSA 私钥留空保原值·`type=password` 不回显/账户)+ 富途(OpenD host/port)+ 同步 cron + 一键测试连接(只拉账户验证)· 审计不记私钥明文 |
| v15-ENTRY-1 | 持仓页有「券商自动同步」入口(`/accounts/{id}/broker`)+ 同步来的持仓打「券商同步」徽章 |
| v15-ENTRY-2 | 账户券商页 `broker/link.html` 只读铁律段口径含<b>一键托管</b>提示(「富途 OpenD 网关支持我们一键托管」)+ 直达 `/admin/broker/opend` 托管向导入口(不止 integrations 管理页有);保留图文教程链接 |
| v15-OPEND-READY | OpenD 向导 `broker/opend-wizard.html` 状态机:phase=RUNNING 且未点重配 → 第 0/1/2 步整段收起(`#sec1`/`#loginSec`/`#step2Locked`/`#step0` 隐藏),只显示 `#readyBox`「OpenD 已配置完成 · 运行中」总览卡(去账户页关联 + 我要重新配置);点 `#reconfigBtn`(reconfig)→ 展开全部向导步骤 + `#reconfigBanner`(OpenD 不停),点 `#reconfigCancel` 回就绪态;未运行/已安装未启动/装到一半 → 照旧展开向导。beta 真机三态(拦截 /status 伪造 RUNNING)截图验 |
| (UT) BrokerTickerTest ×4 | 富途前缀 `HK./US./SH./SZ.`→Market;老虎 market 字段→Market(symbol 归一大写);未支持市场→null;`isEquity` STK/ETF/空→纳入、OPT/FUT/WAR→跳过 |
| (UT) BrokerReconcileTest ×2 | reconcile 新增/更新/归档计数正确 + 手填行(sync_source=null)不被归档;跑 FUTU 对账不碰 TIGER 同步行 |
| (UT) BrokerLinkSafetyTest ×3 | link 顺序:审计快照 → 归档 → 建绑定(InOrder);非持仓类账户拒关联;unlink 清 sync_source + 审计 |
| v15-FIX-TX | 关联与首次同步<b>拆两段事务</b>:`link()` 只做快照+归档+建绑定(@Transactional),提交后由 controller 另起 `initialSync()` 跑首次同步 —— 修 `link()` 内嵌套 `sync()`(自带事务)抛错把外层标 rollback-only 导致「关联失败:Transaction rolled back」的 bug。beta 实测:新建证券账户关联富途 → 302 + flash「已关联 富途 · 首次同步待完成」(非 rollback) |
| v15-UX | 二次确认走<b>自建弹窗</b>(`#lnkModal`/`#unlModal`,ESC/遮罩关闭),broker/link.html 无 native `confirm(`;建账户向导选「证券(STOCK)」时显 `#brokerSyncHint` 券商同步提示(searchable-select 原生 change 触发切换) |

> 决策(承 prd/tech-design v0.15 · 用户 6 点评审):富途优先 + 老虎;只读铁律(富途永不 unlockTrade、老虎只查询,静态护栏钉死整类);关联高危留快照 + 软归档 + 两步确认(可找回);手动 + cron 双同步;币种以我方账户配置为准做 FX 折算;期权/期货本版跳过(见 `docs/backlog.md`)。
> **富途适配器已真机接线并联调通过**(tech-design 决策 L):FutuSession 异步回调包同步、只调三个查询接口;beta 上对用户真实 OpenD 测试连接成功(实盘户 2/共 10)。老虎适配器待用户 key 真机验证。
> **v0.15.x 关联颗粒度重构**(决策 M · 守护 v15-GRAN/v15-ENTRY-1):V40 broker_link 加 opend_host/port(NULL=全局);入口迁账户页(徽章+「券商」操作);per-link 测试连接富卡片;OpenD 向导终端化;同步显示名用券商证券名。beta 真机全验:徽章/入口、OpenD 重启自动重拉、富卡片(港美徽章·尾号3682·5笔)、中文名升级(拼多多/阿里巴巴/小米集团-W/腾讯控股)。

---

## v0.17 · 保险账户(储蓄/理财型)· issue #6

| Case | 校验 |
|---|---|
| v17-INSURANCE-1(静态) | 保险类型全链落地:`AccountType.INSURANCE("保险")` · `V44` 两处 CHECK(account + account_template)放宽含 `INSURANCE` · `pickBucket` 短路 `"INSURANCE".equals(type)→Bucket.INSURANCE` · `AssetInsightService` financialSum 含 `AccountType.INSURANCE` · `account_insurance_policy` 旁表 + `SAVINGS_INSURANCE` 类目 + `annuity_insurance`/`whole_life_insurance` 两模板种子 · `InsurancePolicy(Mapper)`/`InsuranceSubType` 就位 |
| v17-INSURANCE-2(静态) | UI/手填:`.pill-slate` 定义 + 四处徽章三元(detail/index/entry_row)含保险分支 · 向导 `#insuranceHint` 消费型友好提示(「消费型是纯支出」)+ `#insuranceKindWrap` 子类型下拉 · `supportsHoldings` 仅 STOCK/CRYPTO/METAL(保险走手填 snapshot 非持仓) |
| v17-BUCKET(命门) | 保险产品类目流动性 = SEMI_LIQUID,但 `pickBucket` 必须先按 type 短路,否则会误分进「投资」桶。资产配置环形/报表 `_allocation-diff.html` 保险独立类目(`cur['INSURANCE']`),`allocation_anchor.SP_4321` 目标 20% 现成承接 |
| v17-VALUE | 现金价值 = 每期手填 `period_snapshot`(与 WEALTH/PROPERTY 同路,`AccountValuationService` 零改动),计入净资产/总资产/配置;FX 折算复用 |
| v17-POLICY | 保单登记(全可选·纯展示):建/编账户绑定 `InsurancePolicyMapper.upsert`;详情页「现金价值 vs 累计已缴保费」并列(不算 IRR/收益);改成非保险类型时 `deleteByAccount` 清理旁表 |
| (UT) AllocationDiffTest +2 | 保险 SEMI_LIQUID → INSURANCE 桶(非 INVEST);liquidity 缺失时 typeFallback 也落 INSURANCE 桶 |
| (UT) InsurancePolicyTest ×6 | INSURANCE 液性=SEMI_LIQUID/归类=ASSET · paidPremiumTotal=保费×已缴期数(任一缺→null)· hasAnyField · InsuranceSubType.labelOf 大小写不敏感 + 脏值/ null 返空串 · frequencyLabel 中文 |
| (UT) CurrencyInvarianceTest +1 | 保险账户(手填现金价值)计入后:占比比值币种无关、现金价值金额按 fx 因子精确缩放(走同一 AccountPeriodFact 缩放链,不引入新范式) |
| (e2e) 主线 保险 | 建保险账户(子类型=增额终身寿)→ 填现金价值 82,400 → 总资产 +82,400 · 资产配置环形出现「保险」类目 · 详情页登记保单(承保公司/保费/期数)→ 现金价值 vs 已缴保费并列 |
| v17-WIZARD | 账户向导模板卡从**死展示**改为**真选择器**:点卡片(`.tpl-card` data-tpl-*)→ 回填类型/币种/建议名 + 高亮选中(`.tpl-selected`+「已选」)+ **平滑滚动到「新账户」表单区(`#newAcctHead` scrollIntoView)** + 光标落名字(focus preventScroll)+ 触发类型联动(券商/保险提示);删掉重复的「模板」下拉换隐藏 `#tplId`;type 去 `data-searchable` 便于 JS 赋值回显。beta 实测点「贵金属账户」→ METAL/CNY/名字回填 + 页面滚到表单(scrollY 0→3336、表单头进视口),点「增额终身寿」→ INSURANCE+子类型下拉联动 |
| v17-LOAN-PROMPT | 贷款趋势预填从**开账静默外推**改为**填报页显式接受**:`PeriodOpener` 删 `applyLoanPrefill`,贷款开账延续上月值 `prev`(不再静默写 predicted、不再起草转账);`predictLoanBalance` 保留;填报贷款行内提示条「按上两月趋势本月预计还到 X(较上月 ±Y)· [接受] [保持上月]」;**接受** = `POST /entry/{id}/accept-loan-prediction` → `acceptLoanPrediction` 复刻旧逻辑(写 predicted + 起草还款转账 cash→loan + markDone),**保持上月** = 复用 `/entry/{id}/balance` 提交 prev。beta 真机验:房贷置提示态→渲染「预计还到 −¥1,185,640(较上月 +¥4,720)」→ POST 接受 → snapshot=-1185640 + 转账 acct1→11 ¥4720 + todo DONE |
| v17-LOAN-COMPAT(兼容) | 显示闸 `loanPromptVisible(predicted,prev,committed,todoDone)`:仅 `predicted≠prev && !todoDone && committed==prev`(新默认态)才出提示。**老账期**(旧代码已把 committed 写成 predicted≠prev)与**已确认**(todo DONE)天然不出、不回改;`PeriodOpener.createPeriodAndTodos` 幂等只影响新开账期,零迁移。EntryLoanPromptTest ×6(新默认出/老账期隐/已确认隐/无建议隐/手改隐/null 守卫)|

> 决策(承 prd/tech-design v0.17):**坚持原有账户理念**——保险 = 又一类「按周期手填当前价值」的资产账户,复用手填 snapshot 估值链、净资产、配置、报表,**不引入预算引擎、不做逐笔、不替 LLM 算保单收益/IRR**。①独立第 9 类 INSURANCE(配置桶/锚/洞察文案 v0.4–v0.5 早已预埋 INSURANCE,本版接线);②保单 11 字段落**独立旁表** `account_insurance_policy`(冷·纯展示,不污染热表);③子类型 Java 枚举存 name()(loanKind 式,无 DB CHECK);④现金价值手填计入总资产,消费型不建账户;⑤消费型保费提醒列 backlog。**命门**:`pickBucket` 按 type 短路 INSURANCE 桶,必须先于 liquidity_class(SEMI_LIQUID 否则漏进 INVEST)。

---

## v1.1 · 资产透视(多维打标 + 统一查询网关 + 交叉透视 + 旭日下钻)

| Case | 校验 |
|---|---|
| v11-LENS-1(静态) | 底座:`V45`(account 三列 asset_class/platform_tag/industry_tag + stock_holding.industry_tag + lens_board)· `AssetClass`(6大类·defaultFor 派生)/`IndustryTag`(12粗行业·D3)· `LensRegistry` ≥8维/5度量(一处登记全组件生效)· `POST /lens/query` 唯一网关 · `PivotEngine.holdingLevelSplit` 收益归因降级标记 |
| v11-LENS-2(静态) | nav **双端**「透视」入口 · `lens.js`(drill 状态机/sunburst/lens-pivot)· 打标页「保存全部打标」显式接受 + 「AI 推荐打标」· `LensAiTagService.fromName` 枚举白名单 · `LENS-CON-1/2` 集中度规则 + `calc-tweaks` 阈值可配 |
| v11-GATEWAY | 统一网关:旭日/切片排行/交叉透视/明细/预设与自定义看板全部走 `POST /lens/query`(spec=行/列/度量/筛选);响应带头寸目录,cells 索引引用 → 明细零额外请求;beta curl:风险×大类 grand ¥4.06M · 17 头寸 |
| v11-ATTRIB(命门) | 收益归因诚实降级:账户级维度(风险/平台/大类/主理人/类型/币种)按 accountId 去重精确聚合 factview 度量;持仓级维度(行业/地域,含 filters)→ latestPnl/cumReturn=null(UI 显「—」+说明)、cumPnl 改持有口径(市值−成本);**绝不按市值比例假分摊**;不聚合 XIRR |
| v11-VALUE | Σ头寸 ≡ factview 账户现值(统一 fx 因子缩放,与仪表盘同源);LOAN 排除;未填报账户跳过;未打标=「未分类」沉底照常参与 |
| v11-TAGS | 打标:账户编辑页 3 控件(大类默认派生提示/平台 datalist/行业)+ 持仓页行内行业下拉(选完即存·CASH 行不标)+ /lens/tags 集中打标;AI 推荐只预填(AI 角标),显式保存才落库;保存只写非空白名单值;LLM 全不可用 → 入口降级隐藏 |
| (UT) PivotEngineTest ×8 | 单/双维分组 · 行/列小计和=总计 · 未分类沉底 · 筛选内占比 · 账户级 pnl 去重精确(5000/20000/11.11%)· 持仓级降级(null+持有口径 15000)· **占比/累计收益率三币种相等+金额按 fx 缩放** · 注册表完整性(≥8维/5度量) |
| (UT) LensTagsTest ×2 + LensAiTagServiceTest ×2 | AssetClass.defaultFor 穷尽(LOAN/OTHER→null 不装懂·货基→现金及等价)· 枚举 12 行业/安全解析 · AI 白名单(枚举外「半导体/BOND」丢弃·platform 截 40·未出现名称不入)· 无 client → available=false+suggest 空 |
| v11-REVIEW(评审修订) | 透视主体**内嵌仪表盘**(lens/_section fragment · region 外 · TOC「资产透视」条目;/lens=直链别名;配置卡锚点↓);打标页**树状**(账户›└持仓 · 持仓账户行业归子行);行业 12→17;新增**用途**维度(V46 · 纯手标)+ 第 6 预设看板「资金用途」;**单行 AI**(formaction+only 参数);「+自定义」上移+scrollIntoView 修复。beta e2e:6 看板 chips/用途透视出「长期增值/应急金」/树状└/21 个单行 AI 钮/构建器在视口内,零 JS 错误 |
| v11-PERF(性能 · v1.1.1 重设计) | 头寸快照缓存:**TTL 12h 仅兜底** + **SWR 过期不阻塞**(旧值直返·后台单飞换新)+ **启动预热**(ApplicationReady)+ **写路径事件全覆盖**(LensStaleEvent:填报/收支/转账/估值刷新/账户增改档,`@TransactionalEventListener(AFTER_COMMIT)` 防读未提交旧数据)→ 用户任何时刻不等组装。prod 慢根因 = v1.1.0 的 60s TTL 每分钟踩一次同步冷组装(prod 数据/机器均排除:21账户/2核/负载0.03)。beta 实测:重启首查 84ms · 90s 连打全 8–20ms 无慢点 · 填报→2s 内 lens 精确 +12345(注意:测试须用**活跃**账户,归档账户不进 factview——曾两轮误判)。v1.1.0 期(60s TTL · per-family 锁双检 · 并发初载只组装一遍)+ 打标保存/持仓行业改 **evict 即时失效**(余额/估值靠 TTL 收敛,注释说明取舍)+ 透视区 **IntersectionObserver 懒加载**(首屏 0 查询 · 锚点直达/无 IO 降级均覆盖)。beta 实测:/lens/query 命中 200–400ms→**10–60ms**;切看板 ~0.9s→**~0.2s**;首屏 lens 查询 3→**0**;evict 改行业立现。初载"5 次查询"复测 3 次均为 3 次 = 测量误差,无冗余 |
| v11-UED(体验修订) | /lens/tags UED 重做:PC `table-layout:fixed`+colgroup 固定列宽+控件满宽;**AI 预填 = 控件金色高亮**(替代挤位的 pill 角标);手机 ≤820px **表格卡片化**(td 变行+data-label · 子行铜边缩进)。AI prompt 重写:**底层投向**语义 + 判定规则(货基/余额宝/储蓄 → 行业 null 绝不 FINANCE_ESTATE)+ few-shot 5 例 + 宁缺勿滥;beta 实测「支付宝-余额宝」→ CASH_EQ/支付宝·蚂蚁财富/行业未分类(修复前误标金融地产)。打标入口:透视区头部 + 账户页 + **管理页 tile**。双端截图 UED 复核(承 feedback_ui_ued_review) |
| v11-DIM-FINAL(维度拍板) | 「大类资产」→**「资产类型」**(「类型」→「账户类型」防混淆,注册表 label 一处改全组件生效);平台维度保留(A);行业清单=「投向」语义混合清单(A);旭日**每环一套独立配色**(RING_PALETTES 内环深调/外环浅调 · 修订自"全局同色":内外环是独立维度,共色系层级不可辨),**环内**同维值同色(colorFor(v, ring) 稳定哈希),排行按外环维度用外环色系。beta 截图验证:双环色系一眼可辨、外环同值跨父块同色、排行条与外环一致 |
| (e2e) 主线 透视 | 登录 → 仪表盘「深入透视」→ 风险总览出旭日/排行/透视表 → 切「行业集中」看板 → 点透视格 → 明细抽屉(头寸→账户详情)→ /lens/tags 打标 → 手机视口复跑 |

> 决策(承 prd/tech-design v1.1 · D1–D6 全拍板):**要 OLAP 的交互(切片/下钻/换维),不上 OLAP 引擎**(<200 头寸内存 group-by);**近似打标不做基金成分穿透**(个股准·基金粗标·UI 明示);头寸事实=查询时实时组装(不物化);维度/度量注册表一处登记;/lens 页前端例外走原生 JS(拖拽/联动状态机,项目其余仍 HTMX);AI 只做分类不做数学、白名单+显式接受;5 预设看板=spec 常量。


## v1.2 · 月度归因复盘 + 再平衡执行闭环 + 性能底盘(2026-07-20)

| Case | 断言 |
|---|---|
| v12-ATTR | 归因引擎纯函数(calc/review):恒等式 ΔNW=人赚+Σ账户钱赚+开账+未归因 严格闭合;两步法汇率拆分(标的=pnlOrig×fx_end,汇率=pnlBase−标的,两项和≡pnlBase 零残差,CNY 恒 0,清仓回退期初 fx);未归因如实显示不吞;6 归因维度(账户/资产类型/成员/平台/币种/账户类型,**行业不做**:持仓账户账户级行业恒空会误导);dashboard「本期怎么变的」卡内 `hx-trigger=revealed` 懒加载 fragment(瀑布/贡献榜「赚得最多·亏得最多」两列/12 期趋势/维度 chips);**mount 必须显式 `hx-target="this"`**——region 根有继承性 `hx-target=#dashboard-region`(币种镜头+90s 自刷机制),缺省继承会让归因响应把整个 region 连同全部看板吞掉(2026-07-20 事故:用户"看板一闪而过消失"=90s 自刷触发吞噬;守护已断言);4 单测绿。e2e:chips 6 维、维度切换、隐私下 canvas 金额不绘 |
| v12-REVIEW-AI | AI 月度复盘:工程信号(亏损集中≥50%/汇率占比≥20%/入不敷出/口径缺口)→ LLM 禁算只解读(system 指令「就事论事不责怪」);V48 review_ai_cache UNIQUE(family,period,dim) 覆盖写,关账期可回看;真名脱敏;follow 主选 vendor。e2e 真调:输出合规要点行,二次点击「· deepseek · 缓存」 |
| v12-PLAN | 再平衡闭环:V48 rebalance_plan/item 两表;建议 content_json.actions 勾选采纳(账户名→id 精确匹配失配跳过);划转 AFTER_COMMIT 核销(同 from/to 且金额 ≥ 条目×K_REBALANCE_MATCH_PCT 默认 0.8,只核最早一条 PENDING);「已在外部完成」=MANUAL_DONE 标注未经核销;关账自动归档;诊断 prompt 注入执行率(只解读不生成指令);dashboard 洞察条「再平衡计划 x/y」pill。4 单测绿;e2e:采纳 2 条落库、手动完成后 pill 1/2。曾修:insertPlan @Param 嵌套引用 `#{plan.familyId}`(裸 `#{familyId}` BindingException 500) |
| v12-PERF | dashboard TTFB:beta 实测 P50 476ms→**364ms**(目标≤400 ✓)P95 586ms(≤800 ✓);手段:momYoy(FactSlice) 重载——显示窗口覆盖 12 月时(默认 1Y/ALL)复用主 slice 免二次 load,短窗口保持独立 load **显示零回归**;pendingRows 全行装配(含流水)→ todo 计数轻查询(模板只消费个数);归因区懒加载(fragment 独立 128ms 不拖首屏);数值口径零变化(422 单测背书) |
| v12-2-FONTSCALE | 全局字号调节(issue #7 · a11y)。**核心不变量:标准档 scale=1 逐像素等价现状(零回归)**——所有缩放点 `calc(x*var(--fs-scale,1))`,scale=1 时 =x。5 层:①根字号 calc→rem 类+间距+正文自动 ②style.css 6 个写死 `text-[Npx]` 用 `!important` 重定义压 Play CDN(全站 820 处仅 6 离散尺寸;无头实测 10→11.5→13px)③组件类 32 处 calc 化 ④图表 `chartFont(base)=round(base×scale)` + 归因图 `fontscalechange` 活重绘(全局单次绑定防 HTMX 累加)+ dashboard region 90s 自刷跟档 ⑤移动 input 16px 地板**限 lg/xl**(标准零改动)。控件:PC nav `Aa` 下拉三档 + 移动 ☰「字号」行,`[data-fs-opt]` aria-pressed 双端同步;localStorage 按设备 + head FOUC 内联脚本防闪。真机验收:三档×桌面/移动截图,标准档对齐/pill/流水/图例全不坏。守护 v12-2-FONTSCALE 断言 6 类重定义+FOUC+setFontScale+chartFont+控件 |
| v13-SUN-METRIC | 旭日下钻可选分析指标(金额/本期收益额/累计收益额/累计收益率)。**后端零改动**:`/lens/query` 唯一端点 + PivotEngine 已算全部指标 → 旭日改 query `rows=[内] cols=[外] measures=[metric,value]`,用 `rowTotals` 拿内环父级**引擎级正确聚合**(比率父级 ≠ 子级和,不能前端求和)。两渲染模式:**可加金额类**(金额/收益额)弧长=该指标(收益额取\|值\|、绿赚赭亏);**不可加比率类**(收益率)弧长=市值、颜色=收益率热力(绿高→赭负,不假装能按比率分角)。中心圆显当前指标总计(grand[mi])+ 点击弹指标菜单换指标。切片排行跟随指标(排序/着色/条宽)。引线标签跟指标(`_lbl`)。**持仓级降级**:行业/地域下 latestPnl/cumReturn 灰置(用 dims.holdingLevel)+ 红提示 + refresh 自动回退 cumPnl(持有口径);承 PivotEngine 诚实降级不假分摊。币种不变(value/pnl 缩放·share/cumReturn 无关)· 隐私(金额/收益额 data-priv、比率不糊)· 下钻不变。真机 e2e:切4指标旭日/中心/排行随变、行业看板灰置正确+回退累计收益额、中心圆弹层、桌面+移动。守护 v13-SUN-METRIC |
| v13-SUN-METRIC-2 | 旭日多指标 review 六改(2026-07-22):**①指标选择器移到「看板」上方**独立卡(页面级)· **②环色恒用配色方案**(colorMapFor,不再按收益绿赭覆盖环色 follow 管理页设置;指标靠 弧长+标签+中心+排行 表达)· **③加「重置」按钮**(resetLens 回当前看板初始:清 drill/dimStack、复位 sunMetric=value)· **④引导线重写**(贪心自上而下防重叠+越底整体上移+超容纳落补注+引线贴外环外缘,替代旧质心散开)· **⑤指标从 4→6**:LensRegistry 加 `netPrincipal`(净投入·amount)+ `latestReturn`(本期收益率·ratio,分母=期初市值=currentValue−momAmount,Position 加 acctOpenValue,PivotEngine 通用 ratio(num,den) 聚合);持仓级降级集扩为 netPrincipal/latestPnl/latestReturn/cumReturn · **⑥AI 洞察加收益信号**(LensInsightService 固定按 value+latestPnl+cumPnl+cumReturn 聚合当前维度:本期最大拖累/本期最赚/累计收益率最低;prompt「异常信号」→「信号(结构异常+收益亮点/拖累)」)。423 单测(+PivotEngineTest v13NewMeasures:净投入18万·本期收益率2.56%)。真机验:6指标切换、金额=莫兰迪调色板、重置回初始、AI 输出「股票股权本期最大拖累」 |
| v13-1-NAVVER(v1.3.1) | nav logo 下版本徽记(2026-07-22 · 小更新)。**单一来源** `application.yml` `app.version`(env `APP_VERSION` 可覆盖)→ `GlobalModelAdvice` `@Value` 注入全局 model 属性 `appVersion`(与既有 `buildVersion` 同机制,`buildVersion` 是构建时间戳做缓存失效、`appVersion` 是发布语义版本)→ `fragments/nav.html` 品牌 `<a>` 改双行竖排:上行 名称+`№ 印`,下行 `◇ v1.3.1`(brass-deep mono·菱形徽记·账册「版次」味,`th:if=${appVersion != null}` 防空)。header `h-16` 双行不溢出。**发布一致性**:release-prod 预检加硬门(0.57)`app.version` 必须 == 发布 tag(`vX.Y.Z` 去 v),不一致 `die`,防 prod 显示旧版本号。双端真机验:PC/移动 logo 下均显 `◇ v1.3.1`、导航项对齐不坏。守护 v13-1-NAVVER(断言 app.version/appVersion 注入/nav 渲染三件) |
| v14-HOLDING-IMPORT | 持仓截图智能解析(2026-07-23 · 大迭代)。填报页选中基金/理财/券商账户 → 「AI 截图导入」→ 上传(前端 canvas 压缩长边2000/JPEG0.82 · 多图 · 成本预估 图数×模型单价)→ `QwenVisionClient`(qwen-vl-max · 复用 Qwen key/DashScope 端点 · **只转写不算数**:市值只做去逗号/币符格式规整,截断行 `confidence:low` 不编造)→ 合并去重(代码优先否则归一化名称)→ 白名单打标复用 `LensAiTagService` → **三态比对**(与该账户 `sync_source=SCREENSHOT` 活持仓:UPDATE 匹配更新/NEW 新增/SOLD 卖出默认 KEEP 用户定夺;手填/券商持仓永不碰)→ 左旧右新可编辑确认 → `confirm` 事务 增改归档 → `AccountValuationService.refreshOneAccount(IMPORT)` 写回当期快照 + 记 `stock_valuation_event(trigger=IMPORT, ref_import_id)`(**钱赚估值调整非人赚 cash_flow**,储蓄率口径不乱)→ ledger「△估值变动」加「看明细」链到 detail(逐项+原图)。状态机 `holding_import`(UPLOADING→SCANNING→REVIEW→CONFIRMED,断点续看)· 原图压缩持久化 `uploadRoot/family-{id}/holdingshots/`(随库备份)· `supportsHoldings` 放开 WEALTH/CASH(**红线:无持仓不接管手填余额**)· `LensQueryService` 持仓级 assetClass 回落 → 旭日/透视按真实基金行业细分。**真机 e2e**:2 张支付宝→13 支(市值全对/货币现金·固收·科技·宽基·混合打标准/截断行标疑/跳汇总行),1 张招行→5 支(跳「多宝理财」汇总行);确认落库 12 持仓+估值事件+快照写回;透视 28 头寸按行业拆开;双端 UED(上传/比对/详情)。守护 v14-HOLDING-IMPORT + HoldingImportUnitTest(视觉解析/市值规整/归一化键) |
| v142-ENTRY-IMPORT-FIX | v1.4.2 五点打磨/修复(2026-07-23):**①流水删除 ✕ 失效修复**——`renderLedgerHtml` 删除按钮 `hx-target` 原指向不存在的 `#row-{id}`(实际块 id 为 `#entry-block-{id}`)→ HTMX targetError,请求根本没发出,点 ✕ 无反应;改为 `#entry-block-{id}`(删除端点返回的正是该 block)。**②转账二次确认具名双账户**——转账类流水删除的 `hx-confirm` 点明「同时影响两个账户」并按方向给出本账户 ± / 对方 ∓ 的反向冲销。**③导入确认回来源页**——`/entry/import/{acc}` 带 `from`(消毒 `safeLocalPath` 只放站内相对路径防开放重定向),`confirm`/`abandon` 重定向回来源(默认 `/entry`),不再落到单账户过滤视图。**④划转目标下拉带主理人**——`EntryController.addAccountOwnerMeta`(`memberNameById`/`memberColorById`,entry+换行块都注入)+ `_row.html` option `data-owner/data-oc` + 文案「账户名 · 主理人」+ `searchable-select.js` 渲染头像色圆(两账户重名不再选错)。**⑤手机端填报 AI 徽记**——`nav.html` 移动菜单「填报」补 AI 徽记(双端一致)。**⑥导入图片查看/放大/删除**——`HoldingImportService.imageRels/deleteImage`(文件序号「现存最大+1」防删中间张后覆盖)+ `POST /entry/import/{id}/image/delete` + `import.html` 上传态/识别失败态渲染服务端缩略图画廊(灯箱:滚轮/双指缩放+拖动平移+下拉/✕ 关)+ ✕ 删错图 + 失败态「删错图后重新识别」。守护 v142-ENTRY-IMPORT-FIX |
| v142-LENS-RESET | 旭日「重置」按钮语义修正(2026-07-23 · 用户反馈很奇怪)。此前 `resetLens` 只回**当前看板**初始态(切了别的看板再点回不到刷新初始态)。改为**完全回到页面刷新后的初始状态**:`state.sunMetric='value'` + `state.measures=['value','share']`(透视指标复位默认)+ 收起明细抽屉 `drawerWrap` + `applyBoard(PRESETS[0])`(默认看板「资产类型」→ 清 drill/dimStack/pivot + syncSelectors + refresh,refresh 内 syncInsightCard 管 AI 卡显隐)。按钮 title 同步为「回到初始视图(默认看板「资产类型」· 清空下钻与指标 · 等同刷新后)」。宿主页 dashboard/index.html + lens/index.html 共用 `lens/_section.html`。守护 v142-LENS-RESET(断言 resetLens 用 PRESETS[0] + 复位 measures) |
| v143-LENS-TOC-UX | 三点 UX 打磨(2026-07-23 · 用户反馈)。**①隐私浮标不遮 TOC**——移动端目录 sheet 打开时,左下角隐私眼睛浮标 `#priv-float`(z55,bottom:18px)会压住 sheet 底部目录项;`toc.js` openSheet/closeSheet 给 `body` 加/去 `toc-open` 类,CSS `body.toc-open #priv-float, body.toc-open .toc-fab { display:none }` 打开时收起浮钮。**②旭日「分析指标 / 看板」横滑提示**——两行 `overflow-x-auto` 在手机上被截断但无"还能滑"暗示;包 `.lens-hscroll` + CSS 右缘渐隐 + `›` 箭头,`lens.js` `markHScroll`(scrollWidth−clientWidth−scrollLeft>4 → toggle `.more`)在 renderSunMetricBar/renderBoards 后 rAF 调用 + scroll/resize 监听,滑到底自动隐藏。**③重置 / AI 解读按钮独立成行**——此前与面包屑挤一行手机易乱换行;拆为面包屑一行 + 按钮 `justify-end` 独立一行(桌面/移动一致)。宿主 dashboard/lens/reports/checkup 共用件。守护 v143-LENS-TOC-UX |
| v15-PENETRATION | 基金持仓穿透(2026-07-24 · v1.5)。**实体加「持仓方向」层** `账户→持仓→持仓方向`(`holding_allocation`:weight_bp/asset_class/industry/kind/source)· 无穿透持仓不建方向行,lens 回落隐式 100% 单标签 → 老数据零迁移(V51)。**东财客户端** `EastMoneyFundClient`(境内直连):①名↔码 `fundcode_search`(剥份额后缀/公司别名;A/C 同底层)②资产配置股/债/现金 `pingzhongdata.Data_assetAllocation`③前十大 `fundf10 FundArchivesDatas`(2026 版 `unify/r/{mkt}.{code}`+`<td class='tor'>N%`)④个股→东财细行业 `push2 f127`(白酒Ⅱ/白色家电)→关键词映射 `IndustryTag`。**IndustryTag 扩容**对齐申万一级(+29 家电/食品饮料/电子/电力设备…,旧粗值保留兼容,下拉/颜色/AI白名单自动跟进)。**穿透计算**(工程算·LLM不碰):前十大→申万+其他持仓残差+债/现金,归一化万分比→全局共享缓存 `fund_penetration_cache`(只按公开代码·金额不入表)→物化 `holding_allocation`(保留 MANUAL,重拉不覆盖)。**lens 融合** `assemble` 每持仓有方向按 weight_bp 拆多 Position(下游零改)→旭日「行业」出真实分布。**打标页** `/lens/tags` 加方向明细行+「拉取穿透」+「全家一键穿透」(异步);理财无代码→UNPENETRATED 诚实降级。**导入确认**落库后台自动穿透。真机 e2e(prod 真实持仓):兴全合润→半导体26.5%/电子11.1%/化工/通信/医药/其他35%/债5%/现金4.6% · 中欧价值智选→食品饮料21%/汽车19.4% · 债基99.9%债 · 世纪双周盈(理财)UNPENETRATED;lens 旭日按方向出真实行业。守护 v15-PENETRATION |
| v151-PEN-STREAM | 穿透交互升级 + 行业集中过滤修正(2026-07-24 · v1.5.1 · 用户反馈)。**①全家一键穿透改 SSE 流式逐支揭示**:此前 `@Async` fire-and-forget 用户看不到进度(prod 21 账户/38 持仓/~18 支公募可穿透·首次每支 2-15s·同步阻塞要 1-3 分钟)→ 改 `SseEmitter` 端点 `/lens/tags/penetrate-stream` 逐支穿透即推 `fund` 事件(name/code/state/dirs)+ `start`/`done`;前端弹层持续 loading + 进度条 + 逐支揭示真实行业方向(基金A→半导体26%…、基金B→…)+ 完成态「11/28 已穿透·去透视」· DOM 构建无 innerHTML · 只穿 MANUAL 基金持仓(个股/现金不进)· 完成 publish LensStaleEvent。**②旭日「行业集中」去掉硬编码 `assetClass=股票股权` 过滤**:穿透前非股无行业才过滤,穿透后基金的债/现金部分也有行业维值(固收债/货币),过滤会漏 → `PRESETS` 行业集中 `filters:{}`。**③README 致谢补公开数据来源**(新浪/腾讯行情·Binance/CoinGecko/Coinbase加密·上海黄金交易所贵金属·东财天天基金基金穿透·Frankfurter汇率·统计局CPI/人行M2)+ 移动图恢复 PWA 去真实收益率。守护 v151-PEN-STREAM |
| v152-PIVOT-CARTESIAN | 交叉表多指标参与笛卡尔(2026-07-24 · v1.5.2 · item9)。此前多选指标是把几个数字叠在同一格,不清晰 → 改为**指标作为一个维度参与笛卡尔**,每格只显示一个指标值。`lens.js` `state.measurePos`(`col`/`row`,默认 `col`=放列最后一级);`renderPivot` 三分支:单指标退化(`m0` 一值/格,不参与笛卡尔)、多指标 `mpos=col`(colKeys × measures 两级表头 `colspan=指标数`,如 成员共同/王二狗 × 金额/占比)、多指标 `mpos=row`(rowKeys × measures 首列 `rowspan=指标数`,资产类型 × 金额/占比 子行)。热力 `heatM` 按每指标各自量纲 `maxAbsByM`、隐私 `priv` 逐指标判;`renderMeasurePills` 加「指标放 列/行」拨片(`data-mp`)。双端截图验收:col 模式 成员×指标 两级列头单值/格;row 模式 资产类型×指标子行。守护 v152-PIVOT-CARTESIAN |
| v152-PIVOT-MOBILE-HINT | 交叉表移动端可读(2026-07-24 · v1.5.2 · item7/8)。方案「提示+竖屏重排」,**不做 iOS 假横屏**(`screen.orientation.lock` 在 iOS Safari/PWA 不支持,CSS-rotate hack 破坏点击/滚动)。`_section.html` `#pivotHint`(`md:hidden` 仅移动端 · 左右滑动查看/首列固定/横屏更清晰)可点 `×` 关闭,`lens.js` boot 检 `sessionStorage.pivotHintX` 本会话不再打扰;`.sticky-col` 首列 `position:sticky;left:0` 加 `box-shadow` 右缘阴影 → 横滑时明确右侧还有内容。守护 v152-PIVOT-MOBILE-HINT |
| v152-TPL-PLATFORM | 账户模板补平台默认 + 建户自动带出(2026-07-24 · v1.5.2 · item6)。打标「平台」维度原是账户级自由文本、建户后需手标/靠 AI 建议,常与真实账户不一致(用户疑问:平台指啥、和真实账户一致吗)。**V52** 给 `account_template` 加 nullable `platform` 列(附加列·prod 存量零影响),seed 平台明确的 7 个模板(招商/工商/建设/中国银行·支付宝·微信·蚂蚁财富);通用/因人而异的模板(信用卡通用·证券通用·理财·房产·贷款·加密·贵金属·保险·自定义)留 NULL 不臆测。`AccountTemplate.platform` + `AccountTemplateMapper` 两 SELECT 补列。**建户默认**仅在 `AccountController.create`:`platformTag` 空且有 `templateId` 时从模板带出(编辑页有显式输入框不覆盖);向导 `data-tpl-platform` + 「已选」提示告知将标记的平台;管理页只读加「平台默认」列 + 模板数动态计数(修历史写死「14 个」→ `${templates.size()}`)。守护 v152-TPL-PLATFORM |
| v16-UED-TRUST | 跨页口径统一 + 异常值兜底 + 已关账只读(2026-07-27 · v1.6 · review A2/A7/B2-1)。**①体检与仪表盘净资产差 119 万**:`FamilyDiagnoseService` 用裸 `findLatest`(含未来测试账期)而 dashboard `resolveAsOf` 是「优先 OPEN → 不晚于今天的最新期 → 兜底最新」,beta 存在 2029-09 未来期时两页 anchor 落到不同账期 → 同名指标不同值,用户无从分辨谁对。新增 `FamilyDiagnoseService.resolveAnchor()` 逐条同规则(**未抽公共 service**:reports 的 anchor 语义本就不同=只取已关账快照,强行统一会破坏其产品含义),并把 anchor 回传页面渲染「数据截至 YYYY 年 M 月 · 与仪表盘同期口径」。**②紧急储备 723.0 个月**(月均支出趋零导致除法爆炸,还与旁边「建议 ≥3 个月」并列):`FamilyDiagnose.EMERGENCY_OUTLIER_MONTHS=36` + `emergencyOutlier()`,超界显示「> 36 个月」+ 提示去填报补支出;兜底放**展示层**而非计算层(规则引擎与 AI prompt 仍需真实值);`DashboardController.emergencyLabel()` 复用同一常量防漂移。**③账期 CLOSED 仍铺满可编辑表单**:entry 顶部「本期已关账 · 只读」横幅(原因+三条去处)· `_row.html` CLOSED 时余额表单/快捷支出/划转/贷款预测均不渲染、表单位换「本期余额 · 已定稿」+锁图标 · 隐藏刷新持仓估值 · 「提交本期」改陈述态。守护 v16-UED-TRUST |
| v16-UED-MONEY | 金额千分位(2026-07-27 · v1.6 · review A3)。checkup 家庭页 6 处 + checkup 账户页 17 处 + accounts/detail 9 处共 32 处此前 `formatDecimal(x,1,0)` / `(x,1,2)` 无分组符,渲染成 `¥5399878`(7 位数读不出量级),而 dashboard/accounts/lens 都有千分位 —— 同一产品两套格式。统一为 `formatDecimal(x,1,'COMMA',N,'POINT')`;百分比处(带 `multiply(100)`)不动。守护 v16-UED-MONEY(反向断言:三个模板内不得再出现 `formatDecimal(...,1,N)` 无 COMMA 形式) |
| v16-UED-CONTRAST | 色彩对比度与 token 补全(2026-07-27 · v1.6 · visual-spec §1.2/§1.3 实测)。`--ink-subtle #A09486` 在 card 上仅 **2.90:1** / paper **2.59:1**(连大字号 3:1 都不到),而它承载全站**字号最小**的一层(时间/序号/口径/计数/eyebrow)→ 小字+低对比双重不可读;改 `#706657`(5.50/4.92 双底过 AA),原浅值降级 `--ink-faint` 只许画装饰。新增 `--brass-text #85642F`(5.31/4.75)——`--brass` 作小字仅 3.2:1、`--brass-deep` 在 paper 上 4.34:1 都不过 AA。`--rule` 微加深 `#BCAE96`。补 `.pill-mute` 死类名定义(3 处模板在用、0 处定义)· 补 `prefers-reduced-motion` 全局块(此前仅 landing 尊重)· `.grid-hairline` 替代全站 17 处 `gap-px bg-rule`(旧写法靠容器底色从缝隙透出画线,格子不满行时空位露出米灰色块)。守护 v16-UED-CONTRAST |
| v16-UED-MOBILE | 移动端首屏与大组件形态(2026-07-27 · v1.6 · review A4/B1/B2/B4)。四个分析页 844pt 首屏此前 0 数据(全是标题/说明/控件),填报页整页 **11,711px ≈ 13.9 屏**。**①口径控件折叠** `.filter-fold`(dashboard/reports):`<details open>` + 窄屏 JS 去 `open`,PC 由 CSS 隐藏 summary 且恒展开 → 逐像素零回归;摘要显示 `18 个账户 · CNY · 1Y`;HTMX 换入区块重新收起(改完口径直接看结果)。**②L1 结论层**(仅移动端):净资产+较上期+环比+储蓄率+未填提醒一句话。**③填报行折叠** `.entry-fold`:13.9 屏 → 6.6 屏;HTMX 换入的行**不**收起(那是用户正在操作的行)。**④KPI 英雄数字+横滑** `.kpi-band`:主 KPI 88% 宽(露右侧一角暗示可滑)+字号升档,其余 62% scroll-snap;dashboard 与 checkup 共用(checkup 顺带解决 5÷2 空格露底)。**⑤汇总带横滑** `.summary-band`(9 类型曾占满首屏 ~950px)+ 金额升为主角(此前计数 20px > 金额 12px,层级反了)。**⑥环图 >6 类换横向条形**(小扇片标签重叠、引导线堆叠;≤6 类仍环图)。**⑦双向柱 TopN**(21 账户全画进去长尾条仅几像素、图高 648px → 窄屏 Top7/PC Top10 +「其他 N 个」)。**坑**:`.kpi-band{display:flex}` 首次部署完全失效 —— Tailwind Play CDN 在 style.css **之后**注入,`.grid{display:grid}` 同为单类选择器后者胜,须 `!important`(仓库既有手法见 text-[Npx] 档位)。守护 v16-UED-MOBILE |
| v16-UED-IOS | iOS 硬约束(2026-07-27 · v1.6 · review A9 · 主力用户为 iOS)。**①`overscroll-behavior-x: contain`**(全站此前 0 处):横滑区(指标 pill/交叉表/汇总带/KPI 带)滑到尽头继续拖会触发 **Safari 返回手势直接离开页面**,用户几乎无法自行归因。**②`scroll-snap`**(此前 0 处):横滑停在半个 pill 位置(lens 首屏可见「本期收…」被截断)。**③`env(safe-area-inset-bottom)`**(此前仅目录 sheet 1 处):隐私眼 `bottom:18px` / 目录 FAB 未适配,带 home indicator 机型上贴近系统手势区。**④图表容器 `-webkit-touch-callout:none`**:长按图表弹系统「拷贝图片」菜单。另:PC ≥1024px 目录 sheet 改 `display:none`(此前仅 transform 移出视口仍在合成层)。**未验证**:真机 Safari 上返回手势拦截与安全区实际表现需 iPhone 确认。守护 v16-UED-IOS |
| v16-UED-COPY | 去技术化文案 + emoji 清零(2026-07-27 · v1.6 · review A6/A10/B8)。管理页 15 个入口的小标题此前是 **URL 路径**(`/ADMIN/FAMILY` 等)—— 把内部技术标识暴露给非技术家庭成员,且 15 卡平铺无分组;改中文功能分类并按**家庭基础/口径与标签/日常运营/系统**四组重排。状态英文中文化:AI 诊断 `OK/WARN/RISK`→`正常/注意/风险`、`cached`→`已缓存`、账期 `OPEN/CLOSED`→`进行中/已关账`、备份 `✓ SUCCESS`→`成功`。移除 `Spring Boot 3.3` / `PRD` 内部术语;`环比(MoM)`/`同比(YoY)` 去英文缩写;账户类型枚举 code 移动端隐藏(汇总带 + 筛选 pill,PC 保留便于对照);基准对比 `跑输 -2.61pp`→`落后基准 -2.61 个百分点` 且去边框(spec §1.4「有框=要你做决定」,而它只是数据事实)。**emoji 清零 17 处**(🚀🗄💰✨⚠✓ℹ🎯📦💡→ inline SVG 或纯文字),含 `_ai-diagnose` 的 `d.icon()` 与 `goals/new-retirement` JS 里的 `'✨ '`。守护 v16-UED-COPY(含 templates 目录 emoji 反向断言) |
| v16-UED-AFFORD | 假 affordance 与操作收纳(2026-07-27 · v1.6 · review B3-3/B3-5/B7-1/B7-2/B3-4/B1-7)。**①去掉 `☰`**:账户表序号列渲染 `001 ·☰`,`☰` 在几乎所有 UI 语境里=拖拽手柄,但全站无任何拖拽实现(`grep draggable|Sortable|dragstart` 无结果),排序只能去编辑页填数字 —— 明确误导。**②行内操作 7→2+⋯**:划转/流水档案/体检/账本/编辑/券商/归档 七个等权 `btn-ghost` 占表格约 30% 宽;高频两个留行内,其余进 `.row-more` 下拉(不可逆的归档用 rust 语义色)。**③主理人列 nowrap**(此前「王二狗」被挤成三行竖排)。**④目标页**空状态隐藏重复的第二个 `btn-ink`(主操作唯一性)+ 4 入口改等宽 2×2 + 卡片 `max-width:640px` 居中(此前撑满 1324px 而内容仅 540px)+ FIRE 补中文。**⑤lens 三区块**(旭日/排行/交叉表)加 `.lens-skeleton` 计算中占位 —— 此前 JS 填充前是三个大空白框,用户无法分辨是坏了还是在算。守护 v16-UED-AFFORD |
| v161-LANDSCAPE | 自建横竖屏切换 + v1.6.0 三处回调(2026-07-27 · v1.6.1 · 用户 review 反馈)。**⑤自建横屏**:此前判断「iOS 不支持 `screen.orientation.lock` → 只能提示用户自己转手机」是错的 —— 游戏/漫画类 PWA 早在用 CSS `transform:rotate(90deg)` + 视口宽高互换自己转。新增通用声明式能力 `static/js/landscape.js`:`<button data-landscape="#pivot">` 即可获得横屏查看;交叉透视表右上加「横屏看」(仅 <768px 出现)。三个关键点:①**旋转只在设备竖着时加** —— `innerHeight>=innerWidth` 判方向 + 监听 `orientationchange`(iOS 转屏尺寸要一拍才稳,setTimeout 220ms)/`resize` 动态摘挂 `.rot-rotate`,漏了会「用户转了手机画面又被转回竖的」;②用 `100dvh` 非 `100vh`(iOS 地址栏收放会改 vh,旋转后画面被裁),留 `@supports not` 回退;③**DOM 移入而非克隆** + 占位符回填 —— 克隆会让 lens.js 持有的容器引用失效并产生重复 id。三条退出路径(按钮/Esc/浏览器返回 pushState 拦一层)防用户困住;顶部提示「把手机转横过来看 · 转好后画面自动扶正」。**①一句话字号** 15px→13px(用户:像老年机)。**②折叠可发现性**:加「点开筛选账户 / 币种」CTA + 箭头改**收起 ›(右)/ 展开 ⌄(下)**(此前 ⌄/⌃ 看不出能点开)+ 收起态铜边浅底。**③KPI 退回网格**:v1.6.0 改的横滑是过度设计 —— 核心指标要「一目了然」,横滑等于把指标藏到屏幕外;改主指标跨两列+大字、其余 2×2 收紧,5 个同屏可见(带高 358px→273px);顺带覆盖模板里第 5 个 KPI 的 `col-span-2`(老布局残留,否则第 4 个旁边空一格)。守护 v161-LANDSCAPE |
| v162-SUNBURST-LABEL | 旭日标签无盲区(2026-07-27 · v1.6.2 · 用户反馈 P0 bug)。v1.6.1 为解决「窄屏标签被 ECharts 沿弧旋转成竖排中文」把环内标签阈值提到 40°(占比 11%),但图下「小块补注」阈值仍是 14°(3.9%)—— **3.9%~11% 的块两边都不收**,真实数据里这个区间的块最多,一眼看去大半个环是无标识色块,组件几乎不可用。**且在 PC 上看不出来**(PC 有引导线兜底、盲区不存在),只在窄屏复现。修法:抽 `SUN_LABEL_MIN_DEG = 32` 单一常量,三处引用(`sliceLabel` / ECharts `label.minAngle` / `renderLeaders` 的 `MIN_DEG`)—— 注意 **ECharts 的 minAngle 会在 formatter 之前过滤块**,只改 formatter 无效。同时取消「只给占比不给名字」的中间档:用户看到 `10.3%` 却不知道是什么资产,与空白差别不大 —— 非黑即白,≥32° 环内给「名称+占比」两行,<32° 整块进补注。实测 17 块 → 环内 9 块有名有数 + 补注 7 块有名有数,无一遗漏。守护含反向断言 `! grep -qE "deg >= (40|50|60)"` 防回退硬编码。守护 v162-SUNBURST-LABEL |
| v162-LANDSCAPE-GLOBAL | 横屏升为全局顶级功能 + 与系统横屏和平共处(2026-07-27 · v1.6.2 · 用户反馈②③)。**②全局横屏**:此前横屏只是交叉表的局部能力,用户指出应与菜单/隐私同级。现 nav 加横屏钮(窄屏 · ☰ 左侧)+ 汉堡菜单内带文字入口(共 2 处);点击转 `<body>` 实现整页横屏 —— **转 body 而非 main**,因为 body 成为 transform 容器后其内 `position:fixed` 元素(nav 墨条/隐私浮钮/目录 FAB)改为相对 body 定位,正好跟着转;代价两处已处理:清掉 Tailwind `min-h-screen`(`min-height:0 !important`,否则横屏下高度被撑回竖屏高)+ body 变 fixed 后滚动交给自身 `overflow-y:auto` 并配 `overscroll-behavior:contain`。状态存 sessionStorage + head 内 FOUC 防闪恢复;`landscape.js` 从 lens fragment 提为 layout 全站加载(nav 入口要在所有页面可用)。**③屏蔽系统横屏 = 平台做不到**:`screen.orientation.lock()` iOS Safari/PWA 均不支持;manifest `"orientation":"portrait"` 我们早就设了(ManifestController:52)但 **iOS 不读该字段**。改为让位 —— 检测到设备已物理横屏就**整体退出**旋转层(v1.6.1 是只撤 rotate 但留着层,导致「系统重排 + 我们撤旋转」跳两下),用户只经历系统那一次不可避免的重排。守护 v162-LANDSCAPE-GLOBAL |
| v163-SUNBURST-AGG | 旭日「大量空块」根治 · 小块聚合(2026-07-27 · v1.6.3 · 用户三次反馈同一问题)。**三次才修对**:v1.6.1 环内阈值 40°/补注 14° → 3.9%~11% 两边不收(信息黑洞);v1.6.2 两阈值合一 32° → 窄屏无引导线,32° 以下全靠补注,「行业集中」59 块只有 6 块有标签;中途试过窄屏也开引导线 → 名称被容器裁成残字(「货币基金/存款 4.6%」→「⌐ 4.6%」)、补注列 22 项且出现 7 个重复「支付宝·蚂蚁财富」,比不标更糟。**v1.6.3 承认图型容量有限**:320px 环 × 59 维值 = 每块 6°,这是容量问题不是排版问题 → 按 Top N + Other 惯例,同层小于阈值(窄屏 18°≈5% / PC 4°)的块合并成「其他 N 项」(中性纸灰、不参与维值配色、`_agg:true` 禁下钻并提示去看排行)。实测行业集中内环 59→6 块、平台安全 27→12,每块有名有数无重叠。**连带两个隐蔽坑**:①窄屏判断不能用 `el.clientWidth`(依赖布局时机,同一元素读到 630/321 两值 → 聚合阈值悄悄走 PC 档、改参数不生效,一度误判为部署未生效)→ 改 `window.innerWidth < 768`;②聚合块缺 `children` 导致下游 `n.children.forEach` 抛 TypeError,异常落进 `Promise.all().catch()`,而 catch 只往 `#pivot` 写错误又被随后成功的 renderPivot 覆盖 → 页面完全无声(无 console 错误/无失败请求,只有 skeleton 转)→ 修为 catch 加 console.error + 在出错容器内就地报错 + 聚合块显式带 `children:[]` + 下游 `(n.children||[])` 双保险。守护含反向断言(无硬编码角度、无 `= el.clientWidth < 480`)。守护 v163-SUNBURST-AGG |
| v164-ORIENTATION | 屏幕方向锁定 · 不响应手机自身横竖屏(2026-07-27 · v1.6.4 · 用户反馈②③)。**②入口改右下浮钮**:方向切换不再放顶部导航,改为与隐私眼/目录钮同列的 `#ori-float`(仅 `max-width:767px and pointer:coarse` 出现),交叉表快捷入口改调同一开关(不再维护第二套局部旋转层)。**③双向锁定 = 屏蔽系统横屏**:前两版判断「iOS 拿不到 orientation.lock 所以做不到」是错的 —— 系统旋转阻止不了,但页面要不要跟着转在我们手里:设备竖屏+要横屏→`ori-rot90`;**设备横屏+要竖屏→`ori-rotm90`(反向转回竖直)**;方向一致则不加任何 class(零开销零回归)。桌面必须排除(恒「宽>高」,否则被永久反转)。**三个坑**:①`lockable()` 用 `innerWidth` 会自激振荡 —— body 旋转后浏览器重算 layout viewport(实测 390×844→807×1745),`maxSide>1200` 使 lockable 翻转 → 加/删 class 循环抖动 → 改用 `screen.width/height`(物理尺寸不受变换影响;方向判断仍可用 inner* 因污染是等比缩放、宽高比不变);②apply 内 `dispatchEvent(resize)` 而自身又监听 resize = 正反馈回路 → 改点名调 `financeCharts[*].resize()` 与 ECharts 实例 resize;③无条件重写 class 也会引起 layout 变化 → 加幂等闸门(目标与当前一致就 return)。保留 `window.__oriLog`(最近 12 次判断)—— 这类多次触发的逻辑靠猜排不出来。守护 v164-ORIENTATION |
| v164-CHART-PARITY | dashboard 两图窄屏形态一致(2026-07-27 · v1.6.4 · 用户反馈④)。「资产配置」与「按成员分布」此前一个环图一个条形,手机上并列看着不像一套东西。抽共用工厂 `hBarConfig(labels, values, total, title, palette)`,窄屏(<640px)两者一律横向条形(窄屏环图标签必然重叠);PC 保持「>6 类条形、≤6 类环图」规则。顺带消除了两处几乎重复的 bar 配置实现。守护断言两图的 `memFlat`/`flatAlloc` 均含窄屏判定且都走 hBarConfig。守护 v164-CHART-PARITY **v1.6.11 翻回环图(用户反馈:横向条形太不直观)**:v1.6.4 那个「窄屏环图标签必然重叠」的理由**站不住** —— 重叠的根因不是"环图不行",是**类目太多**,与旭日图 v1.6.3 完全同一个问题。直接搬那里验证过的解法 **Top N + 其他 N 项**(`aggSlices`,窄屏 minPct 4% / keepMax 6):环里只剩 ≤6 片,每片都标得下占比;窄屏图例从右侧改到**下方**(390px 宽里右侧图例会把环挤成一条缝),容器高度相应加高(资产配置 300→348、按成员 220→262);金额通过 `legend.labels.generateLabels` 落在图例上 —— 环内放占比、图例放金额,两者互补且都不重叠,满足「数字必须直接在图上、不能只靠 hover」。判断收成共用的 `useBar(labels)`,窄屏恒为 false → **窄屏两图必定同为环图**。**口径说明**:PC 上 `useBar` 仍看各自类目数,所以 PC 可能一个条一个环(资产配置 7 类 > 6 走条形、按成员 3 类走环图)—— 要不要在 PC 也统一成环图是产品取舍(聚合已让多类目环图可读),留给用户定,不在本守护范围。**实测**(手机 390×844):两图同为 doughnut · 资产配置 5 片(4 类 + 其他 3 项)图例在下方带金额 · 按成员 3 片全部标到 · 控制台无错误;PC 1440×900 资产配置仍条形、按成员环图、无错误。踩坑:验证脚本不能读 `chart.options.plugins.datalabels.formatter` —— 那是 Chart.js 的已解析代理,读 scriptable 项会被代理立即调用并抛 `Cannot convert object to primitive value`,要读 `chart.config.options`。 |
| v165-LANDSCAPE-LOCAL | 横屏查看只转宽内容 · 整页旋转方案作废(2026-07-27 · v1.6.5 · 用户真机反馈)。整页旋转做了三版(v1.6.2 全局 class / v1.6.3 修自激振荡 / v1.6.4 双向锁定),真机报三条:①横屏后顶部菜单莫名展开 ②三个浮钮消失或不稳定 ③切横屏后转手机仍有大幅转动。**同一个结构性根因:CSS 媒体查询只认 viewport,不认 transform**。设备横屏时 viewport=844px → 我为 v1.6 加的 7 处 `max-width:767/640` 移动端样式全失效(KPI 带/汇总带回网格、折叠条 summary 显形、**浮钮 display:none** = 反馈②)+ 模板里 347 处 Tailwind `sm:`/`md:` 切宽屏分支 → 布局跳 PC 版(= 反馈③的"大幅转动",是重排不是动画);后者为媒体查询编译产物**无法用 class 覆盖**,要绕过只能全站改 container query 或 iframe 隔离。**故方案作废**,改为只旋转 `[data-landscape-target]` 声明的元素(当前:交叉透视表),body 永不旋转 —— 被转容器内部不依赖断点,三条副作用一条都不发生;无目标的页面浮钮自动隐藏。浮钮与 `.landscape-btn` 的可见性改为只由 `(pointer: coarse)` + JS 判断决定,**不得带 max-width**(那正是②的直接原因,守护含此反向断言)。「屏蔽手机自身横竖屏」如实认定做不到:`orientation.lock()` iOS 不支持、manifest orientation 字段 iOS 不读、CSS 旋转的代价是 354 处响应式错位。附带教训:三个版本都只在桌面 Chromium 模拟移动端验证,而模拟器的 layout viewport 重算行为与真机不同 —— 涉及 viewport/方向/安全区的改动必须真机验证。守护 v165-LANDSCAPE-LOCAL |
| v166-LANDSCAPE-IFRAME | 整页横屏 · iframe 隔离(2026-07-27 · v1.6.6 · 方案由用户在三条路中拍板选 A)。**前四版都失败在同一处**:v1.6.2/1.6.3/1.6.4 直接 transform `<body>`,真机三副作用(顶部菜单展开/浮钮消失/转手机大幅重排),结构性根因是 **CSS 媒体查询只认 viewport 不认 transform** —— 设备横屏时 viewport=844px,模板 453 处 Tailwind `sm:`/`md:` + 自有 13 处 `@media` 全部切宽屏分支,而前者是媒体查询编译产物无法用 class 覆盖。(需澄清:「屏蔽系统旋转事件」本身能做到——不监听即可;但屏蔽事件≠屏蔽效果,viewport 真实变化后 CSS 的响应属渲染管线,无可移除的监听器。)**v1.6.5 我曾未经用户同意把需求改成局部横屏并部署,是流程错误 —— 遇冲突应摆出代价由用户定。****方案 A 实现**:把页面装进固定尺寸同源 iframe 只转 iframe:iframe 有独立 viewport → 453 处响应式一行不改就正确;尺寸进入时定死(长边×短边 844×390)此后不随设备方向变 → 转手机时内部 viewport 恒定、**零重排**;内部 viewport=844 使 `md:` 生效 → 宽表格真正铺开;设备已横屏则外层不转直接铺满,竖屏则转 90°;同源共享 cookie/sessionStorage(登录态/隐私/字号沿用);`window.self !== window.top` 嵌套自检隐藏入口防套娃 + 跳过印章动画;退出时读 `contentWindow.location` 同步父页导航。实测 390×844→844×390 切换:iframe viewport 恒 844×390、`body.transform` 恒 none。前置事实:Tailwind 为 Play CDN 运行时编译(3.4.1),`@container`/`container-type` 包内均 0 处,故方案 B(container query)必须先引构建体系。附带修 `v02-CHART` 守护回归(抽工厂后按调用点计数对不上)—— 未削弱判据,改为把 `ChartDataLabels` 注册提到各调用点。守护 v166-LANDSCAPE-IFRAME |
| v167-VP-SHORTSIDE | 响应式判据必须同时看短边(2026-07-28 · v1.6.7 · 用户第 5 次反馈同一件事「还是不对劲,依然随着手机横屏转动了」)。**前四版找错了层**:v1.6.2/1.6.3/1.6.4 整页 body 旋转、v1.6.6 iframe 隔离,全都在跟「方向」较劲 —— 方向不是问题。**真 bug**:手机横屏是 844×390,453 处 Tailwind 断点全都只判宽度 → 844 被当成宽屏设备 → `sm:`/`md:`/`lg:` 集体切宽屏分支。**手机横屏的特征是短边只有 390,不是宽边有 844**;iPhone 横屏 `innerHeight` ≤ 440(16 Pro Max 短边 440),PC 窗口极少矮于 480 → 阈值 480。**三处判据必须同源**(少一处即「CSS 是移动的、JS 却是 PC 的」半修状态):① `tailwind.config` 的 `theme.screens`(一处覆盖 453 个断点)② `style.css` 自有 `@media` 10 处 ③ `window.vpNarrow()` 收掉图表脚本里 **22 处**硬编码宽度比较(与「加枚举值要扫模板串条件」同形状:一个阈值被复制 22 份,编译器一个都抓不到 → 守护钉「不得再出现 window.innerWidth<数字」网住整类)。**例外**:横屏 iframe 也是 844×390 但是主动要的宽屏视图 → `window.self !== window.top` 自检加 `is-embedded` 走只看宽度那套,且该 class 必须内联在 `<head>` 早于样式表(它是 CSS 选择器的一部分,晚了会闪一帧移动布局);只给真正改变观感的规则加 `html:not(.is-embedded)`(kpi-band 宫格 / summary-band 横滑 / filter-fold 把手)。**场景 2 旋转遮帘**:iOS 那 0.4s 旋转动画抹不掉 —— 已查证三条死路:`manifest.orientation` iOS 不支持(MDN browser-compat-data:safari false)、`screen.orientation.lock()` 需 fullscreen 而 iPhone Safari 无元素级 fullscreen、`orientationchange` non-cancelable。末态本就正确,坏的只是过程 → 旋转期间盖成纯色纸面(纯色转动看不出转动),且**必须瞬盖**(`transition:none`)否则旋转已开始而内容还在淡出就露了,揭开才淡入 .18s。**实测**:844×390 与 390×844 的 md: 状态/KPI 列数/折叠把手可见性/全部 Chart.js 图形态逐项一致;PC 1440×900 与 iPad 820×1180 不受影响;iframe 内 md: 仍 ON。**已知副作用**:PC 窗口高 <480px 退成移动布局(实测 1440×460),取舍见 tech-design §17.1。**残留**(用户选的分两步之第二步待定):内容仍会横向拉宽到 844。顺带发现 `manifest.webmanifest` 是 0 字节空文件(Android PWA 安装坏,本版未修)。守护 v167-VP-SHORTSIDE |
| v168-ORI-CSS | 方向控制的尺寸必须由 CSS 视口单位决定,不许 JS 量(2026-07-28 · v1.6.8 · 用户要求先调研行业做法)。**调研**:GitHub 代码搜索同类写法 4192 处命中,逐个读源码 —— `QiShaoXuan/css_tricks` 横屏范文 `width:100vh;height:100vw;rotate(90deg)` + `@media (orientation:portrait)` 驱动;`MapoMagpie/comic-looms` 漫画阅读器(**有滚动**)`transform:rotate(90deg);width:100vh;height:100vw;transform-origin:0 0;left:100vw`;`TheCaveMembership/izzapay` 游戏插件 `BASE_W=960,BASE_H=540` 固定设计稿 + 整体缩放,内容全绝对定位、**零媒体查询**。**共同点:尺寸用交换后的视口单位,旋转由媒体查询驱动,JS 里一个监听都没有。****我们不行的 diff**:① v1.6.6 用 JS 进入时量 `innerWidth/innerHeight` 写死 px —— iOS 竖屏工具栏 ≠ 横屏工具栏,竖屏量到的短边在横屏不成立 → 旋转后尺寸错、位移;② 监听 `orientationchange` + 220ms toggle class —— 扳正永远晚于 iOS 旋转动画 → 先跟着转一圈再被扳回 = 两次运动;③ 媒体查询没防软键盘(SO 8883163 · 112★:`orientation` 定义是 `height>=width` 才算 portrait,竖屏弹键盘会翻成 landscape)→ 打字时页面会莫名变向,故横屏分支必须叠 `min-width:480px`、竖屏分支叠 `max-width:479px`;④ **单位辨析**:`vh` 是 large viewport(基于屏幕、恒定),`dvh` 才随工具栏收放变化 —— v1.6.1 注释把这条写反(「用 dvh 而非 vh 因为 vh 会变」),用 dvh 当固定尺寸 → 工具栏一收放就重排,这个误解一路带到 v1.6.6 改成 JS 量 px。**实现**:`.ls-stage` 默认 `100vw/100vh`,竖屏分支 `100vh/100vw + translate(100vw,0) rotate(90deg)`(绕左上角转 90° 后盒子落到 x 负侧,需右移一个盒子高度 = 100vw);`landscape.js` 不再计算任何尺寸、不再监听 resize,只留开关 + 遮帘(遮帘用 `matchMedia` 监听与 CSS 完全相同那条查询,揭帘时刻与样式切换严格同步)。**普通模式冻结**:手机横屏把 `body` 宽度锁回短边 `100vh` 并居中 → 排版与竖屏逐像素相同、无 transform → 无旋转方向歧义、不动滚动容器、不丢滚动位置、转过去仍可读;观感用 `--paper-deep` 底色 + `box-shadow: 0 0 0 1px`(**不能用 border,会把盒子撑宽 2px**)+ 浮钮 `right: calc((100vw - 100vh)/2 + 14px)` 内收。四条护栏:`pointer:coarse`(挡 PC 矮窗)/ `max-height:479px` / `min-width:480px`(挡软键盘)/ `:not(.ls-on):not(.is-embedded)`。**横屏模式去顶部导航**:844 宽放不下 7 tab(每项折两行)+ 390 高里导航占 64px 六分之一 + 用户 v1.6.4 已反馈不要横屏冒出 tab 菜单栏。**未选反向旋转钉设备坐标系**:旋转符号取决于用户顺/逆时针转手机(`window.orientation` 90/-90)判错即上下颠倒、body 变滚动容器丢滚动位置、内容躺倒不可读。**清债**:v1.6.1 `.rot-*` 54 行死代码删除(JS 已无引用)—— 它当年就用对了典范写法,只错一个单位,而我没追那个单位反而整体推翻成 JS 量 px,把一行的单位错误升级成架构错误。**实测**:横屏舞台 CSS 盒子两向都 844×390 且无 JS 内联尺寸;iframe 内 viewport 两向都 844×390;普通模式排版宽度竖屏 390/横屏 390 逐像素相同、卡片都 360、横屏左边距 227px 无 transform;PC 1440×460 body 仍 1440。守护 v168-ORI-CSS |
| v169-ORI-PIN | 普通模式把内容钉在设备坐标系(2026-07-28 · v1.6.9 · 用户在 A/B 里选定 B)+ 专用方向图标。**一条要记住的逻辑约束**:「内容在世界坐标系正立」与「内容在设备坐标系不动」在设备旋转时**互斥** —— 主动横屏模式的目的是转过手机读宽表格,必须选"世界正立",所以它**必然响应**设备旋转,只能用遮帘盖过程;而 iOS 那段旋转动画抹不掉(`manifest.orientation` iOS 不支持 / `screen.orientation.lock` 在 Safari 是 false,两者均 MDN BCD 实测 / `orientationchange` 不可 cancel),且 iOS 往往在旋转**之后**才派发该事件,遮帘可能盖不住开头一瞬。游戏看似没这问题的三种真实原因:① 横屏专用、用户本来就横着拿,过程中不发生旋转;② 多数"PWA 安装的游戏"其实是 Capacitor/Cordova 原生外壳,方向锁在 `UISupportedInterfaceOrientations`,网页拿不到;③ 用户自己开了系统旋转锁定。**给用户的实际办法**:开 iOS 控制中心的「竖屏方向锁定」,系统不再旋转,而我们的横屏钮是纯 CSS 变换不依赖设备方向,照样可用。**方案 B 实现**:`body` 盒子 = `100vh × 100vw` = 短边 × 长边 = 竖屏形状(视口单位在横屏自动交换),排版宽度仍是短边 → 实测竖屏/横屏 `body 390`、卡片 `360×92`、KPI `360` **逐项相同**;反向旋转抵消 OS 旋转。**两件 CSS 做不到必须 JS 兜的事**:① 旋转符号 —— CSS 知道"现在横屏"但**不知道用户顺时针还是逆时针转的**(两者 viewport 完全一样),`screen.orientation.angle` 定义为 viewport 相对自然方向**顺时针**转过的角度,抵消即转 `-angle`:angle 90(设备逆时针)→ -90°,angle 270(设备顺时针)→ +90°(挂 `html.ori-cw`);iOS 16.4+ 支持,更老回落 `window.orientation`;判错表现是内容上下颠倒。② 滚动位置交接 —— 冻结时滚动容器从 `html` 变 `body`,两者 `scrollTop` 是两套值,不接就跳回顶部(而"跳回顶部"正是用户最反感的"页面动了");元素级 `scroll` **不冒泡**,必须 `addEventListener('scroll', fn, true)` 捕获阶段收,恢复放双层 `requestAnimationFrame` 等布局落定。实测滚到 600 → 横屏 `body.scrollTop=600` → 转回竖屏 `documentElement.scrollTop=600`。**方案 B 的固有代价(实测踩到)**:`body` 被 transform 后内部 `position:fixed` 浮层的**包含块从视口变成 body 盒子**,那些按未旋转视口写的几何(`inset-x-0` / `bottom:0` / 收起态 `translateY(110%)`)会错位 —— **实测目录抽屉直接漏进屏内**(rect 291,0,281,390);冻结态藏掉目录抽屉/遮罩/目录钮/顶部进度条/toast,但**方向钮必须留**(用户可能横着拿再点开横屏视图)。**后续新增任何全屏浮层(模态框/抽屉/日期选择器)都要回来看这条。**另两条代价:滚动手势方向随内容一起转;`env(safe-area-inset-*)` 仍按真实视口解析不随旋转翻转。**图标**:原来是**摄像机**(`rect 2,6,14,12` + `M18 9l4-2v10l-4-2`),语义完全不对且全仓三处都在用(方向浮钮 / 交叉表「横屏看」/ 交叉表「手机看」提示),换成专用屏幕旋转图标 `rect 8.5,2,7,13` + `M19.5 11.5A8 8 0 0 1 11.5 19.5` + 沿弧切线箭头,守护钉住摄像机路径不得再出现;另加 `aria-pressed="true"` 时图标转 90° 的状态反馈。守护 v169-ORI-PIN |
| v1610-LS-NAV | 横屏模式必须有顶部导航,但按 844×390 自己的尺度重排(2026-07-28 · v1.6.10 · 用户要求把 v1.6.8 藏掉的导航加回来)。**我上一版做错的地方**:v1.6.8 把横屏导航整个 `display:none`,理由是「844 宽放不下、390 高太贵」——**藏掉能力不是解决排版问题的办法**,而且判断本身不准。**宽度账(实测)**:退出钮占 ~112px 必须留 → 可用 ~700px;7 个 tab 压到 10px 字号 + 13px 间距共 **284px**,加印章 21px ≈ 310px,很宽裕。v1.6.8 折行不是 tab 多,是 `gap-12`(48)+`gap-7`(28)+ 完整品牌文字 + 版本徽记 + 字号钮 + 隐私钮 + 账期 pill + 用户名**一起挤**的结果。**高度账(390 才是稀缺资源)**:导航栏高 64→**38px**;`main` 内边距 30/30→8/14;标题块 `mt-10` 37.5→8;**首张卡片从 221px(屏高 57%)提到 169px**。压的是间距与大标题字号,**不删任何内容**;`text-4xl`/`text-3xl` 降档但 **`text-2xl` 刻意不压**(74 处、大量用在金额上,压它违反 visual-spec 的「可读 > 可点 > 可懂」)。**隐私钮从浮层挪进导航条**:实测隐私浮钮在 390 高里会压住内容(压住交叉表「AI 解读当前视图」按钮),而导航条里 tab 结束在 335px、退出钮从 726px 开始,中间 **390px 全是空的** → 放回导航条既不压内容又更好找;目录钮在横屏无意义一并收起。**实现细节**:给 nav.html 加 6 个语义类名钩子(`nav-inner`/`nav-lead`/`nav-brandtext`/`nav-tabs`/`nav-actions`/`nav-priv`)而不是用结构选择器(后者与 DOM 顺序死绑、改结构会静默失效);垂直节奏直接覆盖 **Tailwind 类本身**(`html.is-embedded main .mt-10{...}`)而不是逐页加变体(`mt-8`×15/`mt-12`×4/`mt-10`×1 共 20 处,逐页改会漏且新页不继承);选中态下划线原本靠 `pb-[18px]` 撑到 `h-16` 底,栏高压扁后必须重算否则下划线跑到栏外;退出钮在舞台层、导航在 iframe 内,两者互相看不见只能靠约定 —— 退出钮压到 28px 高 + 导航容器 `padding-right:118px`,这两个数耦合,守护同时钉住。**实测**:dashboard 与 lens 两页均 7 tab 单行(top 差 3px 是选中态内边距不是折行)、栏高 39px、末 tab 右缘 335 < 732 未被退出钮压;手机竖屏与 PC 栏高仍 61px、品牌文字与右侧组都在,未受污染。守护 v1610-LS-NAV |
| v1612-CHART-UNIFORM | 并列同类图表必须共用同一套尺度(2026-07-28 · v1.6.12 · 用户要求写进规范与记忆)。用户原话:「都是饼图 那就保持大小样式一样,现在奇奇怪怪的,两个饼图差距很大,**这种常识问题,落到记忆和规范里面,不要我每次提出来,你自己要先自查**」。**同一件事他提了两次**:① v1.6.4 我按类目数分叉图型(>6 类走条形)→ 资产配置 7 类条形、按成员 3 类环图 → 一个条一个环;② v1.6.11 改成都是环图后,容器高度各写各的(348/262)+ 半径由「容器高 − 标题 − 图例」推导,而两图图例行数不同(5 项 3 行 / 3 项 1 行)→ **两个环一大一小**。根子是我只答"用户指出的那一点",没回头看这一屏的整体一致性。**修法**:两个 canvas 共用同一个 class `.chart-pair-box`(不再各写 `h-[]`,改一侧必然同时改另一侧)+ **半径写死**(窄屏 100 / PC 118 —— 容器同高还不够,图例行数不同会让直径不同)+ 尺度参数收进共享常量 `PAIR`(半径/cutout/标题字号/图例字号/占比字号/内距)+ **删掉 `useBar` 与 `hBarConfig`**(按数据量分叉图型必然产出"同类并列不一致";类目多的真正解法是聚合而非换图型)+ PC 也统一成环图(聚合已让多类目环图可读,v1.6.0 审计「>6 类环图不可读 → 条形」的前提不再成立)。**通用原则(写进 tech-design §22.1)**:能用"只有一处"消除的一致性问题,不要用"两处保持相等"去守护。**已落规范与记忆**:`docs/visual-spec.md` 新增「并列同类图表 / 并列同类元素」一节(规则 + 为什么容器同高还不够 + 两个反面案例 + 交付前四条自查清单),适用范围不限图表(并列 KPI 卡、并列按钮组同理);长期记忆 `feedback-sibling-uniform-selfcheck`。**踩坑第三次**:否定断言 `! grep -q "hBarConfig("` 被我自己讲解历史的注释扫红(注释含 `hBarConfig(窄屏…`);前两次是 `100dvh`(注释在辨析 vh/dvh)和 `window.innerWidth<`(注释举例)→ **否定断言一律盯代码构造**(`function X` / `Object.assign(X`),不要盯裸标识符。**实测**:手机 390×844 两图 doughnut、外/内半径 100/58 相同、容器高 336 相同、图例 bottom、字号 11/10/10 相同;PC 1440×900 两图 doughnut、118/68 相同、容器高 380 相同、图例 right、字号 13/12/11 相同;两端控制台无错误。守护 v1612-CHART-UNIFORM |
