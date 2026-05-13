# 家庭账房 · 部署

参考 openclash / vaultwarden 等自托管项目的部署形态:**服务器上 git clone + 一行命令**,无所谓本机推送、SSH 串行执行那些复杂套路。

## 部署条件

- 一台公网 Linux 服务器:Ubuntu 22+ / Debian 12+ / RHEL 9+ / Alibaba Cloud Linux 都行
- 你能 SSH 进去 + 有 sudo
- 一个公网 80 端口(可选 443),能从公网拉 apt 包

**或** macOS 本机(本地开发 / 个人自用 · 见下面 [macOS 部署](#macos-本地部署))。

---

## 首次部署(2 步)

```bash
# 1. 把本仓库拉到服务器上(任意位置;建议 /opt/src 或 ~)
sudo apt install -y git    # Ubuntu/Debian;RHEL 系是 dnf
git clone https://gitlab.com/xblteam/financial-management.git
cd financial-management

# 2. 跑一次部署
sudo bash deploy/deploy.sh
```

`deploy.sh` 会:

1. 装 JDK 21 / Maven / MySQL 8 / nginx / 辅助工具
2. 创建 finance 系统用户 + 目录(`/opt/finance`、`/var/finance/uploads`、`/var/backup/finance`)
3. 建 MySQL 库 + 用户(交互输密码或自动生成 24 字符)
4. 写 `/etc/finance.env`(系统配置,640 权限)
5. 编译 jar(`mvn package`)
6. 应用所有 `V*__*.sql` 迁移
7. 设种子用户临时密码(`diwa` / `wangergou` + 你设的密码,登入后强制改)
8. 清掉 V3/V4/V5 灌的 dev 演示数据(`sentinel` + "真实数据探测"双保险,不会误删真用户数据)
9. 装 systemd unit(`finance.service`)+ NOPASSWD sudoers
10. 启服务 + `/health` 健康检查
11. 装 nginx 反代 :80 → :20000(交互式 prompt;不要也行)

完事后浏览器访问 `http://<server-ip>/` 即可。

---

## 后续发版迭代(1 步)

每次代码更新后,**在服务器上**:

```bash
cd ~/financial-management              # 仓库目录
git pull                                # 拉新代码
sudo bash deploy/deploy.sh              # 一键迭代
```

`deploy.sh` 检测到已上线,进入「迭代模式」,做:

1. `mysqldump | gzip` 备份 → `/var/backup/finance/pre-deploy-{ts}.sql.gz`(+ `gunzip -t` 完整性校验)
2. 列出待 apply 的增量 `V*.sql`,**交互确认才执行**(`schema_history` 表防重)
3. `mvn package` 编译新 jar
4. 旧 jar 备份到 `app.jar.prev` + 新 jar 切入
5. `systemctl restart finance`
6. `/health` 30s 轮询 + `/login` 烟测
7. **任意步骤失败 → 自动 `app.jar.prev → app.jar` + restart + 健康复检**(DB 备份保留不动,因 schema 多数 backward-compat,老 jar 兼容新表)

---

## 回滚(失败时一行)

```bash
sudo bash deploy/rollback.sh
```

把 `app.jar.prev` 还原到 `app.jar` + restart + 健康检查。**不动 DB**(若 DB 也要还原见脚本输出的 `gunzip ... | mysql` 提示)。

---

## 常用命令

```bash
sudo systemctl status finance          # 服务状态
sudo journalctl -u finance -f          # 实时日志
sudo systemctl restart finance         # 手动重启(deploy.sh 内部已经做)
mysql -ufinance -p$DB_PASS finance     # 进 DB(密码在 /etc/finance.env)
ls /var/backup/finance/                # 看 DB 备份历史
```

---

## 自动备份

`deploy.sh` 装了 `finance-backup.timer`(systemd 定时器),每天 03:30 自动 `mysqldump | gzip` 到 `/var/backup/finance/`,默认保留 56 天(`RETENTION_DAYS` 在 `/etc/finance.env`)。

```bash
sudo systemctl list-timers finance-backup  # 看下次跑的时间
sudo systemctl start finance-backup        # 手动触发一次
```

---

## HTTPS(可选,但 prod 推荐)

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com
```

certbot 自动改 `finance.conf` 加 `listen 443 ssl` + 配 cron 续签。`deploy.sh` 重跑时检测到 `ssl_certificate` 会避让 certbot 改的配置,不冲突。

---

## 故障排查

| 症状 | 解 |
|---|---|
| Linux:`deploy.sh` 步 7 mysql 失败 | `sudo mysql` 看能不能进(Ubuntu 默认 socket 鉴权) |
| Linux:`deploy.sh` 步 14 服务 30s 不起 | 看 `journalctl -u finance --no-pager -n 100`(脚本自动打了 30 行);DB 密码错 / 端口被占最常见 |
| macOS:`deploy-macos.sh` 步 6 ERROR 1045 Access denied for root | root 设了密码 · 重跑输入密码;真忘了:`brew services stop mysql && mysqld_safe --skip-grant-tables &` 重置 |
| macOS:登入后看到 demo 数据 | 你 sentinel 已写但 TRUNCATE 没跑 · 删 `~/finance/.prod-cleaned` + 重跑 `deploy/deploy.sh` |
| macOS:服务起不来 | `tail -f $HOME/finance/logs/app.log` 或前台跑 `bash $HOME/finance/start.sh` 看输出 |
| 切币种 USD 显示 ¥ | `fx_rate` 缺,见 `/admin/fx`;或服务器拉不通 frankfurter.dev(防火墙) |
| 自动备份没生成 | Linux:`sudo systemctl status finance-backup.timer` · macOS:用户自己 `crontab -e` 加 mysqldump |
| Linux:重置成"刚装好"状态 | 删 `/opt/finance/.prod-cleaned` + 重跑 `deploy.sh`(真实数据探测会拦你,见警告 SQL) |

---

## 文件清单

```
deploy/
├── deploy.sh                       ← 主脚本(顶部 OS 探测 · Darwin 自动转 deploy-macos.sh)
├── deploy-macos.sh                 ← macOS 路径($HOME/finance · brew · 无 sudo)
├── finance.macos.plist.template    ← macOS launchd 自启模板(可选)
├── rollback.sh                     ← 紧急回滚(Linux)
├── nginx-setup.sh                  ← nginx 单独配置(deploy.sh 内部会调,macOS 不用)
├── finance.service                 ← systemd unit 模板(Linux)
├── finance.env.example             ← /etc/finance.env 配置示例(参考用)
├── nginx-finance.conf.example      ← nginx 反代模板(__PORT__ __SERVER_NAME__ 占位)
├── backup.sh                       ← 备份脚本(finance-backup.timer 调)
├── finance-backup.{service,timer}  ← systemd 定时备份
├── gen-presets.sh                  ← 一次性:生成 4 套图标 PNG
├── gen-icons.sh                    ← 历史:生成默认 icon PNG(已被 gen-presets 取代)
└── README.md                       ← 本文件
```

---

## macOS 本地部署

适合:本地开发跑通 · 个人 mac 自用 · 不需要公网访问 / nginx / systemd。

**前提**(自己装):

- [Homebrew](https://brew.sh)(`/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"`)
- 当前 macOS 用户(脚本不需要 sudo · 不创建系统用户)

**部署**:

```bash
git clone https://gitlab.com/xblteam/financial-management.git
cd financial-management
bash deploy/deploy.sh         # 或 deploy/deploy-macos.sh,二者等价(主脚本顶部自动分流)
```

脚本会(12 步幂等):
1. `brew install openjdk@21 maven mysql`(已装则跳过)· `brew services start mysql`
2. 探测 MySQL root 鉴权:无密码(brew 默认)or 密码(`mysql_secure_installation` 装过)· 后者 prompt 3 次重试
3. 建 `finance` 库 + 用户(密码 prompt 或自动生成 24 字符)
4. 写 `$HOME/.finance/finance.env`(600 权限)
5. 跑 `V*__*.sql` 迁移(共享 `db/apply.sh` · sha256 portability shim · macOS 用 `shasum -a 256`)
6. **首装清 dev 演示数据**(V3-V5 灌的账户/周期/流水/快照)· sentinel `$HOME/finance/.prod-cleaned` + 真实数据探测(audit > 50 或 extra members > 0 拒绝 TRUNCATE)· 留 family + member(`diwa` / `wangergou`)种子
7. `mvn package` 编译 jar
8. 拷到 `$HOME/finance/app.jar` + 生成 `$HOME/finance/start.sh`(内部 source env + exec java)

**启动**:

```bash
bash $HOME/finance/start.sh                                                # 前台
nohup bash $HOME/finance/start.sh > $HOME/finance/logs/app.log 2>&1 &      # 后台
```

浏览器 `http://127.0.0.1:20000/`(端口在 `~/.finance/finance.env` 改),默认账号 `diwa` / `wangergou` + 临时密码 `demo1234`(或脚本时你设的)。

**(可选)launchd 开机自启**:

```bash
sed "s|{{HOME}}|$HOME|g" deploy/finance.macos.plist.template > ~/Library/LaunchAgents/com.family.finance.plist
launchctl load -w ~/Library/LaunchAgents/com.family.finance.plist
launchctl list | grep com.family.finance       # 见 PID 即跑
# 卸载:launchctl unload -w ~/Library/LaunchAgents/com.family.finance.plist
```

**macOS vs Linux 差异速查**:

| 项 | Linux | macOS |
|---|---|---|
| 入口 | `sudo bash deploy/deploy.sh` | `bash deploy/deploy.sh`(无 sudo) |
| 应用目录 | `/opt/finance` | `$HOME/finance` |
| env | `/etc/finance.env`(640 root:finance) | `$HOME/.finance/finance.env`(600) |
| 服务管理 | systemd(`finance.service`) | launchd(`com.family.finance.plist` · 可选) |
| 日志 | `journalctl -u finance -f` | `tail -f $HOME/finance/logs/app.log` |
| 包管理 | apt / dnf | brew |
| 反代 | nginx :80 → :SERVER_PORT | 无(直接访问 :SERVER_PORT) |
| 应用用户 | `finance` 系统用户 | 当前 macOS 用户 |

**迭代发版**:跟 Linux 一样 `git pull && bash deploy/deploy.sh`,脚本检测到 `~/finance/app.jar` 自动走迭代分支(mysqldump 备份 + 切 jar)。重启需手动:`pgrep -f $HOME/finance/app.jar | xargs kill && nohup bash $HOME/finance/start.sh > $HOME/finance/logs/app.log 2>&1 &`。

**MySQL root 提示**:brew 默认装好 root 无密码;装过 `mysql_secure_installation` 或用 DMG 装的会有密码。脚本会自动探测 → 失败时 prompt 3 次重试(回车 = 试无密码,输入 = 试密码)。3 次都错 die 并给出重置指引(`brew services stop mysql && mysqld_safe --skip-grant-tables &`)。
