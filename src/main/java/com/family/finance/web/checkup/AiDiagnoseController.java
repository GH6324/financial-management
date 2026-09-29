package com.family.finance.web.checkup;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.checkup.AccountDiagnose;
import com.family.finance.service.checkup.AccountDiagnoseService;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.checkup.FamilyDiagnoseService;
import com.family.finance.service.checkup.llm.LlmDiagnoseService;
import com.family.finance.service.checkup.rule.Advice;
import com.family.finance.service.checkup.rule.AdviceEngine;
import com.family.finance.service.checkup.rule.RuleContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * AI 综合智能诊断 controller · v0.2 FR-40c · 2026-05-10 修订(决策 20)
 *
 * <p>替代旧的 {@link AdvicePolishController} per-advice polish 模式。
 * 新方向:进入 /checkup 页面后,前端通过 HTMX `hx-trigger="load"` 异步 fetch
 * 这个 endpoint,后端组装完整全家画像 + 命中规则,调 LLM 综合诊断。
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET /checkup/diagnose} — 全家维度</li>
 *   <li>{@code GET /checkup/diagnose?account=X} — 账户维度</li>
 * </ul>
 *
 * <p>返回 HTMX fragment(`checkup/_ai-diagnose :: panel`),包含:
 * <ul>
 *   <li>成功时:200-500 字综合诊断长文 + vendor 标识 + cache 状态</li>
 *   <li>降级时:「AI 暂时不可用,以下为规则硬数据」+ 刷新链接</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
public class AiDiagnoseController {

    private final AdviceEngine adviceEngine;
    private final AccountDiagnoseService accountDiagnoseService;
    private final FamilyDiagnoseService familyDiagnoseService;
    private final LlmDiagnoseService llmDiagnoseService;
    private final com.family.finance.service.checkup.AdviceDismissService adviceDismiss;
    private final AccountMapper accountMapper;
    private final FactViewService factViewService;
    private final com.family.finance.service.insight.AssetInsightService assetInsightService;
    /** v1.27 · 范围 + 模板 + 分析偏好 */
    private final com.family.finance.service.analysis.AnalysisTemplateService templateService;
    private final com.family.finance.service.analysis.AnalysisScopeService scopeService;
    private final com.family.finance.service.analysis.AnalysisContextService contextService;
    private final com.family.finance.service.analysis.AnalysisHintService hintService;
    /** v1.28 · 卡片头 >_ 指向哪(PRD FR-900) */
    private final com.family.finance.service.llmtrace.PromptPeekLinks promptPeek;

