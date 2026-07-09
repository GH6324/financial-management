package com.family.finance.service.broker;

import com.family.finance.domain.stock.Market;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.15 · 券商代码归一(单一真相源)· 富途前缀 / 老虎 market 字段 → Market+ticker,以及股票判定。
 */
class BrokerTickerTest {

    @Test
    void futu_prefix_maps_to_market() {
        assertThat(BrokerTicker.fromFutu("HK.00700").market()).isEqualTo(Market.HK);
        assertThat(BrokerTicker.fromFutu("HK.00700").ticker()).isEqualTo("00700");
        assertThat(BrokerTicker.fromFutu("US.AAPL").market()).isEqualTo(Market.US);
        assertThat(BrokerTicker.fromFutu("SH.600519").market()).isEqualTo(Market.CN);
        assertThat(BrokerTicker.fromFutu("SZ.000001").market()).isEqualTo(Market.CN);
    }

    @Test
    void futu_unknown_or_malformed_returns_null() {
        assertThat(BrokerTicker.fromFutu("JP.7203")).isNull();   // 未支持市场
        assertThat(BrokerTicker.fromFutu("AAPL")).isNull();       // 无前缀
        assertThat(BrokerTicker.fromFutu(null)).isNull();
    }

    @Test
    void tiger_market_field_maps() {
        assertThat(BrokerTicker.fromTiger("US", "nvda").market()).isEqualTo(Market.US);
        assertThat(BrokerTicker.fromTiger("US", "nvda").ticker()).isEqualTo("NVDA"); // 归一为大写
        assertThat(BrokerTicker.fromTiger("HK", "00700").market()).isEqualTo(Market.HK);
        assertThat(BrokerTicker.fromTiger("CN", "600519").market()).isEqualTo(Market.CN);
        assertThat(BrokerTicker.fromTiger("XX", "ZZZ")).isNull();
        assertThat(BrokerTicker.fromTiger("US", null)).isNull();
    }

    @Test
    void equity_detection_skips_options_and_futures() {
        assertThat(BrokerTicker.isEquity("STK")).isTrue();
        assertThat(BrokerTicker.isEquity("ETF")).isTrue();
        assertThat(BrokerTicker.isEquity(null)).isTrue();   // 空视为股票(保守纳入)
        assertThat(BrokerTicker.isEquity("OPT")).isFalse();
        assertThat(BrokerTicker.isEquity("FUT")).isFalse();
        assertThat(BrokerTicker.isEquity("WAR")).isFalse();
    }
}
