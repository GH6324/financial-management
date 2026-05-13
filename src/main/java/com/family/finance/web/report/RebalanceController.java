package com.family.finance.web.report;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.service.allocation.RebalanceAdvisorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * v0.4 FR-62b · 调仓建议触发端点。
 * 用户点 reports 页"AI 调仓建议"按钮 → POST /reports/rebalance/advise → 触发 LLM(命中 30 天缓存直接返)。
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class RebalanceController {

    private final RebalanceAdvisorService rebalanceAdvisorService;

    @PostMapping("/reports/rebalance/advise")
    public String advise(@AuthenticationPrincipal MemberPrincipal me) {
        try {
            var r = rebalanceAdvisorService.advise(me.getFamilyId());
            log.info("rebalance advise · family={} ok={} fromCache={} actions={}",
                me.getFamilyId(), r.ok(), r.fromCache(), r.actions() == null ? 0 : r.actions().size());
        } catch (Exception e) {
            log.warn("rebalance advise failed: {}", e.toString());
        }
        return "redirect:/reports#allocation-diff";
    }
}
