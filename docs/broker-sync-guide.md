# 券商同步 · 凭据获取图文向导(富途 / 老虎)

> 应用内同款向导:登录后打开 **/help/broker-sync**(管理 → 数据源接入 → ⑥ 券商同步 → 「不知道怎么填?看图文教程」),那里带示意图。本文是同一份步骤的仓库版。

配好后,系统每天(或手动点一下)**只读**把券商里的持仓 + 各币种现金拉进账房,不用再手抄。

## 先说安全

- 本系统**只查询、永不下单 / 划转**:富途只连本地网关查数据、从不解锁交易(`unlockTrade`);老虎只调查询接口。
- 但你拿到的**私钥 / 网关本身权限较大**,请当密码一样保管;担心时随时能在券商后台吊销 / 重置。
- 凭据在本机加密保存,页面上**私钥永不回显**。

---

## 老虎 Tiger(相对简单 · 全程网页 · 免装网关)

**开始前**:先有一个老虎证券账户并已入金(开放平台对已入金用户免费)。

1. **注册成为开发者** —— 打开 <https://developer.itigerup.com/>,用手机号 + 验证码登录 / 注册(和你的老虎账户同一手机号)。
2. **记下 Tiger ID 和 资金账号** —— 在「开发者信息」页:
   - `Tiger ID`:一串数字 → 填我们的 **tiger_id**。
   - `资金账号`:环球账号 U 开头(如 `U12300123`)、综合账号 5–10 位数字 → 填我们的 **交易账户**(留空 = 默认账户)。
   - `牌照 License`(如 TBSG/TBHK):港股才用到,先记着。
3. **生成 RSA 密钥对,复制私钥** —— 点「生成 RSA 密钥对」。
   - ⚠️ **最关键、别关页面**:私钥只显示这一次、刷新就没了、老虎服务器也不存。立刻整段复制。没存到就点「重新生成」。
   - 我们是 Java 程序 → 选 / 保存 **PKCS#8 格式**(页面通常有 Java / Python 两种,选 Java 那份)。
