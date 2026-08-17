package com.family.finance.service.broker.opend;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.family.finance.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 通道实现 · 应用自己把 OpenD 当子进程托管(systemd 原生 / macOS)。
 *
 * <p>v1.17 从 {@code FutuOpendManager} 原样搬过来(行为不变),这样第二种通道(网关容器)
 * 不必和它挤在一个类里。原来那 4 处 {@code Env.DOCKER} 硬拦不在这里 —— 本类不需要知道
 * Docker 存不存在,"该用哪个通道"是 {@link FutuOpendManager} 的事。</p>
 *
 * <p>用户全程点鼠标:下载 → 解压 → 依赖体检 → 傻瓜配置 → 启动 → 短信码中继 → 守护,
 * 免 root、文件都在 {@link AppProperties#brokerOpendHome()}。</p>
 * <p><b>凭据</b>:登录密码服务端立即 MD5、丢弃明文;只把 MD5 存进 600 的 managed.json 供重启重拉;明文不落盘不日志。</p>
 */
@Component
@Slf4j
public class LocalProcessChannel implements OpendChannel {

    /** OpenD telnet 控制口默认值(交互登录 + 中继短信验证码) */
    static final int DEFAULT_TELNET_PORT = 22222;

    /** managed.json:重启重拉所需(pwdMd5 非明文;10.x 交互登录不存密码,见 configureAndStart) */
    record Creds(String account, String pwdMd5, int apiPort) {}

    private final Path home;
    private final ObjectMapper om = new ObjectMapper();

    private volatile Phase phase = Phase.NOT_INSTALLED;
    private volatile String version = null;
    private volatile String message = "";
    private volatile int apiPort = 11111;
    private volatile int telnetPort = DEFAULT_TELNET_PORT;
    private volatile Process process;
    /** 10.x 的控制口会话:登录握手 + 验证码中继 + 状态来源(无 pty 时进程 stdout 是空的) */
    private volatile OpendTelnet.Session ctl;
    private final Deque<String> logRing = new ArrayDeque<>();  // 最近 N 行

    public LocalProcessChannel(AppProperties props) {
        this.home = Path.of(props.brokerOpendHome()).toAbsolutePath().normalize();
    }

    // ========================= 纯逻辑(包可见 · 单测) =========================

    /**
     * 依赖安装命令 —— <b>按实际缺的库</b>给,不再无条件教装 gtk3/fuse。
     *
     * <p>原来的文案有两处错:① gtk3/fuse 是<b>桌面版</b>(AppImage)的依赖,命令行版根本不需要
     * —— beta 实测三个可执行文件在裸 debian/temurin 基底上 {@code ldd} 零缺失;
     * ② {@code libgtk-3-0} / {@code fuse} 在 Ubuntu 24.04+ 已改名 {@code *t64},照抄必然失败。</p>
     *
     * @param missing ldd 报 "not found" 的 so 名(空 = 不缺)
     */
    static String depsInstallCommand(boolean apt, List<String> missing) {
        if (missing == null || missing.isEmpty()) return "命令行版 OpenD 无需额外依赖(gtk3 / fuse 是桌面版才要的)";
        String libs = String.join(" ", missing);
        return apt
                ? "先查是哪个包提供它:apt-file search " + missing.get(0)
                  + "(或 dpkg -S)· 再 sudo apt-get install -y <包名>  # 缺:" + libs
                  + "  # 注意 Ubuntu 24.04+ 很多库包名带 t64 后缀"
                : "先查是哪个包提供它:dnf provides */" + missing.get(0)
                  + " · 再 sudo dnf install -y <包名>  # 缺:" + libs;
    }

    /** 32 位小写 MD5(9.x 老包的 -login_pwd_md5 用;10.x 交互登录不用它)。 */
    static String md5Hex(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /**
     * 启动参数 —— <b>按版本分流</b>。
     *
     * <p>10.x 起 {@code -login_pwd_md5} 与 {@code -telnet_port} 都不在受支持参数里(实测 {@code -help}),
     * 登录改成交互式、控制口只能在 XML 里配 —— 所以新版只传一个配置文件路径,凭据走控制口
     * (见 {@link OpendTelnet})。9.x 老包仍吃老参数,给装了老包的用户留着。</p>
     */
    static List<String> buildStartArgs(String binPath, String version, String cfgFile,
                                       String account, String pwdMd5, int apiPort, int telnetPort) {
        List<String> a = new ArrayList<>();
        a.add(binPath);
        if (OpendRelease.isInteractiveLogin(version)) {
            a.add("-cfg_file=" + cfgFile);          // 10.x:一切在 XML 里,密码走控制口
            return a;
        }
        a.add("-login_account=" + account);
        a.add("-login_pwd_md5=" + pwdMd5);
        a.add("-api_ip=127.0.0.1");
        a.add("-api_port=" + apiPort);
        a.add("-telnet_port=" + telnetPort);
        a.add("-lang=chs");
        return a;
    }

    // ========================= 通道实现 =========================

    @Override public Kind kind() { return Kind.LOCAL; }

    @Override
    public Caps caps() {
        // 本机托管:全能力,显示托管终端,不需要"启用"动作
        return new Caps(true, true, true, true, true, true, false, null);
    }

    @Override public Target target() { return new Target("127.0.0.1", apiPort, false); }

    @Override
    public synchronized Status status() {
        return new Status(phase, version, message, apiPort, process != null && process.isAlive(),
                kind().name(), tail());
    }

    public String osReleaseRaw() {
        try { return Files.readString(Path.of("/etc/os-release")); } catch (Exception e) { return ""; }
    }

    /** 本机探测到的富途包系统后缀。 */
    public String detectedOsTag() { return OpendRelease.packageTag(System.getProperty("os.name"), osReleaseRaw()); }

    /**
     * 下载 + 解压;返回解析到的版本。同步阻塞(controller 异步跑)。
     *
     * <p>v1.17:{@code version} 留空即<b>问官方要最新版</b>({@code fetch-lasted-link} → 302),
     * 用户不必再去官网抄版本号。给了 override 就原样用(仍走 https + 官方域名白名单)。</p>
     */
    @Override
    public synchronized String download(String version, String osTag, String override) throws IOException, InterruptedException {
        phase = Phase.DOWNLOADING; message = "下载中…"; log("download start · version=" + version + " os=" + osTag);
        Files.createDirectories(home);
        String url = (override != null && !override.isBlank())
                ? OpendRelease.requireAllowed(override.trim())
                : (version == null || version.isBlank() ? resolveLatestUrl(osTag)
                                                        : OpendRelease.downloadUrl(version, osTag, null));
        log("download url resolved · " + url);
        Path pkg = home.resolve("FutuOpenD.tar.gz");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        HttpResponse<Path> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofMinutes(10)).build(),
                    HttpResponse.BodyHandlers.ofFile(pkg));
        } catch (Exception e) {
            phase = Phase.ERROR; message = "下载失败(已脱敏):" + shortErr(e.getMessage());
            throw new IOException(message, e);
        }
        if (resp.statusCode() != 200) {
            phase = Phase.ERROR; message = "下载失败 · HTTP " + resp.statusCode() + "(版本号或系统可能不对,可粘官网下载 URL 重试)";
            throw new IOException(message);
        }
        return extractAndFinish(pkg);
    }

    /**
     * 问官方要当前最新版的下载地址:{@code fetch-lasted-link} 返回 302,Location 就是权威 URL。
     * 拿不到就退回按已知命名规则拼(并说清是兜底)。
     */
    String resolveLatestUrl(String osTag) throws IOException {
        String probe = OpendRelease.latestLinkUrl(osTag);
        try {
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)      // 要的就是 Location 本身
                    .connectTimeout(Duration.ofSeconds(15)).build();
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(probe)).GET()
                    .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.discarding());
            String loc = r.headers().firstValue("location").orElse(null);
            if (r.statusCode() / 100 == 3 && loc != null && !loc.isBlank()) {
                return OpendRelease.requireAllowed(loc.trim());
            }
            throw new IOException("官方版本端点没给跳转(HTTP " + r.statusCode() + ")");
        } catch (Exception e) {
            log("resolve latest failed: " + shortErr(e.toString()));
            throw new IOException("问不到富途官方的最新版本(" + shortErr(e.getMessage())
                    + ")· 可改用「上传安装包」或自己粘官网下载地址", e);
        }
    }

    /** 上传已下好的 tar.gz(<b>不依赖服务器能否连 CDN</b> · 墙内也能用):流式落盘(带上限)+ 解压就绪。 */
    @Override
    public synchronized String installFromStream(java.io.InputStream in, long maxBytes) throws IOException, InterruptedException {
        phase = Phase.DOWNLOADING; message = "接收上传的安装包…"; log("install from upload");
        Files.createDirectories(home);
        Path pkg = home.resolve("FutuOpenD.tar.gz");
        long total = 0;
        try (var out = Files.newOutputStream(pkg, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) { phase = Phase.ERROR; message = "上传文件过大(超过上限)"; throw new IOException(message); }
                out.write(buf, 0, n);
            }
        }
        log("uploaded pkg bytes=" + total);
        message = "解压中…";
        return extractAndFinish(pkg);
    }

    /**
     * 从服务器上<b>已存在</b>的 tar.gz 路径导入(scp 到服务器后填路径即可)。
     * 彻底绕开 HTTP 上传的 nginx {@code client_max_body_size} / CDN 上传限额 / 本机连不连 CDN。
     */
    @Override
    public synchronized String installFromServerPath(String path) throws IOException, InterruptedException {
        if (path == null || path.isBlank()) throw new IOException("请填服务器上 tar.gz 的绝对路径");
        Path src = Path.of(path.trim());
        if (!Files.isRegularFile(src)) throw new IOException("路径不存在或不是文件:" + src);
        phase = Phase.DOWNLOADING; message = "从服务器路径导入…"; log("install from server path=" + src);
        Files.createDirectories(home);
        Path pkg = home.resolve("FutuOpenD.tar.gz");
        Files.copy(src, pkg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        message = "解压中…";
        return extractAndFinish(pkg);
    }

    /** tar 解压 + 定位可执行 + 版本 + 置 INSTALLED(download 与 upload 共用)。 */
    private String extractAndFinish(Path pkg) throws IOException, InterruptedException {
        int ex = new ProcessBuilder("tar", "-xzf", pkg.toString(), "-C", home.toString())
                .redirectErrorStream(true).start().waitFor();
        if (ex != 0) { phase = Phase.ERROR; message = "解压失败(tar exit=" + ex + " · 文件可能不是有效的 FutuOpenD tar.gz)"; throw new IOException(message); }
        Path bin = locateBinary();
        if (bin == null) { phase = Phase.ERROR; message = "解压后未找到 FutuOpenD 可执行文件"; throw new IOException(message); }
        bin.toFile().setExecutable(true);
        this.version = OpendRelease.parseVersion(bin.getParent().getFileName().toString());
        if (this.version == null) this.version = OpendRelease.parseVersion(pkg.getFileName().toString());
        phase = Phase.INSTALLED; message = "已安装 · 版本 " + (version == null ? "未知" : version);
        log("installed · version=" + version + " bin=" + bin);
        return version;
    }

    /** 找解压出来的 FutuOpenD 可执行(命令行版在子文件夹里)。 */
    Path locateBinary() {
        if (!Files.isDirectory(home)) return null;
        // 深度 6:macOS 的 FutuOpenD.app/Contents/MacOS/FutuOpenD 也要够得到
        try (var s = Files.walk(home, 6)) {
            return s.filter(p -> p.getFileName().toString().equals("FutuOpenD") && Files.isRegularFile(p))
                    .findFirst().orElse(null);
        } catch (IOException e) { return null; }
    }

    @Override
    public Deps checkDeps() {
        if (isMac()) return new Deps(true, List.of(), "macOS 无需 gtk3(.app 自带依赖)");
        Path bin = locateBinary();
        List<String> missing = new ArrayList<>();
        if (bin != null) {
            try {
                Process p = new ProcessBuilder("ldd", bin.toString()).redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("not found")) missing.add(line.trim().split("\\s+")[0]);
                    }
                }
                p.waitFor();
            } catch (Exception e) { log("ldd failed: " + e); }
        }
        String os = osReleaseRaw();
        boolean apt = os.toLowerCase(Locale.ROOT).contains("ubuntu") || os.toLowerCase(Locale.ROOT).contains("debian");
        return new Deps(missing.isEmpty(), missing, depsInstallCommand(apt, missing));
    }

    private boolean isMac() {
        String n = System.getProperty("os.name");
        return n != null && n.toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * 环境自检:逐项查可执行位、家目录可写、OpenD 运行时数据目录可写(~/.com.futunn.FutuOpenD)、系统依赖,
     * 每项给出可执行修复命令。用户把包自己放服务器上(属主/权限/只读挂载)最常踩的坑都在这里兜住。
     */
    @Override
    public SelfCheck selfCheck() {
        List<CheckItem> items = new ArrayList<>();
        // 1. 安装家目录可写(下载/解压/managed.json/日志都落这里)
        boolean homeW = probeWritable(home);
        items.add(new CheckItem("安装目录可写", homeW, true, home.toString(),
                homeW ? null : "chown -R <应用用户> '" + home + "';若 systemd 加固,unit 里 ReadWritePaths= 追加 " + home + " 后 daemon-reload + 重启"));
        // 2. OpenD 可执行文件 + 可执行位(自己 scp 上来常常丢 +x / 属主不对)
        Path bin = locateBinary();
        if (bin == null) {
            items.add(new CheckItem("OpenD 可执行文件", false, false, "未找到(先完成第 1 步下载 / 导入)", null));
        } else {
            boolean exec = Files.isExecutable(bin);
            items.add(new CheckItem("OpenD 可执行位", exec, true, bin.toString(),
                    exec ? null : "chmod +x '" + bin + "';属主不对再 chown <应用用户> '" + bin + "'"));
        }
        // 3. OpenD 运行时数据目录可写:它按 getpwuid 家目录建 ~/.com.futunn.FutuOpenD(无视 $HOME)
        //    —— 命中「Fail to create app dir: Read-only file system」(systemd ProtectHome=read-only)
        Path osHome = Path.of(System.getProperty("user.home", "/root"));
        boolean osHomeW = isMac() || probeWritable(osHome);
        items.add(new CheckItem("OpenD 数据目录可写(~/.com.futunn.FutuOpenD)", osHomeW, true, osHome.toString(),
                osHomeW ? null : "该目录只读(常见于 systemd ProtectHome=read-only):unit 里 ReadWritePaths= 追加 " + osHome
                        + " 后 daemon-reload + 重启;或把属主 chown 给应用用户"));
        // 4. 系统依赖:按 ldd 实际结果说话。命令行版实测零额外依赖,所以这里通常直接绿
        if (!isMac()) {
            Deps d = checkDeps();
            items.add(new CheckItem("动态库依赖", d.ok(), false,
                    d.ok() ? "齐全(命令行版无需 gtk3 / fuse —— 那是桌面版的依赖)"
                           : "缺:" + String.join(", ", d.missing()),
                    d.ok() ? null : d.installCommand()));
        }
        boolean allOk = items.stream().allMatch(CheckItem::ok);
        return new SelfCheck(allOk, kind().name(), items);
    }

    /**
     * 真实写探测(建一个临时文件再删):能识别 systemd 只读挂载(ProtectHome / ProtectSystem),
     * 普通 {@link Files#isWritable} 只看 mode 位、对只读<b>挂载</b>会误判可写。
     */
    private boolean probeWritable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".finance-write-probe");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception ex) { return false; }
    }

    /**
     * 配置 + 启动 + 登录。
     *
     * <p>10.x 走<b>交互式</b>:生成配置(基于包内官方模板)→ 起进程 → 连控制口按提示依次发账号、密码 →
     * 需要短信码就停在 {@link Phase#NEEDS_SMS} 等页面填。<b>明文密码只经过控制口的 socket,不落盘。</b>
     * 代价要说清:因此 10.x <b>重启后不能自动重登</b>(除非 OpenD 自己记住了设备),
     * 页面会明确要求再登一次 —— 这比把明文密码写到磁盘上强。</p>
     *
     * <p>9.x 老包保持原路径:密码即时 MD5、写 600 的 managed.json、命令行参数带 MD5、可开机重拉。</p>
     */
    @Override
    public synchronized void configureAndStart(String account, String pwdPlain, int port) throws IOException {
        Path bin = locateBinary();
        if (bin == null) throw new IllegalStateException("请先完成第 1 步下载 OpenD");
        // 启动前硬检:家目录/数据目录不可写、可执行位缺失等,直接给清晰中文原因 + 修复命令,
        // 而不是等 OpenD 抛「Fail to create app dir: Read-only file system」这种天书
        for (CheckItem it : selfCheck().items()) {
            if (it.hard() && !it.ok()) {
                throw new IllegalStateException("环境自检未过 · " + it.name() + ":" + it.detail()
                        + (it.fix() != null ? " · 修复:" + it.fix() : ""));
            }
        }
        this.apiPort = port > 0 ? port : 11111;
        if (OpendRelease.isInteractiveLogin(version)) {
            saveCreds(new Creds(account, "", apiPort));   // 只记账号与端口,不记密码
            startProcess(account, null);
            interactiveLogin(account, pwdPlain);          // 明文只走 socket
            return;
        }
        String md5 = md5Hex(pwdPlain);
        saveCreds(new Creds(account, md5, apiPort));   // 明文 pwdPlain 到此为止,不再引用
        startProcess(account, md5);
    }

    private void startProcess(String account, String pwdMd5) throws IOException {
        Path bin = locateBinary();
        if (bin == null) throw new IllegalStateException("OpenD 未安装");
        stopProcess();
        String cfg = OpendRelease.isInteractiveLogin(version) ? writeConfig(bin) : null;
        List<String> args = buildStartArgs(bin.toString(), version, cfg, account, pwdMd5, apiPort, telnetPort);
        phase = Phase.STARTING; message = "启动中…";
        ProcessBuilder pb = new ProcessBuilder(args).directory(bin.getParent().toFile()).redirectErrorStream(true);
        process = pb.start();
        log("opend process started · pid=" + process.pid());
        Thread reader = new Thread(() -> pumpLog(process), "opend-log-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * 基于包内官方 {@code FutuOpenD.xml} 生成我们的配置副本。
     *
     * <p>不改包内文件(幂等、重装不受影响)。注意官方模板里 {@code telnet_ip} 默认是 {@code 0.0.0.0}
     * —— 那个控制口没有鉴权,{@link OpendConfigXml} 会把它按死成回环地址。</p>
     */
    private String writeConfig(Path bin) throws IOException {
        Path official = bin.getParent().resolve("FutuOpenD.xml");
        if (!Files.isRegularFile(official)) throw new IOException("包内缺少 FutuOpenD.xml(不是完整的 OpenD 安装包?)");
        String xml = OpendConfigXml.render(Files.readString(official, StandardCharsets.UTF_8),
                "127.0.0.1", apiPort, telnetPort, null);
        Path gen = home.resolve("FutuOpenD.generated.xml");
        Files.writeString(gen, xml, StandardCharsets.UTF_8);
        log("config written · " + gen + " · telnet 绑 " + OpendConfigXml.TELNET_IP + ":" + telnetPort);
        return gen.toString();
    }

    /**
     * 交互式登录:连控制口,按它的提示依次喂账号、密码。
     *
     * <p>会话<b>保持打开</b>并交给后台线程继续读 —— 后续的验证码就发在同一条会话上,
     * 而且这样即使进程 stdout 没有输出(无 pty 时实测 0 字节)也拿得到状态。</p>
     */
    private void interactiveLogin(String account, String pwdPlain) {
        closeCtl();
        OpendTelnet.Session s = null;
        try {
            // 进程刚起,控制口要等一下才 listen
            for (int i = 0; i < 20 && s == null; i++) {
                try { s = OpendTelnet.open("127.0.0.1", telnetPort, 3000); }
                catch (IOException e) { sleep(500); }
            }
            if (s == null) { phase = Phase.ERROR; message = "OpenD 起来了但控制口连不上(端口 " + telnetPort + ")"; return; }

            s.sendLine("");                                  // 触发它把版本号与当前状态吐出来
            String out = s.readFor(2500);
            log(clean(out));
            OpendTelnet.Step step = OpendTelnet.stepFromPrompt(out);

            if (step == OpendTelnet.Step.WANT_ACCOUNT) {
                s.sendLine(account); log("已发送账号");
                out = s.readFor(3500); log(clean(out));
                step = OpendTelnet.stepFromPrompt(out);
            }
            if (step == OpendTelnet.Step.WANT_PASSWORD) {
                s.sendLine(pwdPlain); log("已发送密码(不记录内容)");
                out = s.readFor(6000); log(clean(out));
                step = OpendTelnet.stepFromPrompt(out);
            }
            applyStep(step);
            this.ctl = s;
            Thread t = new Thread(this::pumpCtl, "opend-ctl-reader");
            t.setDaemon(true); t.start();
            s = null;                                        // 交给后台线程,别在 finally 里关掉
        } catch (IOException e) {
            log("交互登录失败: " + shortErr(e.toString()));
            phase = Phase.ERROR; message = "和 OpenD 的控制口通信失败:" + shortErr(e.getMessage());
        } finally {
            if (s != null) s.close();
        }
    }

    private void applyStep(OpendTelnet.Step step) {
        switch (step) {
            case LOGGED_IN -> { phase = Phase.RUNNING; message = "OpenD 已登录 · 运行中"; }
            case WANT_SMS -> { phase = Phase.NEEDS_SMS; message = "需要手机短信验证码"; }
            case FAILED -> { phase = Phase.ERROR; message = "登录被拒(账号/密码/验证码有误 · 看下方日志)"; }
            case WANT_ACCOUNT, WANT_PASSWORD -> { phase = Phase.STARTING; message = "OpenD 还在等登录信息…"; }
            default -> { /* 认不出:保持当前阶段,让日志说话 */ }
        }
    }

    /** 后台持续读控制口:更新阶段 + 落日志环(这是 10.x 的状态来源,不再依赖 stdout)。 */
    private void pumpCtl() {
        OpendTelnet.Session s = ctl;
        while (s != null && s.isOpen()) {
            String out = s.readFor(4000);
            if (out == null || out.isBlank()) { s = ctl; continue; }
            String c = clean(out);
            if (!c.isBlank()) log(c);
            applyStep(OpendTelnet.stepFromPrompt(out));
            s = ctl;
        }
    }

    /** 去掉 OpenD 的 >>> 提示符与空行,日志才清爽可读。 */
    private static String clean(String raw) {
        if (raw == null) return "";
        return raw.lines()
                .map(l -> l.replace('\r', ' ').trim())
                .filter(l -> !l.isEmpty() && !l.chars().allMatch(ch -> ch == '>'))
                .map(l -> l.startsWith(">>>") ? l.substring(3).trim() : l)
                .reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private void closeCtl() {
        OpendTelnet.Session s = ctl;
        ctl = null;
        if (s != null) s.close();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void pumpLog(Process p) {
        Path logFile = home.resolve("opend.log");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String tl = line.trim();
                // 跳过 OpenD 的 >>> 提示符 / 满屏空白行,日志才清爽可读
                if (tl.isEmpty() || tl.chars().allMatch(c -> c == '>')) continue;
                log(tl);
                try { Files.writeString(logFile, tl + "\n",
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
                catch (IOException ignored) {}
                Phase next = OpendLog.phaseFromLog(tl);
                if (next != null) {
                    phase = next;
                    if (next == Phase.NEEDS_SMS) message = "需要手机短信验证码";
                    else if (next == Phase.RUNNING) message = "OpenD 已登录 · 运行中";
                    else if (next == Phase.ERROR) message = "登录/运行出错(看日志)";
                }
            }
        } catch (IOException e) {
            log("log reader end: " + e);
        }
        if (process == p && phase != Phase.STOPPED) { phase = Phase.ERROR; message = "OpenD 进程已退出"; }
    }

    /** 让 OpenD 重发一条手机验证码(实测限流:1 分钟 1 次)。 */
    @Override
    public synchronized boolean requestSmsCode() {
        if (!relay(OpendTelnet.CMD_REQ_SMS, "req_phone_verify_code")) {
            message = "请求验证码失败(OpenD 未在运行?):看下方日志";
            return false;
        }
        message = "已请求重发验证码 · 留意手机短信,再填入下方";
        return true;
    }

    /** 中继短信验证码。 */
    @Override
    public synchronized boolean submitSmsCode(String code) {
        if (!relay(OpendTelnet.cmdInputSms(code), "input_phone_verify_code")) {
            message = "验证码提交失败(OpenD 未在运行?):看下方日志";
            return false;
        }
        message = "验证码已提交给 OpenD · 正在登录,请看下方实时日志(出现「登录成功 / 运行中」即完成)";
        return true;
    }

    /**
     * 把一条运维命令送给 OpenD。
     *
     * <p>三条路依次试:① 10.x 已建立的控制口会话;② 临时连一次控制口;③ 9.x 的进程 stdin
     * (老包就是在 stdin 的交互 REPL 上读命令,日志里的 {@code >>>} 就是它的提示符)。</p>
     */
    private boolean relay(String cmd, String what) {
        OpendTelnet.Session s = ctl;
        if (s != null && s.isOpen()) {
            try { s.sendLine(cmd); log(what + " -> 控制口(已有会话)"); return true; }
            catch (Exception e) { log("ctl relay failed: " + shortErr(e.toString())); closeCtl(); }
        }
        try (OpendTelnet.Session one = OpendTelnet.open("127.0.0.1", telnetPort, 4000)) {
            one.sendLine(cmd);
            log(what + " -> 控制口 127.0.0.1:" + telnetPort);
            return true;
        } catch (Exception e) { log("telnet relay failed: " + shortErr(e.toString())); }
        Process p = process;
        if (p != null && p.isAlive()) {
            try {
                OutputStream os = p.getOutputStream();   // 9.x:子进程 stdin
                os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                log(what + " -> 进程 stdin(9.x 老包路径)");
                return true;
            } catch (Exception e) { log("stdin relay failed: " + shortErr(e.toString())); }
        }
        return false;
    }

    @Override
    public synchronized void stop() { closeCtl(); stopProcess(); phase = Phase.STOPPED; message = "已停止"; }

    private void stopProcess() {
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        process = null;
    }

    // ---------- managed.json(600)持久化 ----------

    private Path credsFile() { return home.resolve("managed.json"); }

    private void saveCreds(Creds c) throws IOException {
        Files.createDirectories(home);
        Path f = credsFile();
        Files.writeString(f, om.writeValueAsString(c), StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
        catch (Exception ignored) { /* 非 POSIX 文件系统忽略 */ }
    }

    private Creds loadCreds() {
        try { return om.readValue(Files.readString(credsFile()), Creds.class); }
        catch (Exception e) { return null; }
    }

    // ---------- 守护:重启重拉 + 关停清理 ----------

    /**
     * 开机重拉。
     *
     * <p>9.x:managed.json 里有 MD5 → 直接带参数重启,和以前一样。</p>
     * <p>10.x:我们<b>没有</b>存密码,所以只把进程拉起来,然后问控制口"你登录了吗" ——
     * OpenD 自己记住设备的话这里就直接是 RUNNING(免密恢复);没记住就停在需要登录,
     * 页面会要求再登一次。这条分支的实际行为要用真实账号验一次(TDD §8 第 1 条)。</p>
     */
    @PostConstruct
    void resumeOnBoot() {
        Path bin = locateBinary();
        if (bin == null) return;
        version = OpendRelease.parseVersion(bin.getParent().getFileName().toString());
        Creds c = loadCreds();
        if (c == null) { phase = Phase.INSTALLED; return; }
        this.apiPort = c.apiPort() > 0 ? c.apiPort() : 11111;
        if (OpendRelease.isInteractiveLogin(version)) {
            try {
                startProcess(c.account(), null);
                log("resume opend on boot · 10.x:等控制口报告登录状态");
                probeCtlState();
            } catch (Exception e) {
                log("resume failed: " + shortErr(e.toString()));
                phase = Phase.ERROR; message = "开机重拉 OpenD 失败";
            }
            return;
        }
        if (c.pwdMd5() != null && !c.pwdMd5().isBlank()) {
            try { startProcess(c.account(), c.pwdMd5()); log("resume opend on boot"); }
            catch (Exception e) { log("resume failed: " + e); phase = Phase.ERROR; message = "开机重拉 OpenD 失败"; }
        } else {
            phase = Phase.INSTALLED;
        }
    }

    /** 连控制口问一次当前状态(开机重拉后判断是否已免密恢复)。 */
    private void probeCtlState() {
        OpendTelnet.Session s = null;
        for (int i = 0; i < 20 && s == null; i++) {
            try { s = OpendTelnet.open("127.0.0.1", telnetPort, 3000); }
            catch (IOException e) { sleep(500); }
        }
        if (s == null) { phase = Phase.ERROR; message = "OpenD 起来了但控制口连不上(端口 " + telnetPort + ")"; return; }
        try { s.sendLine(""); } catch (IOException ignored) { }
        String out = s.readFor(2500);
        log(clean(out));
        OpendTelnet.Step step = OpendTelnet.stepFromPrompt(out);
        applyStep(step);
        if (step == OpendTelnet.Step.WANT_ACCOUNT || step == OpendTelnet.Step.WANT_PASSWORD) {
            message = "OpenD 已启动,但需要你再登录一次(这一版不在服务器上保存密码)";
        }
        this.ctl = s;
        Thread t = new Thread(this::pumpCtl, "opend-ctl-reader");
        t.setDaemon(true); t.start();
    }

    @PreDestroy
    void onShutdown() { closeCtl(); stopProcess(); }

    // ---------- 日志环 ----------

    private void log(String line) {
        synchronized (logRing) {
            logRing.addLast(line);
            while (logRing.size() > 200) logRing.removeFirst();
        }
        log.debug("[opend] {}", line);
    }

    @Override
    public List<String> tail() {
        synchronized (logRing) { return new ArrayList<>(logRing); }
    }

    static String shortErr(String m) {
        if (m == null) return "";
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }
}
