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
    private final com.family.finance.service.expense.NormalExpenseService normalExpenseService; // v1.24 FR-651
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
    /** issue #22 · 「不适用」的提醒 —— 原来那个按钮调用的是一个不存在的前端函数,点了没反应 */
    private final com.family.finance.service.checkup.AdviceDismissService adviceDismiss;
    /** v1.27 · 分析范围 / 模板 / 首次提示 */
    private final com.family.finance.service.analysis.AnalysisScopeService scopeService;
    private final com.family.finance.service.analysis.AnalysisTemplateService templateService;
    private final com.family.finance.service.analysis.AnalysisHintService hintService;

    @GetMapping("/checkup")
    public String checkup(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(name = "account", required = false) Long accountId,
                          @RequestParam(name = "scope", required = false) String scopeParam,
                          @RequestParam(name = "tpl", required = false) String tplParam,
                          Model model) {
        // v0.16.x 兜底:全新部署(零周期)→ 回引导页并提示先开周期(体检依赖账期快照数据,零周期无可诊断)。
        if (periodMapper.countByFamily(me.getFamilyId()) == 0) {
            return "redirect:/?needs=period";
        }
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
            // v1.27 · 配置 / 风险两张卡与配置类规则按分析范围(?scope= 临时切 · 否则家庭默认)
            FamilyDiagnose diagnose = familyDiagnoseService.diagnose(me.getFamilyId(), scopeParam);
            // 收集所有账户的 diagnose,供家庭级规则使用
            List<AccountDiagnose> accounts = collectAllAccountDiagnoses(me.getFamilyId());
            RuleContext ctx = RuleContext.forFamily(diagnose, accounts, avgMonthlyExpense);
            List<Advice> allAdvice = adviceEngine.evaluate(ctx);
            List<Advice> advice = adviceDismiss.visible(me.getFamilyId(), allAdvice);
            model.addAttribute("adviceHidden", adviceDismiss.hiddenCount(me.getFamilyId(), allAdvice));

            // v0.4 FR-62c · 应急金不闲置评估 · v0.4.18 应急月数 + buffer 倍率改读 ConfigService
            int emergencyMonths = configService.getInt(me.getFamilyId(),
                com.family.finance.service.config.FamilyConfigService.K_EMERGENCY_MONTHS,
                com.family.finance.calc.LiquiditySurplus.DEFAULT_EMERGENCY_MONTHS);
            BigDecimal liquidMultiplier = BigDecimal.valueOf(configService.getDouble(me.getFamilyId(),
                com.family.finance.service.config.FamilyConfigService.K_LIQUID_BUFFER, 1.5));
            var liquidSurplus = com.family.finance.calc.LiquiditySurplus.evaluate(
                diagnose.liquidAssets(), avgMonthlyExpense, emergencyMonths, liquidMultiplier);

            /* v1.24 FR-651 · 流动性维度并列给出「按常态月均」的第二个读数,
             * 并说清差额由几笔一次性支出造成。
             * 【主判定不变】—— liquidSurplus 仍按月均支出算,建议引擎读的还是它。
             * 换判定分母会让所有现存用户的「应急金够不够」结论一夜之间改变。
             * 开关关着 / 没有逐笔数据 → empty → 这一块整个不出现(级联在服务层,不在这里判)。 */
            normalExpenseService.normal(me.getFamilyId()).ifPresent(n -> {
                model.addAttribute("normalExpense", n);
                /* 覆盖月数 = 流动资产 ÷ 常态月均。
                 * 【不要用 LiquiditySurplus.Result.months】—— 那个字段是配置里的
                 * 「应急金该备几个月」阈值,不是「现在够几个月」。两者都是「月数」、
                 * 都是个合理的小整数,拿错了页面上看不出来。 */
                model.addAttribute("normalCoverMonths",
                    n.normalBase().signum() <= 0 ? null
                        : diagnose.liquidAssets().divide(n.normalBase(), 1,
                            java.math.RoundingMode.HALF_UP));
            });

            model.addAttribute("scope", "FAMILY");
            model.addAttribute("diagnose", diagnose);
            addAnalysisModel(me, model, diagnose, scopeParam, tplParam);
            model.addAttribute("advice", advice);
            model.addAttribute("liquidSurplus", liquidSurplus);
            // v1.6 UED review A2 · 体检此前完全不显示数据账期,与仪表盘数值不一致时用户无从分辨。
            // 回显 anchor 期(与 dashboard 同规则解析)到页面标题下。
            model.addAttribute("anchorPeriod", familyDiagnoseService.resolveAnchor(me.getFamilyId()));
            // v0.5.3 · 计算指标真实数值(ⓘ tooltip)· checkup 走家庭本位币
            // 紧急储备分母在 service 内部取 diagnose.kpi().avgExpense()(与 emergencyMonths 同源 · 见决策 51)
            model.addAttribute("calc", metricExplain.checkup(diagnose, familyEntity.getBaseCurrency()));
            return "checkup/family";
        }

        Optional<Account> account = accountMapper.findById(me.getFamilyId(), accountId)
                .filter(a -> a.getFamilyId().equals(me.getFamilyId()));
        if (account.isEmpty()) {
            return "redirect:/checkup";
        }

        AccountDiagnose diagnose = accountDiagnoseService.diagnose(me.getFamilyId(), accountId);
        FamilyDiagnose family = familyDiagnoseService.diagnose(me.getFamilyId());
        RuleContext ctx = RuleContext.forAccount(diagnose, family, List.of(diagnose), avgMonthlyExpense);
        List<Advice> allAdvice = adviceEngine.evaluate(ctx);
        List<Advice> advice = adviceDismiss.visible(me.getFamilyId(), allAdvice);
        model.addAttribute("adviceHidden", adviceDismiss.hiddenCount(me.getFamilyId(), allAdvice));

        model.addAttribute("scope", "ACCOUNT");
        model.addAttribute("account", account.get());
        // v1.27 FR-805 · 被标「不参与配置分析」的账户:页头一行说明 + 改
        model.addAttribute("accountExcluded", account.get().isAnalysisExcluded());
        model.addAttribute("category",
                categoryService.findByCode(account.get().getProductCategoryCode()).orElse(null));
        model.addAttribute("diagnose", diagnose);
        model.addAttribute("advice", advice);
        return "checkup/account";
    }

    /**
     * v1.27 · 体检页的范围切换与 AI 模板行(PRD FR-820 ~ FR-823 · FR-880 / FR-881)。
     *
     * <p>{@code scopeParam} 只在它真的生效时回显到链接上(不可选的脏值不带),
     * 这样「设为家里的默认」按钮只在临时切到别的范围时出现。</p>
     */
    private void addAnalysisModel(MemberPrincipal me, Model model, FamilyDiagnose diagnose,
                                  String scopeParam, String tplParam) {
        long fid = me.getFamilyId();
        var current = diagnose.scope();
        var familyDefault = scopeService.familyDefault(fid);
        String effectiveScopeParam = current.kind() != familyDefault ? current.kind().name() : null;
        var template = templateService.resolve(fid, tplParam);
        var defaultTemplate = templateService.familyDefault(fid);
        String tplForUrl = template.key().equals(defaultTemplate.key()) ? null : template.key();

        model.addAttribute("scopeOptions", diagnose.scopeOptions());
        model.addAttribute("scopeCurrent", current);
        model.addAttribute("scopeFamilyDefault", familyDefault);
        model.addAttribute("scopeParam", effectiveScopeParam);
        model.addAttribute("analysisTemplates", templateService.list(fid));
        model.addAttribute("analysisTemplate", template);
        model.addAttribute("analysisTemplateDefaultKey", defaultTemplate.key());
        model.addAttribute("tplParam", tplForUrl);
        model.addAttribute("analysisHint", !hintService.dismissed(fid, me.getMemberId()));
        model.addAttribute("aiDiagnoseUrl",
                com.family.finance.web.analysis.AnalysisUrls.with("/checkup/diagnose", effectiveScopeParam, tplForUrl, null));
        model.addAttribute("aiInsightUrl",
                com.family.finance.web.analysis.AnalysisUrls.with("/checkup/insight", effectiveScopeParam, tplForUrl, null));
        String back = com.family.finance.web.analysis.AnalysisUrls.with("/checkup", effectiveScopeParam, tplForUrl, "checkup-ai");
        model.addAttribute("checkupBackUrl", back);
        model.addAttribute("customizeUrl", com.family.finance.web.analysis.AnalysisUrls.customize(template.key(), back));
        // 链接在这里拼好(空参数不带)—— 模板里拼会把 null 参数渲染成「?tpl」这种半截
        java.util.Map<String, String> scopeUrls = new java.util.LinkedHashMap<>();
        for (var o : diagnose.scopeOptions()) {
            scopeUrls.put(o.kind().name(), com.family.finance.web.analysis.AnalysisUrls.with("/checkup",
                    o.kind() == familyDefault ? null : o.kind().name(), tplForUrl, null));
        }
        model.addAttribute("scopeOptionUrls", scopeUrls);
        java.util.Map<String, String> tplUrls = new java.util.LinkedHashMap<>();
        for (var t : templateService.list(fid)) {
            tplUrls.put(t.key(), com.family.finance.web.analysis.AnalysisUrls.with("/checkup", effectiveScopeParam,
                    t.key().equals(defaultTemplate.key()) ? null : t.key(), "checkup-ai"));
        }
        model.addAttribute("templateUrls", tplUrls);
        model.addAttribute("reportsAllocationUrl",
                com.family.finance.web.analysis.AnalysisUrls.with("/reports", effectiveScopeParam, null, "allocation-diff"));
    }

    /** v1.27 FR-822 · 体检页「设为家里的默认」 */
    @org.springframework.web.bind.annotation.PostMapping("/checkup/scope/default")
    public String setDefaultScope(@AuthenticationPrincipal MemberPrincipal me,
                                  @RequestParam("scope") String scope,
                                  org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        var kind = com.family.finance.service.analysis.ScopeKind.parse(scope);
        if (kind != null) {
            scopeService.setFamilyDefault(me.getFamilyId(), kind);
            ra.addFlashAttribute("scopeFlash", "家里的默认分析范围改成了「" + kind.getLabel() + "」");
        }
        return "redirect:/checkup";
    }

    /** v1.27 FR-881 · 关掉「分析角度不合适?」首次提示(按人记) */
    @org.springframework.web.bind.annotation.PostMapping("/checkup/hint/dismiss")
    @org.springframework.web.bind.annotation.ResponseBody
    public String dismissHint(@AuthenticationPrincipal MemberPrincipal me) {
        hintService.dismiss(me.getFamilyId(), me.getMemberId());
        return "";
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
     * 家庭近 12 月月均支出(本位币),用于流动性规则。
     * v1.8:委托 householdCashflowService.avgMonthlyExpense,后者已改走 ExpenseLedgerService
     * 统一口径(逐笔 > 总额,不相加,排除现金调整)。本处无需再判断口径。
     */
    private BigDecimal computeAvgMonthlyExpense(long familyId) {
        return householdCashflowService.avgMonthlyExpense(familyId);
    }

    // ──────────────── issue #22 · 「不适用」与「恢复」 ────────────────

    /**
     * 把一条提醒标成「不适用」。
     *
     * <p>用普通表单提交,不靠前端脚本 —— 原来那个按钮正是因为依赖一个从没写过的 JS 函数才一直不工作的。
     * 回到哪一页由账户参数决定(不接受任意跳转地址):带账户回账户体检页,不带回家庭体检页。</p>
     */
    @org.springframework.web.bind.annotation.PostMapping("/checkup/advice/dismiss")
    public String dismissAdvice(@AuthenticationPrincipal MemberPrincipal me,
                                @org.springframework.web.bind.annotation.RequestParam String ruleId,
                                @org.springframework.web.bind.annotation.RequestParam(required = false) Long accountId) {
        // 传了账户但不是自己家的 → 什么都不做。
        //   不能把它当成「没传」存成一条家庭级记录 —— 那会把一个针对账户的操作悄悄变成针对全家的。
        if (accountId != null && ownAccountOrNull(me.getFamilyId(), accountId) == null) return backTo(null);
        adviceDismiss.dismiss(me.getFamilyId(), ruleId, accountId);
        return backTo(accountId);
    }

    /** 把这一页上被标成「不适用」的提醒全部恢复(家庭页恢复家庭级的,账户页恢复这个账户的) */
    @org.springframework.web.bind.annotation.PostMapping("/checkup/advice/restore")
    public String restoreAdvice(@AuthenticationPrincipal MemberPrincipal me,
                                @org.springframework.web.bind.annotation.RequestParam(required = false) Long accountId) {
        if (accountId != null && ownAccountOrNull(me.getFamilyId(), accountId) == null) return backTo(null);
        adviceDismiss.restoreAll(me.getFamilyId(), accountId);
        return backTo(accountId);
    }

    /** 账户必须是自己家的;不是返回 null(调用方据此直接忽略这次请求) */
    private Long ownAccountOrNull(long familyId, Long accountId) {
        if (accountId == null) return null;
        return accountMapper.findById(familyId, accountId).map(a -> accountId).orElse(null);
    }

    private static String backTo(Long accountId) {
        return accountId == null ? "redirect:/checkup#checkup-advice"
                                 : "redirect:/checkup?account=" + accountId + "#checkup-advice";
    }
}
