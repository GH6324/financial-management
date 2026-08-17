package com.family.finance.service.broker.opend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 富途 OpenD 网关的入口(v1.17 起是个 facade)。
 *
 * <p>它只做一件事:<b>决定当前该用哪个 {@link OpendChannel}</b>,其余全部委派。
 * 具体托管逻辑在 {@link LocalProcessChannel}(本机子进程)等实现里。</p>
 *
 * <p>v1.16 之前这个类同时是"本机进程管理器"和"部署方式判定器",于是 Docker 下只能靠 4 处硬拦
 * 把用户挡在门外。拆开之后,加一种拓扑 = 加一个实现,不用再改这里的每个方法。</p>
 */
@Component
@Slf4j
public class FutuOpendManager {

    /** 部署渠道(v1.17 起只用于文案/审计;功能分支看 {@link OpendChannel.Caps}) */
    public enum Env { LINUX, DOCKER, MACOS }

    private final LocalProcessChannel local;
    private final ContainerGatewayChannel container;

    public FutuOpendManager(LocalProcessChannel local, ContainerGatewayChannel container) {
        this.local = local;
        this.container = container;
    }

    /** 纯判定(单测):Mac 优先,其次 /.dockerenv,否则 Linux 原生。 */
    static Env detectEnv(String osName, boolean dockerFlag) {
        if (osName != null && osName.toLowerCase(Locale.ROOT).contains("mac")) return Env.MACOS;
        if (dockerFlag) return Env.DOCKER;
        return Env.LINUX;
    }

    /** 当前部署渠道(Mac / Docker / Linux)。 */
    public Env env() { return detectEnv(System.getProperty("os.name"), Files.exists(Path.of("/.dockerenv"))); }

    /**
     * 当前生效的通道 —— 按<b>能力探测</b>选,不按"你是哪种部署"选。
     *
     * <p>判据顺序(前两条都不看"你是不是在容器里"):</p>
     * <ol>
     *   <li>网关<b>写过 status</b> → 容器通道(它真起过)</li>
     *   <li>共享卷<b>挂上了</b>但网关还没起 → 也是容器通道,{@code caps.needsEnable=true},
     *       页面显示那条启用命令(而不是像 v1.16 那样甩一段"自己打包镜像"的教程)。
     *       这一档不能漏:app 容器无条件挂那个卷,所以"卷在、网关没起"正是刚部署完的常态</li>
     *   <li>在容器里但卷没挂(用户自己改过 compose)→ 仍走容器通道,让它去解释怎么挂</li>
     *   <li>其余(systemd 原生 / macOS)→ 本机托管通道</li>
     * </ol>
     *
     * <p>为什么不再问 {@code /.dockerenv} 决定<b>功能</b>:那个只能回答"我在容器里",
     * 回答不了"网关在哪"——而后者才是真正要分支的东西。</p>
     */
    public OpendChannel active() {
        if (container.enabled()) return container;      // 网关起过(写过 status)
        if (container.ctlMounted()) return container;   // 共享卷挂了但网关还没起 → 给"启用命令"那一屏
        if (env() == Env.DOCKER) return container;      // 在容器里但卷没挂(用户改了 compose)→ 也走容器那套说明
        return local;                                    // systemd 原生 / macOS
    }

    public OpendChannel.Caps caps() { return active().caps(); }

    public OpendChannel.Status status() { return active().status(); }

    public OpendChannel.Target target() { return active().target(); }

    public List<String> tail() { return active().tail(); }

    public String osReleaseRaw() { return local.osReleaseRaw(); }

    /** 本机探测到的富途包系统后缀。 */
    public String detectedOsTag() { return local.detectedOsTag(); }

    public OpendChannel.Deps checkDeps() { return active().checkDeps(); }

    public OpendChannel.SelfCheck selfCheck() { return active().selfCheck(); }

    public String download(String version, String osTag, String override) throws IOException, InterruptedException {
        return active().download(version, osTag, override);
    }

    public String installFromStream(java.io.InputStream in, long maxBytes, String uploadName) throws IOException, InterruptedException {
        return active().installFromStream(in, maxBytes, uploadName);
    }

    /** 页面勾了「用官方当前最新版(我们还没核对过这一版)」→ 放行未核对版本。 */
    public void allowUnverified(boolean allow) { local.allowUnverified(allow); }

    public String installFromServerPath(String path) throws IOException, InterruptedException {
        return active().installFromServerPath(path);
    }

    public void configureAndStart(String account, String pwdPlain, int port) throws IOException {
        active().configureAndStart(account, pwdPlain, port);
    }

    public boolean requestSmsCode() { return active().requestSmsCode(); }

    public boolean submitSmsCode(String code) { return active().submitSmsCode(code); }

    public void stop() { active().stop(); }

    /** 容器通道的共享卷里那把 API 私钥(没启用网关时返回 null)。 */
    public java.nio.file.Path apiRsaKeyFile() {
        return container.enabled() && container.target().encrypted() ? container.rsaKeyFile() : null;
    }
}
