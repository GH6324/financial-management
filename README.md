# 家庭账房 · Family Ledger

> 自托管的家庭资产管理 Web 应用 · 每月 10 分钟、夫妻异步、自动算年化收益率

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-green)](https://spring.io/projects/spring-boot)

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

> 📷 _截图位:`/dashboard`、`/entry`、`/checkup`、`/accounts/{id}` — 欢迎贡献_

## 主要能力

### v0.1(MVP)

- 6 种账户类型(现金 / 股票 / 理财 / 房产 / 负债 / 其他),13 个内置账户模板
- 月度 / 周度周期可切,每周期自动给每个账户生成"填余额"待办
- 余额录入 + 现金流(收入/支出)+ 跨账户转账;**轧差自动建议未解释金额怎么分类**
- 周期自动关闭 + 指标重算(净资产 / 总资产 / 总负债 / 趋势 / 配置)
- 账户级 XIRR + 家庭级 XIRR / TWR
- 多币种(本位币可选 CNY/USD/HKD,自动拉汇率)
- CSV 一键导出全部数据(避免被工具锁死)
- 移动端响应式(妻子手机能完成全部填报)
- 系统级 logo / 品牌名 / 周期类型 / 汇率覆盖 11 个 admin 子页

### v0.2

- 单账户详情页(账本视角:余额时序 + 月分组流水)+ 单账户 CSV
- 单条流水软删除(保留撤销路径)
- iOS PWA 添加到主屏(`apple-touch-icon` + manifest)
- 微信内浏览器引导(打开系统浏览器登录)
- 4 套预设品牌图标(默认 icon2,可自定义 WebP 上传)
- **资产体检**模块:
  - 全家诊断(配置 / 风险敞口 / 流动性 / 收益质量)
  - 账户级诊断(类型差异化 · STOCK / WEALTH / CASH / LOAN / PROPERTY)
  - 16 条智能建议规则(余额负数 / 久未填报 / 单类目集中度过高 等)
  - LLM 综合诊断(主 Qwen-Plus / 备 DeepSeek-Chat · 真名脱敏 · 圆形熔断)
  - 产品类目 + 6 级风险评级体系
  - 报表风险等级分布环形图
- Dashboard 加"按账户分布"横向 bar(资产向右 / 负债向左 / 0 居中)+ "按成员分布"饼图
- 搜索式下拉(大列表 select 自动升级)

### v0.3(2026-05-13 封板 · tag `v0.3`)

- **财务目标体系** · `/goals` 一级页 + Dashboard 顶部进度条带(信息架构 C 混合)
  - 退休 / FIRE(通胀 PV + 提取率 4%)
  - 子女教育金(child member FK + 通胀公式)
  - 应急储备(仅 CASH 类账户口径 · target = 月支出 × 倍数)
  - **三情景预测**(乐观 8% / 中性 5% / 悲观 2%)· 二分反推达成日期
- **AI 4 处介入**(复用 v0.2 LlmOrchestrator · 主 Qwen-Plus / 备 DeepSeek):
  - FR-53a 目标设定向导 · 推荐合理参数 + rationale
  - FR-53b 周期关闭后异步生成目标月报叙事(`goal_ai_report` 表持久化)
  - FR-53c 偏离预警 + 建议(90 天节流)
  - FR-53d 体检页 prompt 扩展(目标 + 储蓄能力维度)
- **股票账户自动估值** · `/accounts/{id}/holdings` 持仓管理页(混合模式):
  - **AUTO** · 录 ticker + 数量 · 系统每日 T+1 拉价(新浪主 + 腾讯备 · 圆形熔断)· 三市场覆盖(美/A/港)
  - **MANUAL** · 手填账户币种市值 · 适合未上市/私募
  - **CASH** · 账户内多币种闲置现金(IBKR / 富途)· FX 链式经家庭 base 中转
  - 估值写回 `account_balance` · 下游 dashboard / XIRR / 目标进度零改动
  - `/entry` 加"📦 持仓变动?"入口
- **储蓄能力指标** · `/entry` 成员级月度收入/支出 +2 框 · `/reports` 月度收支双柱图 + 月均 KPI
- **macOS 一键部署** · `deploy/deploy.sh` 顶部 OS 探测 · Darwin 自动转 `deploy-macos.sh`(brew · 无 sudo · `$HOME/finance` · 可选 launchd)

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.3 + Java 21 + MyBatis 3 |
| 持久化 | MySQL 8(版本化 SQL 迁移 + sha256 校验,无 Flyway 依赖) |
| 前端 | Thymeleaf + HTMX 1.9 + Chart.js 4 + ECharts(无 SPA、无构建管线) |
| 认证 | Spring Security + bcrypt + Session Cookie |
| 部署 | Linux systemd + nginx 反代 :80 → :20000 · macOS launchd(可选)直连 :20000 |
| 测试 | JUnit 5 · 114 单元 / 36 端到端 / 229 黑盒 |

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
mvn test                       # JUnit 单元测试(114)
bash scripts/qa-run.sh         # 黑盒 endpoint + 模板渲染(229)
bash scripts/qa-e2e.sh         # 端到端真值校验(36 · 会清空 DB)
```

## 文档

- **产品需求**:[`prd/v0.1.md`](prd/v0.1.md) · [`prd/v0.2.md`](prd/v0.2.md) · [`prd/v0.3.md`](prd/v0.3.md)
- **技术设计**:[`tech-design/v0.1.md`](tech-design/v0.1.md) · [`tech-design/v0.2.md`](tech-design/v0.2.md) · [`tech-design/v0.2-checkup.md`](tech-design/v0.2-checkup.md) · [`tech-design/v0.3.md`](tech-design/v0.3.md)
- **预览原型**:[`preview/index.html`](preview/index.html)(43+ 页 Tailwind CDN 静态预览 · v0.1 + v0.2 + v0.3)
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
├── preview/                              # 静态 HTML 预览(v0.1 + v0.2 + v0.3 · 43+ 页)
├── docs/qa-cases.md                      # QA case 库
├── icons/                                # 用户可替换的图标源 PNG
└── scripts/
    ├── qa-run.sh                         # 黑盒回归
    └── qa-e2e.sh                         # 端到端真值校验
```

## 配置项

`/etc/finance.env`(由 `deploy.sh` 自动生成,首装时交互填):

| 项 | 说明 |
|---|---|
| `DB_*` | MySQL 连接信息(`deploy.sh` 自动生成 24 字符随机密码,亦可手填)|
| `SERVER_PORT` | Spring Boot 监听端口(默认 20000,nginx 反代到这里)|
| `SERVER_ADDRESS` | `127.0.0.1` 让 nginx 走 loopback 反代;`0.0.0.0` 让 Spring 直接对外 |
| `UPLOAD_ROOT` | 用户上传 logo 的本地路径 |
| `REMEMBER_ME_KEY` | Remember-me cookie 签名 key(自动 32 字节随机)|
| `BACKUP_DIR` | mysqldump 备份目录(默认 `/var/backup/finance`)|
| `RETENTION_DAYS` | 备份保留天数(默认 56)|
| `FINANCE_LLM_QWEN_API_KEY` | 可选 · LLM 综合诊断(资产体检 AI 文案)主厂商 key |
| `FINANCE_LLM_DEEPSEEK_API_KEY` | 可选 · LLM 备选厂商 key,主挂掉时熔断切到备选 |

## 安全

- 单家庭 / 多成员 · Spring Security session cookie · bcrypt 密码哈希
- 所有写操作走 CSRF · 表单与 HTMX 请求自动带 token
- SQL 100% 参数化(MyBatis)· 无 OGNL / 自由表达式
- 文件上传:前端 Canvas 压缩为 WebP + 后端 RIFF magic 校验 + 200KB 上限 + path traversal 防护
- 数据库每日自动备份(systemd timer)· 备份目录权限隔离
- LLM prompt 真名脱敏(成员 A/B/C 稳定映射)· 输出 OutputValidator 检查担保词 / 真名泄露 / 产品代码

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
