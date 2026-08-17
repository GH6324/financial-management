package com.family.finance.service.broker.opend;

import com.family.finance.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.17 · 容器网关通道护栏(共享卷协议 / 能力位 / 加密判定)。
 *
 * <p>这条通道的全部对外行为都通过一个共享目录发生,所以用临时目录就能完整测出来 ——
 * 不需要真起容器。真起容器那部分在 e2e 与手工验收里做。</p>
 */
class ContainerGatewayChannelTest {

    private ContainerGatewayChannel channel(Path ctl) {
        return new ContainerGatewayChannel(new AppProperties(
                "/tmp/up", "k", 1, "https://x", 1000, "/tmp/opend", ctl.toString(), "opend"));
    }

    private void writeStatus(Path ctl, String phase, String msg) throws IOException {
        Files.writeString(ctl.resolve("status"),
                "phase=" + phase + "\nmessage=" + msg + "\nversion=10.10.7008\napiPort=11111\nts="
                        + Instant.now() + "\n", StandardCharsets.UTF_8);
    }

    /**
     * 空的共享卷 = 用户还没启用这个可选组件:页面要给启用命令,而不是报错。
     *
     * <p>这条很关键:app 容器<b>无条件</b>挂共享卷(compose 不支持条件挂载),所以目录永远存在。
     * 判据必须是"网关写过 status",否则"未启用"这个状态永远探不出来、页面会一直显示成"网关掉线"。</p>
     */
    @Test
    void mounted_but_empty_volume_still_counts_as_not_enabled(@TempDir Path ctl) {
        ContainerGatewayChannel c = channel(ctl);
        assertThat(c.ctlMounted()).isTrue();     // 卷挂了
        assertThat(c.enabled()).isFalse();       // 但网关没起过
        assertThat(c.caps().needsEnable()).isTrue();
        assertThat(c.status().message()).contains("可选组件");
    }

    /** 卷压根没挂(原生部署把 futuCtlDir 指到不存在的路径)也是"未启用"。 */
    @Test
    void not_enabled_yields_enable_command_not_an_error(@TempDir Path base) {
        ContainerGatewayChannel c = channel(base.resolve("absent"));
        assertThat(c.enabled()).isFalse();
        OpendChannel.Caps caps = c.caps();
        assertThat(caps.needsEnable()).isTrue();
        assertThat(caps.enableCommand()).contains("--profile futu");
        assertThat(caps.canLogin()).isFalse();
        OpendChannel.Status st = c.status();
        assertThat(st.phase()).isEqualTo(OpendChannel.Phase.NOT_INSTALLED);
        assertThat(st.message()).contains("可选组件");
    }

    /** 网关活着 → 能登录、能中继验证码;但安装与停止不归 app(容器自己的事)。 */
    @Test
    void alive_gateway_exposes_login_but_not_install_or_stop(@TempDir Path ctl) throws Exception {
        writeStatus(ctl, "NEEDS_SMS", "需要手机短信验证码");
        ContainerGatewayChannel c = channel(ctl);
        assertThat(c.alive()).isTrue();
        OpendChannel.Caps caps = c.caps();
        assertThat(caps.canLogin()).isTrue();
        assertThat(caps.canRelaySms()).isTrue();
        assertThat(caps.needsEnable()).isFalse();
        assertThat(caps.canInstall()).isFalse();     // 下载/校验哈希在网关容器里做
        assertThat(caps.canStop()).isFalse();        // 容器生命周期归 docker,页面不越权
        assertThat(c.status().phase()).isEqualTo(OpendChannel.Phase.NEEDS_SMS);
    }

    /** status 过期(control-loop 停了)→ 判成"没在跑",不能假装还活着。 */
    @Test
    void stale_status_means_gateway_is_down(@TempDir Path ctl) throws Exception {
        Files.writeString(ctl.resolve("status"),
                "phase=RUNNING\nmessage=ok\nts=" + Instant.now().minusSeconds(600) + "\n",
                StandardCharsets.UTF_8);
        ContainerGatewayChannel c = channel(ctl);
        assertThat(c.alive()).isFalse();
        assertThat(c.status().phase()).isEqualTo(OpendChannel.Phase.STOPPED);
        assertThat(c.status().processAlive()).isFalse();
    }

