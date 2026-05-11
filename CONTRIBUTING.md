# 贡献指南

感谢你愿意贡献!

## 优先级方向

我们当前最欢迎的贡献:

1. **bug 修复**(开 issue + PR 都行)
2. **i18n**(目前是中文界面 + 部分英文;欢迎完整英文翻译,Locale 切换)
3. **新数据源 / 新模板**(账户模板、产品类目、银行)
4. **移动端 polish**(尤其窄屏 < 380px)
5. **文档改进**(README / deploy / 截图)

**暂不接受**的方向:

- 个股 / 单券持仓级颗粒度(违反"每月 10 分钟"约束)
- 银行账单 OCR / 券商 API 直连(同上)
- 把 Thymeleaf 换成 SPA(后端模板 + HTMX 是有意选择,不引入构建管线)

## 开 issue 之前

- 搜一下有没有重复
- 描述清楚:**做了什么** + **期望什么** + **实际发生什么** + 环境(OS / Java / MySQL 版本)
- bug 报告附上 `journalctl -u finance -n 50` 的关键片段

## 写代码

### 本地准备

见 [README.md · 本地开发](README.md#本地开发)。

### 代码规约

- **Java**: 标准 Spring Boot 风格 + Lombok `@RequiredArgsConstructor`
- **SQL**: 大写 SQL 关键字,小写表名 / 列名
- **Thymeleaf**: 用 fragment 复用,避免大段重复
- **CSS**: Tailwind 优先,自定义只在 `style.css`
- **JS**: vanilla,无 npm 构建。需要库通过 CDN(via `/vendor/`)
- **commit 消息**: 中英文都行,**信息密度** > 格式
  - 简版:`fix · /entry 提交转账 NPE`(动作 · 一句话)
  - 详版:含 commit body 说明根因 + 修法 + 测试

### PR 流程

1. 从 `master` 开新分支:`fix/some-bug` 或 `feat/some-feature`
2. 改完跑测试:`mvn test && bash scripts/qa-run.sh`(可能需要本地 MySQL)
3. 改了 schema?加 `db/migration/V<n+1>__*.sql`(**不要改已发布的 V*.sql**;`apply.sh` 会 sha256 校验拒)
4. 改了行为?同步 `prd/v0.X.md` 或 `tech-design/v0.X.md` 对应段落
5. 改了 endpoint / 重要 UI?加 `scripts/qa-run.sh` 黑盒 case
6. PR 描述:**改了什么** + **为什么改** + **对老数据是什么影响**(prod 已上线项目,backward-compat 是红线)

## 数据库迁移 backward-compat 红线

prod 跑过的 V*.sql 不允许改内容(`apply.sh` sha256 拒)。新增 schema 改动:

| 操作 | 安全做法 |
|---|---|
| 新加列 | `ALTER TABLE x ADD COLUMN y VARCHAR(N) NULL`,**或**有 `DEFAULT` |
| 删列 / 删表 | **禁止**;改为 `archived_at` 软删,等几版后清 |
| 改列类型 / 长度 | 先 `SELECT MAX(LENGTH(col))` 看实际值,确认不溢出 |
| 改默认值 | 新加 `DEFAULT` 只影响新行,老行保留旧值 |
| 大表 ALTER | 用 `ALGORITHM=INPLACE` 或 `pt-online-schema-change`,避免锁表 |

## 测试

```bash
mvn test                       # 76 单元测试(calc / factview / LLM 输出校验等)
bash scripts/qa-run.sh         # 180+ 黑盒 endpoint + 模板渲染
bash scripts/qa-e2e.sh         # 36 端到端真值校验(会清 DB,本地跑)
```

新功能 PR 必须有对应的 ≥1 条 QA case,数学密集型加 1-3 个单元测试。

## License

提交贡献即表示你同意你的代码以 Apache 2.0 协议发布(见 `LICENSE`)。
