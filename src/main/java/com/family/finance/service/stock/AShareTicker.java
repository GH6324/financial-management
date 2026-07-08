package com.family.finance.service.stock;

/**
 * A 股(CN)代码 → 交易所前缀(sh / sz)。
 *
 * <p>Sina/Tencent 行情 API 都要求 CN 代码带交易所前缀(如 {@code sh513180} / {@code sz000001})。
 * 历史 bug(issue #3):两处各自写死 {@code startsWith("6") ? "sh" : "sz"},把上交所 ETF
 * {@code 513180}(5 开头)错判成深市 → 查无此票 → 全源失败 → 熔断。此处集中一份正确规则,两个 client 复用,杜绝再漂。</p>
 *
 * <p>规则(覆盖家庭常见持仓):</p>
 * <ul>
 *   <li><b>沪(sh)</b>:首位 5(ETF/LOF)、6(股票,含 688 科创板)、9(B 股)</li>
 *   <li><b>深(sz)</b>:其余 —— 0(主板)、3(创业板)、1(15/16/18 ETF、12 债)、2(B 股)</li>
 * </ul>
 * <p>边界:上交所可转债 11x/13x(1 开头)按此规则会落到 sz —— 家庭按裸债券代码持仓极罕见,暂不特判;
 * 若将来需要,在此一处补即可。</p>
 */
public final class AShareTicker {
    private AShareTicker() {}

    /** CN 代码 → "sh" / "sz"。空值兜底 "sh"(交给上游校验)。 */
    public static String exchangePrefix(String ticker) {
        if (ticker == null || ticker.isBlank()) return "sh";
        char c = ticker.trim().charAt(0);
        return (c == '5' || c == '6' || c == '9') ? "sh" : "sz";
    }

    /** CN 代码 → 带前缀的行情 symbol(如 513180 → sh513180)。 */
    public static String withExchange(String ticker) {
        return exchangePrefix(ticker) + ticker;
    }
}
