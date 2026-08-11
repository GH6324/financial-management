package com.family.finance.factview;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * v1.10 · 单期的资金流分解 —— 「本期净资产为什么变了」的原子答案。
 *
 * <p>恒等式(项目里到处依赖它,dashboard 的「本期怎么变」卡也靠它):</p>
 * <pre>
 *   ΔNW = 人赚(netInflow) + 开账基线(openingBaseline) + 钱赚(pnl)
 * </pre>
 *
 * <p>这个记录是从 {@code principalVsReturnDecomposition} 里**抽出来**的 ——
 * 那个方法原来只输出累计值,而 v1.10 的资金流瀑布与三列对照需要**逐期**值。
 * 抽出来而不是另写一份,是为了避免"第二套口径"(项目里 v1.8 支出、v1.9.4 财富水位
 * 都栽在同一个指标有两套算法上)。累计版现在消费本方法,两者永远同源。</p>
 *
 * @param netInflow       人赚 · PMC 优先净流入(= 收入 − 支出,与 cashflowBreakdown 同源同分支)
 * @param openingBaseline 本期首次出现账户的期末净值合计 · 外部资本纳入,既不算人赚也不算钱赚
 * @param nwDelta         期末净资产 − 上期末净资产
 * @param pnl             钱赚 · = nwDelta − netInflow − openingBaseline(由构造保证恒等式成立)
 * @param netWorth        本期期末净资产
 * @param prevNetWorth    上期期末净资产
 */
public record PeriodFlow(
        Long periodId,
        LocalDate periodStart,
        String label,
        BigDecimal netInflow,
        BigDecimal openingBaseline,
        BigDecimal nwDelta,
        BigDecimal pnl,
        BigDecimal netWorth,
        BigDecimal prevNetWorth
) {
}
