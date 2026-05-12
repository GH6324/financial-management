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
 * </ul>
 */
public enum Market {
    US,
    CN,
    HK;

    /**
     * 各市场默认报价币种(用户可在添加持仓时覆盖,罕见场景)。
     */
    public String defaultCurrency() {
        return switch (this) {
            case US -> "USD";
            case CN -> "CNY";
            case HK -> "HKD";
        };
    }
}
