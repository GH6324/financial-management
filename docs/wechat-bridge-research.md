# 微信入口接入 Claude Code Session · 调研文档(v2)

> 2026-05-14 · 目标:从微信发消息直接对接**当前 beta 上跑的 Claude Code session**(不是 stateless `claude -p`)· Claude 看到消息 → 在 prod / beta 协助排查实现部署 → 结果推回微信。
>
> v1 跑偏走 WCF hack 路线 · 本 v2 基于「微信官方 + Anthropic 官方 + 开源桥接」三层路径重写。

## 0. 结论先行

**走全官方路径**:
- **微信端 · `ClawBot` 插件**(腾讯官方 · 2026-03-22 上线 · iOS 8.0.70+ / 安卓 8.0.68+)
- **Anthropic 端 · `Claude Code Channels`**(2026-03-20 GA · research preview · 基于 MCP 协议 · 官方支持 Telegram / Discord / iMessage)
- **桥接 · `Johnixr/claude-code-wechat-channel`** 开源 MCP plugin(2026-03-23 · 270 stars · 把微信 ClawBot 的 ilink API 桥到 Claude Code Channels)

**落地难度**:30 分钟 · 全是官方 SDK + 一行命令安装 · 0 封号风险 · 0 备案 · 0 hack。

**完整数据流**:

```
[妻子/你 iPhone 微信 · ClawBot 入口]
        ↓ 发消息
[微信 ilink API(腾讯官方)]
        ↓ HTTP 长轮询
[claude-code-wechat-channel MCP plugin(跑在 beta · Bun runtime)]
        ↓ MCP Channel 协议
[Claude Code Session(beta · 用 --channels 启动)]
        ↓ Claude 干活(读文件 / Edit / Bash / 跑 mvn / 部署)
        ↓ wechat_reply tool
[ilink API]
        ↓
[妻子/你 iPhone 微信] 收到回复
```

## 1. 前置条件(全部 ✓ 就开干)

| 前置 | 你的状态 | 处理 |
|---|---|---|
| Claude Code v2.1.80+ | ✓ 已用 | `claude --version` 确认 · 不够就 `npm i -g @anthropic-ai/claude-code@latest` |
| claude.ai Pro 或 Max 订阅 | ✓ 已用 Pro(你在跑 Claude Code) | — |
| Bun runtime 或 Node 18+ | 大概率没 Bun | `curl -fsSL https://bun.sh/install \| bash`(几秒) |
| iPhone 微信 8.0.70+(妻子用) | 应自动更新 | 我 → 关于微信 看版本 |
| iPhone 微信 → 我 → 设置 → 插件 → 「ClawBot」可见 | **灰度** · 可能没放 | 见 §3「灰度未覆盖时的官方旁路」 |
| 一台 24h 不关机的机器跑 Claude Code | ✓ beta(本机) | — |

## 2. 关键事实链(为什么这条路有保障)

| 时间 | 事件 | 影响 |
|---|---|---|
| 2025-11 | OpenClaw 开源(Peter Steinberger)· 上 Telegram/Discord/WhatsApp/iMessage/Slack channel | 全球爆火 · GitHub 30 万星 |
| 2026-03-19 | 腾讯微信团队官方发布 `@tencent-weixin/openclaw-weixin` 插件 · 给 OpenClaw 提供原生微信 channel | 微信首次给"AI agent"开**官方**入口 |
| 2026-03-20 | **Anthropic 发 Claude Code Channels**(`claude --channels` · MCP 协议 · 官方支持 Telegram / Discord)· VentureBeat 标题「OpenClaw killer」 | Claude Code 自家也开了 channel 生态 |
| 2026-03-23 | GitHub 用户 Johnixr 让 Claude 读了 `@tencent-weixin/openclaw-weixin` 源码 + 写了 `claude-code-wechat-channel` MCP plugin 把微信 ClawBot 接到 Claude Code | **本文核心方案** |
| 2026-03-29 | 企业微信也加 OpenClaw 官方 CLI(企微版) | 商业生产可接 |

