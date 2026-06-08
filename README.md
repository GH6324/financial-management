# 家庭账房 · Family Ledger

> 自托管的家庭资产管理 Web 应用 · 每月 10 分钟、夫妻异步、自动算年化收益率

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-green)](https://spring.io/projects/spring-boot)

<p align="center">
  <img src="docs/screenshots/pc_dashboard.jpg" width="100%" alt="家庭账房 · 仪表盘">
  <br>
  <sub><b>仪表盘</b> · 全家净资产 / 趋势(叠加 CPI 购买力线 + M2 社会财富线)/ 资产配置 — 一屏总览</sub>
</p>

---

## 问题

家庭资产分散在 N 个渠道(银行 / 支付宝 / 券商 / 房产 / 房贷……),日常没系统记录:

- **看不到全局** —— "我们家现在共有多少钱?"
- **看不到趋势** —— "这一年是变富了还是变穷了?"
- **分不清来源** —— 工资攒下来的钱和投资赚的钱混在一起,无法评估投资能力
- **无法异步协作** —— 家庭成员时间错开,缺一个共同载体

市面方案:支付宝/雪球只看自家平台;国内通用记账 App 不区分本金和收益;Beancount / Firefly III 学习曲线陡;Excel 缺自动化(尤其 XIRR)。

## 设计取舍

**核心约束**:每月找一天、10 分钟以内、夫妻异步完成全部录入。

- **颗粒度**:仅到"账户月末快照 + 当月外部现金流",不到单券持仓
- **恒等式**:`本期投资损益 = 期末余额 − 期初余额 − 净外部流入`
- **不做**:个股持仓 / 定投提醒 / 预算包络 / 券商 API 直连 / 银行账单 OCR(都与"每月 10 分钟"冲突)

## 功能截图

### 桌面端

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/pc_dashboard.jpg" alt="仪表盘"><br><sub><b>仪表盘</b> · 净资产趋势(CPI 购买力线 + M2 社会财富线)+ 资产配置环形 + KPI 横条</sub></td>
    <td width="50%"><img src="docs/screenshots/pc_ai_analysis.jpg" alt="AI 综合诊断"><br><sub><b>AI 综合诊断</b> · 总评 + 配置 / 风险 / 流动性 / 收益 四维卡 + 优先行动(数字工程算,LLM 只解读)</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/pc_account.jpg" alt="账户簿"><br><sub><b>账户簿</b> · 6 类账户 · 按成员归集 · 划转 / 体检 / 账本 / 一键导出</sub></td>
    <td width="50%"><img src="docs/screenshots/pc_setting.jpg" alt="可运营管理页"><br><sub><b>可运营管理页</b> · 品牌 / 成员 / 周期 / 提醒 / 汇率 / 数据源 / 阈值 共 14 项 · 改即热生效不重启</sub></td>
  </tr>
</table>

### 移动端 · 响应式 + iOS PWA

<table>
  <tr>
    <td width="20%"><img src="docs/screenshots/mobilez_dashboard.jpg" alt="移动端仪表盘"></td>
    <td width="20%"><img src="docs/screenshots/mobile_account.jpg" alt="移动端填报"></td>
    <td width="20%"><img src="docs/screenshots/mobile_analysis.jpg" alt="移动端资产体检"></td>
    <td width="20%"><img src="docs/screenshots/mobile_ai_analysis.jpg" alt="移动端 AI 调仓"></td>
    <td width="20%"><img src="docs/screenshots/mobile_ios_deck.jpg" alt="iOS 主屏 PWA"></td>
  </tr>
  <tr>
    <td align="center"><sub>仪表盘 · 洞察速览</sub></td>
    <td align="center"><sub>每月填报</sub></td>
    <td align="center"><sub>资产体检</sub></td>
    <td align="center"><sub>AI 调仓建议</sub></td>
    <td align="center"><sub>装为 App</sub></td>
  </tr>
</table>

## 主要能力

- **每月 10 分钟全家完成** — 月度 / 周度周期可切 · 自动生成"填余额"待办 · 移动端响应式 + iOS PWA
- **6 种账户类型** — 现金 / 股票 / 理财 / 房产 / 负债 / 其他 · 13 个内置模板 · 16 类产品类目
- **真实收益率** — 账户级 XIRR + 家庭级 XIRR(含收入,资金加权)/ 资产年化 TWR(剔除收入,几何平均)· 区分"人赚的"vs"钱赚的"
- **多币种** — 本位币 CNY / USD / HKD · 自动拉汇率 · FX 链式经家庭 base 中转
- **股票自动估值** — 录 ticker + 数量 · 每日 T+1 拉价(新浪主 + 腾讯备 · 圆形熔断)· 美 / A / 港 三市场
- **财务目标** — 退休 FIRE(通胀 PV + 4% 提取率)/ 子女教育 / 应急储备 · 三情景预测(乐观 / 中性 / 悲观)
- **资产体检 + AI 诊断** — 4 维度结构化诊断(配置 / 风险 / 流动性 / 收益)· 智能建议规则引擎 · LLM 综合分析(Qwen-Plus 主 / DeepSeek 备)
- **AI 调仓建议** — 4 桶配置 diff(现金 / 投资 / 房产 / 保险)· LLM 给出具体调仓步骤("从 X 调 ¥N 到 Y")· 30 天复用 + 一键刷新
- **决策辅助** — CPI 对照线 / 账户级基准对照 / 提前还贷决策器(NPV 18 年视角)/ 应急金不闲置提示
- **财富水位**(v0.5)— 净资产 vs **CPI 购买力线**(还买得起同样的生活吗)+ **M2 社会财富线**(社会排位升还是降)· 真实收益 / 相对社会收益 · 人赚/钱赚分解诊断 · CPI/M2 三法均值(几何 / 剔极端 / 近10年)严格推导 · 1990-2025 历史底座 + 收支趋势图
- **股票账户现金联动**(v0.5)— 录股票可选「从账户现金划转买入」· 买入扣现金(FX 换算 · 现金可负)· 卖出/归档按市价对称加回
- **FIRE 目标支出自适应**(v0.5)— 退休目标月支出可选「自动适配月结支出」· 周期关闭按近 N 月真实支出滚动重算(剔极端/中位/均值)
- **填报规范化 + 截止前强提醒**(v0.4.14)— 3 种填报模板(实时收入·月末支出 / 月末一次清 / 每周滚动)· 截止前 N 天每天短信强提醒(阿里云)· 站内 banner 兜底 · 9 步阿里云接入指引
- **可运营的管理页**(v0.4.18)— LLM keys / 股票拉取开关+cron / FX cron / checkup 阈值 / 会话期 9 项配置全在 `/admin/integrations` + `/admin/calc-tweaks` 热改 · 实时生效不重启 · DB > env > 代码默认 三层 fallback
- **隐私与可移植** — 自托管 · 真名脱敏后再喂 LLM · 手机号/aksk/LLM-key 双重防回归(单测+静态扫)· CSV 一键导出全部数据 · Apache 2.0

详细变更记录见 [CHANGELOG.md](CHANGELOG.md)。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.3 + Java 21 + MyBatis 3 |
| 持久化 | MySQL 8(版本化 SQL 迁移 + sha256 校验,无 Flyway 依赖) |
| 前端 | Thymeleaf + HTMX 1.9 + Chart.js 4 + ECharts(无 SPA、无构建管线) |
| 认证 | Spring Security + bcrypt + Session Cookie |
| 部署 | Linux systemd + nginx 反代 :80 → :20000 · macOS launchd(可选)直连 :20000 |
| 测试 | JUnit 5 · 244 单元(含 PrivacyIsolationTest 静态扫源码私密红线)/ 36 端到端 / 338 黑盒 |

## 快速开始(自托管部署)

### 前置

- 一台公网 Linux 服务器(Ubuntu 22+ / Debian 12+ / RHEL 9+ / Alibaba Cloud Linux 都行)
- 你能 SSH 进去 + 有 sudo
- 一个 80 端口可达(可选 443)

### 部署

```bash
# 1. SSH 进服务器
ssh user@your-server

# 2. clone + 一键安装(脚本会装 JDK 21 / Maven / MySQL 8 / nginx 全套依赖)
sudo apt install -y git
git clone https://github.com/<your-org>/financial-management.git
cd financial-management
sudo bash deploy/deploy.sh

# 中途交互最多 2 个问题(DB 密码、HTTP 端口),其余全自动
```

完成后浏览器访问 `http://<server-ip>/`,默认账号:

| 用户名 | 密码 |
|---|---|
| `diwa`      | 你刚设的临时密码(默认 `demo1234`)|
| `wangergou` | 同上 |

**首次登录强制改密**,改完即可。然后:

1. `/admin/family` 改家庭名 + 选品牌图标
2. `/admin/members` 改成员显示名为你和家人真名
3. `/admin/periods` 点 "立即开下一周期" 起一个 OPEN 周期
4. `/accounts/new` 用向导加你的银行卡 / 支付宝 / 房贷
5. `/entry` 填本期余额开始记账

### 后续发版迭代

```bash
cd financial-management
git pull
sudo bash deploy/deploy.sh
```

同一个 `deploy.sh`,自动检测到已上线 → 切到迭代模式:mysqldump 备份 + 增量迁移 + 切 jar + restart + 健康检查 + 失败自动回滚。

### 回滚

```bash
sudo bash deploy/rollback.sh
```

### macOS 本地部署(开发 / 个人自用)

```bash
# 前提:已装 Homebrew
git clone https://github.com/<your-org>/financial-management.git
cd financial-management
bash deploy/deploy.sh   # 顶部 OS 探测 · macOS 自动转 deploy-macos.sh
```

跟 Linux 路径的差异:无 sudo · 用 brew 装依赖 · 文件全在 `$HOME/finance` · 启动用 `bash ~/finance/start.sh`(或 launchd 自启)· 没 nginx 反代,浏览器直接 `http://127.0.0.1:20000/`。详见 [`deploy/README.md` § macOS 本地部署](deploy/README.md#macos-本地部署)。

详细部署文档:[`deploy/README.md`](deploy/README.md)

## 本地开发

```bash
# 起 MySQL
sudo systemctl start mysql
sudo mysql <<'SQL'
CREATE DATABASE finance CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'finance'@'localhost' IDENTIFIED BY 'finance';
GRANT ALL ON finance.* TO 'finance'@'localhost';
FLUSH PRIVILEGES;
SQL

# 跑 schema 迁移
DB_USER=finance DB_PASS=finance DB_NAME=finance bash db/apply.sh

# 启动应用(dev profile · DevSeedRunner 把 PLACEHOLDER 密码设为 demo1234 的 bcrypt)
mvn spring-boot:run
```

打开 `http://localhost:8080/login`,默认账号见上。

测试:

```bash
mvn test                       # JUnit 单元测试(215 · v0.5)
bash scripts/qa-run.sh         # 黑盒 endpoint + 模板渲染(319)
bash scripts/qa-e2e.sh         # 端到端真值校验(36 · 会清空 DB)
```

## 文档

- **产品需求**:[`prd/v0.1.md`](prd/v0.1.md) · [`prd/v0.2.md`](prd/v0.2.md) · [`prd/v0.3.md`](prd/v0.3.md) · [`prd/v0.4.md`](prd/v0.4.md) · [`prd/v0.5.md`](prd/v0.5.md)
- **技术设计**:[`tech-design/v0.1.md`](tech-design/v0.1.md) · [`tech-design/v0.2.md`](tech-design/v0.2.md) · [`tech-design/v0.2-checkup.md`](tech-design/v0.2-checkup.md) · [`tech-design/v0.3.md`](tech-design/v0.3.md) · [`tech-design/v0.4.md`](tech-design/v0.4.md) · [`tech-design/v0.5.md`](tech-design/v0.5.md)
- **预览原型**:[`preview/index.html`](preview/index.html)(Tailwind CDN 静态预览)· [`preview/v0.4/`](preview/v0.4/index.html) · [`preview/v0.5/`](preview/v0.5/index.html)(财富水位 / 股票现金联动 / FIRE 自适应)
- **QA case 库**:[`docs/qa-cases.md`](docs/qa-cases.md)
- **部署运行**:[`deploy/README.md`](deploy/README.md)

## 目录结构

```
financial-management/
├── src/main/java/com/family/finance/    # Spring Boot 应用代码
│   ├── auth/                              # Spring Security
│   ├── domain/                            # 实体(family/member/account/period/cash_flow/...)
│   ├── repository/                        # MyBatis @Mapper
│   ├── service/                           # 业务服务 + LLM + FX
│   ├── factview/                          # 大宽表抽象(净资产 / 趋势 / 配置 等指标统一从这里出)
│   ├── calc/                              # 纯函数(PnL / XIRR / TWR / Reconciliation)
│   └── web/                               # Controller(account / dashboard / entry / reports / checkup / admin)
├── src/main/resources/
│   ├── application.yml                   # dev/prod profile
│   ├── mapper/                           # MyBatis XML
│   ├── static/                           # CSS / JS / 图标
│   └── templates/                        # Thymeleaf 模板
├── src/test/java/                        # JUnit 5
├── db/
│   ├── apply.sh                          # 版本化迁移运行器(sha256 校验)
│   └── migration/V*__*.sql               # 数据库 schema + 种子
├── deploy/
│   ├── deploy.sh                         # Linux 一键部署 · 顶部 OS 探测自动转 macOS
│   ├── deploy-macos.sh                   # macOS 一键部署($HOME/finance · brew · 无 sudo)
│   ├── finance.macos.plist.template      # macOS launchd 开机自启模板(可选)
│   ├── rollback.sh                       # Linux 紧急回滚
│   ├── nginx-setup.sh                    # Linux 单独 nginx 配置
│   ├── maven-settings.xml                # 国内 mirror 加速(可改)
│   ├── finance.service                   # Linux systemd unit
│   ├── backup.sh + finance-backup.{service,timer}  # Linux 每日自动备份
│   └── README.md                         # 部署手册
├── prd/                                  # 产品需求文档
├── tech-design/                          # 技术设计文档
├── preview/                              # 静态 HTML 预览(v0.1 ~ v0.5 各版本卷)
├── docs/qa-cases.md                      # QA case 库
├── icons/                                # 用户可替换的图标源 PNG
└── scripts/
    ├── qa-run.sh                         # 黑盒回归
    └── qa-e2e.sh                         # 端到端真值校验
```

## 配置项

**v0.4.18 起 · 运营参数沉淀到管理页 · 实时生效不重启**(详 [`prd/v0.4.md`](prd/v0.4.md) §22)。读取链:**DB 优先 → env(@Value)→ 代码常量**。

### A · 留 `/etc/finance.env`(系统级 · 启动前必须存在)

由 `deploy.sh` 自动生成,首装时交互填:

| 项 | 说明 |
|---|---|
| `DB_*` | MySQL 连接信息(`deploy.sh` 自动生成 24 字符随机密码,亦可手填)|
| `SERVER_PORT` | Spring Boot 监听端口(默认 20000,nginx 反代到这里)|
| `SERVER_ADDRESS` | `127.0.0.1` 让 nginx 走 loopback 反代;`0.0.0.0` 让 Spring 直接对外 |
| `UPLOAD_ROOT` | 用户上传 logo 的本地路径 |
| `REMEMBER_ME_KEY` | Remember-me cookie 签名 key(自动 32 字节随机 · 改即踢人)|
| `BACKUP_DIR` | mysqldump 备份目录(默认 `/var/backup/finance`)|
| `RETENTION_DAYS` | 备份保留天数(默认 56 · 被 backup.sh 独立 cron 读)|

### B · 沉淀到管理页(运营参数 · 实时生效)

| 配置 | 入口 | env 兜底 |
|---|---|---|
| **LLM Qwen API key** | `/admin/integrations` ① 段(私密 · 留空保原值) | `FINANCE_LLM_QWEN_API_KEY` 仍可用作 fallback |
| **LLM DeepSeek API key** | 同上 | `FINANCE_LLM_DEEPSEEK_API_KEY` |
| **LLM max_tokens / timeout** | `/admin/integrations` | — |
| **股票自动拉取开关** | `/admin/integrations` ② 段 · checkbox | `FINANCE_STOCK_FETCH_ENABLED=true/false` |
| **股票 3 市场 cron**(美 06:05 / A 16:10 / 港 16:30) | `/admin/integrations` ② 段 · 各自 cron 表达式 · 改即 cancel 旧 future + 重排 | 代码默认 |
| **FX 拉取 cron**(月初 02:30) | `/admin/integrations` ③ 段 | 代码默认 |
| **提醒 cron**(每天 10:00/20:00) | `/admin/reminders` | 代码默认 |
| **smart_transfer 阈值**(¥3000) | `/admin/calc-tweaks` ① 段 | 代码默认 |
| **checkup 阈值**(集中度 40% / 高风险 40% / LIQUID 1.5x / 应急金 6 月) | `/admin/calc-tweaks` ② 段 | 代码默认 |
| **会话有效期**(remember-me · 默认 30 天) | `/admin/calc-tweaks` ③ 段 · 注意新值生效需重启 | `app.remember-me-validity-seconds` |
| **填报模板 + 提前提醒天数**(v0.4.14) | `/admin/reminders` ① 段 | DB · 默认 T1 · leadDays=2 |
| **短信 aksk + 签名 + 模板**(阿里云) | `/admin/reminders` ② 段(私密)| DB 单一来源 · `docs/aliyun-sms-setup.md` 9 步接入 |
| **成员手机号**(短信收件人) | `/admin/reminders` ④ 段 | DB · `member.phone` |

**升级路径**:`deploy.sh` step 9.5 一次性把 env 里的 LLM keys + 股票开关 seed 到 `family_runtime_config` 表(幂等 flag `/var/finance/.config-migrated-v0.4.18`)。之后改 env 不再生效 · DB 是 source of truth · env 仅当 fallback。

### 不要做

- ✗ 改 `/etc/finance.env` 期望生效(v0.4.18 后改 env 不会触发任何 reload · 改管理页才生效)
- ✗ 直接 SQL 改 `family_runtime_config`(可以,但 cache 5s TTL 内不立刻生效;走管理页才会同步 invalidate cache + rescheduleAll)

## 安全

- 单家庭 / 多成员 · Spring Security session cookie · bcrypt 密码哈希
- 所有写操作走 CSRF · 表单与 HTMX 请求自动带 token
- SQL 100% 参数化(MyBatis)· 无 OGNL / 自由表达式
- 文件上传:前端 Canvas 压缩为 WebP + 后端 RIFF magic 校验 + 200KB 上限 + path traversal 防护
- 数据库每日自动备份(systemd timer)· 备份目录权限隔离
- LLM prompt 真名脱敏(成员 A/B/C 稳定映射)· 输出 OutputValidator 检查担保词 / 真名泄露 / 产品代码
- **私密红线 · 编译期 + 静态扫双重防回归**(v0.4.14 + v0.4.18)— 手机号 / 短信 aksk / LLM API key 绝不进 LLM prompt / audit_log 明文 / 前端明文回显 · `PrivacyIsolationTest` 静态扫源码 + 行为单测 · `qa-run v04-PRIV-1` grep gate

发现安全问题?见 [`SECURITY.md`](SECURITY.md)。

## 贡献

欢迎贡献!见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

## License

Apache 2.0 · 见 [`LICENSE`](LICENSE)。

## 致谢

- [Spring Boot](https://spring.io/projects/spring-boot) · [MyBatis](https://mybatis.org/) · [HTMX](https://htmx.org/) · [Chart.js](https://www.chartjs.org/) · [ECharts](https://echarts.apache.org/) · [Thymeleaf](https://www.thymeleaf.org/)
- [Frankfurter](https://www.frankfurter.dev/)(免费 ECB 汇率 API)
- [阿里云 Maven Mirror](https://maven.aliyun.com/)(国内拉依赖加速)
- 字体:Fraunces / Source Serif 4 / Noto Serif SC / JetBrains Mono(均为开源字体)
- 美学:晚清账册风 + 中式纸面信笺(墨/纸/黄铜/朱印 配色)
