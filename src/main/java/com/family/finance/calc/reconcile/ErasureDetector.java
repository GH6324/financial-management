package com.family.finance.calc.reconcile;

import java.math.BigDecimal;

/**
 * v1.18.3 · 「这次估值写回,是不是正在把刚进账户的钱抹平?」—— <b>一份判据,两处用</b>。
 *
 * <p>用它的有两处,而且必须是同一份:</p>
 * <ul>
 *   <li><b>事后</b>:{@code ReconciliationScanService} 扫历史,把已经被抹掉的钱找出来给人补</li>
 *   <li><b>事前</b>:{@code AccountValuationService.writeBackBalance} 在覆盖余额之前拦一道
 *       —— 复盘里的方案 B。事后能发现,事前才能不发生。</li>
 * </ul>
 *
 * <p><b>为什么判「相消」而不是判「对不上」</b>:第一直觉的不变量是
 * 「余额变化 = 流水 + 估值变动」,但估值抹钱时会<b>忠实地写一条 delta = −(被抹的钱) 的事件</b>,
 * 两边正好相消、恒等式闭合 —— 那种检查永远不会失败。真正的签名在时间线上:</p>
 * <pre>  08-17 17:42  转入 +40,000
 *  08-18 00:20  估值 Δ −40,000.00     ← 精确抹掉(生产实测)</pre>
 * <p>市场波动不可能精确到分,所以「Δ 恰好等于窗口内进出的钱的相反数」几乎不会误报。</p>
 *
 * <p>方向对称:转出没从账户扣掉(凭空多钱)同样会命中 —— 生产上也真有一笔
 * ({@code 划出 −125,000} → 31 秒后 {@code 估值 Δ +125,000})。</p>
 */
public final class ErasureDetector {

    /** 容差下限:配成 0 会让每一分钱的舍入都命中,那样的告警一天就会被关掉。 */
    public static final BigDecimal MIN_EPSILON = new BigDecimal("0.01");

    private ErasureDetector() {}

    /**
     * 这次估值写回,是不是把<b>最近若干笔</b>进出账户的钱抹平了?
     *
     * <p><b>为什么按「后缀和」逐个试,而不是拿窗口总和一次比</b>:窗口(上次估值 → 现在)里
     * 常常混着<b>已经正确入账</b>的流水 —— 只要那期间没有价格波动、估值就不会写事件,
     * 窗口于是一直累积。拿总和去比,一笔正确入账的钱就能把判据顶歪。
     * e2e 实测过:窗口里有 53,210 正确入账 + 48,765 被吞,总和 101,975 与 Δ(−48,765)
     * 相差 53,210 → 漏判。<b>按后缀和从最新往回累加</b>才对得上真实形态
     * (被吞的总是最近那几笔 —— 它们还没来得及落进现金行)。</p>
     *
     * @param delta          这次写回会让余额变化多少(新值 − 当前快照)
     * @param flowsOldestFirst 窗口内进出账户的钱,<b>按时间升序</b>(有符号:进为正)
     * @param epsilon        容差(取 max(epsilon, {@link #MIN_EPSILON}))
     * @return 被抹掉的金额(= 命中的那几笔之和);{@code null} = 没命中
     */
    public static BigDecimal erasedAmount(BigDecimal delta, java.util.List<BigDecimal> flowsOldestFirst,
                                          BigDecimal epsilon) {
        if (delta == null || flowsOldestFirst == null || flowsOldestFirst.isEmpty()) return null;
        BigDecimal eps = epsilon == null || epsilon.compareTo(MIN_EPSILON) < 0 ? MIN_EPSILON : epsilon;
        BigDecimal suffix = BigDecimal.ZERO;
        for (int i = flowsOldestFirst.size() - 1; i >= 0; i--) {
            BigDecimal f = flowsOldestFirst.get(i);
            if (f == null) continue;
            suffix = suffix.add(f);
            if (suffix.signum() == 0) continue;                 // 一进一出净额为 0,没东西可抹
            if (delta.add(suffix).abs().compareTo(eps) <= 0) return suffix;
        }
        return null;
    }

    /** 便捷判定:是否命中。 */
    public static boolean erasesFlows(BigDecimal delta, java.util.List<BigDecimal> flowsOldestFirst,
                                      BigDecimal epsilon) {
        return erasedAmount(delta, flowsOldestFirst, epsilon) != null;
    }
}
