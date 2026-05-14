# 微信入口接入 Claude Code · 调研文档

> 2026-05-14 · 目标:从微信发消息给 Claude(本项目当前 session 或类似 context)· 让 Claude 在 prod / beta 上协助排查 / 实现 / 部署 · 结果回推到微信。
>
> 同期参考:[Claude Code Remote Control(Feb 2026)](https://simonwillison.net/2026/Feb/25/claude-code-remote-control/) · [claude-code-telegram](https://github.com/RichardAtCT/claude-code-telegram) · [Wechaty](https://wechaty.gitbook.io/wechaty/zh) · [WeChatFerry](https://github.com/lich0821/WeChatFerry) · [LangBot](https://docs.langbot.app/zh/usage/platforms/wecom/wecombot)

## 0. 现实约束(决定哪些方案直接被排除)

| 约束 | 影响 |
|---|---|
| **claude.ai / Anthropic API 在中国大陆默认不可达** | Claude Code 官方 Remote Control(2026-02 GA)同步到 claude.ai web/iOS · 国内**直接不可用**;需用 API 中转 / 代理 |
| **微信网页协议被大规模封号(2025-2026)** | Wechaty `web-puppet` 已不安全 · 不可作为生产方案 |
| **个人主体无法 ICP 备案企业相关域名** | 走"企业微信自建应用 + 已备案域名"必须以**个体工商户 / 小微企业**主体备案 · 周级流程 |
| **公众号被动消息有 5s 超时 + 主动推送 48h 限制** | 不适合 long-running Claude Code(单次几十秒-几分钟) |
| **Claude Code 是 stateful REPL · session 在本机进程内** | "继续同一 session"需要 process 不死或 `--resume <id>` · 不是 stateless API |

## 1. 需求分解 · 4 个关键判断题

回答这 4 题决定走哪条路:

**Q1 · 谁发消息?**
- A · 只我自己 → 任何方案都可
- B · 我 + 妻子(2 人)→ 多用户鉴权 · 选有 user whitelist 的框架
- C · 团队 / 客户 → 必合规走企业微信 / 公众号

**Q2 · 实时性要求?**
- A · 即发即响应(几秒)→ 公众号被动消息 5s 超时是硬伤 · 走 WCF / 企业微信 / 飞书钉钉
- B · 异步可接受(发了忙别的 · 完成推送回来)→ 全部方案都行

**Q3 · 双向交互?**
- A · 一来一回多轮(像现在 CLI)→ 需要 session 持久化(`claude --resume <id>`)+ 多用户 session 隔离
- B · 单向"发任务 · 等结果"(类似 issue tracker)→ 每次 `claude -p "<prompt>"` 单发即可

**Q4 · 启动门槛容忍度?**
- A · 几小时落地(快糙猛)→ 个人微信 hook 方案
- B · 1-2 周(办个体户 + ICP 备案 + 服务器)→ 企业微信 / 公众号
- C · 不在乎"必须微信",钉钉/飞书也行 → 替代方案启动最快

## 2. 方案矩阵

| # | 方案 | 入口 | 启动门槛 | 封号风险 | 双向交互 | 多用户 | 合规度 | 月成本 |
|---|---|---|---|---|---|---|---|---|
| **A** | **WeChatFerry(WCF)+ claude -p** | 个人微信 | ⭐ 几小时 | ⚠ 中(注入微信内存) | ✓ | ✓ | ⚠ 灰 | ¥0(本机)/ ¥30 Windows VPS |
| **B** | **企业微信自建应用 + LangBot/OpenClaw** | 企业微信 | ⭐⭐⭐ 1-2 周(个体户+ICP) | ✓ 零 | ✓ | ✓ | ✓ 合规 | ¥0-50(服务器)+ ¥0(备案)/ ¥600+ 个体户年费 |
| **C** | **公众号(订阅号 / 服务号)+ Webhook** | 微信公众号 | ⭐⭐ 几天(备案 + 域名) | ✓ 零 | ⚠ 受限(被动 5s / 主动 48h) | ✓ | ✓ 合规 | ¥0-50 / 服务号 ¥300/年 |
| **D** | **GeweChat / iPad 协议** | 个人微信 | ⭐⭐ 几小时 + 付费 token | ⚠ 中(同省 IP + 限频) | ✓ | ✓ | ⚠ 灰 | ¥50-100 token |
| **E** | **飞书 / 钉钉 bot(放弃微信)** | 飞书 / 钉钉 | ⭐ 当天 | ✓ 零 | ✓ | ✓ | ✓ 合规 | ¥0 |

### 2.A · WeChatFerry · 个人微信(快糙猛 · 推荐短期)

**架构**:

```
[微信(妻子手机)] → [家里 Windows + 微信 PC 客户端]
                          ↓ wcf hook 注入
                    [Python wcfbot 监听消息]
                          ↓ http call
                    [Linux beta(本机) Claude Code -p]
                          ↓ 输出
                    [wcfbot 反向调微信 → 发消息回去]
```

**优点**:
- 启动门槛最低 · 几小时跑通
- Claude Code 跑在本机 beta · 跟现在桌面 session 共享一台机器
- 个人微信即用 · 妻子不用装新 App

**缺点**:
- 需要**一台常开的 Windows 机**跑微信 PC 客户端(本地家用 PC / Windows VPS 都行)
- 微信 hook 灰色地带 · 封号风险中等(社区反馈:消息间隔 ≥5s + 日量 ≤200 条几乎无事 · 高频商业用必封)
- 微信版本绑定 · 微信更新可能需要重装 wcf

**关键开源项目**:
- [`lich0821/WeChatFerry`](https://github.com/lich0821/WeChatFerry) — C++ DLL hook · Python SDK 包装
- [`wangrongding/wechat-bot`](https://github.com/wangrongding/wechat-bot) — 基于 Wechaty 的 ChatGPT/Claude/DeepSeek 接入参考(可借鉴它的 LLM bot 架构,把 puppet 层换成 WCF)

### 2.B · 企业微信自建应用(合规首选 · 推荐长期)

**架构**:

```
[妻子手机 企业微信] → [微信网关]
                          ↓ webhook(已备案 https://xxx.your-domain.com/wecom/bot)
                    [LangBot / OpenClaw on Linux]
                          ↓ spawn claude -p / --resume
                    [Claude Code subprocess]
                          ↓ 完成
                    [LangBot 主动推送回企业微信]
```

**优点**:
- 100% 合规 · 微信永不封号(企业微信跟个人微信打通,妻子用普通微信就能收到企业微信消息)
- 多用户鉴权完整(自建应用可指定可见员工)
- 已有成熟框架 [LangBot](https://docs.langbot.app/zh/usage/platforms/wecom/wecombot) · 接 Claude / DeepSeek / Qwen / 多个 LLM
- 主动推送无 48h 限制(企业微信支持企业内消息发送)

**缺点**:
- **启动门槛高** · 需要:
  1. 注册个体工商户(500-1000 元一次 · 年费几百)或小微企业
  2. 用主体注册域名(¥50/年)
  3. ICP 备案(免费 · 但要 1-3 周通管局审核 · 期间不能开服务)
  4. 公网服务器 + 已备案域名做回调
  5. 企业微信自建应用配置(自助 1-2 小时)
- 一次性投入大 · 但是落地后非常稳

**关键参照**:
- [LangBot 企业微信自建应用接入](https://docs.langbot.app/zh/usage/platforms/wecom/wecombot)
- [华为云 OpenClaw 企业微信 bot 教程](https://support.huaweicloud.com/bestpractice-flexusl/flexusl_bp_0004.html)

### 2.C · 公众号 webhook(合规但功能受限)

**机制**:
- 订阅号:用户给公众号发消息 → 服务器 webhook 收到 → **5 秒内必须回复(微信限制)** · 否则用户看不到响应
- 服务号:同上,但可以用"客服消息"接口在 **48 小时内**主动推送(48 小时之外用户无回复就不能推)

**为什么不适合 Claude Code**:
- Claude Code 单次任务通常 10s-3min(读文件 / 跑测试 / 改代码 / 部署),远超 5s 超时
- 必须立刻回"任务已收到 · 稍后发结果",然后用客服消息接口推送 · 实现复杂度高
- 主动推送 48h 限制让"上次回复一周后 Claude 才跑完"这种异步场景失效

**适用场景**:
- 不适合作为 Claude Code 主入口
- 适合做"产品官方公众号 + 简单问答 bot" · 跟当前场景偏离

### 2.D · GeweChat / iPad 协议(中等门槛 + 付费 token)

**优点**:
- 不需要 Windows · Docker 部署即可
- iPad 协议比网页协议稳定

**缺点**:
- 需要**付费 token**(¥50-100/月 from 第三方)
- 需要**同省 IP**(IP 异地易封)
- 同样是灰色地带

**不推荐**:相比 WCF 没有显著优势,但要付费,且 IP 限制麻烦。

### 2.E · 飞书 / 钉钉 bot(放弃微信入口)

**优点**:
- 启动门槛最低 · 当天落地
- 100% 合规 · 个人就能创建机器人
- 多端可用(PC / 手机 / 网页)

**缺点**:
- **不是微信** · 妻子要装新 App · 平时不开
- 对话历史不在微信里,通知噪音问题

**适用场景**:你不在乎微信入口 / 你和妻子都已经在用飞书 / 钉钉。

## 3. Claude Code 接入方式(三种调用模式)

无论选哪个入口,Claude 的调用都有三种模式:

### 模式 1 · `claude -p "<prompt>"` 非交互单发(最简)

```bash
# 收到微信消息后
claude -p "用户问:dashboard 挂了 排查一下" \
       --allowedTools "Read,Bash,Edit" \
       --output-format json
```

- 每次新 session · 没历史 context · 但 prompt 里写清"项目在 /home/finance/financial-management"它就能干活
- 优点:无状态 · 简单 · 不用管 session 持久化
- 缺点:每次"从头来",对来回多轮对话不友好

### 模式 2 · `claude --resume <session-id>` 恢复 session(进阶)

```bash
# 第一次
session_id=$(claude -p "..." --output-format json | jq -r '.session_id')
# 把 session_id 跟"用户 wxid"绑定,存到 SQLite

# 用户下次再发消息
claude -p "继续刚才的" --resume "$session_id"
```

- 每个微信用户绑一个长 session · 真正"延续上下文"
- 优点:多轮对话连贯
- 缺点:session 文件需要管理(磁盘大小 / 过期清理)

### 模式 3 · Claude Agent SDK + 程序化调用(最完整)

直接用 `@anthropic-ai/claude-agent-sdk` (Node) 或 `claude-agent-sdk` (Python) 库:
- 拿到流式 token 输出 · 可分块推到微信
- 注册 hooks(PostToolUse / Stop / Notification)做执行进度推送
- 鉴权 / 沙盒 / 工具白名单 都更可控

参考实现:[`RichardAtCT/claude-code-telegram`](https://github.com/RichardAtCT/claude-code-telegram) · 这是个**几乎一模一样的需求**(从 Telegram 发消息给 Claude Code · 有 session 持久化 + 多用户鉴权 + FastAPI webhook)· 把 Telegram 换成微信 channel 即可。

## 4. 推荐路径

### 短期 · 1-3 天落地(WCF · 验证场景)

```
1. 一台 Windows 机(本地 PC 或 ¥30/月 Windows VPS)
2. 安装微信 PC 客户端 + WCF
3. Python 写消息处理脚本:
   - 监听妻子/我的微信消息
   - 鉴权(只接受我俩的 wxid)
   - 调 ssh beta-host "claude -p '<prompt>' --resume <sid>"
   - 把 stdout 反向发消息回微信
4. 跑起来用 2 周 · 看实际体验
```

**Pro tip**:第一阶段不要追求多用户 session 隔离,你和妻子用同一个 Claude session(`--continue` 总是恢复最近一次)· 测试可行性后再加 session-per-user。

### 长期 · 2-4 周(企业微信 · 真正合规生产)

```
1. 注册个体工商户(¥500-1000 · 找代办 1-2 周)
2. 用主体备案域名(免费 · 通管局 1-3 周)
3. 在企业微信注册"小微企业"(免费 · 当天)
4. 部署 LangBot 到 prod(Docker 一键 · 1 小时)
5. 自建应用 + 配置 corpId/agentId/Secret/Token/EncodingAESKey
6. LangBot 配置 spawn `claude -p` (或 SDK)
7. 妻子被加到自建应用可见范围 · 用普通微信即可收到企业微信消息
```

**先做短期 WCF 验证 · 跑通后再决定要不要走长期合规路径**(看实际用得勤不勤 · 不勤就 WCF 永远够用)。

## 5. 关键风险与红线

| 风险 | 缓解 |
|---|---|
| **个人微信封号**(WCF / iPad 协议)| 用小号 · 不用主号 · 消息间隔 ≥5s · 日 ≤200 条 · 不发敏感内容 |
| **服务器被滥用**(webhook 暴露后 unauthenticated 调用让 Claude 跑任意指令)| HMAC 签名验证 · IP 白名单 · 用户 wxid allowlist · Claude `--allowedTools` 限制 · 沙盒 Docker |
| **Claude 跑出 destructive 命令** | `--allowedTools Read,Edit,Bash`(白名单工具)· 禁 `Write` 大改文件 · 重要操作回微信确认再执行 |
| **微信收消息延迟 / 漏消息** | 心跳重连 · 失败重试 · 队列削峰 |
| **prod 数据泄露给 LLM** | 走 prod 数据时 LLM 路径默认 disable · 仅在 beta 跑 Claude · prod 只接受"只读查询"(类似 [[feedback_prod_backward_compat]] 红线) |
| **API key 在 Windows 客户端**(WCF 方案)| Windows 机只做消息桥接 · 不放 API key · Claude Code 跑在 Linux beta · SSH 到 Linux 触发 |

## 6. 启动 checklist(选定方案后)

### 选了 A(WCF · 短期)

- [ ] 准备一台 Windows 机(本地 PC 或 Windows VPS · 推荐用小号微信而非主号)
- [ ] 安装微信 PC 客户端(WCF 支持的版本 · 查 WCF 文档当前对接版本)
- [ ] 装 WCF + Python wcfbot
- [ ] Linux beta 上 `claude` CLI 已就绪(SSH key from Windows 机)
- [ ] Python 桥接脚本:
  - [ ] 鉴权(my wxid + wife wxid allowlist)
  - [ ] 用户级 session 持久化(SQLite 存 wxid → claude session_id 映射)
  - [ ] 把消息 `claude -p "<prompt>" --resume <sid>` 转给 Claude
  - [ ] 流式 stdout 分块推回(每收到 200 字推一条避免微信限流)
  - [ ] 任务结束推「✓ 完成」/「✗ 失败 · 见日志」
- [ ] Claude Code `--allowedTools` 限制写入工具
- [ ] 试 1 周 · 监控封号风险

### 选了 B(企业微信 · 长期)

- [ ] 找代办注册个体工商户(¥500-1000 · 1-2 周)
- [ ] 买公网 Linux 服务器(¥40-100/月)
- [ ] 用主体注册域名(¥50/年)
- [ ] 申请 ICP 备案(免费 · 1-3 周通管局)
- [ ] 注册企业微信(小微企业账号 · 当天)
- [ ] 部署 [LangBot](https://docs.langbot.app/zh/usage/platforms/wecom/wecombot)
- [ ] 创建自建应用 · 配置 corpId/agentId/Secret/Token/EncodingAESKey
- [ ] 把妻子加入可见员工
- [ ] LangBot 配置自定义 LLM 后端 → 调用本机 `claude -p`(LangBot 默认接 ChatGPT/Claude API · 我们改成 spawn Claude Code 子进程)
- [ ] HMAC + IP 白名单 + wxid allowlist 三层鉴权
- [ ] 验证主动推送 · 验证多轮对话

## 7. 参考资料

**Claude Code 端**:
- [Run Claude Code programmatically](https://code.claude.com/docs/en/headless) · 官方文档
- [Claude Code Remote Control(2026-02 GA)](https://simonwillison.net/2026/Feb/25/claude-code-remote-control/) · Simon Willison 讲解
- [`claude-code-telegram`](https://github.com/RichardAtCT/claude-code-telegram) · 几乎等同需求的 Telegram 实现 · 可改造为微信
- [`claude-did-this/claude-hub`](https://github.com/claude-did-this/claude-hub) · webhook → Claude Code 容器化执行参考
- [Intercept and control agent behavior with hooks](https://code.claude.com/docs/en/agent-sdk/hooks) · 出站推送进度

**微信 channel 端**:
- [WeChatFerry(WCF)](https://github.com/lich0821/WeChatFerry) · 个人微信 hook 框架
- [Wechaty 文档(中文)](https://wechaty.gitbook.io/wechaty/zh)
- [wangrongding/wechat-bot](https://github.com/wangrongding/wechat-bot) · Wechaty + ChatGPT/Claude/DeepSeek
- [LangBot 企业微信文档](https://docs.langbot.app/zh/usage/platforms/wecom/wecombot)
- [华为云 OpenClaw 企微教程](https://support.huaweicloud.com/bestpractice-flexusl/flexusl_bp_0004.html)
- [微信 AI 机器人全系列方案(知乎)](https://zhuanlan.zhihu.com/p/1891472259190862807)

## 8. 我的最终建议

**先做方案 A(WCF · 几小时落地)**:
- 用小号微信 + 一台常开 Windows
- 验证「我从微信发任务 · Claude 在 beta 干活 · 结果推回微信」这条链路真的能用
- 用 1-2 周看频次 · 看体验

**频次低**(每周 ≤ 几次):WCF 永远够 · 不用升级
**频次高 / 妻子也开始用 / 想做"prod 出问题 → 自动告警 → AI 排查 → 推回微信"这种生产级闭环**:再走方案 B 办主体 + 备案 · 切到企业微信

不推荐:**公众号**(5s 超时硬伤)、**GeweChat**(付费 + IP 限制)、**Telegram**(国内代理不稳)、**飞书钉钉**(不是微信)。
