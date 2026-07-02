package com.family.finance.web.entry;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.flow.CashFlowKind;
import com.family.finance.domain.family.ReportingTemplate;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.domain.stock.Market;
import com.family.finance.service.EntryService;
import com.family.finance.service.EntryRow;
import com.family.finance.service.NavService;
import com.family.finance.service.PeriodService;
import com.family.finance.service.stock.AccountValuationService;
import com.family.finance.service.stock.EntryRefreshRateLimiter;
import com.family.finance.service.stock.StockPriceScheduler;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class EntryController {

    private final EntryService entryService;
    private final PeriodMapper periodMapper;
    private final PeriodService periodService;
    private final AccountMapper accountMapper;
    private final NavService navService;
    private final MemberMapper memberMapper;
    private final PeriodMemberCashflowMapper memberCashflowMapper;
    private final FamilyMapper familyMapper;
    /** v0.12 · 收入侧:类目下拉 + 本期收入列表 */
    private final com.family.finance.repository.CashFlowCategoryMapper cashFlowCategoryMapper;
    private final com.family.finance.repository.CashFlowMapper cashFlowMapper;
    /** v0.4.22 · /entry 一键拉取股价按钮 · 三件套依赖 */
    private final StockPriceScheduler stockScheduler;
    private final AccountValuationService valuationService;
    private final EntryRefreshRateLimiter refreshRateLimiter;
    /** v0.12 · 股票收入:联动持仓 + 按股数入账 */
    private final com.family.finance.service.stock.StockHoldingService stockHoldingService;
    /** v0.12.2 · 收入列表本位币换算(账户币种 → 本位币,与 dashboard 人赚同源) */
    private final com.family.finance.service.FxService fxService;

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

        // v0.4.15 · 按 owner 分组(填报页分割线)· 保留 rows 不动(其他逻辑依赖)
        var ownerGroups = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r.ownerName() == null ? "共同" : r.ownerName(),
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        model.addAttribute("ownerGroups", ownerGroups);
        // owner avatar 颜色映射(按 groupBy 顺序分配 0,1,2... · 模板 class="avatar-N")
        var ownerColorMap = new java.util.LinkedHashMap<String, Integer>();
        int colorIdx = 0;
        for (String key : ownerGroups.keySet()) ownerColorMap.put(key, colorIdx++);
        model.addAttribute("ownerColorMap", ownerColorMap);
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

        // v0.4.14 FR-63b · 推荐填报方案提示 + 距本期截止天数
        familyMapper.findById(me.getFamilyId()).ifPresent(fam -> {
            ReportingTemplate tmpl = ReportingTemplate.fromCode(fam.getReportingTemplate());
            model.addAttribute("reportTemplateName", tmpl.displayName());
            model.addAttribute("reportHint", tmpl.hintText());
        });
        model.addAttribute("daysToDeadline",
                java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.now(), period.getPeriodEnd()));

        // v0.4.22 · 仅家庭有持仓账户时才显示「拉取行情」按钮(避免按钮空响)
        boolean hasHoldingAccounts = rows.stream()
                .anyMatch(r -> r.account().getType() != null
                        && com.family.finance.service.stock.StockHoldingService.supportsHoldings(r.account().getType()));
        model.addAttribute("hasHoldingAccounts", hasHoldingAccounts);

        // v0.12 · 收入侧:类目下拉(含 account_type 绑定,供联动/校验)+ 可作收入落点的账户(现金/股票)+ 本期收入列表
        model.addAttribute("incomeCategories", cashFlowCategoryMapper.listIncomeOrdered());
        model.addAttribute("incomeAccounts", accountMapper.findActiveByFamily(me.getFamilyId()).stream()
                .filter(a -> a.getType() != null
                        && ("CASH".equals(a.getType().name()) || "STOCK".equals(a.getType().name())))
                .toList());
        var incomeEntries = cashFlowMapper.findIncomeEntries(me.getFamilyId(), period.getId());
        model.addAttribute("incomeEntries", incomeEntries);
        // v0.12.2 · 币种修正:cash_flow.amount 是「账户币种」· 逐笔换到本位币汇总(与 dashboard 人赚同源),
        // 每行原币展示 + 非本位币括注 ≈本位币;家庭合计走本位币,不再把美元/港币裸加成 ¥。
        String baseCcy = familyMapper.findById(me.getFamilyId())
                .map(f -> f.getBaseCurrency()).orElse("CNY");
        java.util.Map<Long, BigDecimal> incomeBaseById = new java.util.LinkedHashMap<>();
        BigDecimal incomeBaseTotal = BigDecimal.ZERO;
        for (var e : incomeEntries) {
            BigDecimal b = toBaseAmount(me.getFamilyId(), e.amount(), e.currency(), baseCcy, period.getId());
            incomeBaseById.put(e.id(), b);
            incomeBaseTotal = incomeBaseTotal.add(b);
        }
        model.addAttribute("baseCurrency", baseCcy);
        model.addAttribute("incomeBaseById", incomeBaseById);
        model.addAttribute("incomeBaseTotal", incomeBaseTotal.setScale(2, java.math.RoundingMode.HALF_EVEN));

        return "entry/index";
    }

    /**
     * v0.4.22 · 一键拉取股价 · /entry 顶部按钮 · HTMX POST → 返回 toast fragment。
     *
     * <p>逻辑:</p>
     * <ol>
     *   <li>限频:family 60s 窗口 ≤3 次 · 超频返回 toast「操作太频繁」不调 scheduler</li>
     *   <li>顺序 fetchMarket(US/CN/HK/CRYPTO)· 单市场失败计数但不阻断</li>
     *   <li>{@link AccountValuationService#refreshAllForFamily} MANUAL + memberId</li>
     *   <li>渲染 toast 显示结果</li>
     * </ol>
     */
    @PostMapping("/entry/refresh-stocks")
    public String refreshStocks(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        if (!refreshRateLimiter.tryAcquire(me.getFamilyId())) {
            long wait = refreshRateLimiter.secondsUntilNextAllowed(me.getFamilyId());
            model.addAttribute("toastKind", "rust");
            model.addAttribute("toastIcon", "clock");
            model.addAttribute("toastText", "操作太频繁 · 请 " + wait + " 秒后再试");
            return "entry/_refresh-toast :: toast";
        }
        int marketsOk = 0;
        for (Market mk : List.of(Market.US, Market.CN, Market.HK, Market.CRYPTO)) {
            try {
                stockScheduler.fetchMarket(mk);
                marketsOk++;
            } catch (Exception e) {
                log.warn("entry-refresh fetchMarket failed · market={}: {}", mk, e.toString());
            }
        }
        int accountsRefreshed = 0;
        try {
            accountsRefreshed = valuationService.refreshAllForFamily(
                me.getFamilyId(),
                AccountValuationService.TriggerKind.MANUAL,
                me.getMemberId());
        } catch (Exception e) {
            log.warn("entry-refresh valuation refresh failed: {}", e.toString());
        }
        if (marketsOk == 3) {
            model.addAttribute("toastKind", "forest");
            model.addAttribute("toastIcon", "check");
            model.addAttribute("toastText",
                "3 市场估值已刷新 · " + accountsRefreshed + " 账户");
        } else if (marketsOk > 0) {
            model.addAttribute("toastKind", "rust");
            model.addAttribute("toastIcon", "warn");
            model.addAttribute("toastText",
                "仅 " + marketsOk + "/3 市场估值刷新成功 · " + accountsRefreshed + " 账户已更新 · 详情查 journal");
        } else {
            model.addAttribute("toastKind", "rust");
            model.addAttribute("toastIcon", "fail");
            model.addAttribute("toastText",
                "3 市场估值均刷新失败 · 上游限流/网络 · 详情查 journal");
        }
        return "entry/_refresh-toast :: toast";
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

    /** v0.12 FR-140 · 收入侧录入一笔:金额+类目+目标账户 → 入账 + 留流水(股票落 CASH 现金行)。 */
    @PostMapping("/entry/income")
    public String recordIncome(@AuthenticationPrincipal MemberPrincipal me,
                               @RequestParam long periodId,
                               @RequestParam long accountId,
                               @RequestParam String categoryCode,
                               @RequestParam BigDecimal amount,
                               @RequestParam(required = false) String note) {
        entryService.recordIncome(me.getFamilyId(), me.getMemberId(), periodId, accountId, categoryCode, amount, note);
        return "redirect:/entry?period=" + periodId;
    }

    /** v0.12 FR-145/148 · 删一笔收入 = 软删该 cash_flow + 冲回账户余额(股票+股数冲回股数 / 股票现金冲回现金行);账户明细同步。 */
    @PostMapping("/entry/income/{id}/delete")
    public String deleteIncome(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable("id") long cashFlowId,
                               @RequestParam long periodId) {
        entryService.softDeleteCashFlow(me.getFamilyId(), me.getMemberId(), cashFlowId);
        return "redirect:/entry?period=" + periodId;
    }

    /** v0.12 FR-150 · 选股票账户后联动该账户已有持仓(HTMX fragment)· 供收入侧就地入账。 */
    @GetMapping("/entry/income/stock/holdings")
    public String stockIncomeHoldings(@AuthenticationPrincipal MemberPrincipal me,
                                       @RequestParam long accountId,
                                       @RequestParam long periodId,
                                       Model model) {
        Account account = accountMapper.findById(accountId)
                .filter(a -> a.getFamilyId() == me.getFamilyId())
                .filter(a -> a.getType() != null && "STOCK".equals(a.getType().name()))
                .orElseThrow(() -> new IllegalArgumentException("非法股票账户"));
        var holdings = stockHoldingService.findActiveByAccount(me.getFamilyId(), accountId);
        java.util.Map<Long, BigDecimal> unitValues = new java.util.LinkedHashMap<>();
        for (var h : holdings) {
            if (h.getValuationMode() != null
                    && !"CASH".equals(h.getValuationMode().name())) {
                try {
                    unitValues.put(h.getId(), stockHoldingService.currentUnitValueInAccountCcy(me.getFamilyId(), h));
                } catch (Exception ignored) {}
            }
        }
        model.addAttribute("account", account);
        model.addAttribute("holdings", holdings);
        model.addAttribute("unitValues", unitValues);
        model.addAttribute("period", periodMapper.findById(periodId).orElse(null));
        model.addAttribute("markets", List.of(Market.US, Market.CN, Market.HK));
        return "entry/_income-stock :: holdings";
    }

    /** v0.12 FR-144 · 股票收入 · 已有持仓 +股数(上市/未上市)。 */
    @PostMapping("/entry/income/stock/holding")
    public String stockIncomeExistingHolding(@AuthenticationPrincipal MemberPrincipal me,
                                             @RequestParam long periodId,
                                             @RequestParam long accountId,
                                             @RequestParam long holdingId,
                                             @RequestParam BigDecimal addShares,
                                             @RequestParam(defaultValue = "stock_salary") String categoryCode,
                                             @RequestParam(required = false) String note) {
        entryService.recordStockIncomeExistingHolding(me.getFamilyId(), me.getMemberId(), periodId,
                accountId, holdingId, addShares, categoryCode, note);
        return "redirect:/entry?period=" + periodId;
    }

    /** v0.12 FR-144 · 股票收入 · 新建上市持仓入账(代码+市场+股数)· 先拉价再计值。 */
    @PostMapping("/entry/income/stock/new-auto")
    public String stockIncomeNewAuto(@AuthenticationPrincipal MemberPrincipal me,
                                     @RequestParam long periodId,
                                     @RequestParam long accountId,
                                     @RequestParam(required = false) String displayName,
                                     @RequestParam String ticker,
                                     @RequestParam String market,
                                     @RequestParam BigDecimal shares,
                                     @RequestParam(required = false) String currency,
                                     @RequestParam(defaultValue = "stock_salary") String categoryCode,
                                     @RequestParam(required = false) String note) {
        Market mk = Market.valueOf(market.toUpperCase(java.util.Locale.ROOT));
        try { stockScheduler.fetchMarket(mk); } catch (Exception e) {
            log.warn("stock-income new-auto fetchMarket failed · {}: {}", mk, e.toString());
        }
        entryService.recordStockIncomeNewAuto(me.getFamilyId(), me.getMemberId(), periodId,
                accountId, displayName, ticker, mk, shares, currency, categoryCode, note);
        return "redirect:/entry?period=" + periodId;
    }

    /** v0.12 FR-144 · 股票收入 · 新建未上市持仓入账(名称+股数+单股估值)。 */
    @PostMapping("/entry/income/stock/new-manual")
    public String stockIncomeNewManual(@AuthenticationPrincipal MemberPrincipal me,
                                       @RequestParam long periodId,
                                       @RequestParam long accountId,
                                       @RequestParam String displayName,
                                       @RequestParam BigDecimal shares,
                                       @RequestParam BigDecimal unitValue,
                                       @RequestParam(defaultValue = "stock_salary") String categoryCode,
                                       @RequestParam(required = false) String note) {
        entryService.recordStockIncomeNewManual(me.getFamilyId(), me.getMemberId(), periodId,
                accountId, displayName, shares, unitValue, categoryCode, note);
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
                              @RequestParam(required = false) BigDecimal toAmount,
                              @RequestParam(required = false) String note,
                              @RequestParam(defaultValue = "false") boolean confirmDuplicate,
                              HttpServletResponse response,
                              Model model) {
        EntryRow row = entryService.addTransfer(me.getFamilyId(), me.getMemberId(), periodId,
                accountId, toAccountId, amount, toAmount, note, confirmDuplicate);
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
                                @RequestParam(required = false) BigDecimal toAmount,
                                @RequestParam(required = false) String note,
                                @RequestParam(defaultValue = "false") boolean confirmDuplicate,
                                HttpServletResponse response,
                                Model model) {
        EntryRow row = entryService.quickTransfer(me.getFamilyId(), me.getMemberId(), fromAccountId,
                periodId, toAccountId, amount, toAmount, note, confirmDuplicate);
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
        // 默认折叠(无 open)· 文案随展开状态切换(CSS .lg-when-open / .lg-when-closed)
        sb.append("<details class=\"paper-card -mt-3 mb-3 px-6 py-3 border-t-0 border-rule bg-card-soft\">");
        sb.append("<summary class=\"font-mono text-[10px] tracking-[0.16em] uppercase text-ink-soft cursor-pointer select-none\">");
        sb.append("<span class=\"lg-when-closed\">展开</span><span class=\"lg-when-open\">折叠</span>")
          .append("本期 <b>").append(row.ledger().size()).append("</b> 笔流水</summary>");
        sb.append("<ul class=\"mt-3 divide-y divide-rule-soft text-xs font-mono\">");
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm");
        for (EntryRow.LedgerEntry le : row.ledger()) {
            String kindClass; String kindLabel;
            switch (le.kind()) {
                case INCOME -> { kindClass = "num-pos"; kindLabel = "+ 收入"; }
                case EXPENSE -> { kindClass = "num-neg"; kindLabel = "- 支出"; }
                case TRANSFER_IN -> { kindClass = "text-forest"; kindLabel = "↳ 划入"; }
                case TRANSFER_OUT -> { kindClass = "text-rust"; kindLabel = "↱ 划出"; }
                case VALUATION -> { kindClass = "text-brass-deep"; kindLabel = "△ 估值"; }   // v0.10.6 去 emoji(no-emoji 纪律)· 与 detail.html VALUATION 渲染一致
                default -> { kindClass = "text-ink-subtle"; kindLabel = "= 校准余额"; }
            }
            sb.append("<li class=\"py-1.5 flex items-baseline gap-3 flex-wrap\">");
            sb.append("<span class=\"w-24 inline-flex items-center gap-1 ").append(kindClass).append("\">").append(kindLabel).append("</span>");
            sb.append("<span class=\"tnum w-28\" data-priv>").append(escapeHtml(le.amountSignedLabel())).append("</span>");  // v0.11 隐私模式:账本金额遮
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
        List<String> toAmounts = values(params, "transferToAmount");
        List<EntryService.TransferLine> lines = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) {
            if (amounts.get(i) == null || amounts.get(i).isBlank() || valueAt(targets, i, null) == null) {
                continue;
            }
            String note = valueAt(values(params, "transferNote"), i, null);
            String ta = valueAt(toAmounts, i, null);
            BigDecimal toAmount = (ta == null || ta.isBlank()) ? null : new BigDecimal(ta);
            lines.add(new EntryService.TransferLine(Long.parseLong(targets.get(i)), new BigDecimal(amounts.get(i)), toAmount, note));
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

    /**
     * v0.12.2 · 把账户币种金额换到本位币(收入列表家庭合计用)· 与 AccountValuationService/StockHoldingService 同法:
     * 直接汇率 → 反向取倒数 → 都缺则 1:1 兜底(记日志)。同币种直接返回。
     */
    private BigDecimal toBaseAmount(long familyId, BigDecimal amount, String currency, String base, long periodId) {
        if (amount == null) return BigDecimal.ZERO;
        if (currency == null || base == null || currency.equalsIgnoreCase(base)) return amount;
        var r = fxService.getOrFetchRate(familyId, currency, base, periodId);
        if (r.isPresent() && r.get().getRate() != null && r.get().getRate().signum() > 0) {
            return amount.multiply(r.get().getRate()).setScale(2, java.math.RoundingMode.HALF_EVEN);
        }
        var inv = fxService.getOrFetchRate(familyId, base, currency, periodId);
        if (inv.isPresent() && inv.get().getRate() != null && inv.get().getRate().signum() > 0) {
            return amount.divide(inv.get().getRate(), 2, java.math.RoundingMode.HALF_EVEN);
        }
        log.warn("收入列表本位币换算缺汇率 · {}→{} family={} · 用 1:1 兜底", currency, base, familyId);
        return amount;
    }
}
