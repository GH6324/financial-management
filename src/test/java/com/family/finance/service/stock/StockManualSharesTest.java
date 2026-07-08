package com.family.finance.service.stock;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.StockPriceSnapshot;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.FxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * v0.12 · 未上市(MANUAL)持仓升级为「股数 × 单股估值」+ 股票收入 +股数 的服务层防回归。
 */
class StockManualSharesTest {

    private StockHoldingMapper holdingMapper;
    private AccountMapper accountMapper;
    private FxService fxService;
    private PeriodMapper periodMapper;
    private StockPriceFetcher fetcher;
    private StockHoldingService svc;

    @BeforeEach
    void setUp() {
        holdingMapper = mock(StockHoldingMapper.class);
        accountMapper = mock(AccountMapper.class);
        fxService = mock(FxService.class);
        periodMapper = mock(PeriodMapper.class);
        fetcher = mock(StockPriceFetcher.class);
        svc = new StockHoldingService(holdingMapper, accountMapper, fxService, periodMapper, fetcher,
                mock(com.family.finance.repository.CashFlowMapper.class));
        when(accountMapper.findById(10L)).thenReturn(Optional.of(
                Account.builder().id(10L).familyId(1L).type(AccountType.STOCK).currency("CNY").build()));
    }

    @Test
    void createManual_storesSharesAndUnitValue() {
        svc.createManual(1L, 10L, "字节跳动 RSU", new BigDecimal("2000"), new BigDecimal("240"));
        ArgumentCaptor<StockHolding> cap = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdingMapper).insert(cap.capture());
        StockHolding h = cap.getValue();
        assertThat(h.getValuationMode()).isEqualTo(ValuationMode.MANUAL);
        assertThat(h.getShares()).isEqualByComparingTo("2000");
        assertThat(h.getManualValue()).isEqualByComparingTo("240");   // manual_value 语义 = 单股估值
    }

    /**
     * issue #3 回归(精度):单股估值 15.678 / 2.3456 必须原样落库,服务层不得四舍五入。
     * 防止有人再往 createManual 里塞 .setScale(2) —— 那正是老 DECIMAL(15,2) 截断的翻版。
     */
    @Test
    void createManual_preservesHighPrecisionUnitValue() {
        svc.createManual(1L, 10L, "字节跳动 RSU", new BigDecimal("2000"), new BigDecimal("15.678"));
        ArgumentCaptor<StockHolding> cap = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdingMapper).insert(cap.capture());
        assertThat(cap.getValue().getManualValue())
                .isEqualByComparingTo("15.678")
                .extracting(BigDecimal::scale).isEqualTo(3);   // 未被压到 2 位
    }

    @Test
    void updateManual_preservesHighPrecisionUnitValue() {
        StockHolding h = StockHolding.builder().id(50L).accountId(10L)
                .valuationMode(ValuationMode.MANUAL).shares(new BigDecimal("2000"))
                .manualValue(new BigDecimal("240")).build();
        when(holdingMapper.findById(50L)).thenReturn(Optional.of(h));
        svc.updateManual(1L, 50L, null, new BigDecimal("2.3456"));
        ArgumentCaptor<StockHolding> cap = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdingMapper).update(cap.capture());
        assertThat(cap.getValue().getManualValue())
                .isEqualByComparingTo("2.3456")
                .extracting(BigDecimal::scale).isEqualTo(4);
    }

    @Test
    void createManual_rejectsNonPositiveShares() {
        assertThatThrownBy(() -> svc.createManual(1L, 10L, "X", BigDecimal.ZERO, new BigDecimal("10")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addShares_incrementsExisting() {
        StockHolding h = StockHolding.builder().id(50L).accountId(10L)
                .valuationMode(ValuationMode.MANUAL).shares(new BigDecimal("2000"))
                .manualValue(new BigDecimal("240")).build();
        when(holdingMapper.findById(50L)).thenReturn(Optional.of(h));
        svc.addShares(1L, 50L, new BigDecimal("250"));
        ArgumentCaptor<StockHolding> cap = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdingMapper).update(cap.capture());
        assertThat(cap.getValue().getShares()).isEqualByComparingTo("2250");
    }

    @Test
    void addShares_negativeReversalNotBelowZero() {
        StockHolding h = StockHolding.builder().id(50L).accountId(10L)
                .valuationMode(ValuationMode.MANUAL).shares(new BigDecimal("100"))
                .manualValue(new BigDecimal("10")).build();
        when(holdingMapper.findById(50L)).thenReturn(Optional.of(h));
        svc.addShares(1L, 50L, new BigDecimal("-250"));   // 冲回超量 → 夹到 0
        ArgumentCaptor<StockHolding> cap = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdingMapper).update(cap.capture());
        assertThat(cap.getValue().getShares()).isEqualByComparingTo("0");
    }

    @Test
    void currentUnitValue_manual_returnsUnit() {
        StockHolding h = StockHolding.builder().id(50L).accountId(10L)
                .valuationMode(ValuationMode.MANUAL).shares(new BigDecimal("2000"))
                .manualValue(new BigDecimal("240")).build();
        assertThat(svc.currentUnitValueInAccountCcy(1L, h)).isEqualByComparingTo("240");
    }

    @Test
    void currentUnitValue_auto_priceSameCcyNoFx() {
        StockHolding h = StockHolding.builder().id(51L).accountId(10L)
                .valuationMode(ValuationMode.AUTO).ticker("600519").market(Market.CN)
                .currency("CNY").shares(new BigDecimal("100")).build();
        when(fetcher.findLatestKnown("600519", Market.CN)).thenReturn(
                StockPriceSnapshot.builder().closePrice(new BigDecimal("1680.00")).build());
        assertThat(svc.currentUnitValueInAccountCcy(1L, h)).isEqualByComparingTo("1680.00");
    }

    @Test
    void currentUnitValue_auto_noPriceReturnsNull() {
        StockHolding h = StockHolding.builder().id(52L).accountId(10L)
                .valuationMode(ValuationMode.AUTO).ticker("ZZZZ").market(Market.US)
                .currency("USD").shares(new BigDecimal("10")).build();
        when(fetcher.findLatestKnown("ZZZZ", Market.US)).thenReturn(null);
        assertThat(svc.currentUnitValueInAccountCcy(1L, h)).isNull();
    }

    @Test
    void convertToManual_preservesTotalViaUnit() {
        StockHolding h = StockHolding.builder().id(53L).accountId(10L)
                .valuationMode(ValuationMode.AUTO).ticker("BABA").market(Market.US)
                .currency("USD").shares(new BigDecimal("100")).build();
        when(holdingMapper.findById(53L)).thenReturn(Optional.of(h));
        svc.convertToManual(1L, 53L, new BigDecimal("8000"));   // 整笔 8000 / 100 股 = 单股 80
        ArgumentCaptor<StockHolding> cap = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdingMapper).update(cap.capture());
        StockHolding out = cap.getValue();
        assertThat(out.getValuationMode()).isEqualTo(ValuationMode.MANUAL);
        assertThat(out.getShares()).isEqualByComparingTo("100");
        assertThat(out.getManualValue()).isEqualByComparingTo("80");   // 100 × 80 = 8000 守恒
    }
}
