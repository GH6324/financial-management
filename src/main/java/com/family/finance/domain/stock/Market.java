package com.family.finance.domain.stock;

/**
 * 股票市场 · v0.3 FR-52c。
 *
 * <p>每个市场对应:不同的 ticker 格式 · 不同的数据源 URL · 不同的 cron 拉价时点 · 不同的默认币种。</p>
 *
 * <ul>
 *   <li>US 美股 · ticker BABA / NVDA 大写字母 · 默认 USD · 拉价 06:05 Asia/Shanghai</li>
 *   <li>CN A 股 · ticker sh600519 / sz000001 6 位数字 · 默认 CNY · 拉价 16:10</li>
 *   <li>HK 港股 · ticker 00700 5 位前导零 · 默认 HKD · 拉价 16:30</li>
 *   <li>CRYPTO 加密货币 · ticker BTC / ETH / USDC 大写字母数字 · 默认 USD · 拉价 06:15</li>
 *   <li>METAL 贵金属 · ticker AU9999/AGTD/PT9995(SGE 国内) / XAU/XAG/XPT/XPD(国际) · v0.14</li>
 * </ul>
 */
public enum Market {
    US,
    CN,
    HK,
    CRYPTO,
    METAL;

    /**
     * 各市场默认报价币种(用户可在添加持仓时覆盖,罕见场景)。
     * METAL 默认 CNY(上海 SGE);国际现货持仓建仓时覆盖为 USD。
     */
    public String defaultCurrency() {
        return switch (this) {
            case US -> "USD";
            case CN -> "CNY";
            case HK -> "HKD";
            case CRYPTO -> "USD";
            case METAL -> "CNY";
        };
    }
}
