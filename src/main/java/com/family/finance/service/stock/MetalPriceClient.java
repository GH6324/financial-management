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

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 贵金属(METAL)行情源 · v0.14 · issue #4。
 *
 * <p>复用新浪主源基础设施(与 {@link SinaStockClient} 同 host / 同 Referer / 同 GB18030 编码),
 * 但走贵金属专用 symbol 与解析:</p>
 * <ul>
 *   <li>SGE 国内:{@code https://hq.sinajs.cn/list=gds_AU9999,gds_AGTD,gds_PT9995}</li>
 *   <li>国际现货:{@code https://hq.sinajs.cn/list=hf_XAU,hf_XAG,hf_XPT,hf_XPD}</li>
 * </ul>
 *
 * <p>响应(GB18030):{@code var hq_str_gds_AU9999="892.00,0,891.00,...";}
 * · {@code var hq_str_hf_XAU="4070.94,4105.70,...";} —— 两种格式的 <b>field[0] 都是当前价</b>。</p>
 *
 * <p>落库前统一归一为"原生币种 / 克"({@link MetalUnit#normalizeToPerGram}):
 * SGE 白银元/千克 ÷1000;国际币种/盎司 ÷31.1035。currency 由 {@link MetalUnit#currencyOf} 定(SGE=CNY / 国际=USD)。</p>
 */
@Component
@Slf4j
public class MetalPriceClient implements StockClient {

    private static final String BASE = "https://hq.sinajs.cn/list=";
    private static final String REFERER = "https://finance.sina.com.cn";
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Pattern LINE_RE = Pattern.compile("var hq_str_([a-zA-Z0-9_]+)=\"([^\"]*)\";");

    private final RestTemplate restTemplate;

    public MetalPriceClient(RestTemplateBuilder builder) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(3))
            .setReadTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Override
    public String sourceTag() { return "sina-metal"; }

    @Override
    public Map<String, StockQuote> fetchBatch(Market market, List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return Map.of();

        // 只保留能映射到新浪 symbol 的 ticker(如"钯金+SGE"无盘 → 跳过)
        Map<String, String> symbolByTicker = new HashMap<>();
        for (String t : tickers) {
            String sym = MetalUnit.toSinaMetalSymbol(t);
            if (sym != null) symbolByTicker.put(t, sym);
            else log.warn("metal ticker has no sina symbol · ticker={}", t);
        }
        if (symbolByTicker.isEmpty()) return Map.of();

        String listParam = symbolByTicker.values().stream().collect(Collectors.joining(","));
        URI url = URI.create(BASE + listParam);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.REFERER, REFERER);
        headers.add(HttpHeaders.USER_AGENT, "Mozilla/5.0 finance-self-hosted");
        headers.setAccept(List.of(MediaType.ALL));

        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            if (resp.getStatusCode().isError() || resp.getBody() == null) {
                log.warn("metal fetch HTTP error · status={} url={}", resp.getStatusCode(), url);
                return Map.of();
            }
            return parseResponse(Market.METAL, new String(resp.getBody(), GB18030));
        } catch (Exception e) {
            log.warn("metal fetch exception · tickers={} err={}", tickers, e.toString());
            return Map.of();
        }
    }

    @Override
    public Map<String, StockQuote> parseResponse(Market market, String body) {
        if (body == null || body.isBlank()) return Map.of();
        Map<String, StockQuote> result = new HashMap<>();
        Matcher m = LINE_RE.matcher(body);
        while (m.find()) {
            String sinaKey = m.group(1);              // gds_AU9999 / hf_XAU
            String payload = m.group(2);
            if (payload.isBlank()) continue;
            String ticker = MetalUnit.fromSinaMetalSymbol(sinaKey);
            if (ticker == null) continue;
            String[] fields = payload.split(",");
            if (fields.length == 0) continue;
            BigDecimal raw = parsePrice(fields[0]);   // field[0] = 当前价(gds_ 与 hf_ 同)
            if (raw == null || raw.signum() <= 0) continue;
            BigDecimal perGram = MetalUnit.normalizeToPerGram(ticker, raw);
            if (perGram == null || perGram.signum() <= 0) continue;
            result.put(ticker, new StockQuote(
                ticker, Market.METAL, perGram, MetalUnit.currencyOf(ticker), sourceTag()));
        }
        return result;
    }

    private static BigDecimal parsePrice(String raw) {
        try {
            String t = raw == null ? null : raw.trim();
            if (t == null || t.isEmpty()) return null;
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
