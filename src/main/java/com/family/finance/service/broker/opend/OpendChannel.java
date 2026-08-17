package com.family.finance.service.broker.opend;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 富途 OpenD 网关「通道」抽象(v1.17)。
 *
 * <p>为什么要这层:v0.15~v1.16 只有「本机子进程」一种托管方式,Docker 下靠 7 处 {@code Env.DOCKER} 硬拦
 * + 模板里 10 处 {@code channel != 'DOCKER'} 分支挡住。再加一种拓扑(可选网关容器)就会变成两套并行实现,
 * 下一次改动必然只改一边。所以把「OpenD 在哪、怎么装、怎么喂凭据」收进一个接口,三种实现各自负责:</p>
 * <ul>
 *   <li>{@link LocalProcessChannel} —— 应用自己当子进程托管(systemd 原生 / macOS)</li>
 *   <li>{@code ContainerGatewayChannel} —— 我们发布的可选网关容器(compose profile: futu)</li>
 *   <li>{@code ExternalChannel} —— OpenD 跑在别处(家里 NAS / 反向隧道 / 用户自备镜像)</li>
 * </ul>
 *
 * <p><b>页面按 {@link Caps 能力}渲染,不按"你是哪种部署"渲染</b>。这样出现第四种拓扑时不用再加枚举值 ——
 * 加枚举值编译器只抓穷尽 switch,抓不到模板里的字符串条件(v0.14 加 METAL 就是这么上线才发现的)。</p>
 *
 * <p><b>只读铁律</b>:本层只负责把网关跑起来;查询/下单与否由 {@code BrokerClient} 决定,我方永不解锁交易。</p>
 */
public interface OpendChannel {

    enum Phase { NOT_INSTALLED, DOWNLOADING, INSTALLED, STARTING, NEEDS_SMS, RUNNING, STOPPED, ERROR }

    /** 通道种类(用于日志/审计/页面文案,<b>不</b>用于功能分支 —— 分支看 {@link Caps})。 */
    enum Kind { LOCAL, CONTAINER, EXTERNAL }

    record Status(Phase phase, String version, String message, int apiPort, boolean processAlive,
                  String channel, List<String> logTail) {}

    record Deps(boolean ok, List<String> missing, String installCommand) {}

    /** 环境自检项;hard=true 的失败会拦住启动。 */
    record CheckItem(String name, boolean ok, boolean hard, String detail, String fix) {}

    record SelfCheck(boolean ok, String channel, List<CheckItem> items) {}

    /**
     * 这个通道能做什么 —— 向导页据此决定显示哪些步骤。
     *
     * @param needsEnable   网关还没启用(容器通道未起)→ 页面显示 {@code enableCommand} 让用户跑一条命令
     * @param enableCommand 启用命令(仅 needsEnable 时有意义)
     */
    record Caps(boolean canInstall, boolean canUpload, boolean canLogin, boolean canRelaySms,
                boolean canStop, boolean showTerminal, boolean needsEnable, String enableCommand) {}

    /** app 该连哪儿;encrypted=true 时富途 SDK 走 RSA 加密通道。 */
    record Target(String host, int port, boolean encrypted) {}

    Kind kind();

    Caps caps();

    Status status();

    Deps checkDeps();

    SelfCheck selfCheck();

    Target target();

    List<String> tail();

    /** 下载 + 解包;返回解析到的版本。 */
    String download(String version, String osTag, String override) throws IOException, InterruptedException;

    /** 上传已下好的 tar.gz(墙内退路)。 */
    String installFromStream(InputStream in, long maxBytes) throws IOException, InterruptedException;

    /** 从服务器上已存在的 tar.gz 路径导入。 */
    String installFromServerPath(String path) throws IOException, InterruptedException;

    /** 配置 + 启动 + 登录(密码明文用完即弃)。 */
    void configureAndStart(String account, String pwdPlain, int port) throws IOException;

    boolean requestSmsCode();

    boolean submitSmsCode(String code);

    void stop();
}