    /** 登录指令落成 key=value 请求文件;密码在文件里(由网关读后立刻删),但不进日志。 */
    @Test
    void login_writes_key_value_request(@TempDir Path ctl) throws Exception {
        writeStatus(ctl, "STARTING", "等登录");
        ContainerGatewayChannel c = channel(ctl);
        c.configureAndStart("acct-1", "p@ss", 11111);

        List<Path> reqs;
        try (var s = Files.list(ctl.resolve("cmd"))) {
            reqs = s.filter(p -> p.toString().endsWith(".req")).toList();
        }
        assertThat(reqs).hasSize(1);
        String body = Files.readString(reqs.get(0), StandardCharsets.UTF_8);
        assertThat(body).contains("op=login").contains("account=acct-1").contains("password=p@ss");
        // 没有残留的 .tmp(先写临时文件再原子改名,免得网关读到半截请求)
        try (var s = Files.list(ctl.resolve("cmd"))) {
            assertThat(s.filter(p -> p.toString().endsWith(".tmp")).toList()).isEmpty();
        }
    }

    @Test
    void sms_and_req_sms_write_their_own_ops(@TempDir Path ctl) throws Exception {
        writeStatus(ctl, "NEEDS_SMS", "要码");
        ContainerGatewayChannel c = channel(ctl);
        assertThat(c.requestSmsCode()).isTrue();
        assertThat(c.submitSmsCode(" 428139 ")).isTrue();
        String all;
        try (var s = Files.list(ctl.resolve("cmd"))) {
            StringBuilder sb = new StringBuilder();
            for (Path p : s.toList()) sb.append(Files.readString(p, StandardCharsets.UTF_8));
            all = sb.toString();
        }
        assertThat(all).contains("op=req-sms").contains("op=sms").contains("code=428139");
    }

    /** 网关没启用时下指令要明确失败,而不是把请求写到一个没人读的目录里。 */
    @Test
    void commands_fail_loudly_when_gateway_absent(@TempDir Path base) {
        ContainerGatewayChannel c = channel(base.resolve("absent"));
        assertThatThrownBy(() -> c.configureAndStart("a", "b", 11111))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("--profile futu");
        assertThat(c.requestSmsCode()).isFalse();
        assertThat(c.submitSmsCode("1")).isFalse();
    }

    /** 安装类操作在这条通道上不可用,而且错误信息要告诉用户该怎么做。 */
    @Test
    void install_is_delegated_to_the_gateway_container(@TempDir Path ctl) {
        ContainerGatewayChannel c = channel(ctl);
        assertThatThrownBy(() -> c.download(null, "Ubuntu18.04", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("哈希清单");
    }

    /** 有私钥才算加密通道 —— app 与 OpenD 用共享卷里同一把。 */
    @Test
    void encryption_flag_follows_shared_key_file(@TempDir Path ctl) throws Exception {
        ContainerGatewayChannel c = channel(ctl);
        assertThat(c.target().encrypted()).isFalse();
        Files.writeString(ctl.resolve("opend.pem"), "-----BEGIN RSA PRIVATE KEY-----\n");
        assertThat(c.target().encrypted()).isTrue();
        assertThat(c.target().host()).isEqualTo("opend");
    }

    @Test
    void unknown_phase_string_does_not_blow_up() {
        assertThat(ContainerGatewayChannel.parsePhase("RUNNING")).isEqualTo(OpendChannel.Phase.RUNNING);
        assertThat(ContainerGatewayChannel.parsePhase("garbage")).isEqualTo(OpendChannel.Phase.NOT_INSTALLED);
        assertThat(ContainerGatewayChannel.parsePhase(null)).isEqualTo(OpendChannel.Phase.NOT_INSTALLED);
    }
}
