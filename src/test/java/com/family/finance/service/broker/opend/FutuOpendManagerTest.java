package com.family.finance.service.broker.opend;

import com.family.finance.service.broker.opend.OpendChannel.Phase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v0.15 · OpenD 傻瓜向导纯逻辑护栏(下载白名单 / 密码只 MD5 / 启动只绑本机 / OS→包 / 日志→阶段)。
 */
class FutuOpendManagerTest {

    @Test
    void osTag_maps_distro_to_futu_package() {
        assertThat(LocalProcessChannel.osTag("ID=centos\nVERSION=7")).isEqualTo("Centos7");
        assertThat(LocalProcessChannel.osTag("ID=rocky")).isEqualTo("Centos7");
        assertThat(LocalProcessChannel.osTag("ID=ubuntu\nPRETTY_NAME=\"Ubuntu 22.04\"")).isEqualTo("Ubuntu16.04");
        assertThat(LocalProcessChannel.osTag("ID=debian")).isEqualTo("Ubuntu16.04");
        assertThat(LocalProcessChannel.osTag("ID=weirdos")).isEmpty();   // 认不出 → 下拉兜底
    }

    @Test
    void depsInstallCommand_matches_pkg_manager() {
        assertThat(LocalProcessChannel.isAptOs("ID=ubuntu")).isTrue();
        assertThat(LocalProcessChannel.isAptOs("ID=centos")).isFalse();
        assertThat(LocalProcessChannel.depsInstallCommand(true)).contains("apt-get").contains("libgtk-3-0");
        assertThat(LocalProcessChannel.depsInstallCommand(false)).contains("yum").contains("gtk3");
    }

    @Test
    void downloadUrl_builds_and_enforces_https_and_host_allowlist() {
        assertThat(LocalProcessChannel.downloadUrl("2.19.1252", "Centos7", null))
                .isEqualTo("https://softwarefile.futunn.com/FutuOpenD_2.19.1252_Centos7.tar.gz");
        // override 走白名单校验
        assertThat(LocalProcessChannel.downloadUrl("x", "y", "https://softwarefile.futunn.com/FutuOpenD_9_Centos7.tar.gz"))
                .contains("softwarefile.futunn.com");
        // 非 https 拒绝
        assertThatThrownBy(() -> LocalProcessChannel.downloadUrl("v", "o", "http://softwarefile.futunn.com/x.tar.gz"))
                .isInstanceOf(IllegalArgumentException.class);
        // 非白名单 host 拒绝(防 SSRF / 投毒)
        assertThatThrownBy(() -> LocalProcessChannel.downloadUrl("v", "o", "https://evil.example.com/x.tar.gz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseVersion_extracts_from_name() {
        assertThat(LocalProcessChannel.parseVersion("FutuOpenD_2.19.1252_Centos7")).isEqualTo("2.19.1252");
        assertThat(LocalProcessChannel.parseVersion("FutuOpenD_9.3.5308_Ubuntu16.04.tar.gz")).isEqualTo("9.3.5308");
        assertThat(LocalProcessChannel.parseVersion("Futu_OpenD_10.8.6818_Ubuntu18.04")).isEqualTo("10.8.6818"); // 新版带下划线
        assertThat(LocalProcessChannel.parseVersion("garbage")).isNull();
    }

    @Test
    void md5Hex_is_32_lower_hex() {
        // MD5("123456") 已知值
        assertThat(LocalProcessChannel.md5Hex("123456")).isEqualTo("e10adc3949ba59abbe56e057f20f883e");
        assertThat(LocalProcessChannel.md5Hex("x")).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void startArgs_bind_localhost_and_never_leak_plaintext_password() {
        String md5 = LocalProcessChannel.md5Hex("s3cret");
        List<String> args = LocalProcessChannel.buildStartArgs("/opt/o/FutuOpenD", "acct", md5, 11111, 22222);
        assertThat(args).contains("-api_ip=127.0.0.1");                 // 只绑本机
        assertThat(args).anyMatch(a -> a.equals("-login_pwd_md5=" + md5)); // 传 MD5
        assertThat(args).noneMatch(a -> a.contains("s3cret"));          // 绝不出现明文密码
        assertThat(args).noneMatch(a -> a.toLowerCase().contains("api_ip=0.0.0.0"));
    }

    @Test
    void detectEnv_prioritises_mac_then_docker_then_linux() {
        assertThat(FutuOpendManager.detectEnv("Mac OS X", true)).isEqualTo(FutuOpendManager.Env.MACOS);  // Mac 优先
        assertThat(FutuOpendManager.detectEnv("Linux", true)).isEqualTo(FutuOpendManager.Env.DOCKER);   // 有 /.dockerenv
        assertThat(FutuOpendManager.detectEnv("Linux", false)).isEqualTo(FutuOpendManager.Env.LINUX);
    }

    @Test
    void packageTag_mac_vs_linux() {
        assertThat(LocalProcessChannel.packageTag("Mac OS X", "")).isEqualTo("Mac");
        assertThat(LocalProcessChannel.packageTag("Linux", "ID=centos")).isEqualTo("Centos7");
        assertThat(LocalProcessChannel.packageTag("Linux", "ID=ubuntu")).isEqualTo("Ubuntu16.04");
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
