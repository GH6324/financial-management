package com.family.finance.service.broker.opend;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.17.1 · 向导页自查命令护栏。
 *
 * <p>这几条命令是要用户<b>贴走就跑</b>的 —— 以前 digest 位置写的是 {@code <见 Release 页>},
 * 等于让他自己去翻页面再拼命令。所以这里守两件事:命令里不许再有占位尖括号;
 * 拿不到 digest 时必须<b>诚实降级</b>成按 tag 拉,而不是拼一个假的 sha256。</p>
 */
class GatewayImageInfoTest {

    /** 查得到 digest 的情形(不出网:直接给定值)。 */
    private GatewayImageInfo withDigest(String version, String digest) {
        return new GatewayImageInfo(version) {
            @Override public Optional<String> digest() { return Optional.ofNullable(digest); }
        };
    }

    @Test
    void tag_follows_app_version() {
        assertThat(withDigest("1.17.1", null).tag()).isEqualTo("v1.17.1");
        assertThat(withDigest("", null).tag()).isEqualTo("latest");
        assertThat(withDigest(null, null).tag()).isEqualTo("latest");
    }

    @Test
    void reference_prefers_digest_over_tag() {
        String d = "sha256:1451021ce586ce32b09600a12d2248edce2544ab97d176fe3d6b32d1ab7af79c";
        assertThat(withDigest("1.17.1", d).reference()).isEqualTo(GatewayImageInfo.IMAGE + "@" + d);
    }

    /** 查不到就退回 tag —— 绝不能编一个 sha256 出来(用户照着验会"验证失败",然后开始怀疑镜像被动过)。 */
    @Test
    void reference_falls_back_to_tag_without_inventing_a_digest() {
        GatewayImageInfo g = withDigest("1.17.1", null);
        assertThat(g.hasDigest()).isFalse();
        assertThat(g.reference()).isEqualTo(GatewayImageInfo.IMAGE + ":v1.17.1");
        assertThat(g.reference()).doesNotContain("sha256");
    }

    /** 命令要能直接执行:不许再出现 <见 Release 页> 这类要用户自己替换的占位符。 */
    @Test
    void verify_commands_are_copy_paste_runnable() {
        String d = "sha256:1451021ce586ce32b09600a12d2248edce2544ab97d176fe3d6b32d1ab7af79c";
        List<String> cmds = withDigest("1.17.1", d).verifyCommands("Futu_OpenD_10.10.7008_Ubuntu18.04.tar.gz");

        assertThat(cmds).anyMatch(c -> c.startsWith("docker pull ") && c.contains("@" + d));
        assertThat(cmds).anyMatch(c -> c.startsWith("gh attestation verify ") && c.contains("oci://"));
        assertThat(cmds).anyMatch(c -> c.contains("--entrypoint ls") && c.endsWith("/opt/futu"));
        assertThat(cmds).anyMatch(c -> c.contains("curl -sI") && c.contains("Futu_OpenD_10.10.7008_Ubuntu18.04.tar.gz"));
        // 没有留给用户去替换的尖括号占位(注释行里的中文不算)
        assertThat(cmds.stream().filter(c -> !c.startsWith("#")))
                .allSatisfy(c -> assertThat(c).doesNotContain("<").doesNotContain(">"));
    }

    /** 注释行以 # 打头 —— fragment 靠这个把它渲染成注释色、并且不加 $ 提示符。 */
    @Test
    void comment_lines_are_marked_so_the_block_can_style_them() {
        List<String> cmds = withDigest("1.17.1", "sha256:abc").verifyCommands("pkg.tar.gz");
        assertThat(cmds.stream().filter(c -> c.startsWith("#"))).hasSize(3);
    }

    @Test
    void tiny_json_extractor_reads_the_token() {
        assertThat(GatewayImageInfo.extractJsonString("{\"token\":\"abc123\",\"expires_in\":300}", "token"))
                .isEqualTo("abc123");
        assertThat(GatewayImageInfo.extractJsonString("{\"other\":1}", "token")).isNull();
        assertThat(GatewayImageInfo.extractJsonString(null, "token")).isNull();
    }
}
