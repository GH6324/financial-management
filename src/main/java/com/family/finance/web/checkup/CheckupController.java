package com.family.finance.web.checkup;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.ProductCategoryService;
import com.family.finance.service.checkup.AccountDiagnose;
import com.family.finance.service.checkup.AccountDiagnoseService;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.checkup.FamilyDiagnoseService;
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
 * v0.2 资产体检模块 · 入口 controller(FR-40)
 *
 * <ul>
 *   <li>{@code GET /checkup} → 全家维度(FR-40a)+ 6 条家庭级规则</li>
 *   <li>{@code GET /checkup?account=X} → 账户维度(FR-40b)+ 10 条账户级规则</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
public class CheckupController {

    private final NavService navService;
    private final AccountMapper accountMapper;
    private final ProductCategoryService categoryService;
    private final AccountDiagnoseService accountDiagnoseService;
    private final FamilyDiagnoseService familyDiagnoseService;
    private final AdviceEngine adviceEngine;
    private final FactViewService factViewService;
    private final com.family.finance.service.FamilyService familyService;
    private final com.family.finance.service.HouseholdCashflowService householdCashflowService;
    private final com.family.finance.service.FxService fxService;
    private final com.family.finance.repository.PeriodMapper periodMapper;
    private final com.family.finance.service.config.FamilyConfigService configService;
    private final com.family.finance.service.explain.MetricExplainService metricExplain; // v0.5.3 口径真实数值

    @GetMapping("/checkup")
    public String checkup(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(name = "account", required = false) Long accountId,
                          Model model) {
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("active", "checkup");

        // BUG-FIX(2026-05-11 · critical):checkup 也走 FactView 算总资产,同样需要非 base 币种当期 fx_rate 存在
        // v0.8 BUG-FIX(v08-CCY-INV-2):月均支出/趋势吃多期,非本位币账户在缺汇率的历史期不换算 → ensure ≤latest 全期
        var familyEntity = familyService.require(me.getFamilyId());
        periodMapper.findLatest(me.getFamilyId(), 1).stream().findFirst().ifPresent(latest -> {
            List<Long> ensurePeriodIds = periodMapper.findAllByFamily(me.getFamilyId()).stream()
                    .filter(p -> p.getPeriodStart() != null && !p.getPeriodStart().isAfter(latest.getPeriodStart()))
                    .map(com.family.finance.domain.period.Period::getId)
                    .toList();
            fxService.ensureForAccountCurrencies(me.getFamilyId(), familyEntity.getBaseCurrency(), ensurePeriodIds);
        });

        BigDecimal avgMonthlyExpense = computeAvgMonthlyExpense(me.getFamilyId());

        if (accountId == null) {
            FamilyDiagnose diagnose = familyDiagnoseService.diagnose(me.getFamilyId());
            // 收集所有账户的 diagnose,供家庭级规则使用
            List<AccountDiagnose> accounts = collectAllAccountDiagnoses(me.getFamilyId());
            RuleContext ctx = RuleContext.forFamily(diagnose, accounts, avgMonthlyExpense);
            List<Advice> advice = adviceEngine.evaluate(ctx);

            // v0.4 FR-62c · 应急金不闲置评估 · v0.4.18 应急月数 + buffer 倍率改读 ConfigService
            int emergencyMonths = configService.getInt(me.getFamilyId(),
                com.family.finance.service.config.FamilyConfigService.K_EMERGENCY_MONTHS,
                com.family.finance.calc.LiquiditySurplus.DEFAULT_EMERGENCY_MONTHS);
            BigDecimal liquidMultiplier = BigDecimal.valueOf(configService.getDouble(me.getFamilyId(),
                com.family.finance.service.config.FamilyConfigService.K_LIQUID_BUFFER, 1.5));
            var liquidSurplus = com.family.finance.calc.LiquiditySurplus.evaluate(
                diagnose.liquidAssets(), avgMonthlyExpense, emergencyMonths, liquidMultiplier);

            model.addAttribute("scope", "FAMILY");
            model.addAttribute("diagnose", diagnose);
            model.addAttribute("advice", advice);
            model.addAttribute("liquidSurplus", liquidSurplus);
            // v0.5.3 · 计算指标真实数值(ⓘ tooltip)· checkup 走家庭本位币
            // 紧急储备分母在 service 内部取 diagnose.kpi().avgExpense()(与 emergencyMonths 同源 · 见决策 51)
            model.addAttribute("calc", metricExplain.checkup(diagnose, familyEntity.getBaseCurrency()));
            return "checkup/family";
        }

        Optional<Account> account = accountMapper.findById(accountId)
                .filter(a -> a.getFamilyId().equals(me.getFamilyId()));
        if (account.isEmpty()) {
            return "redirect:/checkup";
        }

        AccountDiagnose diagnose = accountDiagnoseService.diagnose(me.getFamilyId(), accountId);
        FamilyDiagnose family = familyDiagnoseService.diagnose(me.getFamilyId());
        RuleContext ctx = RuleContext.forAccount(diagnose, family, List.of(diagnose), avgMonthlyExpense);
        List<Advice> advice = adviceEngine.evaluate(ctx);

        model.addAttribute("scope", "ACCOUNT");
        model.addAttribute("account", account.get());
        model.addAttribute("category",
                categoryService.findByCode(account.get().getProductCategoryCode()).orElse(null));
        model.addAttribute("diagnose", diagnose);
        model.addAttribute("advice", advice);
        return "checkup/account";
    }

    private List<AccountDiagnose> collectAllAccountDiagnoses(long familyId) {
        List<Account> accounts = accountMapper.findActiveByFamily(familyId);
        List<AccountDiagnose> out = new ArrayList<>();
        for (Account a : accounts) {
            try {
                out.add(accountDiagnoseService.diagnose(familyId, a.getId()));
            } catch (Exception ignored) {
                // 单账户诊断失败不阻塞家庭级
            }
        }
        return out;
    }

    /**
     * 家庭近 12 月月均 EXPENSE(本位币),用于流动性规则。
     * v0.3:优先 period.total_expense_input(用户在 /entry 填的家庭口径),fallback v0.2 cash_flow。
     */
    private BigDecimal computeAvgMonthlyExpense(long familyId) {
        return householdCashflowService.avgMonthlyExpense(familyId);
    }
}
