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
    private final AccountMapper accountMapper;
    private final FactViewService factViewService;

    @GetMapping("/checkup/diagnose")
    public String diagnose(@AuthenticationPrincipal MemberPrincipal me,
                           @RequestParam(name = "account", required = false) Long accountId,
                           @RequestParam(name = "refresh", required = false, defaultValue = "false") boolean refresh,
                           Model model) {
        BigDecimal avgExp = computeAvgExpense(me.getFamilyId());

        LlmDiagnoseService.DiagnoseResult result;
        if (accountId == null) {
            // 全家维度
            FamilyDiagnose diagnose = familyDiagnoseService.diagnose(me.getFamilyId());
            List<AccountDiagnose> accounts = collectAccounts(me.getFamilyId());
            RuleContext ctx = RuleContext.forFamily(diagnose, accounts, avgExp);
            List<Advice> advice = adviceEngine.evaluate(ctx);

            result = llmDiagnoseService.diagnoseFamily(me.getFamilyId(), me.getMemberId(),
                    diagnose, advice, refresh);
        } else {
            // 账户维度
            Optional<Account> account = accountMapper.findById(accountId)
                    .filter(a -> a.getFamilyId().equals(me.getFamilyId()));
            if (account.isEmpty()) {
                model.addAttribute("result", LlmDiagnoseService.DiagnoseResult.unavailable("账户不存在"));
                return "checkup/_ai-diagnose :: panel";
            }
            AccountDiagnose ad = accountDiagnoseService.diagnose(me.getFamilyId(), accountId);
            FamilyDiagnose fd = familyDiagnoseService.diagnose(me.getFamilyId());
            RuleContext ctx = RuleContext.forAccount(ad, fd, List.of(ad), avgExp);
            List<Advice> advice = adviceEngine.evaluate(ctx);

            result = llmDiagnoseService.diagnoseAccount(me.getFamilyId(), me.getMemberId(),
                    fd, ad, advice, refresh);
        }

        model.addAttribute("result", result);
        model.addAttribute("scope", accountId == null ? "FAMILY" : "ACCOUNT");
        model.addAttribute("accountId", accountId);
        return "checkup/_ai-diagnose :: panel";
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
