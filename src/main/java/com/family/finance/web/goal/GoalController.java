package com.family.finance.web.goal;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.goal.Goal;
import com.family.finance.domain.goal.GoalParams;
import com.family.finance.domain.goal.GoalType;
import com.family.finance.domain.member.Member;
import com.family.finance.repository.GoalAiReportMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.goal.GoalLlmService;
import com.family.finance.service.goal.GoalProgressService;
import com.family.finance.service.goal.GoalProgressService.GoalProgress;
import com.family.finance.service.goal.GoalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 财务目标 Web 控制器 · v0.3 FR-50。
 *
 * <p>路由清单:</p>
 * <ul>
 *   <li>GET  /goals               · 列表 + 三情景缩略</li>
 *   <li>GET  /goals/new           · 类型选择页</li>
 *   <li>GET  /goals/new/{type}    · 类型特定向导</li>
 *   <li>POST /goals/new/{type}    · 创建</li>
 *   <li>GET  /goals/{id}          · 详情 + 完整三情景图 + AI 月报</li>
 *   <li>GET  /goals/{id}/edit     · 编辑表单</li>
 *   <li>POST /goals/{id}/edit     · 更新</li>
 *   <li>POST /goals/{id}/archive  · 软删</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;
    private final GoalProgressService progressService;
    private final GoalLlmService llmService;
    private final GoalAiReportMapper aiReportMapper;
    private final MemberMapper memberMapper;
    private final NavService navService;

    // ---------- 列表 ----------

    @GetMapping("/goals")
    public String list(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        List<GoalProgress> goals = progressService.computeAll(me.getFamilyId());
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("goals", goals);
        return "goals/index";
    }

    // ---------- 新建 ----------

    @GetMapping("/goals/new")
    public String newGoalChooser(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("nav", navService.load(me));
        return "goals/new";
    }

    @GetMapping("/goals/new/{type}")
    public String newGoalForm(@AuthenticationPrincipal MemberPrincipal me,
                              @PathVariable String type,
                              Model model) {
        GoalType goalType = parseType(type);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("goalType", goalType);
        model.addAttribute("currentYear", java.time.Year.now().getValue());
        if (goalType == GoalType.EDUCATION) {
            // 准备孩子下拉 · displayName 仅 UI 用
            List<Member> children = memberMapper.findActiveByFamily(me.getFamilyId());
            model.addAttribute("childCandidates", children);
        }
        if (goalType == GoalType.EMERGENCY) {
            BigDecimal baseline = progressService.computeEmergencyAutoBaseline(me.getFamilyId());
            model.addAttribute("autoBaseline", baseline);
        }
        return "goals/new-" + goalType.name().toLowerCase(Locale.ROOT);
    }

    @PostMapping("/goals/new/{type}")
    public String createGoal(@AuthenticationPrincipal MemberPrincipal me,
                             @PathVariable String type,
                             @RequestParam(required = false) String name,
                             @RequestParam(required = false) Integer currentAge,
                             @RequestParam(required = false) Integer retireAge,
                             @RequestParam(required = false) BigDecimal monthlyExpense,
                             @RequestParam(required = false) BigDecimal inflationRate,
                             @RequestParam(required = false) BigDecimal withdrawalRate,
                             @RequestParam(required = false) Long childMemberId,
                             @RequestParam(required = false) Integer childBirthYear,
                             @RequestParam(required = false) Integer targetYearOffset,
                             @RequestParam(required = false) BigDecimal targetAmount,
                             @RequestParam(required = false) Integer monthsTarget,
                             @RequestParam(required = false) Boolean autoBaseline,
                             @RequestParam(required = false) BigDecimal fixedBaseline,
                             @RequestParam(required = false) String expenseMode,
                             @RequestParam(required = false) Integer expenseWindowMonths,
                             @RequestParam(required = false) String expenseSmoothing) {
        GoalType goalType = parseType(type);
        GoalParams params = GoalParams.builder()
            .currentAge(currentAge)
            .retireAge(retireAge)
            .monthlyExpense(monthlyExpense)
            .inflationRate(inflationRate)
            .withdrawalRate(withdrawalRate)
            .childMemberId(childMemberId)
            .childBirthYear(childBirthYear)
            .targetYearOffset(targetYearOffset)
            .targetAmount(targetAmount)
            .monthsTarget(monthsTarget)
            .autoBaseline(autoBaseline)
            .fixedBaseline(fixedBaseline)
            .expenseMode(expenseMode)
            .expenseWindowMonths(expenseWindowMonths)
            .expenseSmoothing(expenseSmoothing)
            .build();
        Goal created = goalService.create(me.getFamilyId(), goalType, name, params);
        // v0.5 FR-82 · AUTO 模式立即派生一次(不必等周期关闭 · 用户建完即见)
        if ("AUTO_MONTHLY".equalsIgnoreCase(expenseMode)) {
            goalService.recomputeAutoExpenseGoals(me.getFamilyId());
        }
        return "redirect:/goals/" + created.getId();
    }

    // ---------- 详情 ----------

    @GetMapping("/goals/{id}")
    public String detail(@AuthenticationPrincipal MemberPrincipal me,
                         @PathVariable long id,
                         Model model) {
        Goal goal = goalService.require(me.getFamilyId(), id);
        GoalProgress progress = progressService.compute(me.getFamilyId(), goal);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("goal", goal);
        model.addAttribute("progress", progress);
        // 孩子 displayName(EDUCATION 用)
        if (goal.getGoalType() == GoalType.EDUCATION && progress.params().getChildMemberId() != null) {
            memberMapper.findById(progress.params().getChildMemberId())
                .ifPresent(m -> model.addAttribute("childMember", m));
        }
        // v0.3 FR-53b/c · AI 月报 + 偏离预警(null safe)
        aiReportMapper.findLatestByGoalAndType(id, "MONTHLY")
            .ifPresent(r -> model.addAttribute("aiMonthlyReport", r));
        aiReportMapper.findLatestByGoalAndType(id, "ALERT")
            .filter(r -> r.getDismissedAt() == null)
            .ifPresent(r -> model.addAttribute("aiAlert", r));
        return "goals/detail";
    }

    /**
     * FR-53c · 用户 dismiss 偏离预警 · 30 天内不再弹同类(简化:永久 dismiss · 下次新预警重写覆盖)。
     */
    @PostMapping("/goals/{id}/alert/dismiss")
    public String dismissAlert(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long id) {
        goalService.require(me.getFamilyId(), id);
        aiReportMapper.findLatestByGoalAndType(id, "ALERT")
            .ifPresent(r -> aiReportMapper.dismiss(r.getId()));
        return "redirect:/goals/" + id;
    }

    // ---------- 编辑 ----------

    @GetMapping("/goals/{id}/edit")
    public String editForm(@AuthenticationPrincipal MemberPrincipal me,
                           @PathVariable long id,
                           Model model) {
        Goal goal = goalService.require(me.getFamilyId(), id);
        GoalParams params = goalService.parseParams(goal);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("goal", goal);
        model.addAttribute("params", params);
        model.addAttribute("goalType", goal.getGoalType());
        model.addAttribute("currentYear", java.time.Year.now().getValue());
        if (goal.getGoalType() == GoalType.EDUCATION) {
            model.addAttribute("childCandidates", memberMapper.findActiveByFamily(me.getFamilyId()));
        }
        return "goals/edit";
    }

    @PostMapping("/goals/{id}/edit")
    public String updateGoal(@AuthenticationPrincipal MemberPrincipal me,
                             @PathVariable long id,
                             @RequestParam(required = false) String name,
                             @RequestParam(required = false) Integer currentAge,
                             @RequestParam(required = false) Integer retireAge,
                             @RequestParam(required = false) BigDecimal monthlyExpense,
                             @RequestParam(required = false) BigDecimal inflationRate,
                             @RequestParam(required = false) BigDecimal withdrawalRate,
                             @RequestParam(required = false) Long childMemberId,
                             @RequestParam(required = false) Integer childBirthYear,
                             @RequestParam(required = false) Integer targetYearOffset,
                             @RequestParam(required = false) BigDecimal targetAmount,
                             @RequestParam(required = false) Integer monthsTarget,
                             @RequestParam(required = false) Boolean autoBaseline,
                             @RequestParam(required = false) BigDecimal fixedBaseline,
                             @RequestParam(required = false) String expenseMode,
                             @RequestParam(required = false) Integer expenseWindowMonths,
                             @RequestParam(required = false) String expenseSmoothing) {
        GoalParams params = GoalParams.builder()
            .currentAge(currentAge)
            .retireAge(retireAge)
            .monthlyExpense(monthlyExpense)
            .inflationRate(inflationRate)
            .withdrawalRate(withdrawalRate)
            .childMemberId(childMemberId)
            .childBirthYear(childBirthYear)
            .targetYearOffset(targetYearOffset)
            .targetAmount(targetAmount)
            .monthsTarget(monthsTarget)
            .autoBaseline(autoBaseline)
            .fixedBaseline(fixedBaseline)
            .expenseMode(expenseMode)
            .expenseWindowMonths(expenseWindowMonths)
            .expenseSmoothing(expenseSmoothing)
            .build();
        goalService.update(me.getFamilyId(), id, name, params);
        if ("AUTO_MONTHLY".equalsIgnoreCase(expenseMode)) {
            goalService.recomputeAutoExpenseGoals(me.getFamilyId());
        }
        return "redirect:/goals/" + id;
    }

    // ---------- AI 推荐(FR-53a)----------

    /**
     * AI 推荐目标参数 · 用户在 /goals/new/{type} 表单点 [🤖 AI 推荐] 时调用。
     * 返回 JSON · 前端 JS 把字段填入对应 input。
     */
    @PostMapping("/goals/advise/{type}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> adviseParams(@AuthenticationPrincipal MemberPrincipal me,
                                                            @PathVariable String type) {
        GoalType goalType = parseType(type);
        GoalLlmService.AiResult<com.family.finance.domain.goal.GoalParams> r =
            llmService.recommendParams(me.getFamilyId(), goalType);
        if (!r.ok()) {
            return ResponseEntity.ok(Map.of("ok", false, "error", r.error() == null ? "AI 暂不可用" : r.error()));
        }
        com.family.finance.domain.goal.GoalParams p = r.value();
        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("ok", true);
        resp.put("rationale", r.rationale());
        // 序列化字段(返回所有非空,前端 JS 选填)
        if (p.getRetireAge() != null) resp.put("retireAge", p.getRetireAge());
        if (p.getCurrentAge() != null) resp.put("currentAge", p.getCurrentAge());
        if (p.getMonthlyExpense() != null) resp.put("monthlyExpense", p.getMonthlyExpense());
        if (p.getInflationRate() != null) resp.put("inflationRate", p.getInflationRate());
        if (p.getWithdrawalRate() != null) resp.put("withdrawalRate", p.getWithdrawalRate());
        if (p.getChildBirthYear() != null) resp.put("childBirthYear", p.getChildBirthYear());
        if (p.getTargetYearOffset() != null) resp.put("targetYearOffset", p.getTargetYearOffset());
        if (p.getTargetAmount() != null) resp.put("targetAmount", p.getTargetAmount());
        if (p.getMonthsTarget() != null) resp.put("monthsTarget", p.getMonthsTarget());
        if (p.getAutoBaseline() != null) resp.put("autoBaseline", p.getAutoBaseline());
        return ResponseEntity.ok(resp);
    }

    // ---------- 软删 ----------

    @PostMapping("/goals/{id}/archive")
    public String archive(@AuthenticationPrincipal MemberPrincipal me,
                          @PathVariable long id) {
        goalService.archive(me.getFamilyId(), id);
        return "redirect:/goals";
    }

    // ---------- helpers ----------

    private static GoalType parseType(String raw) {
        try {
            return GoalType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法目标类型: " + raw);
        }
    }
}