意思:不是某个 hack 项目 · 微信和 Anthropic 都是官方一级支持 · 桥接也只是把两条官方协议串起来。

## 3. iPhone 微信 ClawBot 入口启用

### 路径

```
iPhone 微信 → 我 → 设置 → 插件 → 「ClawBot」开关 → 启用
```

### 灰度未覆盖时的官方旁路(关键 · 几乎不用等)

社区已验证可直接用官方 CLI 强制初始化(微信团队官方包,不是 hack):

```bash
# 你的 beta 上跑(本身就是 OpenClaw 微信团队官方 CLI)
npx -y @tencent-weixin/openclaw-weixin-cli@latest install
# 出现二维码 · 妻子 iPhone 微信扫码 · 即使原本「插件」里没看到 ClawBot
# 也会立刻在妻子微信里多出一个「微信 ClawBot」会话
```

但**我们不走 OpenClaw 路径**,因为我们要对接的是 Claude Code session,不是 OpenClaw。我们走下面的 Johnixr 桥接,它复用同一套 ilink 协议但目标是 Claude Code。

## 4. 桥接 plugin 选型

GitHub 上已有几个等价实现 · 都是 2026-03 集中冒出:

| 项目 | Stars | 备注 |
|---|---|---|
| [**`Johnixr/claude-code-wechat-channel`**](https://github.com/Johnixr/claude-code-wechat-channel) | 270 ⭐ | **原创** · 2026-03-23 · README 最完整 · 推荐 |
| [`Richard-Zhang1019/claude-code-plugin-wechat`](https://github.com/Richard-Zhang1019/claude-code-plugin-wechat) | — | 中文 README 详细 · 提供 `login/logout/status/start` CLI |
| [`m1heng/claude-plugin-weixin`](https://github.com/m1heng/claude-plugin-weixin) | — | 同款实现 |
| [`Wechat-ggGitHub/wechat-claude-code`](https://github.com/Wechat-ggGitHub/wechat-claude-code) | — | 实现成 Claude Code Skill 包 |
| [`AceDataCloud/WeChatClaudeCode`](https://github.com/AceDataCloud/WeChatClaudeCode) | — | 同款 |
| [`op7418/Claude-to-IM-skill`](https://github.com/op7418/Claude-to-IM-skill) | — | 多端(Telegram / Discord / 飞书 / Lark)· 想统一 IM 时用 |
| [`ark338/codex-wechat-channel`](https://github.com/ark338/codex-wechat-channel) | — | 是 Johnixr fork · 但已改为 OpenAI Codex(不要用) |

→ **首选 Johnixr 原版**(成熟 · 270 stars · 主线维护)。

## 5. 落地 step-by-step(目标 30 分钟)

```bash
# 在 beta(本机)上跑 · finance 用户

# 1. 装 Bun runtime
curl -fsSL https://bun.sh/install | bash
source ~/.bashrc

# 2. 确认 Claude Code 版本 ≥ 2.1.80
claude --version
# 不够升一下
# npm i -g @anthropic-ai/claude-code@latest

# 3. 拉桥接 plugin
cd ~
git clone https://github.com/Johnixr/claude-code-wechat-channel.git
cd claude-code-wechat-channel
bun install
# 或 npm install

# 4. 扫码登录微信(妻子或你的 iPhone 微信)
npx claude-code-wechat-channel setup
# 终端出现二维码 → 妻子 iPhone 微信「扫一扫」→ 确认
# 凭证存到 ~/.claude/channels/wechat/account.json

# 5. 生成 MCP 配置
npx claude-code-wechat-channel install
# 生成 .mcp.json:
# { "mcpServers": { "wechat": { "command": "bun", "args": ["/path/to/dist/channel.js"] } } }

# 6. 启动 Claude Code 带 wechat channel(在 financial-management 项目目录里跑)
cd /home/finance/financial-management
claude --dangerously-load-development-channels server:wechat
# session 起来后保持运行 · 用 tmux / systemd 守护

# 7. 妻子 iPhone 微信 → 「微信 ClawBot」会话 → 发消息测试
# Claude 在 beta 直接收到 + 处理 + wechat_reply 回去
```

### 用 systemd 守护(让 session 不死)

```ini
# /etc/systemd/system/claude-wechat-channel.service
[Unit]
Description=Claude Code with WeChat Channel
After=network.target

[Service]
User=finance
WorkingDirectory=/home/finance/financial-management
ExecStart=/home/finance/.bun/bin/bun /home/finance/.claude/local/node_modules/@anthropic-ai/claude-code/cli.mjs --dangerously-load-development-channels server:wechat
Restart=on-failure
RestartSec=10
Environment="ANTHROPIC_API_KEY=..."

[Install]
WantedBy=multi-user.target
```

(具体 ExecStart 路径以 `which claude` 或 `npm root -g` 查)

## 6. 当前已知限制(都不致命)

| 限制 | 影响 | 处理 |
|---|---|---|
| **iOS only** · 安卓灰度更慢 | 妻子如果用安卓需等灰度 | 灰度未到时用 `@tencent-weixin/...` CLI 强制激活(§3) |
| **单 ClawBot 只能连一个 Claude Code 实例** | 你和妻子如果都想各开各的 session 不行 | 共用一个 session · 像现在桌面 CLI 一样 |
| **不支持微信群聊** | 群里 @ClawBot 不响应 | 只能私聊 · 也是出于安全 |
| **不支持流式输出** | Claude 跑 30 秒,微信里 30 秒没动静 | 让 Claude 在长任务时主动发"已开始 X / 正在 Y / 完成" |
| **Mac 端微信看不到 ClawBot** | 只能 iPhone 操作 | 平时也只在手机上用 · 不痛 |
| **`--dangerously-load-development-channels` flag** | 因 wechat 不在 Anthropic 官方 allowlist | 等 Anthropic 把 wechat 收编(估计很快)就能用普通 `--channels` |
| **claude.ai Pro/Max 订阅必须** | 不能用纯 API key | 你已有 Pro · 妻子用你的 |
| **Claude Code session 一关 channel 就断** | tmux/systemd 守住进程 | §5 已配 systemd 守护 |

## 7. 安全模型

Claude Code Channels 设计的三层安全:

1. **Plugin allowlist** · 研究预览期只允许 Anthropic 批准的 plugin(微信目前需 `--dangerously-load-development-channels` 绕开 · 后续应会进 allowlist)
2. **Pairing-code authentication** · 只有配对成功的微信号能跟你的 session 对话 · 别人发的消息**静默丢弃**(不会被 Claude 看到)
3. **No inbound connections** · plugin 是出站长轮询 · 不开任何服务端口 · 不需要公网 IP / 不用穿透

**Claude Code 工具白名单**(独立于 channel · 强烈建议设):

```bash
claude --dangerously-load-development-channels server:wechat \
       --allowedTools "Read,Edit,Bash(git*,mvn test,sudo systemctl restart finance)"
```

→ 只允许读 / 编辑 / git 操作 / 跑测试 / 重启服务 · 禁止 `rm` / 任意 sudo / write 大改。

## 8. 整体成本

| 项 | 金额 |
|---|---|
| 微信端 | ¥0 |
| Anthropic Claude Code Pro 订阅 | 你已付 |
| beta 机器 | 你已有 |
| 桥接 plugin | 开源 ¥0 |
| **合计** | **¥0 增量** |

## 9. 替代 / 降级方案(如果走不通了再看)

按"放弃顺序"列出:

### 降级 1 · 走 OpenClaw 内置 Claude API(不对接 Claude Code session)

如果 Johnixr 桥接 plugin 在 Claude Code v2.x 上有兼容问题:
- 装 [OpenClaw 主程序](https://openclaw.dev) · 配 Anthropic API key · 用 `@tencent-weixin/openclaw-weixin` 官方插件接入微信
- 你失去"对接当前 Claude Code session"的卖点(变成 stateless API 调用)· 但仍是官方路径

### 降级 2 · 走企业微信自建应用 + LangBot(合规但启动慢)

详见 v1 调研段落(已废) · 适合长期合规商业场景 · 需办个体户 + ICP 备案 · 1-2 周。

### 降级 3 · 走 WeChatFerry(个人微信 hook · 灰)

完全不推荐了 · 既然官方路径完全可用 · 没理由走 hack。

## 10. 我的最终建议

**直接走方案(§5 的 7 步)**:

1. 今晚 30 分钟跑通 demo:Bun + Johnixr plugin + 微信扫码 + 系统提示打通
2. 用 1-3 天:你和妻子从微信发常见消息 · 看 Claude 在 beta 实际处理是否到位
3. 加 systemd 守护 + 工具白名单收紧(§5 + §7)
4. 把这个调研文档存进 prd/ 或 tech-design/ 后,如果体验好就升级为正式 feature

**为什么这次很有底气**:
- v1 我把 OpenClaw 当普通框架 · 完全跑偏走了 hack 路径
- v2 是**两个官方一级支持**(腾讯 + Anthropic)+ 一个 270 star 桥接 · 启动门槛比 OpenClaw 自己接入还低
- 你担心的"通过 hack 渠道"这条 · 在 2026-03 之后完全不需要了 · 直接走官方

---

## 11. 参考资料

**Anthropic 官方**:
- [Claude Code Channels GA · 2026-03-20](https://venturebeat.com/orchestration/anthropic-just-shipped-an-openclaw-killer-called-claude-code-channels)
- [Claude Code Channels: Telegram, Discord & iMessage(claudefa.st)](https://claudefa.st/blog/guide/development/claude-code-channels)
- [Claude Code Channels Telegram Setup + OpenClaw 对比](https://www.shareuhack.com/en/posts/claude-code-channels-telegram)
- [Claude Code Channels VPS 部署指南](https://danubedata.ro/blog/claude-code-channels-vps-telegram-discord-2026)

**微信官方**:
- [企业微信支持接入 OpenClaw(work.weixin.qq.com)](https://work.weixin.qq.com/nl/index/openclaw)
- [How to Integrate OpenClaw to WeCom Smart Robot · WeCom Help](https://open.work.weixin.qq.com/help2/pc/21657)
- [iOS 8.0.70 ClawBot 灰度上线 · 新浪](https://cj.sina.com.cn/articles/view/7857201856/1d45362c001903hg4k)
- [QClaw 微信 ClawBot 配置文档(腾讯出品)](https://qclaw.qq.com/docs/206087648449069056.html)

**关键开源项目**:
- [`Johnixr/claude-code-wechat-channel`](https://github.com/Johnixr/claude-code-wechat-channel) · 主推
- [`Richard-Zhang1019/claude-code-plugin-wechat`](https://github.com/Richard-Zhang1019/claude-code-plugin-wechat) · 中文备选
- [`m1heng/claude-plugin-weixin`](https://github.com/m1heng/claude-plugin-weixin)
- [`op7418/Claude-to-IM-skill`](https://github.com/op7418/Claude-to-IM-skill) · 多 IM 桥接

**社区解读**:
- [微信官方接入龙虾,我顺手给接上了 Claude Code · 53AI](https://www.53ai.com/news/Openclaw/2026032373016.html) — 关键综合文章
- [微信官方上线 ClawBot 插件 · 知乎](https://zhuanlan.zhihu.com/p/2019118125963059386)
- [一件分内的小事:关于微信接入 OpenClaw 的 10 条冷思考 · 36 氪](https://36kr.com/p/3733780366999554)
- [没灰度到也可以用上微信 ClawBot · 知乎](https://zhuanlan.zhihu.com/p/2019721011319297398)
- [OpenClaw 个人微信接入教程(2026)· ClawBot 灰测使用指南](https://tbbbk.com/openclaw-wechat-clawbot-guide-2026/)
