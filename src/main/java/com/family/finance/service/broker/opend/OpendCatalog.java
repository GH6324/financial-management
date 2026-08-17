package com.family.finance.service.broker.opend;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * 已核对版本清单(v1.17)。
 *
 * <p><b>为什么需要它</b>:对接 OpenD 等于在家里跑一个能操作真实券商账户的网关,而
 * <b>富途官网不公布任何 md5 / sha256</b>(通读官方下载页确认)。唯一能从官方侧拿到的校验值是
 * 腾讯云 COS 的 {@code etag} —— 实测它就等于文件 MD5,但它和安装包走<b>同一条 TLS、同一个 CDN</b>,
 * 只证明"传输没坏",不是独立信任锚。</p>
 *
 * <p>所以做法是:<b>我们下载、核对、把哈希钉进这个仓库</b>(有 git 历史、可 review),
 * 安装时现算一遍,<b>不一致就拒绝安装</b>。用户自己算的值、仓库里钉的值、CDN 给的 etag 三者对上,
 * 才是有意义的交叉验证 —— 页面和文档要如实讲清这个边界,不许写成"已比对官方 md5"。</p>
 *
 * <p>清单同时被两处消费:app(页面公示 + 安装校验)与 launcher 镜像(构建时 COPY 进去,
 * 这样网关容器不依赖 app 也能独立自检)。</p>
 */
@Component
@Slf4j
public class OpendCatalog {

    /** 仓库里的清单文件(同一份被 launcher 镜像 COPY 走) */
    public static final String CATALOG_PATH = "deploy/futu-opend-releases.json";

