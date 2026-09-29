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
    /** v1.27 · 调仓也按「范围 + 模板 + 分析偏好」(FR-827 / FR-846) */
    private final com.family.finance.service.analysis.AnalysisTemplateService templateService;
    private final com.family.finance.service.analysis.AnalysisScopeService scopeService;
    private final com.family.finance.service.analysis.AnalysisContextService contextService;
    /** v1.28 · 没生成出建议时,也能看这一次发出去了什么(PRD FR-911) */
    private final com.family.finance.service.llmtrace.PromptPeekLinks promptPeek;

    @PostMapping("/reports/rebalance/advise")
    public String advise(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam(name = "refresh", required = false, defaultValue = "false") boolean refresh,
                         @RequestParam(name = "scope", required = false) String scopeParam,
                         @RequestParam(name = "tpl", required = false) String tplParam,
                         RedirectAttributes ra) {
        String back = com.family.finance.web.analysis.AnalysisUrls.with("/reports",
                com.family.finance.service.analysis.ScopeKind.parse(scopeParam) == null ? null : scopeParam.toUpperCase(),
                tplParam == null || tplParam.isBlank() ? null : tplParam, "allocation-diff");
        try {
            long fid = me.getFamilyId();
            var template = templateService.resolve(fid, tplParam);
            var scope = template.scope() != null
                    ? scopeService.exactly(fid, template.scope(), null)
                    : scopeService.resolve(fid, scopeParam, null);
            var ctx = contextService.of(fid, scope, template);
            var r = rebalanceAdvisorService.advise(fid, refresh, ctx);
            log.info("rebalance advise · family={} refresh={} ok={} fromCache={} actions={}",
                me.getFamilyId(), refresh, r.ok(), r.fromCache(), r.actions() == null ? 0 : r.actions().size());
            if (r.ok()) {
                ra.addFlashAttribute("rebalanceFlash",
                    r.fromCache() ? "ok-cache" : "ok-fresh");
            } else {
                ra.addFlashAttribute("rebalanceFlash", "fail");
                ra.addFlashAttribute("rebalanceFlashReason",
                    r.errorReason() == null ? "AI 暂时不可用,请稍后再试" : r.errorReason());
                ra.addFlashAttribute("rebalanceFlashPeek", promptPeek.of(fid, r.promptRecordId()));
            }
        } catch (Exception e) {
            log.warn("rebalance advise failed: {}", e.toString());
            ra.addFlashAttribute("rebalanceFlash", "fail");
            ra.addFlashAttribute("rebalanceFlashReason", "AI 服务异常,请稍后再试");
        }
        return "redirect:" + back;
    }
}
