package com.family.finance.service.macro;

import com.family.finance.factview.TrendPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.9.4 · 财富水位的可用性判定。
 *
 * <p>起因是 prod 实报:关了三期还在显示「财富水位需要至少 2 期净资产数据 + 宏观基准」。
 * 根因在 {@code FactViewServiceImpl.netWorthTrendExOpening} —— 它给财富水位的序列首点
 * **按构造恒为 0**(首期的「首次出现账户」按定义是全部账户),而这里以首点为锚、
 * anchor<=0 直接判不可用。于是只要时间窗包含家庭首期,这一节就永久不出现;
 * 新用户只有两三期、任何窗口都含首期 → 从来没见过它。</p>
 *
 * <p>这些断言守两件事:① 锚是正数就得算出来 ② 不可用时**原因要分得开**,
 * 别再把「期数不足」和「起点净资产非正」混成一句让用户照着记账也没用。</p>
 */
class WaterLevelServiceTest {

    private WaterLevelService svc() {
        MacroBenchmarkService macro = mock(MacroBenchmarkService.class);
        when(macro.all()).thenReturn(List.of());
        // 缺宏观数据时走三法均值 fallback —— 所以「宏观基准」从来不是可用性的条件
        when(macro.cpiAverages()).thenReturn(new MacroBenchmarkService.Averages(
                new BigDecimal("2.00"), new BigDecimal("2.00"), new BigDecimal("2.00")));
        when(macro.m2Averages()).thenReturn(new MacroBenchmarkService.Averages(
                new BigDecimal("9.00"), new BigDecimal("9.00"), new BigDecimal("9.00")));
        return new WaterLevelService(macro);
    }

    private TrendPoint p(int idx, String date, String value) {
        return new TrendPoint((long) idx, LocalDate.parse(date), date.substring(0, 7),
                value == null ? null : new BigDecimal(value));
    }

    // ── 正常可用 ────────────────────────────────────────────────────────

    @Test
    void 两期且锚为正就该算出来() {
        var wl = svc().compute(List.of(p(1, "2026-01-01", "1000000"), p(2, "2026-02-01", "1100000")));
        assertThat(wl.available()).isTrue();
        assertThat(wl.reason()).isNull();
        assertThat(wl.anchor()).isEqualByComparingTo("1000000");
        assertThat(wl.current()).isEqualByComparingTo("1100000");
        assertThat(wl.nominalGrowthPct()).isEqualByComparingTo("10.00");
    }

    @Test
    void 缺宏观数据不影响可用性_走三法均值fallback() {
        // macro.all() 返回空表(逐年数据一条都没有),仍然必须可用 ——
        // 兜底文案里那句「+ 宏观基准」是错的,它根本不是条件。
        var wl = svc().compute(List.of(p(1, "2026-01-01", "1000000"), p(2, "2027-01-01", "1000000")));
        assertThat(wl.available()).isTrue();
        assertThat(wl.cpiBaseline()).isGreaterThan(wl.anchor());   // 一年 2% 通胀,购买力线抬高
        assertThat(wl.aboveCpi()).isFalse();                       // 名义没涨 → 跑输 CPI
    }

    // ── 不可用的两种原因必须分得开 ────────────────────────────────────

    @Test
    void 期数不足的原因是NOT_ENOUGH_PERIODS() {
        assertThat(svc().compute(List.of()).reason())
                .isEqualTo(WaterLevelService.Reason.NOT_ENOUGH_PERIODS);
        assertThat(svc().compute(null).reason())
                .isEqualTo(WaterLevelService.Reason.NOT_ENOUGH_PERIODS);
        assertThat(svc().compute(List.of(p(1, "2026-01-01", "1000000"))).reason())
                .isEqualTo(WaterLevelService.Reason.NOT_ENOUGH_PERIODS);
    }

    @Test
    void 起点净资产非正的原因不是期数不足() {
        // 这是 prod 那条 bug 的核心:期数明明够,却给用户「需要至少 2 期」的提示,
        // 照着提示继续记账永远不会好。原因必须能分开,页面才能说真话。
        for (String anchor : new String[]{"0", "-500000"}) {
            var wl = svc().compute(List.of(p(1, "2026-01-01", anchor), p(2, "2026-02-01", "1100000")));
            assertThat(wl.available()).isFalse();
            assertThat(wl.reason())
                    .as("anchor=%s", anchor)
                    .isEqualTo(WaterLevelService.Reason.NON_POSITIVE_ANCHOR);
        }
    }

    @Test
    void 锚为null也算非正锚而不是期数不足() {
        var wl = svc().compute(List.of(p(1, "2026-01-01", null), p(2, "2026-02-01", "1100000")));
        assertThat(wl.reason()).isEqualTo(WaterLevelService.Reason.NON_POSITIVE_ANCHOR);
    }

    @Test
    void 可用时reason必须是null_否则页面会同时渲染兜底和正文() {
        var wl = svc().compute(List.of(p(1, "2026-01-01", "1"), p(2, "2026-02-01", "2")));
        assertThat(wl.available()).isTrue();
        assertThat(wl.reason()).isNull();
    }
}
