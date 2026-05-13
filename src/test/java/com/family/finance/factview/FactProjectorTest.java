package com.family.finance.factview;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FactProjectorTest {

    @Test
    void loanProjectsAsLiabilityWithNoLiquidity() {
        assertThat(FactProjector.classOf(AccountType.LOAN)).isEqualTo(AccountClass.LIABILITY);
        assertThat(FactProjector.liquidityOf(AccountType.LOAN)).isEqualTo(AccountLiquidity.NA);
    }

    @Test
    void cashProjectsAsLiquidAsset() {
        assertThat(FactProjector.classOf(AccountType.CASH)).isEqualTo(AccountClass.ASSET);
        assertThat(FactProjector.liquidityOf(AccountType.CASH)).isEqualTo(AccountLiquidity.LIQUID);
    }

    @Test
    void wealthFallbackIsSemiLiquid() {
        // 未设 product_category 或 pc.liquidity_class NULL · 走 AccountType 兜底
        assertThat(FactProjector.liquidityOf(AccountType.WEALTH, null)).isEqualTo(AccountLiquidity.SEMI_LIQUID);
        assertThat(FactProjector.liquidityOf(AccountType.WEALTH, "")).isEqualTo(AccountLiquidity.SEMI_LIQUID);
    }

    @Test
    void moneyFundMakesWealthLiquid() {
        // 关键场景:WEALTH 账户 + product=MONEY_FUND → LIQUID(余额宝 / 零钱通)
        assertThat(FactProjector.liquidityOf(AccountType.WEALTH, "LIQUID")).isEqualTo(AccountLiquidity.LIQUID);
    }

    @Test
    void illiquidProductOverridesStockType() {
        // STOCK 账户 + 私募 → ILLIQUID(默认 STOCK 是 SEMI_LIQUID)
        assertThat(FactProjector.liquidityOf(AccountType.STOCK, "ILLIQUID")).isEqualTo(AccountLiquidity.ILLIQUID);
    }

    @Test
    void invalidPcClassFallsBackToType() {
        // 脏数据 · 不抛 · 走 type 兜底
        assertThat(FactProjector.liquidityOf(AccountType.WEALTH, "BOGUS")).isEqualTo(AccountLiquidity.SEMI_LIQUID);
        assertThat(FactProjector.liquidityOf(AccountType.CASH, "garbage")).isEqualTo(AccountLiquidity.LIQUID);
    }

    @Test
    void multipliesOriginalAmountsByFxIntoBaseFields() {
        AccountPeriodFact fact = FactProjector.project(new FactBaseRow(
                1L,
                "富途证券",
                "STOCK",
                "USD",
                1L,
                1,
                10L,
                LocalDate.parse("2026-04-01"),
                LocalDate.parse("2026-04-30"),
                new BigDecimal("1000.00"),
                new BigDecimal("1100.00"),
                new BigDecimal("20.00"),
                new BigDecimal("5.00"),
                new BigDecimal("10.00"),
                new BigDecimal("0.00"),
                new BigDecimal("7.200000"),
                null  // productLiquidityClass · 未设 · 走 STOCK 兜底 (SEMI_LIQUID)
        ));

        assertThat(fact.endBalanceBase()).isEqualByComparingTo("7920.00");
        assertThat(fact.incomeBase()).isEqualByComparingTo("144.00");
        assertThat(fact.periodPnlBase()).isEqualByComparingTo("540.00");
        assertThat(fact.accountLiquidity()).isEqualTo(AccountLiquidity.SEMI_LIQUID);
    }

    @Test
    void projectorPicksUpProductLiquidityFromRow() {
        AccountPeriodFact fact = FactProjector.project(new FactBaseRow(
                7L, "余额宝", "WEALTH", "CNY", 1L, 5, 10L,
                LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"),
                new BigDecimal("10000.00"), new BigDecimal("10010.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE,
                "LIQUID"  // pc.liquidity_class · 来自 MONEY_FUND
        ));
        assertThat(fact.accountLiquidity()).isEqualTo(AccountLiquidity.LIQUID);
    }
}