    @GetMapping("/checkup/diagnose")
    public String diagnose(@AuthenticationPrincipal MemberPrincipal me,
                           @RequestParam(name = "account", required = false) Long accountId,
                           @RequestParam(name = "refresh", required = false, defaultValue = "false") boolean refresh,
                           @RequestParam(name = "scope", required = false) String scopeParam,
                           @RequestParam(name = "tpl", required = false) String tplParam,
                           Model model) {
        BigDecimal avgExp = computeAvgExpense(me.getFamilyId());

        LlmDiagnoseService.DiagnoseResult result;
        if (accountId == null) {
            // 全家维度 · v1.27 按「范围 + 模板 + 分析偏好」(FR-846):
            //   模板固定了范围 → 按模板的范围重算诊断(配置 / 风险两节必须与 AI 用的范围同源);
            //   否则按页面上的范围(?scope= / 家庭默认)
            var template = templateService.resolve(me.getFamilyId(), tplParam);
            FamilyDiagnose diagnose = template.scope() != null
                    ? familyDiagnoseService.diagnoseExactly(me.getFamilyId(), template.scope())
                    : familyDiagnoseService.diagnose(me.getFamilyId(), scopeParam);
            List<AccountDiagnose> accounts = collectAccounts(me.getFamilyId());
            RuleContext ctx = RuleContext.forFamily(diagnose, accounts, avgExp);
            // issue #22 · 用户标成「不适用」的,也不再拿去问 AI
            List<Advice> advice = adviceDismiss.visible(me.getFamilyId(), adviceEngine.evaluate(ctx));

            var analysis = contextService.of(me.getFamilyId(), diagnose.scope(), template);
            result = llmDiagnoseService.diagnoseFamily(me.getFamilyId(), me.getMemberId(),
                    diagnose, advice, refresh, analysis);
            addFooterModel(me, model, analysis, scopeParam, tplParam, "/checkup/diagnose");
        } else {
            // 账户维度
            Optional<Account> account = accountMapper.findById(me.getFamilyId(), accountId)
                    .filter(a -> a.getFamilyId().equals(me.getFamilyId()));
            if (account.isEmpty()) {
                model.addAttribute("result", LlmDiagnoseService.DiagnoseResult.unavailable("账户不存在"));
                return "checkup/_ai-diagnose :: panel";
            }
            AccountDiagnose ad = accountDiagnoseService.diagnose(me.getFamilyId(), accountId);
            FamilyDiagnose fd = familyDiagnoseService.diagnose(me.getFamilyId());
            RuleContext ctx = RuleContext.forAccount(ad, fd, List.of(ad), avgExp);
            // issue #22 · 用户标成「不适用」的,也不再拿去问 AI
            List<Advice> advice = adviceDismiss.visible(me.getFamilyId(), adviceEngine.evaluate(ctx));

            // v1.27 FR-851 · 单账户诊断也读分析偏好
            result = llmDiagnoseService.diagnoseAccount(me.getFamilyId(), me.getMemberId(),
                    fd, ad, advice, refresh, contextService.preferences(me.getFamilyId()));
        }

        model.addAttribute("result", result);
        model.addAttribute("peekSrc", peekSrc(me.getFamilyId(), result, accountId == null
                ? com.family.finance.service.llmtrace.PromptSurface.DIAGNOSE_FAMILY
                : com.family.finance.service.llmtrace.PromptSurface.DIAGNOSE_ACCOUNT));
        model.addAttribute("scope", accountId == null ? "FAMILY" : "ACCOUNT");
        model.addAttribute("accountId", accountId);
        return "checkup/_ai-diagnose :: panel";
    }

    /**
     * v0.6 FR-100~109 · AI 资产洞察(集中度 / 资产负债表 / 再平衡·行为 / 低利率)。
     *
     * <p>前端通过 HTMX `hx-trigger="load"` 异步 fetch。返回 fragment 同时含:
     * 「硬数据」(由 {@link com.family.finance.service.insight.AssetInsightService} 工程预算)+
     * 「AI 解读」(由 {@link LlmDiagnoseService#diagnoseAssetInsight} · LLM 只解读不算数)。</p>
     */
    @GetMapping("/checkup/insight")
    public String insight(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(name = "refresh", required = false, defaultValue = "false") boolean refresh,
                          @RequestParam(name = "scope", required = false) String scopeParam,
                          @RequestParam(name = "tpl", required = false) String tplParam,
                          Model model) {
        long fid = me.getFamilyId();
        // v1.27 · 集中度 / 再平衡 / 低利率按范围;资产负债表永远全量(FR-824 / FR-825)
        var template = templateService.resolve(fid, tplParam);
        FactSlice slice = factViewService.loadDefault(fid);
        var scope = template.scope() != null
                ? scopeService.exactly(fid, template.scope(), slice)
                : scopeService.resolve(fid, scopeParam, slice);
        com.family.finance.service.insight.AssetInsight insight =
                assetInsightService.compute(fid, slice, scope, template.anchor());
        var analysis = contextService.of(fid, scope, template);
        LlmDiagnoseService.DiagnoseResult result = scope.empty()
                ? LlmDiagnoseService.DiagnoseResult.skipped("所有资产都标成了不参与配置分析,没有可分析的部分 —— AI 这次不分析。")
                : llmDiagnoseService.diagnoseAssetInsight(fid, me.getMemberId(), insight, refresh, analysis);
        model.addAttribute("insight", insight);
        model.addAttribute("result", result);
        model.addAttribute("peekSrc", peekSrc(fid, result, com.family.finance.service.llmtrace.PromptSurface.ASSET_INSIGHT));
        addFooterModel(me, model, analysis, scopeParam, tplParam, "/checkup/insight");
        return "checkup/_ai-insight :: panel";
    }

