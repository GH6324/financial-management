package com.family.finance.service.broker.opend;

import com.family.finance.service.broker.opend.OpendChannel.Phase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.15 起的 OpenD 向导纯逻辑护栏;v1.17 拆通道后这里只留「本机通道 + 部署渠道判定」的部分,
 * 发布物定位挪到 {@link OpendReleaseTest},配置生成挪到 {@link OpendConfigXmlTest}。
 */
class FutuOpendManagerTest {

    @Test
    void md5Hex_is_32_lower_hex() {
        // MD5("123456") 已知值
        assertThat(LocalProcessChannel.md5Hex("123456")).isEqualTo("e10adc3949ba59abbe56e057f20f883e");
        assertThat(LocalProcessChannel.md5Hex("x")).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void startArgs_9x_bind_localhost_and_never_leak_plaintext_password() {
        String md5 = LocalProcessChannel.md5Hex("s3cret");
        List<String> args = LocalProcessChannel.buildStartArgs(
                "/opt/o/FutuOpenD", "9.3.5308", null, "acct", md5, 11111, 22222);
        assertThat(args).contains("-api_ip=127.0.0.1");                 // 只绑本机
        assertThat(args).anyMatch(a -> a.equals("-login_pwd_md5=" + md5)); // 老包才传 MD5
        assertThat(args).noneMatch(a -> a.contains("s3cret"));          // 绝不出现明文密码
        assertThat(args).noneMatch(a -> a.toLowerCase().contains("api_ip=0.0.0.0"));
    }

    /**
     * v1.17 护栏:10.x 起 {@code -login_pwd_md5} / {@code -telnet_port} 都已被富途废弃
     * (实测 {@code -help} 里没有),再传就是启动即失败。新版只能传配置文件,凭据走控制口。
     */
    @Test
    void startArgs_10x_only_pass_cfg_file_no_credentials() {
        List<String> args = LocalProcessChannel.buildStartArgs(
                "/opt/o/FutuOpenD", "10.10.7008", "/data/FutuOpenD.generated.xml",
                "acct", "deadbeef", 11111, 22222);
        assertThat(args).containsExactly("/opt/o/FutuOpenD", "-cfg_file=/data/FutuOpenD.generated.xml");
        assertThat(args).noneMatch(a -> a.contains("login_pwd_md5"));
        assertThat(args).noneMatch(a -> a.contains("telnet_port"));
        assertThat(args).noneMatch(a -> a.contains("acct"));
    }

    /**
     * v1.17:依赖提示按 ldd 实际结果说话。命令行版实测零额外依赖,所以"没缺"时不能再教用户装
     * gtk3/fuse —— 那是桌面版的依赖,而且包名在 Ubuntu 24.04+ 已改成 *t64,照抄必然失败。
     */
    @Test
    void depsInstallCommand_stops_teaching_gtk3_when_nothing_missing() {
        String none = LocalProcessChannel.depsInstallCommand(true, List.of());
        assertThat(none).doesNotContain("libgtk-3-0").doesNotContain("apt-get install");
        assertThat(none).contains("无需额外依赖");

        String missing = LocalProcessChannel.depsInstallCommand(true, List.of("libfoo.so.1"));
        assertThat(missing).contains("libfoo.so.1");
        assertThat(missing).doesNotContain("libgtk-3-0");   // 不再无条件塞这两个包名
    }

    @Test
    void detectEnv_prioritises_mac_then_docker_then_linux() {
        assertThat(FutuOpendManager.detectEnv("Mac OS X", true)).isEqualTo(FutuOpendManager.Env.MACOS);  // Mac 优先
        assertThat(FutuOpendManager.detectEnv("Linux", true)).isEqualTo(FutuOpendManager.Env.DOCKER);   // 有 /.dockerenv
        assertThat(FutuOpendManager.detectEnv("Linux", false)).isEqualTo(FutuOpendManager.Env.LINUX);
    }

    @Test
    void phaseFromLog_detects_sms_and_running() {
        assertThat(OpendLog.phaseFromLog("请输入验证码 verify code")).isEqualTo(Phase.NEEDS_SMS);
        assertThat(OpendLog.phaseFromLog("Login success!")).isEqualTo(Phase.RUNNING);
        assertThat(OpendLog.phaseFromLog("登录成功")).isEqualTo(Phase.RUNNING);
        assertThat(OpendLog.phaseFromLog("登录失败")).isEqualTo(Phase.ERROR);
        assertThat(OpendLog.phaseFromLog("just some noise")).isNull();
    }
}
