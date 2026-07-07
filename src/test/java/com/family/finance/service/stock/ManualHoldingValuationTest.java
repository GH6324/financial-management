package com.family.finance.service.stock;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.stock.StockHolding;
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
    private AccountValuationService svc;

    @BeforeEach
    void setUp() {
        accountMapper = mock(AccountMapper.class);
        holdingMapper = mock(StockHoldingMapper.class);
        svc = new AccountValuationService(
                accountMapper, holdingMapper,
                mock(StockPriceSnapshotMapper.class), mock(SnapshotMapper.class),
                mock(PeriodMapper.class), mock(MemberMapper.class),
                mock(FxService.class), mock(FamilyService.class),
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
}
