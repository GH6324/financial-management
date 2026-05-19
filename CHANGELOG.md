# Changelog

按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格记录。每个版本详细需求见对应 [`prd/v0.X.md`](prd/),技术设计见 [`tech-design/v0.X.md`](tech-design/),QA case 见 [`docs/qa-cases.md`](docs/qa-cases.md)。

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
