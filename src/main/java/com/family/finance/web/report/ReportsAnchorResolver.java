package com.family.finance.web.report;

import com.family.finance.domain.period.Period;

import java.util.List;
import java.util.Optional;

/**
 * 报表锚定期选择 · v0.5.5 FR-94。
 *
 * <p>报表 = <b>已关账账期的稳定快照</b>(区别于实时的 dashboard)。锚定优先级:</p>
 * <ol>
 *   <li>最近一个「已关账(CLOSED)且 ≤ 今天」的账期 → 作快照锚点,{@code closedSnapshot=true}</li>
 *   <li>无已关账期 → 退到 currentOpen / 最新一期,<b>仅用于渲染页面外壳</b>(账户范围 / FX / range 选择器),
 *       {@code closedSnapshot=false} → 上游据此显引导空态、不显朱印、不算收益指标</li>
 * </ol>
 *
 * <p>纯函数 · 不碰 DB:三个候选由 caller 从 {@code PeriodMapper} 取好传入,便于单测。</p>
 */
public final class ReportsAnchorResolver {

    private ReportsAnchorResolver() {}

    /**
     * @param anchor         锚定期(快照锚 or 外壳锚)
     * @param closedSnapshot 是否落在"已关账快照"上(true 才显朱印 + 算收益指标)
     */
    public record AnchorChoice(Period anchor, boolean closedSnapshot) {}

    /**
     * @param latestClosed   最近已关账(≤今天)账期 · {@code PeriodMapper.findLatestClosedAsOf}
     * @param currentOpen    当前 OPEN 账期 · {@code PeriodMapper.findCurrentOpen}
     * @param latestFallback 最新一期(任意状态)· {@code PeriodMapper.findLatest(familyId, 1)} · 末位兜底
     * @throws IllegalStateException 三者皆空(家庭尚未创建任何账期)
     */
    public static AnchorChoice resolve(Optional<Period> latestClosed,
                                       Optional<Period> currentOpen,
                                       List<Period> latestFallback) {
        if (latestClosed != null && latestClosed.isPresent()) {
            return new AnchorChoice(latestClosed.get(), true);
        }
        Period fallback = (currentOpen != null && currentOpen.isPresent())
                ? currentOpen.get()
                : (latestFallback == null || latestFallback.isEmpty() ? null : latestFallback.get(0));
        if (fallback == null) {
            throw new IllegalStateException("尚未创建周期");
        }
        return new AnchorChoice(fallback, false);
    }
}
