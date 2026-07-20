package com.family.finance.web.review;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.family.Family;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.RebalanceAdviceCacheMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.PeriodService;
import com.family.finance.service.review.RebalancePlanService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * v1.2 · 再平衡计划(tech-design v1.2 §3)。
 * 铁律(FR-9):条目仅 账户+金额;采纳解析建议 JSON 的 from/to 账户名 → id 精确匹配,失配跳过并提示。
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class RebalancePlanController {

    private final RebalancePlanService planService;
    private final RebalanceAdviceCacheMapper adviceCacheMapper;
    private final AccountMapper accountMapper;
    private final FamilyService familyService;
    private final PeriodService periodService;
    private final ObjectMapper objectMapper;

    /** 采纳建议条目(indices = 建议 actions 下标) */
    @PostMapping("/rebalance-plan/adopt")
    public String adopt(@AuthenticationPrincipal MemberPrincipal me,
                        @RequestParam(value = "idx", required = false) List<Integer> indices,
                        RedirectAttributes ra) {
        if (indices == null || indices.isEmpty()) {
            ra.addFlashAttribute("flash", "没有勾选任何建议条目");
            return "redirect:/reports#ai-rebalance";
        }
        Family family = familyService.require(me.getFamilyId());
        var cache = adviceCacheMapper.findByFamilyAndAnchor(me.getFamilyId(), family.getAllocationAnchor());
        if (cache.isEmpty()) {
            ra.addFlashAttribute("flash", "建议已过期,请先重新生成 AI 调仓建议");
            return "redirect:/reports#ai-rebalance";
        }
        Map<String, Long> nameToId = accountMapper.findActiveByFamily(me.getFamilyId()).stream()
                .collect(Collectors.toMap(Account::getDisplayName, Account::getId, (a, b) -> a));
        List<RebalancePlanService.ItemReq> reqs = new ArrayList<>();
        int skipped = 0;
        try {
            JsonNode actions = objectMapper.readTree(cache.get().getContentJson()).path("actions");
            for (Integer i : indices) {
                if (i == null || i < 0 || i >= actions.size()) { skipped++; continue; }
                JsonNode a = actions.get(i);
                Long from = nameToId.get(a.path("from_account").asText());
                Long to = nameToId.get(a.path("to_account").asText());
                BigDecimal amount = new BigDecimal(a.path("amount").asText("0"));
                if (from == null || to == null || amount.signum() <= 0) { skipped++; continue; }
                reqs.add(new RebalancePlanService.ItemReq(from, to, amount, a.path("reason").asText(null)));
            }
        } catch (Exception e) {
            ra.addFlashAttribute("flash", "建议内容解析失败: " + e.getMessage());
            return "redirect:/reports#ai-rebalance";
        }
        long periodId = periodService.findCurrentOpen(me.getFamilyId())
                .map(p -> p.getId()).orElse(0L);
        planService.addItems(me.getFamilyId(), periodId, reqs);
        ra.addFlashAttribute("flash", "已采纳 " + reqs.size() + " 条为本期计划"
                + (skipped > 0 ? "(" + skipped + " 条账户名对不上已跳过,可手动添加)" : ""));
        return "redirect:/reports#ai-rebalance";
    }

    /** 手动加条目 */
    @PostMapping("/rebalance-plan/items")
    public String addItem(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam long fromAccountId, @RequestParam long toAccountId,
                          @RequestParam BigDecimal amount,
                          @RequestParam(required = false) String note,
                          RedirectAttributes ra) {
        long periodId = periodService.findCurrentOpen(me.getFamilyId()).map(p -> p.getId()).orElse(0L);
        planService.addItems(me.getFamilyId(), periodId,
                List.of(new RebalancePlanService.ItemReq(fromAccountId, toAccountId, amount, note)));
        ra.addFlashAttribute("flash", "已加入本期计划");
        return "redirect:/reports#ai-rebalance";
    }

    @PostMapping("/rebalance-plan/item/{id}/manual-done")
    public String manualDone(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id, RedirectAttributes ra) {
        planService.manualDone(me.getFamilyId(), id);
        ra.addFlashAttribute("flash", "已标记为外部完成(未经划转核销)");
        return "redirect:/reports#ai-rebalance";
    }

    @PostMapping("/rebalance-plan/item/{id}/amount")
    public String amount(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id,
                         @RequestParam BigDecimal amount, RedirectAttributes ra) {
        planService.updateAmount(me.getFamilyId(), id, amount);
        ra.addFlashAttribute("flash", "金额已更新");
        return "redirect:/reports#ai-rebalance";
    }

    @PostMapping("/rebalance-plan/item/{id}/delete")
    public String delete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id, RedirectAttributes ra) {
        planService.deleteItem(me.getFamilyId(), id);
        ra.addFlashAttribute("flash", "条目已删除");
        return "redirect:/reports#ai-rebalance";
    }
}
