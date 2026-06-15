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

---

# Docker 部署(v0.7 · 推荐)

比直装(deploy.sh)更干净:不污染宿主机、跨平台(Linux / macOS / NAS · amd64 + arm64)、升级一条命令。compose 只起 **app + MySQL + 备份 sidecar**,**反代/HTTPS 由你在前面自己挂**(见下「反代/HTTPS」)。

## 全新机一键起

```bash
git clone https://github.com/LuoDi-Nate/financial-management.git
cd financial-management
bash deploy/docker-up.sh          # 自检环境 + 生成密钥 + 起服务 + 验健康,一条命令
```

`docker-up.sh` 会逐项自检并在卡住时给出可复制的修复命令:① docker 装没装 ② 引擎(daemon)起没起 ③ Compose **V2** 在不在(`docker compose` 优先,回退 V2 版 `docker-compose`,老 V1 直接拒并教你装)④ 镜像拉不到就本地源码构建 ⑤ 起完轮询 `/health`。macOS 上 Docker Desktop / OrbStack / colima 各种装法都适配。

<details><summary>想手动控制每一步</summary>

```bash
bash deploy/docker-init.sh        # 仅生成 .env;或手动 cp .env.example .env 再改
docker compose up -d              # 有预构建镜像就拉,没有就 docker compose build 后再 up
```
报 `unknown shorthand flag: 'd' in -d` → 这台机 Compose V2 没装好,见下「国内镜像加速 / Apple Silicon」排障,或直接用上面的 `docker-up.sh`。
</details>

浏览器开 `http://<宿主>:20000`(默认只发布到 `127.0.0.1`,公网访问请前置反代)。

**首次登录**:种子账号 **`diwa`** 或 **`wangergou`**,临时密码默认 **`demo1234`**(在 `.env` 的 `SEED_ADMIN_PASSWORD` 可自定义,仅首装生效),**首次登录后强制改密**。`docker-up.sh` 起完会直接把这行打印出来;也可 `docker compose logs app | grep -A4 首次登录` 看启动横幅。(机制:Docker 跑 `prod` profile,`ProdSeedRunner` 在首启时把种子占位密码设为该临时密码;已初始化的库不会被改。)

LLM key / 短信 aksk / 阈值等运营参数,登录后走 `/admin/integrations` 配(存数据库,不在 .env)。

- 数据持久化在命名卷:`db-data`(库)/ `uploads`(logo)/ `backups`(每日 mysqldump)。`docker compose down` 不删卷,数据还在。
- 升级:`git pull && docker compose pull && docker compose up -d`(entrypoint 自动跑增量迁移,幂等)。
- 镜像源码自构建:`docker compose build`(基础镜像 maven / temurin / mysql 均有 arm64,Apple Silicon 原生构建)。

## 从已部署(systemd / macOS)迁移到 Docker

存量用户**数据零丢**迁过来,一条命令自动识别 systemd 还是 macOS:

```bash
# Linux systemd(读 /etc/finance.env,要停 finance 服务,需 sudo):
sudo bash deploy/migrate-to-docker.sh

# macOS(读 ~/.finance/finance.env,会提示你先停掉旧的前台 java / launchd):
bash deploy/migrate-to-docker.sh
```

流程:mysqldump 备份 → 生成 .env(**携带原 REMEMBER_ME_KEY**,登录态不丢)→ 停旧 app 腾端口 → 起 db 容器灌 dump(含 `schema_history`,**迁移不会重放**)→ 搬 uploads → 起 app → 验 `/health`。

**回滚**:全程不删旧部署。不满意 → `docker compose down` + 重启旧应用(systemd:`systemctl start finance`)。满意后再 `systemctl disable finance`(或停 brew mysql)释放宿主资源。

> 注:`deploy.sh`(systemd 直装)路径**继续保留支持**,不想迁的人可不动。

## 反代 / HTTPS(compose 不内置,自己挂)

app 默认只在 `127.0.0.1:20000`。在前面挂个反代即可。两段照抄:

**nginx + certbot**
```nginx
server {
    listen 80;
    server_name your.domain.com;
    location / {
        proxy_pass         http://127.0.0.1:20000;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }
}
# 然后:sudo certbot --nginx -d your.domain.com --redirect
```

**Caddy(自动 HTTPS,零配置)** —— Caddyfile:
```
your.domain.com {
    reverse_proxy localhost:20000
}
```

## 国内镜像加速 / Apple Silicon

- GHCR / Docker Hub 在大陆慢:配 Docker 镜像加速(阿里云容器镜像服务的加速地址写进 `/etc/docker/daemon.json` 的 `registry-mirrors`),或直接 `docker compose build` 源码构建。
- **macOS / Apple Silicon**:Docker Desktop / OrbStack / colima 均可;`docker compose build` 原生 arm64,预构建镜像也是 amd64+arm64 多架构,`pull` 自动取对的那个。
- **`brew install docker` 后报「连不上 daemon / Cannot connect to the Docker daemon」**:brew 装的 docker **只是命令行客户端,没有引擎**——Mac 上 docker 引擎跑在一个小 Linux 虚拟机里,要单独装一个。最省事的命令行方案:`brew install colima docker-compose && colima start`(第一次起约 1-2 分钟),再 `bash deploy/docker-up.sh`。或装带界面的 `brew install orbstack`(/ Docker Desktop)打开 App 即可。`docker-up.sh` 已能自检这一步并给出对应你机器的命令。
- **`docker compose up -d` 报 `unknown shorthand flag: 'd' in -d`**:这台机的 Compose V2 插件没装好,docker 没把 `compose` 当子命令,把 `-d` 当成了顶层 flag。处理:
  - Docker Desktop / OrbStack 自带 V2 —— 确认它装好且在运行(`docker compose version` 应有输出)。
  - Homebrew 装的纯 docker CLI(常配 colima):`brew install docker-compose`,再按 caveat 软链到 `~/.docker/cli-plugins/docker-compose`,`docker compose`(带空格)才生效。
  - 临时绕过:直接用老版连字符写法 `docker-compose up -d`(我们的 compose 文件两者都兼容)。`deploy/migrate-to-docker.sh` 与 `deploy/docker-init.sh` 已自动探测这两种写法。
