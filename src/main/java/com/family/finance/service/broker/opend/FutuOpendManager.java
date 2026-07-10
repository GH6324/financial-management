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
 * 富途 FutuOpenD 傻瓜安装向导 · 应用自管子进程(v0.15)。
 *
 * <p>用户全程点鼠标:应用把 OpenD <b>下载 → 解压 → 依赖体检 → 傻瓜配置 → 启动 → 短信码中继 → 守护</b>
 * 都做进系统里。OpenD 只是富途 API 的必经网关(SDK 连它、它连富途),这里由 finance 应用当子进程托管,
 * 免 root、文件都在 {@link AppProperties#brokerOpendHome()}。</p>
 *
 * <p><b>只读铁律</b>:本类只负责把网关跑起来;查询/下单与否由 {@code BrokerClient} 决定,我方永不解锁交易。</p>
 * <p><b>凭据</b>:登录密码服务端立即 MD5、丢弃明文;只把 MD5 存进 600 的 managed.json 供重启重拉;明文不落盘不日志。</p>
 */
@Component
@Slf4j
public class FutuOpendManager {

    /** 富途官方软件分发 host(下载白名单,只允许它 + https) */
    static final String SOFTWARE_HOST = "softwarefile.futunn.com";
    /** OpenD telnet 控制口默认值(用于中继短信验证码) */
    static final int DEFAULT_TELNET_PORT = 22222;

    public enum Phase { NOT_INSTALLED, DOWNLOADING, INSTALLED, STARTING, NEEDS_SMS, RUNNING, STOPPED, ERROR }

    public record Status(Phase phase, String version, String message, int apiPort, boolean processAlive,
                         List<String> logTail) {}
    /** managed.json:重启重拉所需(pwdMd5 非明文) */
    record Creds(String account, String pwdMd5, int apiPort) {}
    public record Deps(boolean ok, List<String> missing, String installCommand) {}

    private final Path home;
    private final ObjectMapper om = new ObjectMapper();

    private volatile Phase phase = Phase.NOT_INSTALLED;
    private volatile String version = null;
    private volatile String message = "";
    private volatile int apiPort = 11111;
    private volatile int telnetPort = DEFAULT_TELNET_PORT;
    private volatile Process process;
    private final Deque<String> logRing = new ArrayDeque<>();  // 最近 N 行

    public FutuOpendManager(AppProperties props) {
        this.home = Path.of(props.brokerOpendHome()).toAbsolutePath().normalize();
    }

    // ========================= 纯逻辑(包可见 · 单测) =========================

    /** 从 /etc/os-release 内容判定富途包系统后缀;认不出返回 "" 让 UI 下拉兜底。 */
    static String osTag(String osRelease) {
        if (osRelease == null) return "";
        String s = osRelease.toLowerCase(Locale.ROOT);
        if (s.contains("centos") || s.contains("rhel") || s.contains("red hat") || s.contains("rocky") || s.contains("alma")) return "Centos7";
        if (s.contains("ubuntu")) return "Ubuntu16.04";
        if (s.contains("debian")) return "Ubuntu16.04"; // Debian 复用 Ubuntu 包更接近
        return "";
    }

    /** 是否 apt 系(决定依赖安装命令);否则按 yum。 */
    static boolean isAptOs(String osRelease) {
        String s = osRelease == null ? "" : osRelease.toLowerCase(Locale.ROOT);
        return s.contains("ubuntu") || s.contains("debian");
    }

    /** 依赖安装命令(缺 gtk3 / fuse 时给用户贴)。 */
    static String depsInstallCommand(boolean apt) {
        return apt ? "sudo apt-get update && sudo apt-get install -y libgtk-3-0 fuse"
                   : "sudo yum install -y gtk3 && sudo yum --enablerepo=epel -y install fuse-sshfs";
    }

    /** 构造下载 URL;override 非空则原样用(仍强制 https + host 白名单),否则按版本+系统拼。 */
    static String downloadUrl(String version, String osTag, String override) {
        String url = (override != null && !override.isBlank())
                ? override.trim()
                : "https://" + SOFTWARE_HOST + "/FutuOpenD_" + version.trim() + "_" + osTag.trim() + ".tar.gz";
        URI u = URI.create(url);
        if (!"https".equalsIgnoreCase(u.getScheme())) throw new IllegalArgumentException("仅允许 https 下载地址");
        if (!SOFTWARE_HOST.equalsIgnoreCase(u.getHost())) throw new IllegalArgumentException("仅允许从 " + SOFTWARE_HOST + " 下载");
        return url;
    }

    /** 从包文件名 / 解压目录名解析版本号(FutuOpenD_2.19.1252_Centos7 → 2.19.1252)。 */
    static String parseVersion(String nameOrDir) {
        if (nameOrDir == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("FutuOpenD[_-]([0-9]+(?:\\.[0-9]+)+)").matcher(nameOrDir);
        return m.find() ? m.group(1) : null;
    }

    /** 32 位小写 MD5(密码 → 密文;明文用完即弃)。 */
    static String md5Hex(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** 启动参数:永远 -api_ip=127.0.0.1(只本机)、传 MD5 而非明文密码。 */
    static List<String> buildStartArgs(String binPath, String account, String pwdMd5, int apiPort, int telnetPort) {
        List<String> a = new ArrayList<>();
        a.add(binPath);
        a.add("-login_account=" + account);
        a.add("-login_pwd_md5=" + pwdMd5);
        a.add("-api_ip=127.0.0.1");
        a.add("-api_port=" + apiPort);
        a.add("-telnet_port=" + telnetPort);
        a.add("-lang=chs");
        return a;
    }

    /** 从一行 OpenD 日志推断阶段迁移(best-effort · 认不出保持 STARTING)。 */
    static Phase phaseFromLog(String line) {
        if (line == null) return null;
        String s = line.toLowerCase(Locale.ROOT);
        if (s.contains("验证码") || s.contains("verify code") || s.contains("phone_verify") || s.contains("sms")) return Phase.NEEDS_SMS;
        if (s.contains("登录成功") || s.contains("login success") || s.contains("login succeed") || s.contains("initconnect success")) return Phase.RUNNING;
        if (s.contains("密码错误") || (s.contains("password") && s.contains("error")) || s.contains("login failed") || s.contains("登录失败")) return Phase.ERROR;
        return null;
    }

    // ========================= 实例 IO =========================

    public synchronized Status status() {
        return new Status(phase, version, message, apiPort, process != null && process.isAlive(), tail());
    }

    public String osReleaseRaw() {
        try { return Files.readString(Path.of("/etc/os-release")); } catch (Exception e) { return ""; }
    }

    /** 本机探测到的富途包系统后缀(供 controller;静态 {@link #osTag} 保持包可见给单测)。 */
    public String detectedOsTag() { return osTag(osReleaseRaw()); }

    /** 下载 + 解压;返回解析到的版本。同步阻塞(controller 异步跑)。 */
    public synchronized String download(String version, String osTag, String override) throws IOException, InterruptedException {
        phase = Phase.DOWNLOADING; message = "下载中…"; log("download start · version=" + version + " os=" + osTag);
        Files.createDirectories(home);
        String url = downloadUrl(version, osTag, override);
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
        // 解压:tar -xzf 到 home
        int ex = new ProcessBuilder("tar", "-xzf", pkg.toString(), "-C", home.toString())
                .redirectErrorStream(true).start().waitFor();
        if (ex != 0) { phase = Phase.ERROR; message = "解压失败(tar exit=" + ex + ")"; throw new IOException(message); }
        Path bin = locateBinary();
        if (bin == null) { phase = Phase.ERROR; message = "解压后未找到 FutuOpenD 可执行文件"; throw new IOException(message); }
        bin.toFile().setExecutable(true);
        this.version = parseVersion(bin.getParent().getFileName().toString());
        if (this.version == null) this.version = parseVersion(pkg.getFileName().toString());
        phase = Phase.INSTALLED; message = "已安装 · 版本 " + (version == null ? "未知" : version);
        log("installed · version=" + version + " bin=" + bin);
        return version;
    }

    /** 找解压出来的 FutuOpenD 可执行(命令行版在子文件夹里)。 */
    Path locateBinary() {
        if (!Files.isDirectory(home)) return null;
        try (var s = Files.walk(home, 3)) {
            return s.filter(p -> p.getFileName().toString().equals("FutuOpenD") && Files.isRegularFile(p))
                    .findFirst().orElse(null);
        } catch (IOException e) { return null; }
    }

    public Deps checkDeps() {
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
        return new Deps(missing.isEmpty(), missing, depsInstallCommand(isAptOs(os)));
    }

    /** 傻瓜配置 + 启动:MD5 密码、持久化 600 managed.json、以 127.0.0.1 起进程并守护读日志。 */
    public synchronized void configureAndStart(String account, String pwdPlain, int port) throws IOException {
        Path bin = locateBinary();
        if (bin == null) throw new IllegalStateException("请先完成第 1 步下载 OpenD");
        this.apiPort = port > 0 ? port : 11111;
        String md5 = md5Hex(pwdPlain);
        saveCreds(new Creds(account, md5, apiPort));   // 明文 pwdPlain 到此为止,不再引用
        startProcess(account, md5);
    }

    private void startProcess(String account, String pwdMd5) throws IOException {
        Path bin = locateBinary();
        if (bin == null) throw new IllegalStateException("OpenD 未安装");
        stopProcess();
        List<String> args = buildStartArgs(bin.toString(), account, pwdMd5, apiPort, telnetPort);
        phase = Phase.STARTING; message = "启动中…";
        ProcessBuilder pb = new ProcessBuilder(args).directory(bin.getParent().toFile()).redirectErrorStream(true);
        process = pb.start();
        log("opend process started · pid=" + process.pid());
        Thread reader = new Thread(() -> pumpLog(process), "opend-log-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void pumpLog(Process p) {
        Path logFile = home.resolve("opend.log");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log(line);
                try { Files.writeString(logFile, line + "\n",
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
                catch (IOException ignored) {}
                Phase next = phaseFromLog(line);
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

    /** 中继短信验证码:连 OpenD telnet 控制口发 input_phone_verify_code。 */
    public synchronized boolean submitSmsCode(String code) {
        try (Socket sock = new Socket("127.0.0.1", telnetPort)) {
            sock.setSoTimeout(4000);
            OutputStream os = sock.getOutputStream();
            os.write(("input_phone_verify_code -code=" + code.trim() + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            log("sms code relayed to telnet:" + telnetPort);
            message = "验证码已提交,等待登录…";
            return true;
        } catch (Exception e) {
            log("sms relay failed: " + e);
            message = "验证码提交失败(OpenD 未在等待验证码?):" + shortErr(e.getMessage());
            return false;
        }
    }

    public synchronized void stop() { stopProcess(); phase = Phase.STOPPED; message = "已停止"; }

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

    @PostConstruct
    void resumeOnBoot() {
        // 之前配过(有 managed.json)且已安装 → 应用启动时自动把 OpenD 也拉起来
        Creds c = loadCreds();
        if (c != null && locateBinary() != null) {
            this.apiPort = c.apiPort() > 0 ? c.apiPort() : 11111;
            try { startProcess(c.account(), c.pwdMd5()); log("resume opend on boot"); }
            catch (Exception e) { log("resume failed: " + e); phase = Phase.ERROR; message = "开机重拉 OpenD 失败"; }
        } else if (locateBinary() != null) {
            phase = Phase.INSTALLED;
            version = parseVersion(locateBinary().getParent().getFileName().toString());
        }
    }

    @PreDestroy
    void onShutdown() { stopProcess(); }

    // ---------- 日志环 ----------

    private void log(String line) {
        synchronized (logRing) {
            logRing.addLast(line);
            while (logRing.size() > 200) logRing.removeFirst();
        }
        log.debug("[opend] {}", line);
    }

    public List<String> tail() {
        synchronized (logRing) { return new ArrayList<>(logRing); }
    }

    private static String shortErr(String m) {
        if (m == null) return "";
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }
}
