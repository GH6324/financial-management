package com.family.finance.calc.reconcile;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.18.3 · 「这次估值写回是不是在抹平刚进账户的钱」—— 判据本身的护栏。
 *
 * <p>这一份判据<b>两处用</b>:事后对账扫描({@code ReconciliationScanService})、
 * 事前写回拦截({@code AccountValuationService.writeBackBalance})。
 * 两处共用同一个函数,是这次复盘的核心结论之一 ——
 * <b>同一件事有两份判据</b>正是这个 bug 反复出现的形状(已归档 5 次)。</p>
 *
 * <p>每条都成对写:该拦的拦住 + 不该拦的别拦。只写前者的检查是个装饰品。</p>
 */
class ErasureDetectorTest {

    private static final BigDecimal EPS = ErasureDetector.MIN_EPSILON;

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    // ──────────────── 该拦的 ────────────────

    /** 生产形态:转入 +40,000,估值 Δ −40,000.00 精确抹平。 */
    @Test
    void 拦住_精确抹平刚进来的钱() {
        assertThat(ErasureDetector.erasesFlows(d("-40000.00"), List.of(d("40000.00")), EPS)).isTrue();
    }

    /** 方向对称:转出 −125,000 没扣掉,估值 Δ +125,000 又加回去(生产上真有这一笔)。 */
    @Test
    void 拦住_转出被加回去也算抹平() {
        assertThat(ErasureDetector.erasesFlows(d("125000.00"), List.of(d("-125000.00")), EPS)).isTrue();
    }

    /** 一分钱的舍入不该让判据失效。 */
    @Test
    void 拦住_容差之内仍算精确相消() {
        assertThat(ErasureDetector.erasesFlows(d("-40000.005"), List.of(d("40000.00")), EPS)).isTrue();
    }

    // ──────────────── 不该拦的 ────────────────

    /** 钱正确入账:估值 Δ 只反映真实涨跌,与流水无关。 */
    @Test
    void 不拦_正常涨跌() {
        assertThat(ErasureDetector.erasesFlows(d("1350.00"), List.of(d("40000.00")), EPS)).isFalse();
        assertThat(ErasureDetector.erasesFlows(d("-1350.00"), List.of(d("40000.00")), EPS)).isFalse();
    }

    /** 窗口里没有钱进出 —— 没有东西可被抹,再大的 Δ 也只是市场波动。 */
    @Test
    void 不拦_窗口内没有流水() {
        assertThat(ErasureDetector.erasesFlows(d("-40000.00"), List.of(BigDecimal.ZERO), EPS)).isFalse();
        assertThat(ErasureDetector.erasesFlows(d("-40000.00"), null, EPS)).isFalse();
    }

    /** 差一点点不算「精确相消」—— 市场波动恰好差 1000 的情况必须放行。 */
    @Test
    void 不拦_差额明显时不当成抹平() {
        assertThat(ErasureDetector.erasesFlows(d("-39000.00"), List.of(d("40000.00")), EPS)).isFalse();
    }

    /**
     * 容差有<b>下限</b>:传 0 会让每一分钱的舍入都命中,那样的拦截一天就会被人关掉 ——
     * 而这条拦截守的是钱,被关掉等于没有。
     */
    @Test
    void 容差有下限_传0也按0_01算() {
        assertThat(ErasureDetector.erasesFlows(d("-40000.005"), List.of(d("40000.00")), BigDecimal.ZERO)).isTrue();
        assertThat(ErasureDetector.erasesFlows(d("-40000.005"), List.of(d("40000.00")), null)).isTrue();
    }

    /**
     * <b>e2e 抓出来的那条</b>:窗口里混着<b>已经正确入账</b>的钱(53,210)和<b>被吞</b>的钱(48,765)。
     * 拿窗口<b>总和</b>去比会相差 53,210 → 漏判;按<b>后缀和</b>从最新往回累加才对得上 ——
     * 被吞的总是最近那几笔(还没来得及落进现金行)。
     */
    @Test
    void 拦住_窗口里混着已入账的钱时仍按后缀和命中() {
        assertThat(ErasureDetector.erasedAmount(d("-48765.00"),
                List.of(d("53210.00"), d("48765.00")), EPS)).isEqualByComparingTo("48765.00");
    }

    /** 最近两笔一起被吞:后缀和累加到第二笔才命中。 */
    @Test
    void 拦住_最近两笔一起被吞() {
        assertThat(ErasureDetector.erasedAmount(d("-75000.00"),
                List.of(d("10000.00"), d("40000.00"), d("35000.00")), EPS)).isEqualByComparingTo("75000.00");
    }

    /** 只有【更早】那笔与 Δ 相消、而最近的没有 —— 不该命中(被吞的不会跳过最近的那几笔)。 */
    @Test
    void 不拦_只有更早那笔对得上() {
        assertThat(ErasureDetector.erasedAmount(d("-40000.00"),
                List.of(d("40000.00"), d("7.00")), EPS)).isNull();
    }

    @Test
    void 空值不炸() {
        assertThat(ErasureDetector.erasesFlows(null, List.of(d("40000.00")), EPS)).isFalse();
    }
}
