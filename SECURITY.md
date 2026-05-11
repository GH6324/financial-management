# 安全策略

## 报告漏洞

**请不要**在公开 issue 里报告安全漏洞,以免在修复前被恶意利用。

发邮件到:**security@<your-domain>**(或在 GitHub 用 [Private Vulnerability Reporting](https://docs.github.com/en/code-security/security-advisories) 提交)。

包含:
- 影响版本(`git rev-parse HEAD` 或 release tag)
- 漏洞类型(认证绕过 / SQL 注入 / XSS / CSRF / 信息泄漏 / 其它)
- 复现步骤
- 你认为的可能影响

## 响应承诺

- **48 小时内**:确认收到 + 初步评估
- **7 天内**:给出修复时间表(高危 ≤ 7 天,中危 ≤ 30 天,低危 ≤ 90 天)
- **修复发布**:打 CVE + 致谢报告者(若你愿意公开)

## 自托管安全建议

如果你自己跑这套(prod 上),建议:

- **改默认密码**:首次登录后立即在 `/profile/password` 改
- **改默认账号**:`alice` / `bob` 是种子用户名,生产环境改成自己的(在 `/admin/members`)
- **关 :20000 公网入口**:用 nginx 反代 :80(`deploy/deploy.sh` 步 15 会装);云防火墙 / iptables 也建议关 :20000 的入站
- **上 HTTPS**:`sudo certbot --nginx -d your-domain.com` 一行装好
- **限制访问 IP**(可选,但适合"仅家庭使用"):nginx 加 `allow <家庭固定 IP>; deny all;`
- **MySQL 加固**:`mysql_secure_installation` 走一遍
- **关注每日备份**:`/var/backup/finance/` 每天 03:30 自动 mysqldump · gzip,异地备份自己加(rclone 到对象存储 / scp 到第二台机)
- **LLM API key 不要 commit**:`FINANCE_LLM_*_API_KEY` 放 `/etc/finance.env`(640 权限),不要写进 application.yml

## 已知安全特性

- Spring Security session cookie + bcrypt(cost 10)
- 所有写操作 CSRF · 表单和 HTMX 自动带 token
- SQL 100% MyBatis 参数化,无字符串拼接
- Logo 上传:Canvas 压缩 WebP + RIFF magic 校验 + 200KB 上限 + path traversal 防护
- LLM prompt 真名脱敏(成员A/B/C 稳定映射)+ OutputValidator 校验(担保词 / 真名泄露 / 产品代码不能出现在输出)
- 输入校验:`@Valid` + Bean Validation 注解

## 已知 limitations(不算 vuln 但建议知道)

- 单家庭设计,不是 SaaS;`family_id=1` 硬编码部分
- 没做 rate limit(假设单家庭流量,无需)
- 没做 audit log 加密 / 不可篡改(写一行 audit_log 即可)
- LLM 集成是可选的,但 API key 一旦配上,prompt 会发到 LLM 厂商服务器(已做姓名脱敏)
