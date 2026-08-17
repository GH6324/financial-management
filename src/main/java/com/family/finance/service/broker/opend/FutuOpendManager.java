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

    public FutuOpendManager(LocalProcessChannel local) {
        this.local = local;
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
     * 当前生效的通道。
     *
     * <p>v1.17 第一阶段:仍只有本机通道 —— 容器通道在后续块引入,那时这里换成<b>能力探测</b>
     * (控制卷在不在 / 网关服务连不连得上 / 库里有没有手填地址),而不是问 {@code /.dockerenv}。</p>
     */
    public OpendChannel active() { return local; }

    public OpendChannel.Caps caps() { return active().caps(); }

    public OpendChannel.Status status() { return active().status(); }

    public OpendChannel.Target target() { return active().target(); }

    public List<String> tail() { return active().tail(); }

    public String osReleaseRaw() { return local.osReleaseRaw(); }

    /** 本机探测到的富途包系统后缀。 */
    public String detectedOsTag() { return local.detectedOsTag(); }

    public OpendChannel.Deps checkDeps() { return active().checkDeps(); }

    public OpendChannel.SelfCheck selfCheck() {
        if (env() == Env.DOCKER) {
            // 容器通道还没接线(后续块):如实说明,并给出今天可用的那条路
            return new OpendChannel.SelfCheck(false, Env.DOCKER.name(), List.of(
                    new OpendChannel.CheckItem("部署渠道", false, true, "检测到 Docker · app 容器内不托管 OpenD",
                            "改用 sidecar:docker compose -f docker-compose.yml -f deploy/futu-opend.compose.yml up -d")));
        }
        return active().selfCheck();
    }

    public String download(String version, String osTag, String override) throws IOException, InterruptedException {
        requireNotDocker();
        return active().download(version, osTag, override);
    }

    public String installFromStream(java.io.InputStream in, long maxBytes) throws IOException, InterruptedException {
        requireNotDocker();
        return active().installFromStream(in, maxBytes);
    }

    public String installFromServerPath(String path) throws IOException, InterruptedException {
        requireNotDocker();
        return active().installFromServerPath(path);
    }

    public void configureAndStart(String account, String pwdPlain, int port) throws IOException {
        if (env() == Env.DOCKER) throw new IllegalStateException("Docker 环境请用 sidecar,不在 app 容器内托管 OpenD");
        active().configureAndStart(account, pwdPlain, port);
    }

    public boolean requestSmsCode() { return active().requestSmsCode(); }

    public boolean submitSmsCode(String code) { return active().submitSmsCode(code); }

    public void stop() { active().stop(); }

    private void requireNotDocker() throws IOException {
        if (env() == Env.DOCKER) throw new IOException("Docker 环境请用 sidecar(不在 app 容器内托管 OpenD)");
    }
}
