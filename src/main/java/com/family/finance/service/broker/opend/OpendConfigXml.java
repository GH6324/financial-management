package com.family.finance.service.broker.opend;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成 OpenD 的运行配置(v1.17)。
 *
 * <p>10.x 起 OpenD <b>不再接受命令行传密码</b>({@code -login_pwd_md5} 已不在受支持参数里),
 * telnet 控制口也只能在 XML 里配 —— 所以启动方式从"一串命令行参数"变成"一个配置文件 + 交互登录"。</p>
 *
 * <p><b>基于官方模板改,而不是自己写一份</b>:官方包里自带 {@code FutuOpenD.xml},字段会随版本演进;
 * 我们只把关心的几个值换掉,其余原样保留。生成的副本写到我们自己的目录,不动包内文件(幂等、可重复安装)。</p>
 *
 * <p><b>安全要点(2026-08-17 实测)</b>:官方模板里 {@code telnet_ip} 的默认值是 {@code 0.0.0.0} ——
 * 也就是那个<b>没有鉴权</b>的控制口默认对所有网络接口开放,连上就能重登、发验证码、退进程。
 * 所以这里把它<b>硬编码</b>成 {@code 127.0.0.1},不给调用方留参数。这不是配置项,是红线。</p>
 */
public final class OpendConfigXml {

    /** telnet 控制口只允许绑本机回环 —— 它没有鉴权,不能对网络开放。 */
    public static final String TELNET_IP = "127.0.0.1";

    private OpendConfigXml() {}

    /**
     * 用官方模板生成我们的配置。
     *
     * @param officialXml 包内 {@code FutuOpenD.xml} 原文
     * @param apiIp       API 监听地址:本机托管填 {@code 127.0.0.1};网关容器里要被 app 容器连到,填 {@code 0.0.0.0}
     *                    (容器不对宿主发布端口,所以 0.0.0.0 只在 compose 内网可达)
     * @param apiPort     API 端口(富途 SDK 连这个)
     * @param telnetPort  控制口端口(只在本机/容器内可达)
     * @param rsaKeyPath  非空则给 API 通道开 RSA 加密(客户端要用同一把私钥);null = 不加密
     */
    public static String render(String officialXml, String apiIp, int apiPort, int telnetPort, String rsaKeyPath) {
        if (officialXml == null || officialXml.isBlank()) throw new IllegalArgumentException("官方 FutuOpenD.xml 模板为空");
        String s = officialXml;
        s = setTag(s, "ip", apiIp);
        s = setTag(s, "api_port", String.valueOf(apiPort));
        s = setTag(s, "telnet_ip", TELNET_IP);
        s = setTag(s, "telnet_port", String.valueOf(telnetPort));
        s = setTag(s, "lang", "chs");
        s = setTag(s, "log_level", "info");
        if (rsaKeyPath != null && !rsaKeyPath.isBlank()) {
            s = enableRsa(s, rsaKeyPath);
        }
        return s;
    }

    /**
     * 替换第一个<b>未被注释</b>的 {@code <tag>值</tag>}。
     *
     * <p>官方模板里每个字段上方都有中英文注释,注释里也出现同名标签(如
     * {@code <!-- <log_path>D:\log</log_path> -->}),所以不能无脑替换第一个匹配。</p>
     */
    static String setTag(String xml, String tag, String value) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        while (m.find()) {
            if (isInsideComment(xml, m.start())) continue;
            return xml.substring(0, m.start()) + "<" + tag + ">" + value + "</" + tag + ">" + xml.substring(m.end());
        }
        return xml;   // 模板里没有这个字段(官方改了结构)→ 不硬塞,交由 OpenD 用默认值
    }

    /** 该位置是否落在某个 {@code <!-- ... -->} 里。 */
    static boolean isInsideComment(String xml, int pos) {
        int open = xml.lastIndexOf("<!--", pos);
        if (open < 0) return false;
        int close = xml.indexOf("-->", open);
        return close < 0 || close > pos;
    }

    /** 打开 RSA:官方模板里这一行是注释掉的,换成活跃标签。 */
    static String enableRsa(String xml, String keyPath) {
        Matcher m = Pattern.compile("<!--\\s*<rsa_private_key>[^<]*</rsa_private_key>\\s*-->").matcher(xml);
        if (m.find()) {
            return xml.substring(0, m.start()) + "<rsa_private_key>" + keyPath + "</rsa_private_key>" + xml.substring(m.end());
        }
        // 已经是活跃标签(重复生成)→ 直接改值
        if (Pattern.compile("<rsa_private_key>").matcher(xml).find()) return setTag(xml, "rsa_private_key", keyPath);
        // 模板里连注释都没有 → 插在 </futu_opend> 前
        int end = xml.lastIndexOf("</futu_opend>");
        if (end < 0) return xml;
        return xml.substring(0, end) + "\t\t<rsa_private_key>" + keyPath + "</rsa_private_key>\n" + xml.substring(end);
    }
}