    /**
     * 一个已核对的发布物。
     *
     * @param bytes  期望字节数(第一道便宜的门,能立刻认出"下到一半"或"下到一个错误页面")
     * @param sha256 我们算的 sha256(<b>不是</b>官方公布的 —— 官方不公布)
     * @param md5    我们算的 md5(用户可用 {@code curl -sI} 看 CDN 的 etag 交叉核对)
     */
    public record Release(String version, String os, String file, long bytes, String sha256, String md5) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)   // 清单里还有 _readme / verifiedAt 等说明字段
    public record Catalog(List<Release> releases) {}

    private volatile Catalog cache;
    /**
     * 清单读取失败的原因(null = 读到了)。
     *
     * <p>刻意<b>不</b>把"读不到"降级成"这一版没核对过":那会让安全机制静默失效
     * —— 用户看到的是"未核对,请确认",而真实情况是"我们的校验数据丢了"。两件事必须分开说。</p>
     */
    private volatile String loadError;

    /** 读清单。仓库文件优先(开发/原生部署),否则退回打进 jar 的副本。 */
    public Catalog catalog() {
        Catalog c = cache;
        if (c != null) return c;
        return reload();
    }

    /** 清单读取的错误原因(null = 正常)。 */
    public String loadError() { catalog(); return loadError; }

    private synchronized Catalog reload() {
        Catalog c;
        ObjectMapper om = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            Path repo = Path.of(CATALOG_PATH);
            String json;
            if (Files.isRegularFile(repo)) {
                json = Files.readString(repo, StandardCharsets.UTF_8);
            } else {
                ClassPathResource cp = new ClassPathResource("futu-opend-releases.json");
                try (InputStream in = cp.getInputStream()) {
                    json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            c = om.readValue(json, Catalog.class);
            if (c.releases() == null || c.releases().isEmpty()) {
                loadError = "清单里没有任何已核对版本(" + CATALOG_PATH + ")";
                log.warn("[opend] {}", loadError);
            } else {
                loadError = null;
            }
        } catch (Exception e) {
            loadError = "读不到已核对版本清单(" + CATALOG_PATH + "):" + e.getMessage();
            log.error("[opend] {} —— 安装校验会明确报错,而不是把它当成「未核对版本」放行", loadError);
            c = new Catalog(List.of());
        }
        cache = c;
        return c;
    }

    /** 该系统下我们核对过的最新版本(版本号按数字段比较)。 */
    public Optional<Release> latestVerified(String os) {
        return catalog().releases().stream()
                .filter(r -> r.os() != null && r.os().equalsIgnoreCase(os))
                .max((a, b) -> compareVersion(a.version(), b.version()));
    }

    public Optional<Release> find(String version, String os) {
        return catalog().releases().stream()
                .filter(r -> r.version() != null && r.version().equals(version)
                        && r.os() != null && r.os().equalsIgnoreCase(os))
                .findFirst();
    }

    /** 按文件名找(上传 / 服务器路径导入时只有文件名)。 */
    public Optional<Release> findByFile(String fileName) {
        if (fileName == null) return Optional.empty();
        String base = fileName.substring(fileName.lastIndexOf('/') + 1);
        return catalog().releases().stream()
                .filter(r -> base.equalsIgnoreCase(r.file()))
                .findFirst();
    }

    /** 版本号比较:按数字段逐段比(10.10.7008 > 9.3.5308,字符串比较会搞错)。 */
    static int compareVersion(String a, String b) {
        String[] x = (a == null ? "" : a).split("\\.");
        String[] y = (b == null ? "" : b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parse(x[i]) : 0;
            int yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    // ---------- 校验 ----------

    /** 一次校验的结果。{@code verified=false} 表示这一版我们没核对过(不是"校验失败")。 */
    public record Verdict(boolean verified, boolean ok, String sha256, String md5, long bytes, String detail) {}

    /**
     * 校验一个下载好的包。
     *
     * <p>三种结局:</p>
     * <ul>
     *   <li>清单里有 + 对上了 → {@code verified=true, ok=true}</li>
     *   <li>清单里有 + 对不上 → {@code verified=true, ok=false} —— <b>调用方必须拒绝安装</b></li>
     *   <li>清单里没有 → {@code verified=false, ok=true},并把实算的哈希带出来让用户自己判断</li>
     * </ul>
     */
    public Verdict verify(Path pkg, String expectFile) throws IOException {
        long bytes = Files.size(pkg);
        String sha = digest(pkg, "SHA-256");
        String md5 = digest(pkg, "MD5");
        Optional<Release> known = findByFile(expectFile != null ? expectFile : pkg.getFileName().toString());
        if (catalog().releases().isEmpty()) {
            // 我们的校验数据本身没了 —— 这不是"未核对版本",别混为一谈:
            // 后者用户勾一下"我确认"就能过,前者必须先修好清单。
            return new Verdict(true, false, sha, md5, bytes,
                    "无法校验:" + (loadError != null ? loadError : "已核对版本清单是空的")
                    + " · 实算 sha256=" + sha + "(请先修好清单,不要绕过)");
        }
        if (known.isEmpty()) {
            return new Verdict(false, true, sha, md5, bytes,
                    "这一版我们还没核对过(富途官方不公布校验和)· 实算 sha256=" + sha + " md5=" + md5);
        }
        Release r = known.get();
        if (r.bytes() > 0 && r.bytes() != bytes) {
            return new Verdict(true, false, sha, md5, bytes,
                    "文件大小和我们核对过的不一样:期望 " + r.bytes() + " 字节,实得 " + bytes + " 字节(下载可能不完整)");
        }
        if (!r.sha256().equalsIgnoreCase(sha)) {
            return new Verdict(true, false, sha, md5, bytes,
                    "这个安装包和我们核对过的版本不一样 · 期望 sha256=" + r.sha256() + " · 实得 " + sha
                    + " —— 可能是富途换了包,也可能下载被中间人改过。请不要绕过这个检查。");
        }
        return new Verdict(true, true, sha, md5, bytes, "已核对 · " + r.version() + " · sha256 与仓库钉住的值一致");
    }

    static String digest(Path f, String algo) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            try (InputStream in = Files.newInputStream(f)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
