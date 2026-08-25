package com.family.finance.factview;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 净资产趋势上的一个点。
 *
 * @param live v1.18.7 · 这一期是否<b>还在进行中</b>(未关账)。
 *
 * <p>加它是因为同一页上两张图做法不一致:收支趋势的 {@code CashflowPoint} 早就有 {@code live},
 * 图上把进行中的本月标成浅色 + 「· 进行中」,并写明「报表的收支趋势只到上一已关账期」;
 * 而净资产趋势的最右点同样是进行中期,却和已关账的点<b>长得一模一样</b>。</p>
 *
 * <p>净资产是<b>存量</b>,月中快照本身是条曲线上合法的点(不像流量那样只录了半个月),
 * 所以危害比收支小 —— 但「这个点还会变」这件事该让人看见,而不是靠他自己记得今天是几号。</p>
 */
public record TrendPoint(Long periodId, LocalDate periodStart, String label, BigDecimal value, boolean live) {

    /** 向后兼容:不关心「是否进行中」的调用方继续传 4 参(默认 false = 视作已定格)。 */
    public TrendPoint(Long periodId, LocalDate periodStart, String label, BigDecimal value) {
        this(periodId, periodStart, label, value, false);
    }
}
