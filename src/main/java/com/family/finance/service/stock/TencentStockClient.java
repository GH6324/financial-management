package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * 腾讯财经股票数据源适配器 · v0.3 FR-52a · 备源(新浪挂掉熔断时启用)。
 *
 * <p>接口:</p>
 * <ul>
 *   <li>美股:{@code https://qt.gtimg.cn/q=usBABA}</li>
 *   <li>A 股:{@code https://qt.gtimg.cn/q=sh600519}</li>
 *   <li>港股:{@code https://qt.gtimg.cn/q=hk00700}</li>
 * </ul>
 *
 * <p>响应格式(GB18030):</p>
 * <pre>
 *   v_sh600519="1~贵州茅台~600519~1234.56~1230.00~...";
 * </pre>
 *
 * <p>字段以 {@code ~} 分隔 · index 3 是当前价(所有市场统一)。</p>
 */
@Component
@Slf4j
public class TencentStockClient implements StockClient {

    private static final String BASE = "https://qt.gtimg.cn/q=";
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Pattern LINE_RE = Pattern.compile("v_([a-zA-Z0-9_]+)=\"([^\"]*)\";");

    private final RestTemplate restTemplate;

    public TencentStockClient(RestTemplateBuilder builder) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(3))
            .setReadTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Override
    public String sourceTag() { return "tencent"; }

    @Override
    public Map<String, StockQuote> fetchBatch(Market market, List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return Map.of();
        String listParam = tickers.stream()
            .map(t -> toTencentSymbol(market, t))
            .collect(Collectors.joining(","));
        URI url = URI.create(BASE + listParam);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.USER_AGENT, "Mozilla/5.0 finance-self-hosted");

        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            if (resp.getStatusCode().isError() || resp.getBody() == null) {
                log.warn("tencent fetch HTTP error · status={} url={}", resp.getStatusCode(), url);
                return Map.of();
            }
            String body = new String(resp.getBody(), GB18030);
            return parseResponse(market, body);
        } catch (Exception e) {
            log.warn("tencent fetch exception · market={} tickers={} err={}", market, tickers, e.toString());
            return Map.of();
        }
    }

    @Override
    public Map<String, StockQuote> parseResponse(Market market, String body) {
        if (body == null || body.isBlank()) return Map.of();
        Map<String, StockQuote> result = new HashMap<>();
        Matcher m = LINE_RE.matcher(body);
        while (m.find()) {
            String tencentKey = m.group(1);   // 如 sh600519 / usBABA / hk00700
            String payload = m.group(2);
            if (payload.isBlank()) continue;
            String[] fields = payload.split("~");
            if (fields.length <= 3) continue;
            BigDecimal price = parsePrice(fields[3]);
            if (price == null || price.signum() <= 0) continue;
            String ticker = fromTencentSymbol(market, tencentKey);
            if (ticker == null) continue;
            result.put(ticker, new StockQuote(
                ticker, market, price, market.defaultCurrency(), sourceTag()));
        }
        return result;
    }

    /**
     * 业务 ticker → 腾讯 symbol:
     *   US BABA  → usBABA   (大写)
     *   CN 600519 → sh600519 (沪深前缀)
     *   HK 00700 → hk00700
     */
    static String toTencentSymbol(Market market, String ticker) {
        return switch (market) {
            case US -> "us" + ticker.toUpperCase();
            case CN -> (ticker.startsWith("6") ? "sh" : "sz") + ticker;
            case HK -> "hk" + ticker;
        };
    }

    static String fromTencentSymbol(Market market, String tencentKey) {
        return switch (market) {
            case US -> tencentKey.startsWith("us") ? tencentKey.substring(2).toUpperCase() : null;
            case CN -> (tencentKey.startsWith("sh") || tencentKey.startsWith("sz"))
                       ? tencentKey.substring(2) : null;
            case HK -> tencentKey.startsWith("hk") ? tencentKey.substring(2) : null;
        };
    }

    private static BigDecimal parsePrice(String raw) {
        try {
            String trimmed = raw == null ? null : raw.trim();
            if (trimmed == null || trimmed.isEmpty()) return null;
            return new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
