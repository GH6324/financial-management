package com.family.finance.web.entry;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.flow.CashFlowKind;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.service.EntryService;
import com.family.finance.service.EntryRow;
import com.family.finance.service.NavService;
import com.family.finance.service.PeriodService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class EntryController {

    private final EntryService entryService;
    private final PeriodMapper periodMapper;
    private final PeriodService periodService;
    private final AccountMapper accountMapper;
    private final NavService navService;
    private final MemberMapper memberMapper;
    private final PeriodMemberCashflowMapper memberCashflowMapper;

    @GetMapping("/entry")
    public String entry(@AuthenticationPrincipal MemberPrincipal me,
                        @RequestParam(value = "period", required = false) String periodParam,
                        @RequestParam(value = "mine", defaultValue = "false") boolean mineOnly,
                        @RequestParam(value = "account", required = false) Long accountFilter,
                        Model model) {
        Period period = entryService.findSelectedPeriod(me.getFamilyId(), periodParam)
                .orElseThrow(() -> new IllegalStateException("找不到周期: " + periodParam));
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        List<EntryRow> rows = entryService.listRows(me.getFamilyId(), me.getMemberId(), period, mineOnly);
        if (accountFilter != null) {
            rows = rows.stream().filter(r -> r.account().getId().equals(accountFilter)).toList();
        }
        // 把 ledger 拼成 HTML 字符串塞 model(规避 Thymeleaf each + record nested List 的 accessor bug)
        java.util.Map<String, String> ledgerHtmlByAccount = new java.util.LinkedHashMap<>();
        for (EntryRow r : rows) {
            ledgerHtmlByAccount.put(String.valueOf(r.account().getId()), renderLedgerHtml(r));
        }
        model.addAttribute("ledgerHtmlByAccount", ledgerHtmlByAccount);
        model.addAttribute("period", period);
        model.addAttribute("periods", periodMapper.findLatest(me.getFamilyId(), 12));
        model.addAttribute("accounts", accountMapper.findActiveByFamily(me.getFamilyId()));
        model.addAttribute("rows", rows);
        model.addAttribute("doneCount", rows.stream().filter(EntryRow::done).count());
        model.addAttribute("mineOnly", mineOnly);
        model.addAttribute("accountFilter", accountFilter);

        // v0.3 FR-51 · 成员级月度收支(2026-05-13 修订)
        // 当前用户自己的本期填报
        var myCashflow = memberCashflowMapper.findByPeriodAndMember(period.getId(), me.getMemberId()).orElse(null);
        model.addAttribute("myCashflow", myCashflow);
        // 上期参考(同成员自己的)
        Period previousPeriod = periodMapper.findLatest(me.getFamilyId(), 12).stream()
                .filter(p -> !p.getId().equals(period.getId()))
                .findFirst().orElse(null);
        if (previousPeriod != null) {
            memberCashflowMapper.findByPeriodAndMember(previousPeriod.getId(), me.getMemberId())
                .ifPresent(prev -> model.addAttribute("myPrevCashflow", prev));
        }
        // 家庭本期汇总(SUM 跨成员)
        memberCashflowMapper.findFamilyAggregateForPeriod(period.getId())
            .ifPresent(agg -> model.addAttribute("familyCurrentAgg", agg));
        // 本期已填的成员名单(给"家庭已填:N 人")
        var filledRows = memberCashflowMapper.findByPeriod(period.getId());
        var filledMembers = new java.util.HashMap<Long, String>();
        for (var fr : filledRows) {
            if (fr.getTotalIncomeInput() != null || fr.getTotalExpenseInput() != null) {
                memberMapper.findById(fr.getMemberId()).ifPresent(m -> filledMembers.put(m.getId(), m.getDisplayName()));
            }
        }
        model.addAttribute("filledMembers", filledMembers);
        // 全家成员数(给"N/M 人")
        model.addAttribute("totalMembers", memberMapper.findActiveByFamily(me.getFamilyId()).size());

        return "entry/index";
    }

    /**
     * v0.3 FR-51 · 成员级月度收支提交(2026-05-13 修订)。
     * 每个成员只能填自己的(memberId 强制 = 当前登录用户)。
     */
    @PostMapping("/entry/cashflow-summary")
    public String submitCashflowSummary(@AuthenticationPrincipal MemberPrincipal me,
                                        @RequestParam("periodId") long periodId,
                                        @RequestParam(value = "totalIncomeInput", required = false) BigDecimal totalIncomeInput,
                                        @RequestParam(value = "totalExpenseInput", required = false) BigDecimal totalExpenseInput,
                                        HttpServletResponse response) {
        Period period = periodMapper.findById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("周期不存在: " + periodId));
        if (!period.getFamilyId().equals(me.getFamilyId())) {
            throw new IllegalArgumentException("无权操作此周期");
        }
        if (period.getStatus() != null && period.getStatus().name().equals("CLOSED")) {
            throw new IllegalStateException("周期已关闭,不可修改");
        }
        BigDecimal income = (totalIncomeInput != null && totalIncomeInput.signum() > 0) ? totalIncomeInput : null;
        BigDecimal expense = (totalExpenseInput != null && totalExpenseInput.signum() > 0) ? totalExpenseInput : null;
        // v0.3 修订(2026-05-13):成员级 upsert
        memberCashflowMapper.upsert(com.family.finance.domain.period.PeriodMemberCashflow.builder()
            .familyId(me.getFamilyId())
            .periodId(periodId)
            .memberId(me.getMemberId())
            .totalIncomeInput(income)
            .totalExpenseInput(expense)
            .build());
        return "redirect:/entry?period=" + periodId;
    }

    @PostMapping("/entry/{accountId}/balance")
    public String submitBalance(@AuthenticationPrincipal MemberPrincipal me,
                                @PathVariable long accountId,
                                @RequestParam MultiValueMap<String, String> params,
                                HttpServletResponse response,
                                Model model) {
        long periodId = longParam(params, "periodId", () -> periodService.requireCurrentOpen(me.getFamilyId()).getId());
        EntryRow row = entryService.submitBalance(
                me.getFamilyId(),
                me.getMemberId(),
                periodId,
                accountId,
                decimalParam(params, "newBalance"),
                cashFlowLines(params),
                transferLines(params),
                params.getFirst("note")
        );
        response.setHeader("HX-Trigger", "refresh-row-" + accountId);
        return rowFragment(me, row, periodId, model);
    }

    @PostMapping("/entry/{accountId}/cash-flow")
    public String addCashFlow(@AuthenticationPrincipal MemberPrincipal me,
                              @PathVariable long accountId,
                              @RequestParam long periodId,
                              @RequestParam CashFlowKind kind,
                              @RequestParam(defaultValue = "other_income") String categoryCode,
                              @RequestParam BigDecimal amount,
                              @RequestParam(required = false) String note,
                              HttpServletResponse response,
                              Model model) {
        EntryRow row = entryService.addCashFlow(me.getFamilyId(), me.getMemberId(), periodId, accountId,
                kind, categoryCode, amount, note);
        // 触发自身 hx-get refresh,确保 ledger details 用 GET 路径完整渲染(POST fragment 路径在 fragment 内嵌套时
        // 会丢失 row.ledger 子元素求值结果,这里走 self-refresh 兜底)
        response.setHeader("HX-Trigger", "refresh-row-" + accountId);
        return rowFragment(me, row, periodId, model);
    }

    @PostMapping("/entry/{accountId}/transfer")
    public String addTransfer(@AuthenticationPrincipal MemberPrincipal me,
                              @PathVariable long accountId,
                              @RequestParam long periodId,
                              @RequestParam long toAccountId,
                              @RequestParam BigDecimal amount,
                              @RequestParam(required = false) String note,
                              @RequestParam(defaultValue = "false") boolean confirmDuplicate,
                              HttpServletResponse response,
                              Model model) {
        EntryRow row = entryService.addTransfer(me.getFamilyId(), me.getMemberId(), periodId,
                accountId, toAccountId, amount, note, confirmDuplicate);
        // HX-Trigger:让目标行 div 自己再 hx-get 拉一次,实现"两行同时刷新",避开 Thymeleaf fragment 嵌套坑
        response.setHeader("HX-Trigger", "refresh-row-" + toAccountId);
        return rowFragment(me, row, periodId, model);
    }

    /** v0.2 FR-32 · 软删现金流 */
    @PostMapping("/entry/cash-flow/{id}/delete")
    public String deleteCashFlow(@AuthenticationPrincipal MemberPrincipal me,
                                 @PathVariable("id") long cashFlowId,
                                 HttpServletResponse response,
                                 Model model) {
        EntryRow row = entryService.softDeleteCashFlow(me.getFamilyId(), me.getMemberId(), cashFlowId);
        response.setHeader("HX-Trigger", "refresh-row-" + row.account().getId());
        return rowFragment(me, row, row.currentSnapshot() == null ? null : row.currentSnapshot().getPeriodId(), model);
    }

    /** v0.2 FR-32 · 软删转账 */
    @PostMapping("/entry/transfer/{id}/delete")
    public String deleteTransfer(@AuthenticationPrincipal MemberPrincipal me,
                                 @PathVariable("id") long transferId,
                                 HttpServletResponse response,
                                 Model model) {
        EntryRow row = entryService.softDeleteTransfer(me.getFamilyId(), me.getMemberId(), transferId);
        response.setHeader("HX-Trigger", "refresh-row-" + row.account().getId());
        return rowFragment(me, row, row.currentSnapshot() == null ? null : row.currentSnapshot().getPeriodId(), model);
    }

    @PostMapping("/entry/transfer/quick")
    public String quickTransfer(@AuthenticationPrincipal MemberPrincipal me,
                                @RequestParam long fromAccountId,
                                @RequestParam(required = false) Long periodId,
                                @RequestParam long toAccountId,
                                @RequestParam BigDecimal amount,
                                @RequestParam(required = false) String note,
                                @RequestParam(defaultValue = "false") boolean confirmDuplicate,
                                HttpServletResponse response,
                                Model model) {
        EntryRow row = entryService.quickTransfer(me.getFamilyId(), me.getMemberId(), fromAccountId,
                periodId, toAccountId, amount, note, confirmDuplicate);
        long effectivePeriodId = periodId == null ? periodService.requireCurrentOpen(me.getFamilyId()).getId() : periodId;
        response.setHeader("HX-Trigger", "refresh-row-" + toAccountId);
        return rowFragment(me, row, effectivePeriodId, model);
    }

    @PostMapping("/entry/{periodId}/complete")
    public String completePeriod(@AuthenticationPrincipal MemberPrincipal me,
                                 @PathVariable long periodId) {
        periodService.markCompletedByMember(periodId, me.getMemberId());
        return "redirect:/entry?period=" + periodId;
    }

    private String rowFragment(MemberPrincipal me, EntryRow row, long periodId, Model model) {
        // 改为返回 block(row + ledger 整块),让 ledger 流水列表也实时刷新
        return blockFragment(me, row, periodId, model);
    }

    /** 把 EntryRow.ledger 渲染为预格式化的 HTML 片段(规避 Thymeleaf 在 each 嵌套 List 上的 accessor bug)。 */
    private String renderLedgerHtml(EntryRow row) {
        if (row == null || row.ledger() == null || row.ledger().isEmpty()) return "";
        // CSRF token 用于本期内 ⋮ 删除按钮的 hx-headers
        String csrfToken = "";
        try {
            org.springframework.security.web.csrf.CsrfToken t = (org.springframework.security.web.csrf.CsrfToken)
                    org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()
                            .getAttribute("_csrf", org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
            if (t != null) csrfToken = t.getToken();
        } catch (Exception ignored) {}

        StringBuilder sb = new StringBuilder();
        sb.append("<details open class=\"paper-card -mt-3 mb-3 px-6 py-3 border-t-0 border-rule bg-card-soft\">");
        sb.append("<summary class=\"font-mono text-[10px] tracking-[0.16em] uppercase text-ink-soft cursor-pointer select-none\">");
        sb.append("展开本期 <b>").append(row.ledger().size()).append("</b> 笔流水</summary>");
        sb.append("<ul class=\"mt-3 divide-y divide-rule-soft text-xs font-mono\">");
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm");
        for (EntryRow.LedgerEntry le : row.ledger()) {
            String kindClass; String kindLabel;
            switch (le.kind()) {
                case INCOME -> { kindClass = "num-pos"; kindLabel = "+ 收入"; }
                case EXPENSE -> { kindClass = "num-neg"; kindLabel = "- 支出"; }
                case TRANSFER_IN -> { kindClass = "text-forest"; kindLabel = "↳ 划入"; }
                case TRANSFER_OUT -> { kindClass = "text-rust"; kindLabel = "↱ 划出"; }
                default -> { kindClass = "text-ink-subtle"; kindLabel = "= 校准余额"; }
            }
            sb.append("<li class=\"py-1.5 flex items-baseline gap-3 flex-wrap\">");
            sb.append("<span class=\"w-24 inline-flex items-center gap-1 ").append(kindClass).append("\">").append(kindLabel).append("</span>");
            sb.append("<span class=\"tnum w-28\">").append(escapeHtml(le.amountSignedLabel())).append("</span>");
            String label = le.label() != null ? le.label()
                    : (le.kind() == EntryRow.LedgerKind.SNAPSHOT ? "用户校准余额" : "");
            sb.append("<span class=\"text-ink-soft flex-1 min-w-[120px]\">").append(escapeHtml(label)).append("</span>");
            if (le.occurredAt() != null) {
                sb.append("<span class=\"text-ink-subtle text-[10px]\">").append(le.occurredAt().format(fmt)).append("</span>");
            }
            // v0.2 FR-32 · OPEN 周期下的 cash_flow / transfer 加 ⋮ 删除按钮(SNAPSHOT 不能删)
            if (le.periodOpen() && le.sourceId() != null
                    && le.kind() != EntryRow.LedgerKind.SNAPSHOT) {
                String url = (le.kind() == EntryRow.LedgerKind.TRANSFER_IN || le.kind() == EntryRow.LedgerKind.TRANSFER_OUT)
                        ? "/entry/transfer/" + le.sourceId() + "/delete"
                        : "/entry/cash-flow/" + le.sourceId() + "/delete";
                sb.append("<button class=\"text-[11px] text-ink-subtle hover:text-rust px-1\" title=\"删除此条\" ")
                        .append("hx-post=\"").append(url).append("\" ")
                        .append("hx-target=\"#row-").append(row.account().getId()).append("\" ")
                        .append("hx-swap=\"outerHTML\" ")
                        .append("hx-confirm=\"确定删除这条流水?余额会自动反向冲销。\" ")
                        .append("hx-headers='{\"X-XSRF-TOKEN\":\"").append(escapeHtml(csrfToken)).append("\"}'>")
                        .append("✕</button>");
            }
            if (le.note() != null && !le.note().isBlank()) {
                sb.append("<span class=\"w-full text-ink-subtle italic pl-24\">· ").append(escapeHtml(le.note())).append("</span>");
            }
            sb.append("</li>");
        }
        sb.append("</ul></details>");
        return sb.toString();
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 单行刷新:返回 entry-block 整块(row + ledger),由 HTMX 监听 refresh-row-{id} 事件 OR 用户手动刷新 icon 触发 */
    @GetMapping("/entry/{accountId}/refresh")
    public String refreshRow(@AuthenticationPrincipal MemberPrincipal me,
                             @PathVariable long accountId,
                             @RequestParam(required = false) Long period,
                             Model model) {
        long effectivePeriodId = period == null
                ? periodService.requireCurrentOpen(me.getFamilyId()).getId() : period;
        EntryRow row = entryService.rowFor(me.getFamilyId(), me.getMemberId(), effectivePeriodId, accountId);
        return blockFragment(me, row, effectivePeriodId, model);
    }

    /** 渲染 entry-block 整块(row + ledger),用于 HTMX swap 整块 */
    private String blockFragment(MemberPrincipal me, EntryRow row, long periodId, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("row", row);
        model.addAttribute("period", periodMapper.findById(periodId).orElse(null));
        model.addAttribute("accounts", accountMapper.findActiveByFamily(me.getFamilyId()));
        java.util.Map<String, String> singleLedger = new java.util.LinkedHashMap<>();
        singleLedger.put(String.valueOf(row.account().getId()), renderLedgerHtml(row));
        model.addAttribute("ledgerHtmlByAccount", singleLedger);
        return "entry/_row :: block(row=${row}, oob=null)";
    }

    private List<EntryService.CashFlowLine> cashFlowLines(MultiValueMap<String, String> params) {
        List<String> amounts = values(params, "cashFlowAmount");
        List<EntryService.CashFlowLine> lines = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) {
            if (amounts.get(i) == null || amounts.get(i).isBlank()) {
                continue;
            }
            CashFlowKind kind = CashFlowKind.valueOf(valueAt(values(params, "cashFlowKind"), i, "INCOME"));
            String category = valueAt(values(params, "cashFlowCategory"), i, kind == CashFlowKind.INCOME ? "other_income" : "consumption");
            String note = valueAt(values(params, "cashFlowNote"), i, null);
            lines.add(new EntryService.CashFlowLine(kind, category, new BigDecimal(amounts.get(i)), note));
        }
        return lines;
    }

    private List<EntryService.TransferLine> transferLines(MultiValueMap<String, String> params) {
        List<String> amounts = values(params, "transferAmount");
        List<String> targets = values(params, "transferToAccountId");
        List<EntryService.TransferLine> lines = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) {
            if (amounts.get(i) == null || amounts.get(i).isBlank() || valueAt(targets, i, null) == null) {
                continue;
            }
            String note = valueAt(values(params, "transferNote"), i, null);
            lines.add(new EntryService.TransferLine(Long.parseLong(targets.get(i)), new BigDecimal(amounts.get(i)), note));
        }
        return lines;
    }

    private List<String> values(MultiValueMap<String, String> params, String key) {
        return params.get(key) == null ? List.of() : params.get(key);
    }

    private String valueAt(List<String> values, int index, String fallback) {
        return index < values.size() ? values.get(index) : fallback;
    }

    private long longParam(MultiValueMap<String, String> params, String key, java.util.function.LongSupplier fallback) {
        String value = params.getFirst(key);
        return value == null || value.isBlank() ? fallback.getAsLong() : Long.parseLong(value);
    }

    private BigDecimal decimalParam(MultiValueMap<String, String> params, String key) {
        String value = params.getFirst(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return new BigDecimal(value);
    }
}
