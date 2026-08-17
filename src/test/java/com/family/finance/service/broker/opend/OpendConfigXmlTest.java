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

    /**
     * 官方模板的<b>真实形态</b>(2026-08-17 从 10.10.7008 包内 FutuOpenD.xml 照抄):
     * 基础参数是活跃标签,而 telnet / rsa 这类"进阶参数"<b>整行被注释掉</b>,
     * 并且每个字段上方还有中英文注释(注释里也出现同名标签)。
     *
     * <p>这个形态很重要:第一版测试自己写了个"telnet 是活跃标签"的模板,于是测试全绿,
     * 而真容器里控制口压根没启用 —— 值改进了注释里。</p>
     */
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
            		<!-- Telnet listening address. 127.0.0.1 by default -->
            		<!-- <telnet_ip>127.0.0.1</telnet_ip> -->
            		<!-- Telnet监听端口 -->
            		<!-- <telnet_port>22222</telnet_port> -->
            		<!-- API协议加密私钥文件路径,不设置则不加密 -->
            		<!-- <rsa_private_key>D:\\rsa</rsa_private_key> -->
            </futu_opend>
            """;

    /**
     * 最重要的两条:
     * ① 控制口在官方模板里是<b>注释掉的</b>(默认不启用)→ 我们要用它就必须<b>取消注释</b>,
     *    否则 OpenD 连 {@code Telnet监听地址} 都不会打印,交互登录压根连不上(容器实跑才暴露);
     * ② 它<b>没有鉴权</b> → 取消注释时地址必须按死成回环,不给调用方留参数。
     */
    @Test
    void commented_out_telnet_is_enabled_and_pinned_to_loopback() {
        String out = OpendConfigXml.render(OFFICIAL, "0.0.0.0", 11111, 22222, null);
        // 取消注释成了活跃标签
        assertThat(out).contains("<telnet_ip>127.0.0.1</telnet_ip>");
        assertThat(out).contains("<telnet_port>22222</telnet_port>");
        assertThat(out).doesNotContain("<!-- <telnet_ip>");
        assertThat(out).doesNotContain("<!-- <telnet_port>");
        // 绝不允许对网络开放
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
    /**
     * 官方哪天改了结构、连注释示例都没了 → 基础字段不硬塞(交给 OpenD 用默认值);
     * 但控制口是我们【必须】有的能力,所以它会被插进去 —— 否则交互登录直接不可用。
     */
    @Test
    void missing_basic_field_is_not_forced_but_control_port_is() {
        String slim = "<futu_opend>\n\t<api_port>11111</api_port>\n</futu_opend>\n";
        String out = OpendConfigXml.render(slim, "127.0.0.1", 11222, 22222, null);
        assertThat(out).contains("<api_port>11222</api_port>");
        assertThat(out).doesNotContain("<log_level>");                    // 模板没有就不塞
        assertThat(out).contains("<telnet_ip>127.0.0.1</telnet_ip>");     // 控制口必须有
        assertThat(out).contains("<telnet_port>22222</telnet_port>");
    }
}