4. **回本系统填写并测试** —— 管理 → 数据源接入 → ⑥ 券商同步 → 老虎 Tiger:

   | 我们的输入框 | 填什么 |
   |---|---|
   | tiger_id | 第 2 步的 Tiger ID |
   | RSA 私钥 | 第 3 步复制的私钥(PKCS#8) |
   | 交易账户 | 第 2 步的资金账号(可留空) |

   点「保存券商配置」→「测试老虎连接」,显示成功即通。再到证券账户「持仓 → 券商自动同步」点关联。

官方参考:[准备工作(含截图)](https://quant.itigerup.com/openapi/zh/python/quickStart/prepare.html) · [API 文档](https://docs.itigerup.com/)

---

## 富途 Futu(需常驻一个「OpenD」网关程序)

**开始前**:有富途牛牛账户并开通对应市场权限。富途机制:官方给一个叫 **FutuOpenD** 的小网关,你在自己电脑运行它、用富途账号登录;我们的系统连这个本地网关只读取数据。**网关关掉就同步不了**,最好放一台常开的电脑 / 服务器。

1. **下载 FutuOpenD** —— <https://www.futunn.com/download/openAPI>。有「可视化(带界面)」和「命令行」两种,**入门选可视化版**。支持 Windows / Mac / Linux。
2. **运行 OpenD,用富途账号登录** —— 输入富途账号 + 登录密码。首次登录可能要完成一次问卷 + 协议确认。
3. **记下地址和端口** —— OpenD 界面显示监听地址,默认 `127.0.0.1` 端口 `11111`。管理 → 数据源接入 → ⑥ 券商同步 → 富途 Futu:

   | 我们的输入框 | 填什么 |
   |---|---|
   | FutuOpenD 地址 | OpenD 与本系统同机 → `127.0.0.1`;不同机 → 那台机器的内网 IP |
   | OpenD 端口 | `11111`(除非你在 OpenD 改过) |

   保存后「测试富途连接」验证。再到证券账户「持仓 → 券商自动同步」点关联。

**两个提醒**:
- 查持仓 / 现金**不需要「解锁交易」**:解锁只在下单时用,我们永不下单,不用输交易密码。
- OpenD 装云服务器时别把监听设成 `0.0.0.0` 直接开公网端口 —— 用内网 / SSH 隧道更安全。

官方参考:[OpenAPI 文档](https://openapi.futunn.com/futu-api-doc/intro/intro.html) · [OpenD 常见问题](https://openapi.futunn.com/futu-api-doc/qa/opend.html)

---

## 富途 OpenD 部署到哪(自托管在服务器时)

**先理解一件事**:富途没有「拿 key 直连的云端 API」——`futu-api` SDK 只能连 **FutuOpenD 网关**,网关再连富途。所以富途要生效,必须有一个 OpenD **一直开着、且本系统能通过 TCP(host:port)连到它**。老虎则是直连云端 API、不需要任何网关——如果你嫌 OpenD 麻烦,**老虎是零运维的那条路**。

> ⚠️ **共同前提**:无论哪种拓扑,都要先在一台**有桌面的机器**上用**可视化版 OpenD 登录一次**,完成富途要求的**首次问卷 + 协议确认**;之后 headless(命令行 / 容器)才能正常登录。

按你的部署形态三选一:

### 拓扑 A · 与 app 同机(systemd 部署推荐)
app 用 systemd 跑在某台 Linux 服务器上 → 把命令行版 OpenD 也常驻在同机,配 `127.0.0.1:11111`。
- 用模板 [`deploy/futu-opend.service.example`](../deploy/futu-opend.service.example) → `cp` 成 `/etc/systemd/system/futu-opend.service`,账号密码放 `chmod 600` 的 `/etc/futu-opend.env`。
- `systemctl enable --now futu-opend`,`ss -ltn | grep 11111` 确认监听。
- 管理页 ⑥ 富途填 `127.0.0.1` / `11111`。
- 安全:`api_ip=127.0.0.1` 只本机可连,别设 `0.0.0.0` 开公网。

### 拓扑 B · docker compose sidecar
app 走 docker compose 时,用可选覆盖文件把 OpenD 挂成同网络的 sidecar。

> **先看这条**:富途**没有官方 OpenD 镜像**,也没有现成可拉的 —— 这条路要你**自备镜像**。
> 做不到就走**拓扑 C**(OpenD 放家里常开的机器 + 反向隧道),别在这里耗。

**① 备镜像**:自己写 Dockerfile 拉富途官方命令行包构建,**把 gtk3 打进去**;并先在有桌面的机器用可视化版 OpenD 登录一次,过掉首次问卷 + 协议确认(否则命令行版起不来)。

**② 配 `.env`**(三个变量缺一个都起不来,模板见 [`.env.example`](../.env.example)):
```
FUTU_OPEND_IMAGE=你自备的镜像:tag
FUTU_ACCOUNT=富途账号
FUTU_PWD_MD5=密码的MD5        # printf '你的密码' | md5sum
```

**③ 合并覆盖文件启动**(默认的 `docker compose up` 不加载它,不影响正常部署):
```
docker compose -f docker-compose.yml -f deploy/futu-opend.compose.yml up -d
```

**④ 管理页** ⑥ 富途填服务名 `opend` / `11111`(compose 内网直连,不对外发布端口)。

跳过第 ② 步会看到 `required variable FUTU_ACCOUNT is missing a value` —— 这是**预期行为**(没配账号就不该起 sidecar),不是故障;补上变量重跑即可。更多说明见 [`deploy/futu-opend.compose.yml`](../deploy/futu-opend.compose.yml) 顶部。

### 拓扑 C · OpenD 在家用机 / NAS + 反向隧道
不想在云服务器上放券商登录态,就把 OpenD 跑在家里常开的机器,用 SSH 反向隧道把它的 11111 转到 prod 本地:
```
# 在家用机执行(把家里的 11111 反向暴露到 prod 的 127.0.0.1:11111)
ssh -N -R 11111:127.0.0.1:11111 用户@prod服务器IP
```
- 管理页 ⑥ 富途填 `127.0.0.1` / `11111`(prod 上看到的是隧道口)。
- 隧道断了就同步不了 → 建议用 `autossh` 保活。

**共同点**:三种都要 OpenD 常开;查持仓 / 现金**不需要解锁交易**(我们永不下单)。嫌重就先用老虎。

