package com.family.finance.web.report;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.calc.LiquiditySurplus;
import com.family.finance.calc.RefinanceNpvCalculator;
import com.family.finance.calc.RefinanceNpvCalculator.Input;
import com.family.finance.calc.RefinanceNpvCalculator.Result;
import com.family.finance.domain.account.Account;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.NavService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * v0.4 FR-62d · 提前还贷 vs 投资 决策器。
 *
 * <p>用户输入剩余房贷情况 + 投资预期 · 算 NPV 对照 · 推荐还贷 / 投资 / 必还 / 拒绝。</p>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class RefinanceController {

    private final AccountMapper accountMapper;
    private final FactViewService factViewService;
    private final NavService navService;

    @GetMapping("/reports/refinance")
    public String form(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        // 默认从 LOAN 账户拉余额(取最大一个 · 通常是主房贷)
        List<Account> loans = accountMapper.findActiveByFamily(me.getFamilyId()).stream()
            .filter(a -> a.getType() != null && "LOAN".equals(a.getType().name()))
            .toList();
        BigDecimal defaultAmount = new BigDecimal("100000");
        BigDecimal defaultLoanRate = new BigDecimal("0.045");
        int defaultYears = 18;
        BigDecimal defaultInvestRate = factViewService.familyXirr(factViewService.loadDefault(me.getFamilyId()));
        if (defaultInvestRate == null || defaultInvestRate.signum() <= 0) {
            defaultInvestRate = new BigDecimal("0.07");
        }

        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("loans", loans);
        model.addAttribute("defaultAmount", defaultAmount);
        model.addAttribute("defaultLoanRate", defaultLoanRate);
        model.addAttribute("defaultYears", defaultYears);
        model.addAttribute("defaultInvestRate", defaultInvestRate);
        return "reports/refinance";
    }

    @PostMapping("/reports/refinance")
    public String calculate(@AuthenticationPrincipal MemberPrincipal me,
                            @RequestParam("amount") BigDecimal amount,
                            @RequestParam("loanRate") BigDecimal loanRate,
                            @RequestParam("investRate") BigDecimal investRate,
                            @RequestParam("years") int years,
                            Model model) {
        // 算应急储备月数(给 NPV 决策器用)
        KpiSnapshot kpis = factViewService.kpis(factViewService.loadDefault(me.getFamilyId()));
        BigDecimal emergencyMonths = kpis.emergencyFundMonths();

        Result r;
        String validationError = null;
        try {
            Input in = new Input(amount, loanRate, investRate, years, emergencyMonths);
            r = RefinanceNpvCalculator.compute(in);
        } catch (IllegalArgumentException e) {
            validationError = e.getMessage();
            r = null;
        }

        // 也回填表单值(form 不刷)
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("loans", accountMapper.findActiveByFamily(me.getFamilyId()).stream()
            .filter(a -> a.getType() != null && "LOAN".equals(a.getType().name())).toList());
        model.addAttribute("defaultAmount", amount);
        model.addAttribute("defaultLoanRate", loanRate);
        model.addAttribute("defaultYears", years);
        model.addAttribute("defaultInvestRate", investRate);

        model.addAttribute("result", r);
        model.addAttribute("validationError", validationError);
        model.addAttribute("emergencyMonths", emergencyMonths);
        return "reports/refinance";
    }
}
