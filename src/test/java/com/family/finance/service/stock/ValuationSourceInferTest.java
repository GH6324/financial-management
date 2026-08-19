package com.family.finance.service.stock;

import com.family.finance.domain.ledger.LedgerSource;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.18 · 估值事件的来源推断。
 *
 * <p>用户在流水时间线上看到一笔「△ 估值」,要能分出是拉股价、拉金价、还是券商同步 ——
 * 以前只有 triggerKind(定时/手动/持仓变了),那说的是"什么动作触发",不是"数字从哪来"。</p>
 */
class ValuationSourceInferTest {

    private StockHolding h(Market m) {
        StockHolding sh = new StockHolding();
        sh.setMarket(m);
        return sh;
    }

    /** 调用方明确知道来源(券商同步)时,推断让位于显式值。 */
    @Test
    void explicit_source_wins() {
        var got = AccountValuationService.inferSource(LedgerSource.SYNC_BROKER_FUTU,
                AccountValuationService.TriggerKind.HOLDING_CHANGE, null, List.of(h(Market.US)));
        assertThat(got).isEqualTo(LedgerSource.SYNC_BROKER_FUTU);
    }

    @Test
    void screenshot_import_is_recognised_by_either_signal() {
        // 挂了 refImportId
        assertThat(AccountValuationService.inferSource(null,
                AccountValuationService.TriggerKind.HOLDING_CHANGE, 42L, List.of(h(Market.CN))))
                .isEqualTo(LedgerSource.IMPORT_SCREENSHOT);
        // 或 trigger 就是 IMPORT
        assertThat(AccountValuationService.inferSource(null,
                AccountValuationService.TriggerKind.IMPORT, null, List.of(h(Market.CN))))
                .isEqualTo(LedgerSource.IMPORT_SCREENSHOT);
    }

    @Test
    void manual_refresh_is_manual() {
        assertThat(AccountValuationService.inferSource(null,
                AccountValuationService.TriggerKind.MANUAL, null, List.of(h(Market.HK))))
                .isEqualTo(LedgerSource.MANUAL);
    }

    /** 定时拉价:按持仓市场分出股价 / 金价 / 币价 —— 这是用户点名要区分的三类。 */
    @Test
    void cron_splits_by_which_price_feed_was_used() {
        var cron = AccountValuationService.TriggerKind.CRON;
        assertThat(AccountValuationService.inferSource(null, cron, null,
                List.of(h(Market.US), h(Market.CN)))).isEqualTo(LedgerSource.SYNC_STOCK_API);
        assertThat(AccountValuationService.inferSource(null, cron, null,
                List.of(h(Market.METAL), h(Market.METAL)))).isEqualTo(LedgerSource.SYNC_METAL_API);
        assertThat(AccountValuationService.inferSource(null, cron, null,
                List.of(h(Market.CRYPTO)))).isEqualTo(LedgerSource.SYNC_CRYPTO_API);
    }

    /** 混合持仓按占多的那类算 —— 一次估值只能挂一个来源,与其编个"混合"不如取主要那类。 */
    @Test
    void mixed_holdings_take_the_majority() {
        var cron = AccountValuationService.TriggerKind.CRON;
        assertThat(AccountValuationService.inferSource(null, cron, null,
                List.of(h(Market.METAL), h(Market.METAL), h(Market.US)))).isEqualTo(LedgerSource.SYNC_METAL_API);
        assertThat(AccountValuationService.inferSource(null, cron, null,
                List.of(h(Market.US), h(Market.CN), h(Market.METAL)))).isEqualTo(LedgerSource.SYNC_STOCK_API);
    }

    /** 边界:没有持仓 / market 为空都不许抛异常(这一列只是展示用的元信息)。 */
    @Test
    void empty_or_null_never_blows_up() {
        var cron = AccountValuationService.TriggerKind.CRON;
        assertThat(AccountValuationService.inferSource(null, cron, null, List.of()))
                .isEqualTo(LedgerSource.SYNC_STOCK_API);
        assertThat(AccountValuationService.inferSource(null, cron, null, null))
                .isEqualTo(LedgerSource.SYNC_STOCK_API);
        assertThat(AccountValuationService.inferSource(null, cron, null, List.of(h(null))))
                .isEqualTo(LedgerSource.SYNC_STOCK_API);
    }
}
