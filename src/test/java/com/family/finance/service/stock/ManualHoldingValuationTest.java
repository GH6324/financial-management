package com.family.finance.service.stock;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.fx.FxRate;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.StockPriceSnapshot;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.repository.StockPriceSnapshotMapper;
import com.family.finance.repository.StockValuationEventMapper;
import com.family.finance.service.FxService;
import com.family.finance.service.FamilyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * v0.12 · 未上市(MANUAL)持仓估值 = 股数 × 单股估值 · 老数据(shares=null)兜底按 1 股。
 */
class ManualHoldingValuationTest {

    private AccountMapper accountMapper;
    private StockHoldingMapper holdingMapper;
    private StockPriceSnapshotMapper priceMapper;
    private PeriodMapper periodMapper;
    private FxService fxService;
    private AccountValuationService svc;

    @BeforeEach
    void setUp() {
        accountMapper = mock(AccountMapper.class);
        holdingMapper = mock(StockHoldingMapper.class);
        priceMapper = mock(StockPriceSnapshotMapper.class);
        periodMapper = mock(PeriodMapper.class);
        fxService = mock(FxService.class);
        svc = new AccountValuationService(
                accountMapper, holdingMapper,
                priceMapper, mock(SnapshotMapper.class),
                periodMapper, mock(MemberMapper.class),
                fxService, mock(org.springframework.context.ApplicationEventPublisher.class), mock(FamilyService.class),
                mock(StockValuationEventMapper.class));
        when(accountMapper.findById(10L)).thenReturn(Optional.of(
                Account.builder().id(10L).familyId(1L).type(AccountType.STOCK).currency("CNY").build()));
    }

    @Test
    void manualValuation_isSharesTimesUnit() {
        StockHolding h = StockHolding.builder().accountId(10L).valuationMode(ValuationMode.MANUAL)
                .shares(new BigDecimal("2000")).manualValue(new BigDecimal("240")).build();
        when(holdingMapper.findActiveByAccount(10L)).thenReturn(List.of(h));
        var r = svc.valuate(1L, 10L);
        assertThat(r.manualBaseValue()).isEqualByComparingTo("480000");   // 2000 × 240
        assertThat(r.totalBaseValue()).isEqualByComparingTo("480000");
    }

    @Test
    void legacyManual_sharesNull_treatedAsOneShare() {
        // 迁移前老数据:shares=NULL,manual_value=整笔市值 → 兜底 1 股 → 总值不变
        StockHolding h = StockHolding.builder().accountId(10L).valuationMode(ValuationMode.MANUAL)
                .shares(null).manualValue(new BigDecimal("480000")).build();
        when(holdingMapper.findActiveByAccount(10L)).thenReturn(List.of(h));
        var r = svc.valuate(1L, 10L);
        assertThat(r.totalBaseValue()).isEqualByComparingTo("480000");
    }

    @Test
    void cryptoAutoValuation_convertsUsdQuoteIntoAccountCurrency() {
        when(accountMapper.findById(10L)).thenReturn(Optional.of(
                Account.builder().id(10L).familyId(1L).type(AccountType.CRYPTO).currency("CNY").build()));
        StockHolding h = StockHolding.builder().accountId(10L).valuationMode(ValuationMode.AUTO)
                .ticker("BTC").market(Market.CRYPTO).currency("USD")
                .shares(new BigDecimal("0.5")).build();
        when(holdingMapper.findActiveByAccount(10L)).thenReturn(List.of(h));
        when(priceMapper.findLatest("BTC", "CRYPTO")).thenReturn(Optional.of(
                StockPriceSnapshot.builder()
                        .ticker("BTC")
                        .market(Market.CRYPTO)
                        .tradeDate(java.time.LocalDate.now())
                        .closePrice(new BigDecimal("60000"))
                        .currency("USD")
                        .source("binance")
                        .build()));
        when(periodMapper.findCurrentOpen(1L)).thenReturn(Optional.of(Period.builder().id(99L).build()));
        when(fxService.getOrFetchRate(1L, "USD", "CNY", 99L)).thenReturn(Optional.of(
                FxRate.builder().rate(new BigDecimal("7.20")).build()));

        var r = svc.valuate(1L, 10L);

        assertThat(r.autoBaseValue()).isEqualByComparingTo("216000.00"); // 0.5 × 60000 USD × 7.20
        assertThat(r.totalBaseValue()).isEqualByComparingTo("216000.00");
    }
}
