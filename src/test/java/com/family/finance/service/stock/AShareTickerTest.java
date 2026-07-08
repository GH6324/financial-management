package com.family.finance.service.stock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AShareTicker 交易所前缀单测 · issue #3 回归护栏。
 *
 * <p>历史 bug:Sina/Tencent 两处各自 {@code startsWith("6") ? "sh" : "sz"},
 * 把上交所 ETF 513180 误判成深市 → 全源查无 → 熔断。规则集中到 AShareTicker 后,
 * 这里一处网住整类,防止再漂。</p>
 */
class AShareTickerTest {

    @Test
    void shanghaiPrefixes() {
        // 沪:5(ETF/LOF)、6(股票,含 688 科创板)、9(B 股)
        assertThat(AShareTicker.exchangePrefix("513180")).isEqualTo("sh"); // 恒生科技 ETF —— 正是 issue #3 报的票
        assertThat(AShareTicker.exchangePrefix("510300")).isEqualTo("sh"); // 沪深300 ETF
        assertThat(AShareTicker.exchangePrefix("600519")).isEqualTo("sh"); // 贵州茅台
        assertThat(AShareTicker.exchangePrefix("601398")).isEqualTo("sh"); // 工商银行
        assertThat(AShareTicker.exchangePrefix("688981")).isEqualTo("sh"); // 科创板 中芯国际
        assertThat(AShareTicker.exchangePrefix("900901")).isEqualTo("sh"); // 沪 B 股
    }

    @Test
    void shenzhenPrefixes() {
        // 深:0(主板)、3(创业板)、1(15/16/18 ETF)、2(B 股)
        assertThat(AShareTicker.exchangePrefix("000001")).isEqualTo("sz"); // 平安银行
        assertThat(AShareTicker.exchangePrefix("002594")).isEqualTo("sz"); // 比亚迪
        assertThat(AShareTicker.exchangePrefix("300750")).isEqualTo("sz"); // 创业板 宁德时代
        assertThat(AShareTicker.exchangePrefix("159915")).isEqualTo("sz"); // 深 ETF 创业板
        assertThat(AShareTicker.exchangePrefix("200011")).isEqualTo("sz"); // 深 B 股
    }

    @Test
    void withExchangePrependsPrefix() {
        assertThat(AShareTicker.withExchange("513180")).isEqualTo("sh513180");
        assertThat(AShareTicker.withExchange("000001")).isEqualTo("sz000001");
    }

    @Test
    void blankFallsBackToShanghai() {
        // 空/异常输入兜底 sh(交给上游校验),不抛异常
        assertThat(AShareTicker.exchangePrefix(null)).isEqualTo("sh");
        assertThat(AShareTicker.exchangePrefix("")).isEqualTo("sh");
        assertThat(AShareTicker.exchangePrefix("  ")).isEqualTo("sh");
    }

    @Test
    void trimsWhitespace() {
        assertThat(AShareTicker.exchangePrefix(" 513180 ")).isEqualTo("sh");
    }
}
