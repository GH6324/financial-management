package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 新浪财经股票数据源适配器 · v0.3 FR-52a · 主源。
 *
 * <p>接口:</p>
 * <ul>
 *   <li>美股:{@code https://hq.sinajs.cn/list=gb_baba,gb_tsla}(小写)</li>
 *   <li>A 股(沪):{@code https://hq.sinajs.cn/list=sh600519}</li>
 *   <li>A 股(深):{@code https://hq.sinajs.cn/list=sz000001}</li>
 *   <li>港股:{@code https://hq.sinajs.cn/list=hk00700}(5 位前导零)</li>
 * </ul>
 *
 * <p>必须加 {@code Referer: https://finance.sina.com.cn} 头,否则 403。</p>
 *
 * <p>响应格式(GB18030 编码):</p>
 * <pre>
 *   var hq_str_sh600519="贵州茅台,1234.56,1230.00,1240.50,1245.00,1230.00,...";
 *   var hq_str_gb_baba="ALIBABA GRP,89.20,0.50,2026-05-11 16:00:00,...";
 *   var hq_str_hk00700="TENCENT,腾讯控股,402.00,400.00,395.00,402.00,400.50,...";
 * </pre>
 *
 * <p>字段索引按市场不同(决策 24)。</p>
 */
@Component
@Slf4j
public class SinaStockClient implements StockClient {

    private static final String BASE = "https://hq.sinajs.cn/list=";
    private static final String REFERER = "https://finance.sina.com.cn";
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Pattern LINE_RE = Pattern.compile("var hq_str_([a-zA-Z0-9_]+)=\"([^\"]*)\";");

    private final RestTemplate restTemplate;

    public SinaStockClient(RestTemplateBuilder builder) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(3))
            .setReadTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Override
    public String sourceTag() { return "sina"; }

    @Override
    public Map<String, StockQuote> fetchBatch(Market market, List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return Map.of();

        // 拼新浪 ticker key(带市场前缀)
        String listParam = tickers.stream()
            .map(t -> toSinaSymbol(market, t))
            .collect(Collectors.joining(","));
        URI url = URI.create(BASE + listParam);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.REFERER, REFERER);
        headers.add(HttpHeaders.USER_AGENT, "Mozilla/5.0 finance-self-hosted");
        headers.setAccept(List.of(MediaType.ALL));

        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            if (resp.getStatusCode().isError() || resp.getBody() == null) {
                log.warn("sina fetch HTTP error · status={} url={}", resp.getStatusCode(), url);
                return Map.of();
            }
            String body = new String(resp.getBody(), GB18030);
            return parseResponse(market, body);
        } catch (Exception e) {
            log.warn("sina fetch exception · market={} tickers={} err={}", market, tickers, e.toString());
            return Map.of();
        }
    }

    @Override
    public Map<String, StockQuote> parseResponse(Market market, String body) {
        if (body == null || body.isBlank()) return Map.of();
        Map<String, StockQuote> result = new HashMap<>();
        Matcher m = LINE_RE.matcher(body);
        while (m.find()) {
            String sinaKey = m.group(1);          // 如 sh600519 / gb_baba / hk00700
            String payload = m.group(2);
            if (payload.isBlank()) continue;       // 停牌 / 无效 ticker
            String[] fields = payload.split(",");
            BigDecimal price = extractPrice(market, fields);
            if (price == null || price.signum() <= 0) continue;
            String ticker = fromSinaSymbol(market, sinaKey);
            if (ticker == null) continue;
            result.put(ticker, new StockQuote(
                ticker, market, price, market.defaultCurrency(), sourceTag()));
        }
        return result;
    }

    /**
     * 业务 ticker → 新浪 symbol:
     *   US BABA  → gb_baba   (小写)
     *   CN 600519 → sh600519  (沪 6 开头)/ sz000001 (深 0/3 开头)
     *   HK 00700 → hk00700   (5 位前导零)
     */
    static String toSinaSymbol(Market market, String ticker) {
        return switch (market) {
            case US -> "gb_" + ticker.toLowerCase();
            case CN -> (ticker.startsWith("6") ? "sh" : "sz") + ticker;
            case HK -> "hk" + ticker;
        };
    }

    /** 新浪 symbol → 业务 ticker(去前缀 · 大小写复原)。 */
    static String fromSinaSymbol(Market market, String sinaKey) {
        return switch (market) {
            case US -> sinaKey.startsWith("gb_") ? sinaKey.substring(3).toUpperCase() : null;
            case CN -> (sinaKey.startsWith("sh") || sinaKey.startsWith("sz"))
                       ? sinaKey.substring(2) : null;
            case HK -> sinaKey.startsWith("hk") ? sinaKey.substring(2) : null;
        };
    }

    /**
     * 按市场不同提取当前价:
     *   美股 gb_:fields[1] (当前价)
     *   A 股 sh/sz:fields[3] (当前价 · index 0=名称 1=今开 2=昨收 3=当前)
     *   港股 hk:fields[6] (当前价 · index 0=英文 1=中文 2=今开 3=昨收 4=最高 5=最低 6=当前)
     */
    static BigDecimal extractPrice(Market market, String[] fields) {
        int idx = switch (market) {
            case US -> 1;
            case CN -> 3;
            case HK -> 6;
        };
        if (fields.length <= idx) return null;
        try {
            String raw = fields[idx].trim();
            if (raw.isEmpty()) return null;
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
