package com.family.finance.web.report;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.service.allocation.RebalanceAdvisorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * v0.4 FR-62b · 调仓建议触发端点。
 * 用户点 reports 页"AI 调仓建议"按钮 → POST /reports/rebalance/advise → 触发 LLM(命中 30 天缓存直接返)。
 * 用 flash attribute 把成功/失败反馈带回 reports 页 · 用户看得到结果(不再"点了没反应")。
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class RebalanceController {

    private final RebalanceAdvisorService rebalanceAdvisorService;

    @PostMapping("/reports/rebalance/advise")
    public String advise(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam(name = "refresh", required = false, defaultValue = "false") boolean refresh,
                         RedirectAttributes ra) {
        try {
            var r = rebalanceAdvisorService.advise(me.getFamilyId(), refresh);
            log.info("rebalance advise · family={} refresh={} ok={} fromCache={} actions={}",
                me.getFamilyId(), refresh, r.ok(), r.fromCache(), r.actions() == null ? 0 : r.actions().size());
            if (r.ok()) {
                ra.addFlashAttribute("rebalanceFlash",
                    r.fromCache() ? "ok-cache" : "ok-fresh");
            } else {
                ra.addFlashAttribute("rebalanceFlash", "fail");
                ra.addFlashAttribute("rebalanceFlashReason",
                    r.errorReason() == null ? "AI 暂时不可用,请稍后再试" : r.errorReason());
            }
        } catch (Exception e) {
            log.warn("rebalance advise failed: {}", e.toString());
            ra.addFlashAttribute("rebalanceFlash", "fail");
            ra.addFlashAttribute("rebalanceFlashReason", "AI 服务异常,请稍后再试");
        }
        return "redirect:/reports#allocation-diff";
    }
}
