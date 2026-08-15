package com.family.finance.web.review;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.calc.review.AttributionEngine;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.factview.FactViewService;
import com.family.finance.service.review.AttributionService;
import com.family.finance.service.review.ReviewInsightService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.2 · AI 月度复盘端点(tech-design v1.2 §2)。
 * 复用与归因 fragment 完全相同的口径装配(同 slice 同恒等式),LLM 只解读。
 */
@Controller
@RequiredArgsConstructor
public class ReviewController {

    private final FamilyService familyService;
    private final PeriodMapper periodMapper;
    private final FactViewService factViewService;
    private final AttributionService attributionService;
    private final ReviewInsightService reviewInsightService;

    public record Req(String asof, String dim, boolean force, String currency, String accounts) {}

    @PostMapping("/review/insight")
    @ResponseBody
    public Map<String, Object> insight(@AuthenticationPrincipal MemberPrincipal me, @RequestBody Req req) {
        if (!reviewInsightService.available(me.getFamilyId())) {
            return Map.of("ok", false, "text", "AI 暂不可用 · 管理页配置 LLM 后开启");
        }
        Family family = familyService.require(me.getFamilyId());
        List<Period> all = periodMapper.findAllByFamily(me.getFamilyId());
        Period anchor = all.stream()
                .filter(p -> p.getPeriodStart() != null && p.getPeriodStart().toString().equals(req.asof()))
                .findFirst()
                .orElseGet(() -> all.stream()
                        .max(java.util.Comparator.comparing(Period::getPeriodStart)).orElseThrow());
        String dim = AttributionService.DIMS.containsKey(req.dim()) ? req.dim() : "acct";
        List<Long> accountIds = (req.accounts() == null || req.accounts().isBlank()) ? null
                : java.util.Arrays.stream(req.accounts().split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
        String ccy = req.currency() == null || req.currency().isBlank() ? family.getBaseCurrency() : req.currency();
        LocalDate start = anchor.getPeriodStart().minusMonths(1);
        FactSlice slice = factViewService.load(new FactFilter(me.getFamilyId(), family.getPeriodType(),
                start, anchor.getPeriodStart(), false, accountIds, ccy));
        KpiSnapshot kpis = factViewService.kpis(slice);
        var cf = factViewService.cashflowBreakdown(slice, slice.lastPeriodId());
        BigDecimal human = cf == null ? BigDecimal.ZERO
                : nz(cf.income()).subtract(nz(cf.expense()));
        AttributionEngine.Result attr = attributionService.attribute(me.getFamilyId(),
                slice.byPeriod().getOrDefault(slice.lastPeriodId(), List.of()),
                kpis.netWorthDelta(), human, kpis.openingBaselineLast());
        LinkedHashMap<String, BigDecimal> grouped =
                AttributionEngine.groupBy(attr, "acct".equals(dim) ? null : dim);
        ReviewInsightService.Review r = reviewInsightService.review(me.getFamilyId(), anchor.getId(),
                anchor.getPeriodStart().toString().substring(0, 7), dim, attr, grouped, req.force());
        return r == null ? Map.of("ok", false, "text", "AI 服务暂时不可用,稍后再试")
                         : Map.of("ok", true, "text", r.text(), "vendor", r.vendor(), "cached", r.cached());
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