    /**
     * v1.28 · 这份结果的「AI 收到了什么」:有记录 → 面板;这次有意不分析 → 说明原因;
     * 什么都没发出去(还没配大模型 / 内部错误)→ 照实说没发。体检三处的结果只在内存里,不存在「老结果」。
     */
    private String peekSrc(long familyId, LlmDiagnoseService.DiagnoseResult r,
                           com.family.finance.service.llmtrace.PromptSurface surface) {
        if (r.promptRecordId() != null) return promptPeek.of(familyId, r.promptRecordId());
        String why = "skipped".equals(r.vendor()) ? r.text()
                : "这次没有发出去 —— 还没配大模型,或配好的都暂时不可用(管理 → AI 接入)。";
        return promptPeek.of(familyId, null, surface, null, why);
    }

    /**
     * v1.27 FR-848 · AI 结果页脚:「按「X」· 范围 · 参考了 N 条分析偏好 · 换模板 ▾ · 定制这个模板 →」。
     * 换模板 = 带着新 tpl 回到体检页(记在网址上,不改家里的默认);定制 = 去定制页,存完回到这里用新模板重跑。
     */
    private void addFooterModel(MemberPrincipal me, Model model,
                                com.family.finance.service.analysis.AnalysisContext analysis,
                                String scopeParam, String tplParam, String selfPath) {
        String scope = com.family.finance.service.analysis.ScopeKind.parse(scopeParam) == null ? null
                : scopeParam.toUpperCase();
        String tpl = tplParam == null || tplParam.isBlank() ? null : tplParam;
        model.addAttribute("analysis", analysis);
        model.addAttribute("analysisTemplates", templateService.list(me.getFamilyId()));
        model.addAttribute("footerScopeParam", scope);
        model.addAttribute("refreshUrl", com.family.finance.web.analysis.AnalysisUrls.with(selfPath, scope, tpl, null)
                + (scope == null && tpl == null ? "?" : "&") + "refresh=true");
        model.addAttribute("customizeUrl", "/admin/analysis/template/new?from="
                + java.net.URLEncoder.encode(analysis.template().key(), java.nio.charset.StandardCharsets.UTF_8)
                + "&back=" + java.net.URLEncoder.encode(
                        com.family.finance.web.analysis.AnalysisUrls.with("/checkup", scope, tpl, "checkup-ai"),
                        java.nio.charset.StandardCharsets.UTF_8));
        model.addAttribute("analysisHint", !hintService.dismissed(me.getFamilyId(), me.getMemberId()));
        String defaultKey = templateService.familyDefault(me.getFamilyId()).key();
        java.util.Map<String, String> urls = new java.util.LinkedHashMap<>();
        for (var t : templateService.list(me.getFamilyId())) {
            urls.put(t.key(), com.family.finance.web.analysis.AnalysisUrls.with("/checkup", scope,
                    t.key().equals(defaultKey) ? null : t.key(), "checkup-ai"));
        }
        model.addAttribute("footTemplateUrls", urls);
    }

    private List<AccountDiagnose> collectAccounts(long familyId) {
        List<AccountDiagnose> out = new ArrayList<>();
        for (var a : accountMapper.findActiveByFamily(familyId)) {
            try {
                out.add(accountDiagnoseService.diagnose(familyId, a.getId()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private BigDecimal computeAvgExpense(long familyId) {
        FactSlice slice = factViewService.loadDefault(familyId);
        if (slice.periodIds().isEmpty()) return BigDecimal.ZERO;
        BigDecimal total = slice.rows().stream()
                .map(AccountPeriodFact::expenseBase)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(Math.max(1, slice.periodIds().size())), 2, RoundingMode.HALF_EVEN);
    }
}
