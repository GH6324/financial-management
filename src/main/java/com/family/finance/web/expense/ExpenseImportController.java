package com.family.finance.web.expense;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.expense.ExpenseSource;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.ExpenseImportBatchMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.expense.ExpenseCategoryService;
import com.family.finance.service.expense.imports.BillAccountResolver;
import com.family.finance.service.expense.imports.BillCategoryResolver;
import com.family.finance.service.expense.imports.BillCommitService;
import com.family.finance.service.expense.imports.BillImportService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21(第 2 稿)· 账单导入:上传 → <b>逐笔</b>归类 → 确认页核对 → 落成真流水。
 *
 * <h3>草稿存 session,不落盘</h3>
 *
 * <p>账单是<b>整月的消费流水</b>,本项目迄今最敏感的输入。文件字节只活在方法栈里;
 * 草稿(逐笔的日期/商户/金额/归类)放 session 等用户确认,确认或丢弃即清。
 * <b>任何时候都不写磁盘、不进日志</b> —— 失败时只记「哪个渠道 / 哪一步 / 什么原因」。</p>
 *
 * <h3>确认页是必经之路</h3>
 *
 * <p>渠道的自动分类不是每笔都准,AI 更是在猜。直接落账等于把猜的结果写进用户的账。
 * 所以必须有一步核对,并且每行标出<b>归类依据</b>(FR-564)—— 用户没时间看 428 笔,
 * 但可以只看「AI 猜的」和「兜底」那十几笔,而这一页把它们排在最前面(FR-565)。</p>
 *
 * <h3>回传的是差异,不是整个草稿</h3>
 *
 * <p>确认时页面只回传「哪几行被改到了哪个分类 / 哪几行被剔除」。草稿在 session 里是权威的。
 * 全量回传的话,一个被篡改的金额就能直接写进账。</p>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ExpenseImportController {

    private static final String DRAFT_KEY = "v121ImportDraft";
    private static final String DRAFT_PERIOD = "v121ImportPeriod";

    private final BillImportService importService;
    private final BillCommitService commitService;
    private final ExpenseCategoryService categoryService;
    private final PeriodMapper periodMapper;
    private final AccountMapper accountMapper;
    private final ExpenseImportBatchMapper batchMapper;
    private final NavService navService;
    private final com.family.finance.repository.ExpenseAccountRuleMapper acctRuleMapper;

    @GetMapping("/expense/import")
    public String page(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam(required = false) Long periodId,
                       Model model, HttpSession session) {
        long fam = me.getFamilyId();
        var period = resolvePeriod(fam, periodId);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("period", period);
        model.addAttribute("hasCategories", categoryService.hasAny(fam));
        if (period == null) return "expense/import";

        model.addAttribute("accounts", accountMapper.findActiveByFamily(fam));
        model.addAttribute("batches", batchMapper.findLiveByPeriod(fam, period.getId()));
        /* 「记住我的改动」攒下来的两类规则 —— 用户要能看见自己记了什么,也要能删。
         * 只写不给看的记忆,用户第一次发现它归错类时会无从下手。 */
        model.addAttribute("rules", importService.rules(fam));
        model.addAttribute("acctRules", acctRuleMapper.findByFamily(fam));
        java.util.Map<Long, String> catName = new LinkedHashMap<>();
        for (var e : categoryService.pickable(fam).entrySet()) {
            catName.put(e.getKey().getId(), e.getKey().getName());
            for (var k : e.getValue()) catName.put(k.getId(), e.getKey().getName() + " › " + k.getName());
        }
        model.addAttribute("catName", catName);
        java.util.Map<Long, String> acctName = new LinkedHashMap<>();
        for (var a : accountMapper.findActiveByFamily(fam)) acctName.put(a.getId(), a.getDisplayName());
        model.addAttribute("acctName", acctName);

        var draft = (BillCategoryResolver.Draft) session.getAttribute(DRAFT_KEY);
        Object dp = session.getAttribute(DRAFT_PERIOD);
        if (draft != null && dp instanceof Long l && l.equals(period.getId())) {
            putDraft(model, fam, draft);
        }
        return "expense/import";
    }

    /**
     * 确认页要的所有派生数据。
     *
     * <p>集中在这里算,不在模板里算 —— Thymeleaf 表达式里做分组排序会长到没人读得懂,
     * 而且那类错误是<b>渲染期</b>才炸的(响应截断成半页),编译和单测都发现不了。</p>
     */
    private void putDraft(Model model, long familyId, BillCategoryResolver.Draft draft) {
        model.addAttribute("draft", draft);
        model.addAttribute("channelLabel", draft.channel().getLabel());

        var spend = draft.bucket(BillCategoryResolver.Bucket.SPEND);
        /* 排序:【需要核对的排最前】(FR-565)。
         * 打开这一页时最该看到的不是「餐饮美食 96 笔」,而是「没把握的 8 笔」。
         * 其余按分类分组,组间按金额倒序。 */
        List<BillCategoryResolver.Line> review =
                spend.stream().filter(BillCategoryResolver.Line::needsReview).toList();
        List<BillCategoryResolver.Line> settled =
                spend.stream().filter(l -> !l.needsReview()).toList();

        Map<String, List<BillCategoryResolver.Line>> groups = new LinkedHashMap<>();
        if (!review.isEmpty()) groups.put("先看这些 · 没把握", new ArrayList<>(review));
        Map<String, List<BillCategoryResolver.Line>> byCat = new LinkedHashMap<>();
        for (var l : settled) {
            byCat.computeIfAbsent(l.categoryName() == null ? "其他" : l.categoryName(),
                    k -> new ArrayList<>()).add(l);
        }
        byCat.entrySet().stream()
                .sorted((a, b) -> sum(b.getValue()).compareTo(sum(a.getValue())))
                .forEach(e -> groups.put(e.getKey(), e.getValue()));
        model.addAttribute("groups", groups);
        model.addAttribute("groupSums", groups.entrySet().stream().collect(
                LinkedHashMap::new, (m, e) -> m.put(e.getKey(), sum(e.getValue())), Map::putAll));
        model.addAttribute("reviewCount", review.size());

        model.addAttribute("spendCount", spend.size());
        model.addAttribute("spendSum", draft.sum(BillCategoryResolver.Bucket.SPEND));
        model.addAttribute("natureCount", draft.count(BillCategoryResolver.Bucket.NATURE));
        model.addAttribute("natureSum", draft.sum(BillCategoryResolver.Bucket.NATURE));
        model.addAttribute("incomeCount", draft.count(BillCategoryResolver.Bucket.INCOME));
        model.addAttribute("droppedCount", draft.count(BillCategoryResolver.Bucket.DROPPED));
        model.addAttribute("skippedCount", draft.count(BillCategoryResolver.Bucket.SKIPPED));
        model.addAttribute("noTxNo", draft.noTxNo());
        model.addAttribute("parsed", draft.parsed());

        // 下拉给整棵树,细类带父名(「餐饮美食 › 外卖」)—— 两个同名细类才分得清
        List<Map<String, Object>> opts = new ArrayList<>();
        for (var e : categoryService.pickable(familyId).entrySet()) {
            opts.add(Map.of("id", e.getKey().getId(), "label", e.getKey().getName()));
            for (var k : e.getValue()) {
                opts.add(Map.of("id", k.getId(), "label", e.getKey().getName() + " › " + k.getName()));
            }
        }
        model.addAttribute("catOptions", opts);

        /* 账户下拉 + 「这批用到了几个账户」—— 后者是给用户的信号:
         * 一份账单跨了 3 个账户时,他得知道这件事,否则不会想到去核对账户列。 */
        java.util.Set<Long> distinct = new java.util.LinkedHashSet<>();
        for (var l : draft.lines()) if (l.accountId() != null) distinct.add(l.accountId());
        model.addAttribute("acctSpan", distinct.size());
        model.addAttribute("acctReviewCount",
                draft.bucket(BillCategoryResolver.Bucket.SPEND).stream()
                        .filter(l -> l.accountHow() != null && l.accountHow().needsReview()).count());
    }

    /**
     * 把异常翻译成一句人话。
     *
     * <p>只翻译<b>已知能撞上的</b>几种;其余返回异常类型名 —— 那至少比「再试一次」有信息量,
     * 而且用户能把它贴给我们。<b>不给用户看堆栈</b>(那是 v0.14 的教训)。</p>
     */
    private static String humanCause(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("ck_cash_flow_amount")) {
            return "有一笔的金额是 0 或负数(账单里的冲正行),数据库不接受";
        }
        if (m.contains("Data too long")) return "某一栏的文字太长了(多半是商户名)";
        if (m.contains("foreign key") || m.contains("FOREIGN KEY")) return "引用了一个已经不存在的账户或类目,刷新一下再试";
        if (m.contains("Duplicate entry")) return "有重复数据撞上了唯一约束";
        return e.getClass().getSimpleName();
    }

    private boolean ownedAccount(long familyId, Long accountId) {
        if (accountId == null) return false;
        return accountMapper.findById(accountId)
                .filter(a -> a.getFamilyId() != null && a.getFamilyId() == familyId
                          && a.getArchivedAt() == null)
                .isPresent();
    }

    private String accountLabel(long familyId, long accountId) {
        return accountMapper.findById(accountId)
                .filter(a -> a.getFamilyId() != null && a.getFamilyId() == familyId)
                .map(a -> a.getDisplayName()).orElse("该账户");
    }

    private static BigDecimal sum(List<BillCategoryResolver.Line> ls) {
        return ls.stream().map(BillCategoryResolver.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private com.family.finance.domain.period.Period resolvePeriod(long familyId, Long periodId) {
        if (periodId != null) {
            var p = periodMapper.findById(periodId).orElse(null);
            if (p != null && p.getFamilyId() != null && p.getFamilyId() == familyId) return p;
            return null;
        }
        return periodMapper.findCurrentOpen(familyId).orElse(null);
    }

    @PostMapping("/expense/import/file")
    public String uploadFile(@AuthenticationPrincipal MemberPrincipal me,
                             @RequestParam long periodId,
                             @RequestParam String channel,
                             @RequestParam MultipartFile file,
                             @RequestParam(required = false) String zipPassword,
                             @RequestParam(required = false) Long defaultAccountId,
                             HttpSession session, RedirectAttributes ra) {
        try {
            ExpenseSource ch = ExpenseSource.valueOf(channel.toUpperCase(java.util.Locale.ROOT));
            var draft = importService.parseFile(me.getFamilyId(), ch, file.getBytes(),
                    file.getOriginalFilename(), zipPassword, defaultAccountId);
            session.setAttribute(DRAFT_KEY, draft);
            session.setAttribute(DRAFT_PERIOD, periodId);
        } catch (BillImportService.ImportException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", "先选一下这份账单是哪个渠道的。");
        } catch (Exception e) {
            // 只记形状,不记文件内容
            log.warn("账单上传失败 · channel={} · {}", channel, e.toString());
            ra.addFlashAttribute("flashError", "这个文件读不了 —— 确认一下是渠道导出的 csv 或加密 zip。");
        }
        return "redirect:/expense/import?periodId=" + periodId;
    }

    @PostMapping("/expense/import/confirm")
    public String confirm(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam long periodId,
                          @RequestParam long accountId,
                          @RequestParam(required = false) List<Integer> idx,
                          @RequestParam(required = false) List<String> cat,
                          @RequestParam(required = false) List<String> acct,
                          @RequestParam(required = false) List<Integer> drop,
                          @RequestParam(defaultValue = "false") boolean remember,
                          @RequestParam(defaultValue = "false") boolean affectsBalance,
                          HttpSession session, RedirectAttributes ra) {
        var draft = (BillCategoryResolver.Draft) session.getAttribute(DRAFT_KEY);
        if (draft == null) {
            ra.addFlashAttribute("flashError", "草稿已经过期了 —— 重新传一次文件。");
            return "redirect:/expense/import?periodId=" + periodId;
        }
        long fam = me.getFamilyId();
        try {
            Map<Integer, Long> changed = new LinkedHashMap<>();
            if (idx != null && cat != null) {
                for (int i = 0; i < Math.min(idx.size(), cat.size()); i++) {
                    String v = cat.get(i);
                    if (v == null || v.isBlank()) continue;
                    try { changed.put(idx.get(i), Long.parseLong(v.trim())); }
                    catch (NumberFormatException ignore) { /* 脏值忽略,保持原归类 */ }
                }
            }
            /* 账户也按 idx 对齐回传(与 cat 同一套机制)。
             * 一份账单里资金来源是变化的 —— 整批一个账户会让几个账户的余额一起错。 */
            Map<Integer, Long> acctOf = new LinkedHashMap<>();
            if (idx != null && acct != null) {
                for (int i = 0; i < Math.min(idx.size(), acct.size()); i++) {
                    String v = acct.get(i);
                    if (v == null || v.isBlank()) continue;
                    try { acctOf.put(idx.get(i), Long.parseLong(v.trim())); }
                    catch (NumberFormatException ignore) { /* 脏值忽略 */ }
                }
            }
            java.util.Set<Integer> dropped =
                    drop == null ? java.util.Set.of() : new java.util.HashSet<>(drop);

            List<BillCategoryResolver.Line> finalLines = new ArrayList<>();
            int userDropped = 0;
            for (var l : draft.lines()) {
                if (dropped.contains(l.idx())) { userDropped++; continue; }

                // ── 账户:用户改过就用他的(校验归属),否则保留推荐值 ──
                Long newAcct = acctOf.get(l.idx());
                Long finalAcct = l.accountId();
                var accountHow = l.accountHow();
                if (newAcct != null && ownedAccount(fam, newAcct) && !newAcct.equals(l.accountId())) {
                    finalAcct = newAcct;
                    accountHow = BillAccountResolver.How.RULE;
                    if (remember && l.payMethod() != null && !l.payMethod().isBlank()) {
                        importService.rememberAccountRule(fam, l.payMethod(), newAcct);
                    }
                }

                Long newCat = changed.get(l.idx());
                boolean changeable = l.bucket() == BillCategoryResolver.Bucket.SPEND;
                boolean catChanged = newCat != null && changeable && !newCat.equals(l.categoryId())
                        && categoryService.isUsable(fam, newCat);
                if (!catChanged && finalAcct == l.accountId()) { finalLines.add(l); continue; }
                if (!catChanged) {
                    finalLines.add(new BillCategoryResolver.Line(l.idx(), l.occurredAt(), l.merchant(),
                            l.amount(), l.categoryId(), l.categoryName(), l.how(), l.bucket(),
                            l.natureCode(), l.dropReason(), l.txNo(), l.payMethod(), finalAcct, accountHow));
                    continue;
                }
                finalLines.add(new BillCategoryResolver.Line(l.idx(), l.occurredAt(), l.merchant(),
                        l.amount(), newCat, categoryService.displayName(fam, newCat),
                        BillCategoryResolver.How.RULE, l.bucket(), null, null, l.txNo(),
                        l.payMethod(), finalAcct, accountHow));
                /* 「记住我的改动」—— 越用越准是这个功能的核心价值(FR-567)。
                 * 关键字由 merchantKeyword 提取(剥掉门店名/编号/企业后缀),
                 * 不是简单截前 20 字 —— 那样「瑞幸咖啡(国贸店)」换家店就不命中。 */
                if (remember) {
                    importService.rememberRule(fam,
                            BillImportService.merchantKeyword(l.merchant()), newCat);
                }
            }

            var r = commitService.commit(fam, me.getMemberId(), periodId, accountId,
                    draft.channel(), finalLines,
                    draft.count(BillCategoryResolver.Bucket.DROPPED) + userDropped,
                    draft.count(BillCategoryResolver.Bucket.SKIPPED), affectsBalance);
            session.removeAttribute(DRAFT_KEY);
            session.removeAttribute(DRAFT_PERIOD);
            ra.addFlashAttribute("flashOk", "导入了 " + r.rows() + " 笔 · 合计 ¥"
                    + r.amount().setScale(2, java.math.RoundingMode.HALF_UP)
                    + (r.dropped() > 0 ? " · 剔除 " + r.dropped() + " 笔" : "")
                    + (r.skipped() > 0 ? " · 跳过 " + r.skipped() + " 笔(上次已导)" : "")
                    + (affectsBalance
                        ? " · 已从「" + accountLabel(fam, accountId) + "」的余额里扣掉 —— 记得回填报页核对余额。"
                        : " · 没有动任何账户余额,只记了花在哪。"));
        } catch (BillCommitService.CommitException | BillImportService.ImportException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        } catch (Exception e) {
            /* 【把原因带出来】—— 原来这里只说「落库失败,再试一次」,
             * 用户再试一百次也还是失败(真实账单里一行冲正记录撞了 CHECK(amount>0)),
             * 而我这边也只能靠翻服务器日志才知道是什么。
             * 堆栈仍然不给用户看,但要给一句【能指导下一步】的话。 */
            log.warn("账单落库失败 · period={} · {}", periodId, e.toString(), e);
            ra.addFlashAttribute("flashError",
                    "落库失败,什么都没写进去。原因:" + humanCause(e)
                    + " —— 如果看不懂,把这句话发给我们。");
        }
        return "redirect:/expense/import?periodId=" + periodId;
    }

    @PostMapping("/expense/import/discard")
    public String discard(@RequestParam long periodId, HttpSession session) {
        session.removeAttribute(DRAFT_KEY);
        session.removeAttribute(DRAFT_PERIOD);
        return "redirect:/expense/import?periodId=" + periodId;
    }

    @PostMapping("/expense/import/acct-rule/delete")
    public String deleteAcctRule(@AuthenticationPrincipal MemberPrincipal me,
                                 @RequestParam long periodId, @RequestParam long ruleId) {
        acctRuleMapper.delete(me.getFamilyId(), ruleId);
        return "redirect:/expense/import?periodId=" + periodId;
    }

    /** 整批撤销(FR-539)· 软删该批次落的流水 + 把钱加回账户余额 */
    @PostMapping("/expense/import/revoke")
    public String revoke(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam long periodId, @RequestParam long batchId,
                         RedirectAttributes ra) {
        try {
            int n = commitService.revoke(me.getFamilyId(), me.getMemberId(), batchId);
            ra.addFlashAttribute("flashOk", "撤销了这一批 · " + n + " 笔已从账上移除,余额已加回。");
        } catch (BillCommitService.CommitException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/expense/import?periodId=" + periodId;
    }
}
