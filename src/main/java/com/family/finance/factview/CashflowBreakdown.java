package com.family.finance.factview;

import java.math.BigDecimal;

/**
 * v0.10 · 某期家庭「毛收入 / 毛支出 / 净流入(人赚)」· viewCurrency 口径。
 *
 * <p>与 {@code FactViewServiceImpl.pmcFirstNetInflow} <b>同源同分支</b>(PMC 优先 · 空回退 account cash_flow),
 * 只是把净额拆成毛收入、毛支出两分量,故 {@code income − expense == netInflow} 天然成立、
 * 且与 KPI 的人赚(lastNetInflow)同口径(避免卡片上「收入−支出」对不上人赚)。</p>
 */
public record CashflowBreakdown(BigDecimal income, BigDecimal expense, BigDecimal netInflow) {}
