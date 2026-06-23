package com.family.finance.service.stock;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.FxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** v0.5 FR-78/79 防回归 · 股票现金联动(买入扣 / 卖出加 · 可负)。 */
class StockHoldingCashLinkTest {

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
        // USD 股票账户 family=1
        when(accountMapper.findById(10L)).thenReturn(Optional.of(
                Account.builder().id(10L).familyId(1L).type(AccountType.STOCK).currency("USD").build()));
    }

    @Test
    void deductCashWithoutCostThrows() {
        assertThatThrownBy(() -> svc.createAuto(1L, 10L, "PDD", "PDD", Market.US,
                new BigDecimal("100"), null, "USD", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buyDeductsCashCreatingNegativeRowWhenNoCash() {
        // 无现金行 → 买 100×$80=$8000 → 新建 -$8000 现金行(同币种无 FX)
        when(holdingMapper.findActiveByAccount(10L)).thenReturn(List.of());
        svc.createAuto(1L, 10L, "PDD", "PDD", Market.US, new BigDecimal("100"), new BigDecimal("80"), "USD", true);

        ArgumentCaptor<StockHolding> cap = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdingMapper, atLeast(2)).insert(cap.capture());
        StockHolding cashRow = cap.getAllValues().stream()
                .filter(h -> h.getValuationMode() == ValuationMode.CASH).findFirst().orElse(null);
        assertThat(cashRow).isNotNull();
        assertThat(cashRow.getManualValue()).isEqualByComparingTo("-8000.00");  // 现金可负
        // AUTO 持仓标 cashLinked
        StockHolding auto = cap.getAllValues().stream()
                .filter(h -> h.getValuationMode() == ValuationMode.AUTO).findFirst().orElse(null);
        assertThat(auto.getCashLinked()).isTrue();
    }

    @Test
    void buyDeductsFromExistingCashRow() {
        StockHolding cash = StockHolding.builder().id(99L).accountId(10L).valuationMode(ValuationMode.CASH)
                .currency("USD").manualValue(new BigDecimal("20000")).build();
        when(holdingMapper.findActiveByAccount(10L)).thenReturn(List.of(cash));
        svc.createAuto(1L, 10L, "BABA", "BABA", Market.US, new BigDecimal("100"), new BigDecimal("80"), "USD", true);

        ArgumentCaptor<StockHolding> cap = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdingMapper).update(cap.capture());
        assertThat(cap.getValue().getManualValue()).isEqualByComparingTo("12000.00"); // 20000 − 8000
    }

    @Test
    void nonLinkedHoldingArchiveDoesNotTouchCash() {
        // 向后兼容:非联动持仓归档不动现金
        StockHolding old = StockHolding.builder().id(5L).accountId(10L).valuationMode(ValuationMode.AUTO)
                .ticker("PDD").market(Market.US).shares(new BigDecimal("100")).cashLinked(false).build();
        when(holdingMapper.findById(5L)).thenReturn(Optional.of(old));
        svc.archive(1L, 5L);
        verify(holdingMapper, never()).update(any());
        verify(holdingMapper, never()).insert(any());
        verify(holdingMapper).archive(5L);
    }

    @Test
    void linkedHoldingArchiveAddsBackCashAtMarket() {
        StockHolding linked = StockHolding.builder().id(6L).accountId(10L).valuationMode(ValuationMode.AUTO)
                .ticker("PDD").market(Market.US).shares(new BigDecimal("100"))
                .costBasis(new BigDecimal("80")).currency("USD").cashLinked(true).build();
        when(holdingMapper.findById(6L)).thenReturn(Optional.of(linked));
        when(holdingMapper.findActiveByAccount(10L)).thenReturn(List.of());
        // 当前市价 $97 → 加回 100×97 = $9700
        when(fetcher.findLatestKnown("PDD", Market.US)).thenReturn(
                com.family.finance.domain.stock.StockPriceSnapshot.builder()
                        .ticker("PDD").market(Market.US).closePrice(new BigDecimal("97")).build());
        svc.archive(1L, 6L);

        ArgumentCaptor<StockHolding> cap = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdingMapper).insert(cap.capture());  // 新建现金行(加回)
        assertThat(cap.getValue().getValuationMode()).isEqualTo(ValuationMode.CASH);
        assertThat(cap.getValue().getManualValue()).isEqualByComparingTo("9700.00");
        verify(holdingMapper).archive(6L);
    }
}
