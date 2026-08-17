package com.family.finance.service.broker.opend;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 富途 OpenD 官方发布物的定位逻辑(v1.17)。
 *
 * <p><b>为什么单独一个类</b>:v0.15 把下载地址写死在管理器里,富途改了分发域名与文件名之后
 * 「下载并安装」在<b>原生部署上也点不动了</b>,而且我们的白名单只放老域名 —— 用户手填现行官方
 * URL 会被我们自己拒掉,连手动救的路都堵着。这些都是纯字符串逻辑,拎出来单测护住。</p>
 *
 * <p>2026-08-17 实测(beta):</p>
 * <ul>
 *   <li>老域名 {@code softwarefile.futunn.com} 的 443 已连不上,旧文件名 404</li>
 *   <li>现行下载:{@code https://softwaredownload.futunn.com/Futu_OpenD_10.10.7008_Ubuntu18.04.tar.gz}
 *       —— 注意是 {@code Futu_OpenD_} 带下划线、系统标识是 {@code Ubuntu18.04},
 *       和我们原来拼的 {@code FutuOpenD_<版本>_Ubuntu16.04} 完全不同</li>
 *   <li>取最新版有稳定端点:{@code https://www.futunn.com/download/fetch-lasted-link?name=opend-ubuntu}
 *       → 302 到具体版本包(还有 {@code -centos}/{@code -macos}/{@code -windows})</li>
 * </ul>
 */
public final class OpendRelease {

    /** 现行官方分发域名(2026-08 实测) */
    public static final String DOWNLOAD_HOST = "softwaredownload.futunn.com";
    /** 「取最新版」端点所在域名 */
    public static final String LATEST_HOST = "www.futunn.com";
    /** 历史分发域名:443 已不通,但保留在白名单里 —— 用户手上可能还有老链接,让它走到"连不上"而不是被我们拒收 */
    public static final String LEGACY_HOST = "softwarefile.futunn.com";

    static final Set<String> ALLOWED_HOSTS = Set.of(DOWNLOAD_HOST, LATEST_HOST, LEGACY_HOST);

    private OpendRelease() {}

    /** 系统标识 → 官方 {@code fetch-lasted-link} 的 name 参数。 */
    public static String latestName(String osTag) {
        String t = osTag == null ? "" : osTag.toLowerCase(Locale.ROOT);
        if (t.startsWith("mac")) return "opend-macos";
        if (t.startsWith("centos")) return "opend-centos";
        return "opend-ubuntu";
    }

    /** 「取最新版」端点(返回 302,Location 即权威下载地址)。 */
    public static String latestLinkUrl(String osTag) {
        return "https://" + LATEST_HOST + "/download/fetch-lasted-link?name=" + latestName(osTag);
    }

    /** 按官方现行命名拼包文件名(取最新版失败时的兜底)。 */
    public static String fileName(String version, String osTag) {
        return "Futu_OpenD_" + version.trim() + "_" + osTag.trim() + ".tar.gz";
    }

    /**
     * 构造下载 URL;override 非空则原样用。两种情况都强制 https + host 白名单(防 SSRF / 投毒)。
     */
    public static String downloadUrl(String version, String osTag, String override) {
        String url = (override != null && !override.isBlank())
                ? override.trim()
                : "https://" + DOWNLOAD_HOST + "/" + fileName(version, osTag);
        return requireAllowed(url);
    }

    /** 校验一个 URL 能不能下:必须 https + 官方白名单 host。 */
    public static String requireAllowed(String url) {
        URI u = URI.create(url);
        if (!"https".equalsIgnoreCase(u.getScheme())) throw new IllegalArgumentException("仅允许 https 下载地址");
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!ALLOWED_HOSTS.contains(host)) {
            throw new IllegalArgumentException("只接受富途官方域名的下载地址(" + String.join(" / ", ALLOWED_HOSTS) + "),收到:" + host);
        }
        return url;
    }

    /**
     * 从 /etc/os-release 内容判定富途包系统标识;认不出返回 "" 让 UI 下拉兜底。
     *
     * <p>官方现在只发 Ubuntu18.04 与 Centos7 两个 Linux 包(不再有 Ubuntu16.04)。
     * Ubuntu18.04 包按 glibc 2.27 构建,在更新的发行版上向上兼容 —— beta 实测在 glibc 2.36
     * 的 debian:12-slim 上 {@code ldd} 零缺失。</p>
     */
    public static String osTag(String osRelease) {
        if (osRelease == null) return "";
        String s = osRelease.toLowerCase(Locale.ROOT);
        if (s.contains("centos") || s.contains("rhel") || s.contains("red hat") || s.contains("rocky") || s.contains("alma")) return "Centos7";
        if (s.contains("ubuntu") || s.contains("debian")) return "Ubuntu18.04";
        return "";
    }

    /** 下载包系统标识:Mac → Mac;否则按 /etc/os-release 判。 */
    public static String packageTag(String osName, String osRelease) {
        if (osName != null && osName.toLowerCase(Locale.ROOT).contains("mac")) return "Mac";
        return osTag(osRelease);
    }

    /** 从包文件名 / 解压目录名解析版本号。容忍 {@code FutuOpenD_2.19.1252} 与 {@code Futu_OpenD_10.10.7008} 两种命名。 */
    public static String parseVersion(String nameOrDir) {
        if (nameOrDir == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Futu_?OpenD[_-]([0-9]+(?:\\.[0-9]+)+)").matcher(nameOrDir);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 版本号是否 ≥ 10.0 —— 决定启动方式。
     *
     * <p>10.x 起<b>不再接受命令行传密码</b>({@code -login_pwd_md5} 不在受支持参数里,
     * {@code -telnet_port} 也只能在 XML 配),改成进程起来后交互式登录。9.x 老包仍吃老参数,
     * 所以这里按版本分流,而不是一刀切。</p>
     */
    public static boolean isInteractiveLogin(String version) {
        if (version == null || version.isBlank()) return true;   // 认不出 → 按新版走(官方只发新版)
        try {
            int major = Integer.parseInt(version.trim().split("\\.")[0]);
            return major >= 10;
        } catch (Exception e) { return true; }
    }
}
