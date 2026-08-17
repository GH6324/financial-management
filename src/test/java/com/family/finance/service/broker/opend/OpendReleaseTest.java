package com.family.finance.service.broker.opend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.17 · 官方发布物定位护栏。
 *
 * <p>这些字符串错一个,原生部署的「下载并安装」就点不动 —— v0.15~v1.16 就是这么坏掉的:
 * 域名换了、文件名换了、我们的白名单还只放老域名(连用户手填官方 URL 都会被自己拒)。</p>
 */
class OpendReleaseTest {

    @Test
    void download_url_uses_current_official_host_and_naming() {
        // 2026-08-17 实测的现行地址形态:Futu_OpenD_<版本>_<系统>.tar.gz
        assertThat(OpendRelease.downloadUrl("10.10.7008", "Ubuntu18.04", null))
                .isEqualTo("https://softwaredownload.futunn.com/Futu_OpenD_10.10.7008_Ubuntu18.04.tar.gz");
        // 老命名(FutuOpenD_<版本>_Ubuntu16.04)已不复存在,不许再出现
        assertThat(OpendRelease.downloadUrl("10.10.7008", "Ubuntu18.04", null))
                .doesNotContain("Ubuntu16.04").doesNotContain("softwarefile");
    }

    @Test
    void allowlist_accepts_official_hosts_and_rejects_others() {
        // 现行下载域名
        assertThat(OpendRelease.requireAllowed("https://softwaredownload.futunn.com/Futu_OpenD_10.10.7008_Centos7.tar.gz"))
                .contains("softwaredownload.futunn.com");
        // 取最新版端点所在域名(302 的 Location 校验会用到)
        assertThat(OpendRelease.requireAllowed("https://www.futunn.com/download/fetch-lasted-link?name=opend-ubuntu"))
                .contains("www.futunn.com");
        // 老域名保留:让它走到"连不上",而不是被我们拒收
        assertThat(OpendRelease.requireAllowed("https://softwarefile.futunn.com/FutuOpenD_9_Centos7.tar.gz"))
                .contains("softwarefile.futunn.com");
        // 非 https 拒绝
        assertThatThrownBy(() -> OpendRelease.requireAllowed("http://softwaredownload.futunn.com/x.tar.gz"))
                .isInstanceOf(IllegalArgumentException.class);
        // 非白名单 host 拒绝(防 SSRF / 投毒)
        assertThatThrownBy(() -> OpendRelease.requireAllowed("https://evil.example.com/x.tar.gz"))
                .isInstanceOf(IllegalArgumentException.class);
        // 近似域名也拒(前缀/后缀混淆)
        assertThatThrownBy(() -> OpendRelease.requireAllowed("https://softwaredownload.futunn.com.evil.io/x.tar.gz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void latest_link_maps_os_to_official_name_param() {
        assertThat(OpendRelease.latestName("Ubuntu18.04")).isEqualTo("opend-ubuntu");
        assertThat(OpendRelease.latestName("Centos7")).isEqualTo("opend-centos");
        assertThat(OpendRelease.latestName("Mac")).isEqualTo("opend-macos");
        assertThat(OpendRelease.latestLinkUrl("Centos7"))
                .isEqualTo("https://www.futunn.com/download/fetch-lasted-link?name=opend-centos");
    }

    @Test
    void osTag_maps_distro_to_current_futu_packages() {
        assertThat(OpendRelease.osTag("ID=centos\nVERSION=7")).isEqualTo("Centos7");
        assertThat(OpendRelease.osTag("ID=rocky")).isEqualTo("Centos7");
        // 官方现在只发 Ubuntu18.04(不再有 Ubuntu16.04);Debian 复用它
        assertThat(OpendRelease.osTag("ID=ubuntu\nPRETTY_NAME=\"Ubuntu 22.04\"")).isEqualTo("Ubuntu18.04");
        assertThat(OpendRelease.osTag("ID=debian")).isEqualTo("Ubuntu18.04");
        assertThat(OpendRelease.osTag("ID=weirdos")).isEmpty();   // 认不出 → 下拉兜底
    }

    @Test
    void packageTag_mac_vs_linux() {
        assertThat(OpendRelease.packageTag("Mac OS X", "")).isEqualTo("Mac");
        assertThat(OpendRelease.packageTag("Linux", "ID=centos")).isEqualTo("Centos7");
        assertThat(OpendRelease.packageTag("Linux", "ID=ubuntu")).isEqualTo("Ubuntu18.04");
    }

    @Test
    void parseVersion_extracts_from_name() {
        assertThat(OpendRelease.parseVersion("FutuOpenD_2.19.1252_Centos7")).isEqualTo("2.19.1252");
        assertThat(OpendRelease.parseVersion("FutuOpenD_9.3.5308_Ubuntu16.04.tar.gz")).isEqualTo("9.3.5308");
        assertThat(OpendRelease.parseVersion("Futu_OpenD_10.10.7008_Ubuntu18.04")).isEqualTo("10.10.7008");
        assertThat(OpendRelease.parseVersion("garbage")).isNull();
    }

    /** 10.x 交互式登录 / 9.x 命令行参数 —— 分流判据。 */
    @Test
    void interactive_login_only_for_10_and_above() {
        assertThat(OpendRelease.isInteractiveLogin("10.10.7008")).isTrue();
        assertThat(OpendRelease.isInteractiveLogin("11.0.1")).isTrue();
        assertThat(OpendRelease.isInteractiveLogin("9.3.5308")).isFalse();
        assertThat(OpendRelease.isInteractiveLogin("2.19.1252")).isFalse();
        // 认不出版本 → 按新版走(官方只发新版了)
        assertThat(OpendRelease.isInteractiveLogin(null)).isTrue();
        assertThat(OpendRelease.isInteractiveLogin("")).isTrue();
        assertThat(OpendRelease.isInteractiveLogin("十点十")).isTrue();
    }
}
