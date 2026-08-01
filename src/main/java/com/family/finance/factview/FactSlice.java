package com.family.finance.factview;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record FactSlice(
        FactFilter filter,
        List<AccountPeriodFact> rows,
        List<Long> periodIds,
        Long lastPeriodId,
        /**
         * v1.6.30 · 窗口内**已关账**的期(升序 · periodIds 的子集)。
         *
         * <p>为什么要单独有这个:{@code queryBase} 是 account × period 全交叉且**不过滤 status**,
         * 所以进行中的 OPEN 期照样进切片并成为 {@code lastPeriodId}。存量类指标(净资产/总资产/总负债)
         * 锚在它上面是对的(填报过程中就该看到最新余额,缺快照还会结转上期);但**收益类**指标
         * (本月资产收益 / XIRR / TWR / 钱赚)锚在半填的期上会把"还没录的收支"整个算成投资收益 ——
         * 2026-08 的 prod 实测:余额 21 条全填、收支 0 条,于是 9.1 万净资产变化一分不扣地成了投资收益。
         * 收益类改锚 {@link #returnAnchorPeriodId()}。</p>
         */
        List<Long> closedPeriodIds
) {
    /**
     * 4 参兼容构造 · 未提供关账信息时把全部期视为已关账(= v1.6.30 之前的行为)。
     * 保留它是为了让既有单测(直接 new FactSlice 造切片)无需改动、且断言口径不变。
     */
    public FactSlice(FactFilter filter, List<AccountPeriodFact> rows, List<Long> periodIds, Long lastPeriodId) {
        this(filter, rows, periodIds, lastPeriodId, periodIds);
    }

    /**
     * 收益类指标的期序列 · 只取已关账期,并统一收敛到**最近 12 期**;
     * 一期都没关账时退回全部期(宁可给个数,不给空页面)。
     *
     * <p>为什么要截到 12:各页窗口宽度本来就不一致 —— reports 自 v0.5.5 起把窗口锚「最新已关账期」
     * (注释写明就是为躲开月中半填的 OPEN 期),dashboard 用 {@code anchor.minusMonths(12)}(13 期),
     * checkup 用 {@code anchor.minusMonths(11)}(12 期)。于是同一个「家庭 XIRR」在 checkup 按 11 期
     * 走累计口径、在 reports 按 12 期走年化口径,实测 −0.21% vs +1.11%,用户无从分辨谁对。
     * 统一成「最近 ≤12 个已关账期」后,三页对同一指标取到同一批期。</p>
     *
     * <p>注意别加下限截断:{@code ytdInvestPnl} 会拿一个跨年 13 期的窗口进来,靠 periodIds
     * 取上一期做分段起点(不受本方法影响),按自然年过滤后再累加。</p>
     */
    public List<Long> returnPeriodIds() {
        List<Long> base = (closedPeriodIds == null || closedPeriodIds.isEmpty()) ? periodIds : closedPeriodIds;
        return base.size() <= 12 ? base : base.subList(base.size() - 12, base.size());
    }

    /** 收益类指标锚点 = 最新已关账期(无已关账期则 = lastPeriodId)。 */
    public Long returnAnchorPeriodId() {
        List<Long> ids = returnPeriodIds();
        return ids.isEmpty() ? null : ids.get(ids.size() - 1);
    }

    /** 最后一期是否「填报中」(在切片里但未关账)· 给页面打标注用。 */
    public boolean filingInProgress() {
        return lastPeriodId != null
                && closedPeriodIds != null
                && !closedPeriodIds.isEmpty()
                && !closedPeriodIds.contains(lastPeriodId);
    }

    /** 某期的 period_start(从事实行里取)· 给「2026-08 填报中」这类标注拼文案用。 */
    public java.time.LocalDate periodStartOf(Long periodId) {
        if (periodId == null) return null;
        return rows.stream()
                .filter(r -> periodId.equals(r.periodId()))
                .map(AccountPeriodFact::periodStart)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    public Map<Long, List<AccountPeriodFact>> byAccount() {
        return rows.stream()
                .collect(Collectors.groupingBy(AccountPeriodFact::accountId, LinkedHashMap::new, Collectors.toList()));
    }

    public Map<Long, List<AccountPeriodFact>> byPeriod() {
        return rows.stream()
                .sorted(Comparator.comparing(AccountPeriodFact::periodStart).thenComparing(AccountPeriodFact::displayOrder))
                .collect(Collectors.groupingBy(AccountPeriodFact::periodId, LinkedHashMap::new, Collectors.toList()));
    }
}
