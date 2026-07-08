package com.family.finance.service.stock;

import java.math.BigDecimal;

/**
 * 贵金属(METAL)单位、品种×源→新浪 symbol 映射、每克归一 —— 单一真相源 · v0.14。
 *
 * <p>设计(见 tech-design/v0.14 决策 B/C/D):</p>
 * <ul>
 *   <li>持仓 <b>ticker 编码"品种×源"</b>:SGE 国内 = {@code AU9999/AGTD/PT9995};国际现货 = {@code XAU/XAG/XPT/XPD}。
 *       currency 定源币种(SGE=CNY / 国际=USD)。</li>
 *   <li>快照 {@code close_price} 一律归一为 <b>"原生币种 / 克"</b>:SGE 白银原报价是元/千克 → ÷1000;
 *       国际 X* 原报价是币种/盎司 → ÷{@link #GRAMS_PER_TROY_OUNCE}。</li>
 *   <li>估值时按持仓 {@code unit}(GRAM/OUNCE)把"每克价"换算到"每持仓单位价"再 × shares。</li>
 * </ul>
 */
public final class MetalUnit {
    private MetalUnit() {}

    public static final String GRAM = "GRAM";
    public static final String OUNCE = "OUNCE";

    /** 1 金衡盎司(troy ounce)= 31.1035 克。 */
    public static final BigDecimal GRAMS_PER_TROY_OUNCE = new BigDecimal("31.1035");

    /** 某 ticker 是否国际现货源(X 开头:XAU/XAG/XPT/XPD)· 其余视为 SGE 国内。 */
    public static boolean isInternational(String ticker) {
        return ticker != null && ticker.toUpperCase().startsWith("X");
    }

    /** 该 ticker 的默认计价单位:国际现货→盎司,SGE 国内→克。 */
    public static String defaultUnit(String ticker) {
        return isInternational(ticker) ? OUNCE : GRAM;
    }

    /** 该 ticker 的报价币种:国际→USD,SGE→CNY。 */
    public static String currencyOf(String ticker) {
        return isInternational(ticker) ? "USD" : "CNY";
    }

    /**
     * 持仓 ticker → 新浪行情 symbol。返回 null = 不支持(如"钯金 + SGE",SGE 无常见钯盘)。
     * <pre>
     *   AU9999 → gds_AU9999   AGTD → gds_AGTD   PT9995 → gds_PT9995   (SGE, hq_str_gds_*)
     *   XAU → hf_XAU  XAG → hf_XAG  XPT → hf_XPT  XPD → hf_XPD          (国际, hq_str_hf_*)
     * </pre>
     */
    public static String toSinaMetalSymbol(String ticker) {
        if (ticker == null || ticker.isBlank()) return null;
        String t = ticker.trim().toUpperCase();
        if (isInternational(t)) {
            return switch (t) {
                case "XAU", "XAG", "XPT", "XPD" -> "hf_" + t;
                default -> null;
            };
        }
        return switch (t) {
            case "AU9999", "AUTD", "AGTD", "PT9995" -> "gds_" + t;
            default -> null;
        };
    }

    /** 新浪 symbol(gds_ / hf_ 前缀)→ 持仓 ticker。 */
    public static String fromSinaMetalSymbol(String sinaKey) {
        if (sinaKey == null) return null;
        if (sinaKey.startsWith("gds_")) return sinaKey.substring(4);
        if (sinaKey.startsWith("hf_")) return sinaKey.substring(3);
        return null;
    }

    /**
     * 把某 ticker 的原生报价归一为"原生币种 / 克"。
     *   SGE 白银 AGTD 原生 = 元/千克 → ÷1000;其余 SGE(金/铂)= 元/克,不变。
     *   国际 X* 原生 = 币种/盎司 → ÷31.1035。
     */
    public static BigDecimal normalizeToPerGram(String ticker, BigDecimal rawPrice) {
        if (rawPrice == null) return null;
        String t = ticker == null ? "" : ticker.trim().toUpperCase();
        if (isInternational(t)) {
            return rawPrice.divide(GRAMS_PER_TROY_OUNCE, 8, java.math.RoundingMode.HALF_EVEN);
        }
        if (t.equals("AGTD")) {
            return rawPrice.divide(new BigDecimal("1000"), 8, java.math.RoundingMode.HALF_EVEN);
        }
        return rawPrice; // SGE 金/铂:元/克
    }

    /** 每克价 → 每持仓单位价(OUNCE 时 ×31.1035,GRAM/其它按每克)。 */
    public static BigDecimal perHoldingUnit(String unit, BigDecimal pricePerGram) {
        if (pricePerGram == null) return null;
        return OUNCE.equals(unit) ? pricePerGram.multiply(GRAMS_PER_TROY_OUNCE) : pricePerGram;
    }

    /**
     * 品种(AU/AG/PT/PD 或 GOLD/SILVER/...)× 源(sge/intl)→ 持仓 ticker。
     * 返回 null = 该组合无盘(如"钯金 + 上海 SGE",需改选国际)。
     */
    public static String tickerFor(String metal, String source) {
        String m = metal == null ? "" : metal.trim().toUpperCase();
        boolean intl = source != null && (source.equalsIgnoreCase("intl") || source.equalsIgnoreCase("international"));
        return switch (m) {
            case "AU", "GOLD"      -> intl ? "XAU" : "AU9999";
            case "AG", "SILVER"    -> intl ? "XAG" : "AGTD";
            case "PT", "PLATINUM"  -> intl ? "XPT" : "PT9995";
            case "PD", "PALLADIUM" -> intl ? "XPD" : null;   // SGE 无常见钯盘
            default -> null;
        };
    }

    /** 持仓 ticker → 品种中文名(展示用)。 */
    public static String metalLabel(String ticker) {
        String t = ticker == null ? "" : ticker.toUpperCase();
        if (t.startsWith("AU") || t.equals("XAU")) return "黄金";
        if (t.startsWith("AG") || t.equals("XAG")) return "白银";
        if (t.startsWith("PT") || t.equals("XPT")) return "铂金";
        if (t.startsWith("PD") || t.equals("XPD")) return "钯金";
        return t;
    }

    /** 单位中文标签。 */
    public static String unitLabel(String unit) {
        return OUNCE.equals(unit) ? "盎司" : "克";
    }
}
