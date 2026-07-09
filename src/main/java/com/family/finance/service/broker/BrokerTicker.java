package com.family.finance.service.broker;

import com.family.finance.domain.stock.Market;

import java.util.Locale;

/**
 * 券商代码 → 我们的 Market + 纯 ticker 归一(单一真相源 · v0.15)。
 * 富途:{@code HK.00700 / US.AAPL / SH.600519 / SZ.000001};老虎:market 字段 + symbol。
 */
public final class BrokerTicker {
    private BrokerTicker() {}

    public record Norm(Market market, String ticker) {}

    /** 富途持仓 code(带交易所前缀)→ 归一。无法识别返回 null(调用方跳过)。 */
    public static Norm fromFutu(String futuCode) {
        if (futuCode == null || !futuCode.contains(".")) return null;
        String[] p = futuCode.trim().toUpperCase(Locale.ROOT).split("\\.", 2);
        String pre = p[0], ticker = p[1];
        Market m = switch (pre) {
            case "US" -> Market.US;
            case "HK" -> Market.HK;
            case "SH", "SZ", "CN" -> Market.CN;
            default -> null;
        };
        return m == null ? null : new Norm(m, ticker);
    }

    /** 老虎持仓 market + symbol → 归一。无法识别返回 null。 */
    public static Norm fromTiger(String market, String symbol) {
        if (symbol == null || symbol.isBlank() || market == null) return null;
        Market m = switch (market.trim().toUpperCase(Locale.ROOT)) {
            case "US" -> Market.US;
            case "HK" -> Market.HK;
            case "CN", "SH", "SZ", "A", "CHINA_A" -> Market.CN;
            default -> null;
        };
        return m == null ? null : new Norm(m, symbol.trim().toUpperCase(Locale.ROOT));
    }

    /** 是否股票(secType=STK / 空视为股票);OPT/FUT/WAR/BOND 等本版跳过。 */
    public static boolean isEquity(String secType) {
        if (secType == null || secType.isBlank()) return true;
        String s = secType.trim().toUpperCase(Locale.ROOT);
        return s.equals("STK") || s.equals("STOCK") || s.equals("EQUITY") || s.equals("ETF") || s.equals("FUND");
    }
}
