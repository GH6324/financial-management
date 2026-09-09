package com.family.finance.web.expense;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.expense.ExpenseSource;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.NavService;
import com.family.finance.service.expense.ExpenseCategoryService;
import com.family.finance.service.expense.ExpenseSplitService;
import com.family.finance.service.expense.imports.BillCategoryResolver;
import com.family.finance.service.expense.imports.BillImportService;
import com.family.finance.service.expense.imports.ExpenseShotClient;
import com.family.finance.service.expense.imports.ZipOpener;
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
 * v1.21 · 账单导入。三条通道(文件 / 截图 / 手抄)在这里汇成<b>同一个确认页</b>。
 *
 * <h3>草稿存 session,不落盘</h3>
 *
 * <p>账单是整月消费流水 —— 本项目迄今最敏感的输入。所以文件字节读完即弃,
 * 只把<b>聚合后的类目金额</b>放进 session 等用户确认;确认或离开即清。</p>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ExpenseImportController {

    private static final String DRAFT_KEY = "v121ImportDraft";
    private static final String DRAFT_PERIOD = "v121ImportPeriod";

    private final BillImportService importService;
    private final ExpenseShotClient shotClient;
    private final ExpenseSplitService splitService;
    private final ExpenseCategoryService categoryService;
    private final PeriodMapper periodMapper;
    private final NavService navService;
    private final AuditLogService auditLogService;
    private final com.family.finance.service.config.FamilyConfigService configService;

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
        model.addAttribute("shotAvailable", shotClient.available());
        model.addAttribute("shotReason", shotClient.unavailableReason());
        model.addAttribute("batches", splitService.batches(fam, period.getId()));
        model.addAttribute("rules", importService.rules(fam));
        /* 确认页的下拉给【整棵树】(大类 + 细类),不只是当前深度可填的那一层 ——
         * 渠道分类名本来就是大类粒度,用户也得能把它改回某个大类(落在大类上 = 未细分)。 */
        var pickable = categoryService.all(fam).stream().filter(c -> !c.isArchived()).toList();
        model.addAttribute("categories", pickable);
        // 商户规则那一栏要显示类目【名字】而不是 id —— 规则表里存的是 id
        Map<Long, String> catName = new LinkedHashMap<>();
        for (var c : categoryService.all(fam)) catName.put(c.getId(), c.getName());
        model.addAttribute("catName", catName);

        @SuppressWarnings("unchecked")
        var draft = (BillCategoryResolver.Draft) session.getAttribute(DRAFT_KEY);
        if (draft != null) {
            model.addAttribute("draft", draft);
            model.addAttribute("draftPeriodId", session.getAttribute(DRAFT_PERIOD));
        }
        return "expense/import";
    }

    /** 文件通道:csv 直传,或加密 zip + 密码 */
    @PostMapping("/expense/import/file")
    public String uploadFile(@AuthenticationPrincipal MemberPrincipal me,
                             @RequestParam long periodId,
                             @RequestParam String channel,
                             @RequestParam(required = false) String zipPassword,
                             @RequestParam("file") MultipartFile file,
                             HttpSession session, RedirectAttributes ra) {
        try {
            if (file == null || file.isEmpty()) throw new BillImportService.ImportException("先选一个文件。");
            var src = ExpenseSource.parse(channel);
            var draft = importService.parseFile(me.getFamilyId(), src,
                    file.getBytes(), file.getOriginalFilename(), zipPassword);
            session.setAttribute(DRAFT_KEY, draft);
            session.setAttribute(DRAFT_PERIOD, periodId);
        } catch (java.io.IOException e) {
            ra.addFlashAttribute("impError", "读这个文件时出错了,再传一次试试。");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("impError", human(e));
        }
        return "redirect:/expense/import?periodId=" + periodId;
    }

    /**
     * 截图通道:多张图,或一个装着图的 zip。
     *
     * <p>逐张送 qwen-vl 转写(只转写不算数),失败的那张<b>单独报</b>、其余照旧 ——
     * 一张糊了不该让整批白传。</p>
     */
    @PostMapping("/expense/import/shots")
    public String uploadShots(@AuthenticationPrincipal MemberPrincipal me,
                              @RequestParam long periodId,
                              @RequestParam("files") MultipartFile[] files,
                              HttpSession session, RedirectAttributes ra) {
        if (!shotClient.available()) {
            ra.addFlashAttribute("impError", "截图识别现在用不了:" + shotClient.unavailableReason());
            return "redirect:/expense/import?periodId=" + periodId;
        }
        List<ExpenseShotClient.ShotRow> all = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            List<byte[]> images = new ArrayList<>();
            List<String> mimes = new ArrayList<>();
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                byte[] b = f.getBytes();
                if (ZipOpener.looksLikeZip(b)) {
                    for (var e : ZipOpener.open(b, null, ".jpg", ".jpeg", ".png", ".webp")) {
                        images.add(e.bytes());
                        mimes.add(e.name().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg");
                    }
                } else {
                    images.add(b);
                    mimes.add(f.getContentType() == null ? "image/jpeg" : f.getContentType());
                }
            }
            if (images.isEmpty()) throw new BillImportService.ImportException("没找到图片。");
            if (images.size() > 20) {
                throw new BillImportService.ImportException("一次最多 20 张 —— 月度统计页通常两三张就够了。");
            }
            for (int i = 0; i < images.size(); i++) {
                try {
                    all.addAll(shotClient.extract(images.get(i), mimes.get(i)));
                } catch (RuntimeException ex) {
                    failed.add("第 " + (i + 1) + " 张");
                    log.warn("截图转写失败 · 第 {} 张 · {}", i + 1, ex.getClass().getSimpleName());
                }
            }
            if (all.isEmpty()) {
                throw new BillImportService.ImportException("这些图里没读出任何分类金额 —— "
                        + "要拍的是【月度收支统计页】(支付宝「月账单」/ 微信账单的「统计」/ 银行 App 的收支统计),"
                        + "不是流水明细页。");
            }
            var draft = importService.toDraft(me.getFamilyId(), ExpenseSource.SHOT,
                    ExpenseShotClient.toParsed(all));
            session.setAttribute(DRAFT_KEY, draft);
            session.setAttribute(DRAFT_PERIOD, periodId);
            if (!failed.isEmpty()) {
                ra.addFlashAttribute("impNote", String.join("、", failed) + " 没认出来,其余已解析 —— "
                        + "确认页上核对一下,少的手工补。");
            }
        } catch (java.io.IOException e) {
            ra.addFlashAttribute("impError", "读图片时出错了,再传一次试试。");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("impError", human(e));
        }
        return "redirect:/expense/import?periodId=" + periodId;
    }

    /**
     * 确认落账。
     *
     * <p>参数 {@code map_{原名}={类目id}} 允许用户当场改映射;勾了 {@code remember_{原名}}
     * 就把它记成商户规则,下次自动命中。</p>
     */
    @PostMapping("/expense/import/confirm")
    public String confirm(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam long periodId,
                          jakarta.servlet.http.HttpServletRequest req,
                          HttpSession session, RedirectAttributes ra) {
        var draft = (BillCategoryResolver.Draft) session.getAttribute(DRAFT_KEY);
        if (draft == null) {
            ra.addFlashAttribute("impError", "这份草稿已经过期了,重新传一次文件。");
            return "redirect:/expense/import?periodId=" + periodId;
        }
        try {
            // 用户改过的映射:按「渠道原名 → 类目」重算聚合
            Map<Long, BigDecimal> byCategory = new LinkedHashMap<>();
            for (var line : draft.lines()) {
                String override = first(req.getParameterValues("map_" + line.channelLabel()));
                long target = line.categoryId();
                if (override != null && !override.isBlank()) {
                    try { target = Long.parseLong(override.trim()); } catch (NumberFormatException ignore) { }
                }
                byCategory.merge(target, line.amount(), BigDecimal::add);
                if (req.getParameter("remember_" + line.channelLabel()) != null && target != line.categoryId()) {
                    importService.rememberRule(me.getFamilyId(), line.channelLabel(), target);
                }
            }
            var res = splitService.applyBatch(me.getFamilyId(), periodId, me.getMemberId(),
                    me.getMemberId(), draft.channel(), byCategory, draft.rowCount());
            auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.SYSTEM,
                    "expense_import", res.batchId(),
                    "导入 " + draft.channel().getLabel() + " 账单 · " + draft.rowCount() + " 笔");
            session.removeAttribute(DRAFT_KEY);
            session.removeAttribute(DRAFT_PERIOD);
            ra.addFlashAttribute("impNote", "已导入 " + draft.channel().getLabel() + " 的 "
                    + draft.rowCount() + " 笔支出"
                    + (res.replacedId() == null ? "" : "(替换了这个渠道上一批数据)")
                    + " —— 去填报页核对一下合计。");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("impError", human(e));
        }
        return "redirect:/expense/import?periodId=" + periodId;
    }

    @PostMapping("/expense/import/discard")
    public String discard(@RequestParam long periodId, HttpSession session, RedirectAttributes ra) {
        session.removeAttribute(DRAFT_KEY);
        session.removeAttribute(DRAFT_PERIOD);
        ra.addFlashAttribute("impNote", "扔掉了,什么都没落账。");
        return "redirect:/expense/import?periodId=" + periodId;
    }

    /** 撤销某个批次:该渠道的行整批移除,其余来源自动回落 */
    @PostMapping("/expense/import/revoke")
    public String revoke(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam long periodId, @RequestParam long batchId,
                         RedirectAttributes ra) {
        try {
            splitService.revokeBatch(me.getFamilyId(), batchId);
            ra.addFlashAttribute("impNote", "撤掉了 —— 手工填的和别的渠道一分没动。");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("impError", human(e));
        }
        return "redirect:/expense/import?periodId=" + periodId;
    }

    @PostMapping("/expense/import/rule/delete")
    public String deleteRule(@AuthenticationPrincipal MemberPrincipal me,
                             @RequestParam long periodId, @RequestParam long ruleId,
                             RedirectAttributes ra) {
        importService.deleteRule(me.getFamilyId(), ruleId);
        ra.addFlashAttribute("impNote", "规则删了。已经导进去的数据不受影响。");
        return "redirect:/expense/import?periodId=" + periodId;
    }

    // ──────────────────────── 小工具 ────────────────────────

    /** 录入深度 —— 家庭级(树是共享的,一家两种深度会让报表下钻语义分裂) */
    private boolean deep(long fam) {
        return "L2".equalsIgnoreCase(configService.getString(
                fam, com.family.finance.service.config.FamilyConfigService.K_EXPENSE_SPLIT_DEPTH, "L1"));
    }

    private com.family.finance.domain.period.Period resolvePeriod(long fam, Long periodId) {
        if (periodId != null) {
            var p = periodMapper.findById(periodId).orElse(null);
            // 必须校家庭 —— 否则改一下 URL 的 periodId 就能往别人家的账期里导数据
            if (p != null && p.getFamilyId() != null && p.getFamilyId() == fam) return p;
        }
        return periodMapper.findCurrentOpen(fam)
                .or(() -> periodMapper.findLatest(fam, 1).stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("还没有账期"));
    }

    private static String first(String[] a) { return a == null || a.length == 0 ? null : a[0]; }

    private static String human(RuntimeException e) {
        if (e instanceof BillImportService.ImportException
                || e instanceof ZipOpener.ZipException
                || e instanceof ExpenseSplitService.SplitException
                || e instanceof ExpenseCategoryService.CategoryException) {
            return e.getMessage();
        }
        log.warn("账单导入失败(不记内容)", e);
        return "没成功。再试一次,如果还不行把这一步告诉我们。";
    }
}
