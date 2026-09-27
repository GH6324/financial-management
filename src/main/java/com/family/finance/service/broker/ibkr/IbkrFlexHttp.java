package com.family.finance.service.broker.ibkr;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IBKR Flex Web Service 的两步 HTTP · v1.26。
 *
 * <pre>
 *   SendRequest?t=口令&amp;q=查询号&amp;v=3    → 回执号
 *   (等报表生成)
 *   GetStatement?t=口令&amp;q=回执号&amp;v=3   → 报表 XML;没生成好时回 1019,等一会儿再取
 * </pre>
 *
 * <p>实测过的两条(beta · 2026-09-24):<b>失败也是 HTTP 200</b>(由 {@link IbkrFlexParser} 认信封);
 * <b>不带 User-Agent 直接 HTTP 403</b>(不是 XML)。</p>
 *
 * <p>限流:官方写的是「每个口令每秒 1 次、每分钟 10 次」。本类对同一口令保证请求间隔 ≥ 1.1 秒;
 * 一次取数 = 1 次 SendRequest + 至多 {@link #MAX_POLLS} 次 GetStatement,总数不超过 7,低于每分钟 10 次。</p>
 *
 * <p>只有两个 GET,没有任何写操作 —— 报表口令本身也做不到写。</p>
 */
public class IbkrFlexHttp {

    public static final String DEFAULT_BASE = "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService";
    static final int MAX_POLLS = 6;
    static final long MIN_INTERVAL_MS = 1100;
    /** 单份报表上限:正常持仓报表几十 KB,超过 20 MB 多半是报表选了全年逐笔明细 */
    static final int MAX_BYTES = 20 * 1024 * 1024;

    /** 等待:单测里替换成不真睡 */
    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    /** 同一口令的上一次请求时刻(键是口令的哈希,不存口令本身) */
    private static final Map<Integer, Long> LAST_CALL = new ConcurrentHashMap<>();

    private final HttpClient http;
    private final String base;
    private final String userAgent;
    private final Sleeper sleeper;
    private final long firstWaitMs;
    private final long pollWaitMs;

    public IbkrFlexHttp(String base, String userAgent) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL).build(),
                base, userAgent, Thread::sleep, 3000, 5000);
    }

    IbkrFlexHttp(HttpClient http, String base, String userAgent, Sleeper sleeper, long firstWaitMs, long pollWaitMs) {
        this.http = http;
        this.base = normalizeBase(base);
        this.userAgent = userAgent;
        this.sleeper = sleeper;
        this.firstWaitMs = firstWaitMs;
        this.pollWaitMs = pollWaitMs;
    }

    /**
     * 基址只接受 IBKR 自己的 HTTPS 域名,或本机回环地址(e2e 用桩回放报表)。
     * 这个值能从家庭配置改(为了 e2e),所以要防「把口令发到别处」。
     */
    static String normalizeBase(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_BASE;
        String b = raw.trim().replaceAll("/+$", "");
        URI u;
        try { u = URI.create(b); } catch (IllegalArgumentException e) { return DEFAULT_BASE; }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        boolean ibkr = "https".equalsIgnoreCase(u.getScheme()) && (host.equals("interactivebrokers.com") || host.endsWith(".interactivebrokers.com"));
        boolean loopback = "http".equalsIgnoreCase(u.getScheme()) && (host.equals("127.0.0.1") || host.equals("localhost"));
        return (ibkr || loopback) ? b : DEFAULT_BASE;
    }

    /** 发请求 → 等 → 取报表(遇 1019 等再取)。返回报表 XML 原文。 */
    public String fetchStatementXml(String token, String queryId) {
        String sendXml = get("/SendRequest", token, queryId);
        String ref = IbkrFlexParser.parseSendResponse(sendXml);
        sleepQuietly(firstWaitMs);
        IbkrFlexException last = null;
        for (int i = 0; i < MAX_POLLS; i++) {
            String body = get("/GetStatement", token, ref);
            var env = IbkrFlexParser.readEnvelope(body);
            if (env == null) return body;                    // 不是信封 = 报表
            IbkrFlexException e = IbkrErrors.fromEnvelope(env.get("ErrorCode"),
                    env.getOrDefault("ErrorMessage", env.get("Status")));
            if (!"1019".equals(e.code()) && !"1018".equals(e.code())) throw e;   // 只有「还在生成 / 限流」值得再等
            last = e;
            sleepQuietly(pollWaitMs);
        }
        throw last != null ? last : new IbkrFlexException("1019", null, IbkrErrors.human("1019"), true);
    }

    private String get(String path, String token, String q) {
        throttle(token);
        String url = base + path + "?t=" + enc(token) + "&q=" + enc(q) + "&v=3";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent)     // 不带 → 403(实测)
                .GET().build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw IbkrErrors.network(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw IbkrErrors.network(e);
        }
        byte[] body = resp.body() == null ? new byte[0] : resp.body();
        if (body.length > MAX_BYTES) {
            throw new IbkrFlexException(null, body.length + " bytes",
                    "IBKR 的报表太大了 —— 报表里只需要 Open Positions 和 Cash Report 两栏,时段选 Last Business Day", false);
        }
        String text = new String(body, StandardCharsets.UTF_8);
        if (resp.statusCode() != 200) {
            throw new IbkrFlexException(null, "HTTP " + resp.statusCode() + " " + IbkrFlexParser.abbreviate(text),
                    resp.statusCode() == 403 ? "IBKR 拒绝了这个请求(HTTP 403)" : "IBKR 返回了错误(HTTP " + resp.statusCode() + ")",
                    resp.statusCode() >= 500);
        }
        return text;
    }

    private void throttle(String token) {
        int key = token == null ? 0 : token.hashCode();
        synchronized (LAST_CALL) {
            long now = System.currentTimeMillis();
            Long prev = LAST_CALL.get(key);
            if (prev != null && now - prev < MIN_INTERVAL_MS) {
                sleepQuietly(MIN_INTERVAL_MS - (now - prev));
            }
            LAST_CALL.put(key, System.currentTimeMillis());
        }
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try { sleeper.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s.trim(), StandardCharsets.UTF_8); }

    /** 单测用:清掉限流记录 */
    static void resetThrottle() { LAST_CALL.clear(); }
}
