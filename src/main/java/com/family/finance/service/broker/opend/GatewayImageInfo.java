package com.family.finance.service.broker.opend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 网关镜像的 digest(v1.17.1)。
 *
 * <p><b>为什么要查</b>:向导页那三条自查命令里,镜像 digest 原来写的是 {@code @sha256:<见 Release 页>} ——
 * 等于让用户自己去翻 Release 页再拼命令。用户要的是<b>贴走就能跑</b>。</p>
 *
 * <p><b>为什么运行时查而不是写死在代码/配置里</b>:digest 每次发版都会变。写死就意味着
 * 每次发版都要有人记得更新它,漏一次页面上就是一个<b>错的</b>校验值 —— 那比没有更糟
 * (用户照着验会得到"验证失败",然后开始怀疑镜像被人动过)。GHCR 的 manifest 查询是匿名的,
 * 一次 HEAD 就能拿到权威值。</p>
 *
 * <p>拿不到就<b>诚实降级</b>:页面回落成按 tag 拉,并说明"没查到 digest"。绝不猜、绝不显示旧值。</p>
 */
@Component
@Slf4j
public class GatewayImageInfo {

    /** 网关镜像仓库(GHCR 上的路径,不含 registry 前缀) */
    public static final String REPO = "luodi-nate/financial-management-futu-opend";
    public static final String IMAGE = "ghcr.io/" + REPO;

    private static final Duration TTL = Duration.ofHours(6);
    private static final String ACCEPT =
            "application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,"
            + "application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json";

    private final String appVersion;
    private volatile String cached;      // sha256:...
    private volatile Instant cachedAt;
    private volatile String cachedTag;

    public GatewayImageInfo(@Value("${app.version:}") String appVersion) {
        this.appVersion = appVersion;
    }

    /** 当前 app 版本对应的镜像 tag(镜像与 app 同版本发布)。 */
    public String tag() {
        return (appVersion == null || appVersion.isBlank()) ? "latest" : "v" + appVersion.trim();
    }

    /**
     * 实际用来查 digest 的 tag:先试 {@code v<当前版本>},没有就退 {@code latest}。
     *
     * <p>两种情况都会落到 latest 上,而且都是<b>对的</b>:</p>
     * <ul>
     *   <li>开发期(版本已 bump、镜像还没发)—— 这时 v1.x.y 本来就不存在</li>
     *   <li>用户没升到最新 app —— 他 compose 里拉的默认就是 {@code :latest},页面给 latest 的 digest 才对得上</li>
     * </ul>
     */
    String resolveTag() {
        String v = tag();
        if (!"latest".equals(v) && fetchDigest(v).isPresent()) return v;
        return "latest";
    }

    /** 页面上用的完整引用:能查到 digest 就用 digest(不认 tag),否则退回 tag。 */
    public String reference() {
        return digest().map(d -> IMAGE + "@" + d).orElse(IMAGE + ":" + tag());
    }

    /** digest 查得到吗(页面据此决定要不要提示"这次没查到")。 */
    public boolean hasDigest() { return digest().isPresent(); }

    /**
     * 查这个 tag 的 manifest digest(带 6 小时缓存)。
     *
     * <p>失败返回空 —— 调用方必须能接受"没有",不许拿旧值或猜的值糊弄。</p>
     */
    public Optional<String> digest() {
        String c = cached;
        if (c != null && cachedAt != null && cachedAt.isAfter(Instant.now().minus(TTL))) {
            return Optional.of(c);
        }
        String tag = resolveTag();
        Optional<String> fresh = fetchDigest(tag);
        if (fresh.isPresent()) {
            cached = fresh.get(); cachedAt = Instant.now(); cachedTag = tag;
        }
        return fresh;
    }

    private Optional<String> fetchDigest(String tag) {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            // GHCR 的公开镜像也要先换一个匿名 token
            HttpRequest tokReq = HttpRequest.newBuilder(URI.create(
                    "https://ghcr.io/token?scope=repository:" + REPO + ":pull&service=ghcr.io"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> tokResp = http.send(tokReq, HttpResponse.BodyHandlers.ofString());
            if (tokResp.statusCode() != 200) return Optional.empty();
            String token = extractJsonString(tokResp.body(), "token");
            if (token == null) return Optional.empty();

            HttpRequest manReq = HttpRequest.newBuilder(URI.create(
                    "https://ghcr.io/v2/" + REPO + "/manifests/" + tag))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", ACCEPT)
                    .timeout(Duration.ofSeconds(6))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> manResp = http.send(manReq, HttpResponse.BodyHandlers.discarding());
            if (manResp.statusCode() != 200) {
                log.debug("[opend] 查网关镜像 digest:{} 返回 {}", tag, manResp.statusCode());
                return Optional.empty();
            }
            return manResp.headers().firstValue("docker-content-digest")
                    .map(String::trim).filter(d -> d.startsWith("sha256:"));
        } catch (Exception e) {
            log.debug("[opend] 查网关镜像 digest 失败(页面会退回按 tag 拉): {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 向导页「请你自己也验一遍」那三条命令(v1.17.1)。
     *
     * <p>刻意放在 Java 侧拼:模板里做字符串拼接会踩 Thymeleaf 的 fragment 选择器解析 ——
     * {@code ~{tpl :: cmd(...)}} 里的参数一旦含 {@code /} 或 {@code |}(而 URL 和 shell 管道里全是),
     * 就会被当成选择器语法,报 {@code Invalid syntax in selector}。而且拼在这里还能单测。</p>
     *
     * @param pkgFile 官方安装包文件名(清单里没有已核对版本时给个占位)
     */
    public java.util.List<String> verifyCommands(String pkgFile) {
        String ref = reference();
        return java.util.List.of(
                "# 1) 我们的镜像:按 digest 拉(不认 tag),并验构建来源",
                "docker pull " + ref,
                "gh attestation verify --repo LuoDi-Nate/financial-management oci://" + ref,
                "# 2) 镜像里确实没有富途文件",
                "docker run --rm --entrypoint ls " + ref + " /opt/futu",
                "# 3) 官方包的 MD5:CDN 响应头里的 etag 实测等于文件 MD5",
                "curl -sI https://softwaredownload.futunn.com/" + pkgFile + " | grep -i etag");
    }

    /** 极小的 JSON 取值(只为拿 token,不值得为它引一个解析器)。 */
    static String extractJsonString(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int q1 = json.indexOf('"', json.indexOf(':', i + needle.length()) + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}
