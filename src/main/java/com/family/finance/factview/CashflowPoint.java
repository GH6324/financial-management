package com.family.finance.factview;

import java.math.BigDecimal;

/**
 * v0.10 · 实时收支趋势的单期点 · viewCurrency 口径。
 *
 * <p>{@code live=true} 标「进行中」的当前账期(前端最右浅色/描边)—— 这正是仪表盘趋势
 * 区别于 /reports 储蓄区(已关账快照,故意不含本月)的意义所在。</p>
 */
public record CashflowPoint(Long periodId, String label,
                            BigDecimal income, BigDecimal expense, BigDecimal netInflow,
                            boolean live) {}
