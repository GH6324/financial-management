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
 * <p><b>控制口(2026-08-17 在容器里实跑才看清)</b>:官方模板把 {@code telnet_ip} / {@code telnet_port}
 * <b>整行注释掉了</b>(示例值 {@code 127.0.0.1} / {@code 22222}),也就是控制口<b>默认不启用</b>。
 * 我们要用它喂账号密码,所以必须<b>取消注释</b>并写值 —— 而它<b>没有任何鉴权</b>(连上就能重登、发验证码、
 * 退进程),所以地址被<b>硬编码</b>成 {@code 127.0.0.1},不给调用方留参数。这不是配置项,是红线。</p>
 *
 * <p>踩过的坑:第一版只有 {@link #setTag}(会刻意跳过注释里的同名标签),于是"改了值"其实改在注释里,
 * OpenD 根本没启用控制口 —— 日志里连 {@code Telnet监听地址} 那行都不会出现,表现为交互登录连不上。
 * 单测里的模板必须照抄官方那种<b>注释形态</b>,否则测试全绿而现实不通。</p>
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
        // 控制口:官方默认整行注释掉 → 必须"取消注释并设值",光 setTag 会改到注释里去
        s = setOrEnableTag(s, "telnet_ip", TELNET_IP);
        s = setOrEnableTag(s, "telnet_port", String.valueOf(telnetPort));
        s = setTag(s, "lang", "chs");
        s = setTag(s, "log_level", "info");
        if (rsaKeyPath != null && !rsaKeyPath.isBlank()) {
            s = setOrEnableTag(s, "rsa_private_key", rsaKeyPath);
        }
        return s;
    }

    /**
     * 设值;字段<b>被注释掉</b>时先取消注释。
     *
     * <p>官方模板里 telnet 与 rsa 这类"进阶参数"都是注释状态的示例行,不取消注释等于没配。</p>
     */
    static String setOrEnableTag(String xml, String tag, String value) {
        // 已是活跃标签 → 直接改值(重复渲染也走这里,保持幂等)
        Matcher live = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        while (live.find()) {
            if (!isInsideComment(xml, live.start())) return setTag(xml, tag, value);
        }
        // 注释形态:<!-- <tag>示例</tag> -->
        Matcher m = Pattern.compile("<!--\\s*<" + tag + ">[^<]*</" + tag + ">\\s*-->").matcher(xml);
        if (m.find()) {
            return xml.substring(0, m.start()) + "<" + tag + ">" + value + "</" + tag + ">" + xml.substring(m.end());
        }
        // 模板里连注释都没有 → 插在 </futu_opend> 前
        int end = xml.lastIndexOf("</futu_opend>");
        if (end < 0) return xml;
        return xml.substring(0, end) + "\t\t<" + tag + ">" + value + "</" + tag + ">\n" + xml.substring(end);
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

}
