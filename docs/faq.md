# 常见问题 FAQ · 家庭账房

## 部署 / 访问

**Q：最低需要什么配置?**
A：1 GB 内存 · 1 核 · ~2 GB 磁盘。app 约 512 MB + MySQL 约 300 MB,**512 MB 的机器会 OOM 起不来**,建议 1 GB 起。NAS、旧笔记本、1 核 1 G 云服务器都够。

**Q：部署在远程 VPS,为什么浏览器打不开 `:20000`?**
A：默认只绑定 `127.0.0.1`(loopback),不对公网开放——这是安全默认值,不是 bug。两种正确做法:
- **临时看一眼**:本地开 SSH 隧道 `ssh -L 20000:127.0.0.1:20000 user@你的服务器`,然后本地浏览器开 `http://127.0.0.1:20000`。
- **长期用**:在服务器上前置反代(nginx / Caddy)并配 HTTPS,把 80/443 转到容器的 20000。片段见 [`deploy/README.md`](../deploy/README.md) 的「反代 / HTTPS」。
- (不推荐)直接公网裸奔:把 `.env` 的 `SERVER_PORT` 映射改成 `0.0.0.0` / 或 compose 端口去掉 `127.0.0.1:` 前缀——务必先配好登录强密码,且家庭财务数据建议别裸奔。

**Q：`docker compose down` 会丢数据吗?**
A：不会。数据在命名卷(`db-data` / `uploads` / `backups`),`down` 不删卷。除非你 `down -v`(那会删卷,慎用)。

**Q：怎么升级 / 回滚?**
A：升级 `git pull && docker compose pull && docker compose up -d`(entrypoint 自动跑增量迁移,幂等)。systemd 直装则 `git pull && sudo bash deploy/deploy.sh`。回滚见 `deploy/README.md`(Docker 切回旧 tag;systemd 用 `deploy/rollback.sh`)。

## 数据 / 备份

**Q：备份怎么恢复?**
A:备份是每日 `mysqldump` 到 `backups` 卷 / `/var/backup/finance`。恢复:
```bash
# Docker:把某个备份灌回 db 容器
gunzip -c 备份文件.sql.gz | docker compose exec -T db mysql -uroot -p<MYSQL_ROOT_PASSWORD> finance
# systemd:
gunzip -c 备份文件.sql.gz | mysql -ufinance -p<DB_PASS> finance
```
恢复前建议先停 app(`docker compose stop app` / `systemctl stop finance`),灌完再起。

**Q:我能托管多个家庭吗?**
A:**目前是单家庭设计**(一套部署 = 一个家庭、多个成员)。要给多个家庭用,各自独立部署一套(各自的数据库/容器)。多租户不在当前路线。

**Q:改了 `/etc/finance.env` 或 `.env` 怎么不生效?**
A:LLM key / 股票开关 / 阈值 等**运营参数走管理页**(存数据库、实时生效),改 env 不再触发 reload。env 只在「数据库还没配过该项」时作为兜底。详见 [配置与接入指南](configuration.md)。DB 连接、端口、`REMEMBER_ME_KEY` 这类系统级才在 env,改完要重启。

## 账号 / 登录

**Q:忘记密码、把自己锁外面了怎么办?**
A:单家庭模式你就是管理员,目前需手动重置(后续版本计划加一键重置)。利用「占位密码会在重启时被重置」的机制:
```bash
# Docker(用 .env 里的 DB_PASS 替换 <DB_PASS>):
docker compose exec db mysql -ufinance -p<DB_PASS> finance \
  -e "UPDATE member SET password_hash='PLACEHOLDER_RESET' WHERE username='diwa';"
docker compose restart app
# systemd:
mysql -ufinance -p<DB_PASS> finance \
  -e "UPDATE member SET password_hash='PLACEHOLDER_RESET' WHERE username='diwa';"
sudo systemctl restart finance
```
重启后,prod profile 的 `ProdSeedRunner` 会把占位密码重置成 `.env` 里的 `SEED_ADMIN_PASSWORD`(默认 `demo1234`),登录后强制改密。把 `diwa` 换成你要重置的用户名。

**Q:默认账号是什么?**
A:`diwa` 或 `wangergou`,初始密码 `demo1234`(Docker 下可在 `.env` 的 `SEED_ADMIN_PASSWORD` 改),首次登录强制改密。

## AI / 数据源

**Q:不配 AI / 短信能用吗?**
A:能。所有外部服务都可选,核心功能(记账 / 净资产 / 真实年化 / 图表)零配置即用。配了各自解锁什么、怎么配,见 [配置与接入指南](configuration.md)。

**Q:股票 / 汇率拉不到值?**
A:股票(新浪 / 腾讯)、汇率(frankfurter)都免费、无需 key,但需要服务器能联外网。拉不通时用最后一次有效值、不影响记账;可在 `/admin/fx` 手动补汇率。

---

没找到答案?提 [Issue](https://github.com/LuoDi-Nate/financial-management/issues) 或看 [`deploy/README.md`](../deploy/README.md)。
