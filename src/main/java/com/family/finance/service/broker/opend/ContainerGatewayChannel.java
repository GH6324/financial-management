package com.family.finance.service.broker.opend;

import com.family.finance.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通道实现 · 富途网关跑在一个<b>可选的</b>容器里(v1.17 · compose profile: futu)。
 *
 * <p><b>为什么不是直接连它的控制口</b>:OpenD 的 telnet 控制口<b>没有任何鉴权</b> —— 连上就能重登、
 * 发验证码、退进程。所以那个口只绑网关容器内的 {@code 127.0.0.1},app 连不到它。
 * app 与网关容器共挂一个小卷({@link AppProperties#futuCtlDir()},默认 {@code /ctl}),
 * 通过文件下指令:<b>文件权限就是鉴权</b>,不必再发明一套令牌协议。</p>
 *
 * <p>报文是 {@code key=value} 而不是 JSON —— 网关镜像里没有 {@code jq},bash 解析 JSON 会脆;
 * 为解析 JSON 往那个镜像里塞 python3 又与"小到能读完"相悖。</p>
 *
 * <p><b>安装不由 app 做</b>:下载/校验哈希/解包都在网关容器的 entrypoint 里(它自带我们核对过的
 * 哈希清单,不依赖 app 就能自检)。所以本通道的 {@code canInstall=false},向导页据此不显示安装步骤。</p>
 */
@Component
@Slf4j
public class ContainerGatewayChannel implements OpendChannel {

    private final Path ctlDir;
    private final String gatewayHost;
    private final AtomicLong seq = new AtomicLong(System.nanoTime() / 1_000_000);

    /** status 文件多久没更新就认为网关不在了(control-loop 每 ~10 秒刷一次) */
    private static final Duration STALE = Duration.ofSeconds(90);

    public ContainerGatewayChannel(AppProperties props) {
        this.ctlDir = Path.of(props.futuCtlDir());
        this.gatewayHost = props.futuGatewayHost();
    }

    @Override public Kind kind() { return Kind.CONTAINER; }

    /**
     * 网关有没有被启用过 —— 判据是<b>网关容器写过 status 文件</b>,不是"控制目录在不在"。
     *
     * <p>为什么不能看目录:app 容器必须<b>无条件</b>挂这个共享卷(否则启用网关后 app 没法下指令,
     * 而 compose 不支持条件挂载),所以目录对容器部署永远存在 —— 拿它当判据会让"未启用"永远探不出来。
     * status 文件只有网关容器的 entrypoint / control-loop 会写,它在 = 网关真的起过。</p>
     */
    public boolean enabled() { return Files.isRegularFile(ctlDir.resolve("status")); }

    /** 控制目录本身在不在(compose 有没有把共享卷挂进来)。 */
    public boolean ctlMounted() { return Files.isDirectory(ctlDir); }

    /** 网关是不是活着(status 文件新鲜)。 */
    public boolean alive() {
        Map<String, String> st = readStatus();
        String ts = st.get("ts");
        if (ts == null) return false;
        try {
            return Instant.parse(ts).isAfter(Instant.now().minus(STALE));
        } catch (Exception e) { return false; }
    }

    /** 页面显示的启用命令(用户只需复制这一条)。 */
    public static final String ENABLE_COMMAND = "docker compose --profile futu up -d";

    @Override
    public Caps caps() {
        boolean up = enabled() && alive();
        boolean canInstall = false;    // 下载/校验哈希/解包都在网关容器的 entrypoint 里(它自带清单)
        boolean canUpload = false;     // 离线塞包这条退路在容器里是 docker cp,不从页面做
        boolean canLogin = up;
        boolean canRelaySms = up;
        boolean canStop = false;       // 容器生命周期归 docker compose,页面不越权代劳
        boolean showTerminal = true;   // 终端显示网关状态(完整日志在 docker logs)
        boolean needsEnable = !up;     // 没起来 → 页面给启用命令,而不是甩一段自己打包镜像的教程
        return new Caps(canInstall, canUpload, canLogin, canRelaySms, canStop, showTerminal,
                needsEnable, ENABLE_COMMAND);
    }

    @Override
    public Target target() {
        // API 通道加密:私钥由网关容器首启生成在共享卷里,app 用同一把
        return new Target(gatewayHost, apiPort(), Files.isRegularFile(rsaKeyFile()));
    }

    /** 共享卷里的 API 私钥(网关容器生成,app 只读)。 */
    public Path rsaKeyFile() { return ctlDir.resolve("opend.pem"); }

    private int apiPort() {
        try { return Integer.parseInt(readStatus().getOrDefault("apiPort", "11111").trim()); }
        catch (Exception e) { return 11111; }
    }

    @Override
    public synchronized Status status() {
        Map<String, String> st = readStatus();
        if (!enabled()) {
            return new Status(Phase.NOT_INSTALLED, null,
                    "富途网关还没启用(这是个可选组件)· 在服务器上执行:docker compose --profile futu up -d",
                    apiPort(), false, kind().name(), tail());
        }
        if (!alive()) {
            return new Status(Phase.STOPPED, st.get("version"),
                    "网关容器没在跑(或刚起来还没就绪)· 状态最后更新:" + st.getOrDefault("ts", "未知"),
                    apiPort(), false, kind().name(), tail());
        }
        Phase phase = parsePhase(st.get("phase"));
        return new Status(phase, st.get("version"), st.getOrDefault("message", ""), apiPort(),
                true, kind().name(), tail());
    }

    static Phase parsePhase(String s) {
        if (s == null) return Phase.NOT_INSTALLED;
        try { return Phase.valueOf(s.trim()); } catch (Exception e) { return Phase.NOT_INSTALLED; }
    }

    /** 读 key=value 状态文件(网关容器写的)。 */
    Map<String, String> readStatus() {
        Map<String, String> m = new LinkedHashMap<>();
        Path f = ctlDir.resolve("status");
        if (!Files.isRegularFile(f)) return m;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int i = line.indexOf('=');
                if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1));
            }
        } catch (IOException e) { log.debug("[opend] 读网关状态失败: {}", e.toString()); }
        return m;
    }

    @Override
    public Deps checkDeps() {
        // 依赖体检是网关容器自己的事(它的基底我们选定并实测过 ldd 零缺失)
        return new Deps(true, List.of(), "网关容器自带运行环境,不需要你在宿主上装任何库");
    }

    @Override
    public SelfCheck selfCheck() {
        List<CheckItem> items = new ArrayList<>();
        boolean mounted = ctlMounted();
        boolean started = enabled();
        items.add(new CheckItem("控制卷已挂载", mounted, false,
                mounted ? ctlDir + " 在" : ctlDir + " 不在(app 容器没挂共享卷?)",
                mounted ? null : "检查 docker-compose.yml 里 app 服务是否挂了 futu-ctl:/ctl"));
        items.add(new CheckItem("富途网关已启用", started, true,
                started ? "网关容器写过状态文件" : "还没启用这个可选组件(默认不装、不跑)",
                started ? null : "在服务器上执行:" + ENABLE_COMMAND + "(或 ./docker-up.sh --with-futu)"));
        if (started) {
            boolean w = Files.isWritable(ctlDir);
            items.add(new CheckItem("控制目录可写", w, true, ctlDir.toString(),
                    w ? null : "两个容器要共享这个卷;检查 compose 里 app 与 opend 是否都挂了它"));
            boolean up = alive();
            Map<String, String> st = readStatus();
            items.add(new CheckItem("网关容器在跑", up, true,
                    up ? "最后心跳 " + st.getOrDefault("ts", "?") : "status 文件过期或不存在",
                    up ? null : "看网关日志:docker compose logs --tail=50 opend"));
            boolean rsa = Files.isRegularFile(rsaKeyFile());
            items.add(new CheckItem("API 通道加密(RSA)", rsa, false,
                    rsa ? rsaKeyFile() + " 已就绪 · 同栈其它容器读不到你的持仓" : "未启用加密(网关容器可能设了 FUTU_API_RSA=0)",
                    rsa ? null : "让网关容器生成密钥:去掉 FUTU_API_RSA=0 后重启它"));
        }
        boolean ok = items.stream().allMatch(CheckItem::ok);
        return new SelfCheck(ok, kind().name(), items);
    }

    // ---------- 安装:不由 app 做 ----------

    private IOException notHere() {
        return new IOException("网关容器自己负责下载与校验 OpenD(它带着我们核对过的哈希清单)。"
                + "要换版本或重装:docker compose --profile futu up -d --force-recreate opend");
    }

    @Override public String download(String v, String os, String override) throws IOException { throw notHere(); }
    @Override public String installFromStream(java.io.InputStream in, long max, String name) throws IOException { throw notHere(); }
    @Override public String installFromServerPath(String path) throws IOException { throw notHere(); }

    // ---------- 登录 / 验证码:走共享卷 ----------

    @Override
    public void configureAndStart(String account, String pwdPlain, int port) throws IOException {
        requireUp();
        // 密码写进请求文件后由网关读取并【立刻删除】;文件 600、卷不对外挂载、内容不进日志
        submit("login", Map.of("account", account, "password", pwdPlain));
    }

    @Override
    public boolean requestSmsCode() {
        try { requireUp(); submit("req-sms", Map.of()); return true; }
        catch (IOException e) { log.warn("[opend] 请求验证码失败: {}", e.toString()); return false; }
    }

    @Override
    public boolean submitSmsCode(String code) {
        try { requireUp(); submit("sms", Map.of("code", code.trim())); return true; }
        catch (IOException e) { log.warn("[opend] 提交验证码失败: {}", e.toString()); return false; }
    }

    @Override
    public void stop() {
        // 不代劳:容器的生命周期归 docker。页面上这个按钮对容器通道是隐藏的(caps.canStop=false)。
        log.info("[opend] 容器通道不代管进程生命周期;停止请用 docker compose stop opend");
    }

    private void requireUp() throws IOException {
        if (!enabled()) throw new IOException("富途网关还没启用:docker compose --profile futu up -d");
        if (!alive()) throw new IOException("网关容器没在跑(或还没就绪);看日志:docker compose logs --tail=50 opend");
    }

    /** 写一条指令给网关容器(key=value · mode 600)。 */
    private void submit(String op, Map<String, String> args) throws IOException {
        Path cmdDir = ctlDir.resolve("cmd");
        Files.createDirectories(cmdDir);
        Path f = cmdDir.resolve(seq.incrementAndGet() + ".req");
        StringBuilder sb = new StringBuilder("op=").append(op).append('\n');
        args.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        // 先写临时文件再原子改名:否则 control-loop 可能读到只写了一半的请求
        Path tmp = cmdDir.resolve("." + f.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")); }
        catch (Exception ignored) { /* 非 POSIX 忽略 */ }
        Files.move(tmp, f, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        log.info("[opend] 已给网关下指令 op={}(参数内容不记录)", op);
    }

    /** 网关容器的日志由 {@code docker logs} 看;这里只回状态行,避免假装我们有它的全量日志。 */
    @Override
    public List<String> tail() {
        Map<String, String> st = readStatus();
        if (st.isEmpty()) {
            return List.of("(富途网关未启用 —— 这是可选组件)",
                    "启用:docker compose --profile futu up -d");
        }
        List<String> out = new ArrayList<>();
        out.add("网关容器状态(每 10 秒刷新一次)");
        st.forEach((k, v) -> out.add("  " + k + " = " + v));
        out.add("完整日志:docker compose logs -f opend");
        return out;
    }
}
