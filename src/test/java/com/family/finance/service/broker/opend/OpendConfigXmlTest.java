package com.family.finance.service.broker.opend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.17 · OpenD 配置生成护栏。
 *
 * <p>模板片段照抄官方包里的 {@code FutuOpenD.xml}(10.10.7008),包括那些<b>注释里也出现同名标签</b>
 * 的地方 —— 无脑替换第一个匹配会改到注释里去。</p>
 */
class OpendConfigXmlTest {

    /** 官方模板的真实形态:字段上方有中英文注释,注释里还有同名标签示例。 */
    private static final String OFFICIAL = """
            <futu_opend>
            	<!-- 协议监听地址,不填默认127.0.0.1 -->
            	<!-- Listening address. 127.0.0.1 by default -->
            		<ip>127.0.0.1</ip>
            		<api_port>11111</api_port>
            		<lang>chs</lang>
            		<log_level>info</log_level>
            		<!-- 日志路径 -->
            		<!-- <log_path>D:\\log</log_path> -->
            		<!-- Telnet监听地址,不填默认127.0.0.1 -->
            		<telnet_ip>0.0.0.0</telnet_ip>
            		<telnet_port>22222</telnet_port>
            		<!-- <rsa_private_key>D:\\rsa</rsa_private_key> -->
            </futu_opend>
            """;

    /**
     * 最重要的一条:官方模板把无鉴权的 telnet 控制口默认绑在 {@code 0.0.0.0} 上
     * —— 连上就能重登、发验证码、退进程。我们必须按死成回环地址,而且不给调用方留参数。
     */
    @Test
    void telnet_control_port_is_always_pinned_to_loopback() {
        String out = OpendConfigXml.render(OFFICIAL, "0.0.0.0", 11111, 22222, null);
        assertThat(out).contains("<telnet_ip>127.0.0.1</telnet_ip>");
        assertThat(out).doesNotContain("<telnet_ip>0.0.0.0</telnet_ip>");
        // api 可以按通道要求绑 0.0.0.0(容器内网),但 telnet 不行
        assertThat(out).contains("<ip>0.0.0.0</ip>");
    }

    @Test
    void api_ip_and_ports_follow_channel() {
        String local = OpendConfigXml.render(OFFICIAL, "127.0.0.1", 11111, 22222, null);
        assertThat(local).contains("<ip>127.0.0.1</ip>").contains("<api_port>11111</api_port>");

        String container = OpendConfigXml.render(OFFICIAL, "0.0.0.0", 11188, 22333, null);
        assertThat(container).contains("<ip>0.0.0.0</ip>")
                .contains("<api_port>11188</api_port>")
                .contains("<telnet_port>22333</telnet_port>");
    }

    /** 注释里的 {@code <log_path>} 示例不能被当成真字段改掉。 */
    @Test
    void commented_out_fields_are_left_alone() {
        String out = OpendConfigXml.render(OFFICIAL, "127.0.0.1", 11111, 22222, null);
        assertThat(out).contains("<!-- <log_path>D:\\log</log_path> -->");
    }

    @Test
    void rsa_stays_off_unless_key_given_and_is_idempotent() {
        String off = OpendConfigXml.render(OFFICIAL, "127.0.0.1", 11111, 22222, null);
        assertThat(off).contains("<!-- <rsa_private_key>");          // 原样保持注释
        assertThat(off).doesNotContain("\t\t<rsa_private_key>/ctl");

        String on = OpendConfigXml.render(OFFICIAL, "0.0.0.0", 11111, 22222, "/ctl/opend.pem");
        assertThat(on).contains("<rsa_private_key>/ctl/opend.pem</rsa_private_key>");
        assertThat(on).doesNotContain("<!-- <rsa_private_key>");     // 注释被换成活跃标签

        // 再渲染一次(重复安装/重启)不能出现两个标签
        String again = OpendConfigXml.render(on, "0.0.0.0", 11111, 22222, "/ctl/opend.pem");
        assertThat(again.split("<rsa_private_key>", -1)).hasSize(2);  // 只出现一次
    }

    /** 官方哪天改了结构、字段不见了 → 不硬塞,交给 OpenD 用它自己的默认值,而不是产出一份坏配置。 */
    @Test
    void missing_field_in_template_is_not_forced_in() {
        String slim = "<futu_opend>\n\t<api_port>11111</api_port>\n</futu_opend>\n";
        String out = OpendConfigXml.render(slim, "127.0.0.1", 11222, 22222, null);
        assertThat(out).contains("<api_port>11222</api_port>");
        assertThat(out).doesNotContain("telnet_ip");
    }
}
