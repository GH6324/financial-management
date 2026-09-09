package com.family.finance.web.expense;

import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.auth.MemberPrincipal;
import com.family.finance.service.NavService;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.expense.ExpenseCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * v1.21 · 支出类目管理。
 *
 * <p>形态照 v1.20 分组页二次验收后的结论走:<b>主页面就是一个列表</b>,
 * 新建走弹窗;起步包只在一片空白时出现(它是「开头的快捷方式」,不是常驻区块)。</p>
 */
@Controller
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class ExpenseCategoryController {

    private final ExpenseCategoryService categoryService;
    private final FamilyConfigService configService;
    private final NavService navService;
    private final AuditLogService auditLogService;

    @GetMapping("/expense/categories")
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        long fam = me.getFamilyId();
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("tree", categoryService.tree(fam));
        model.addAttribute("total", categoryService.all(fam).size());
        model.addAttribute("maxTotal", ExpenseCategoryService.MAX_TOTAL);
        model.addAttribute("deep", "L2".equalsIgnoreCase(
                configService.getString(fam, FamilyConfigService.K_EXPENSE_SPLIT_DEPTH, "L1")));
        model.addAttribute("starter", ExpenseCategoryService.STARTER);
        return "expense/categories";
    }

    /** 一键起步:{@code deep=true} 连细类一起建。两个深度的<b>一级完全相同</b>。 */
    @PostMapping("/expense/categories/seed")
    public String seed(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam(defaultValue = "false") boolean deep,
                       RedirectAttributes ra) {
        try {
            categoryService.seed(me.getFamilyId(), deep);
            configService.set(me.getFamilyId(), FamilyConfigService.K_EXPENSE_SPLIT_DEPTH, deep ? "L2" : "L1");
            audit(me, "用" + (deep ? "复杂版" : "简单版") + "起步包建了支出类目");
            ra.addFlashAttribute("catNote", deep
                    ? "建好了(复杂版)。想换回按大类填,右上角切一下就行 —— 历史数据一分不丢。"
                    : "建好了(简单版)。以后想细分,右上角切成「按细类填」即可 —— 大类不变,历史无损。");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("catError", human(e));
        }
        return "redirect:/expense/categories";
    }

    /**
     * 切换录入深度。
     *
     * <p><b>只改「以后按哪一层填」,历史行一行不动</b> —— 因为简单版与复杂版是同一棵树的两个深度,
     * 报表始终按大类聚合,所以两个深度下大类合计逐分相等。</p>
     */
    @PostMapping("/expense/categories/depth")
    public String depth(@AuthenticationPrincipal MemberPrincipal me,
                        @RequestParam String depth, RedirectAttributes ra) {
        boolean toDeep = "L2".equalsIgnoreCase(depth);
        configService.set(me.getFamilyId(), FamilyConfigService.K_EXPENSE_SPLIT_DEPTH, toDeep ? "L2" : "L1");
        audit(me, "支出录入深度切成" + (toDeep ? "细类" : "大类"));
        ra.addFlashAttribute("catNote", toDeep
                ? "以后按细类填。已经记在大类上的钱在报表里显示为「未细分」—— 一分没丢,只是还没细分过。"
                : "以后按大类填。已经填过的细类明细【原样留着】,报表按大类聚合 —— 合计一分不变。");
        return "redirect:/expense/categories";
    }

    @PostMapping("/expense/categories/create")
    public String create(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam(required = false) Long parentId,
                         @RequestParam(required = false) String name,
                         RedirectAttributes ra) {
        try {
            var c = categoryService.create(me.getFamilyId(), parentId, name);
            audit(me, "新增支出类目「" + c.getName() + "」");
            ra.addFlashAttribute("catNote", "已加「" + c.getName() + "」。");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("catError", human(e));
        }
        return "redirect:/expense/categories";
    }

    @PostMapping("/expense/categories/{id}/rename")
    public String rename(@AuthenticationPrincipal MemberPrincipal me,
                         @PathVariable long id,
                         @RequestParam(required = false) String name,
                         RedirectAttributes ra) {
        try {
            categoryService.rename(me.getFamilyId(), id, name);
            ra.addFlashAttribute("catNote", "改好了 —— 历史数据跟着新名字显示,一分钱都没动。");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("catError", human(e));
        }
        return "redirect:/expense/categories";
    }

    @PostMapping("/expense/categories/{id}/archive")
    public String archive(@AuthenticationPrincipal MemberPrincipal me,
                          @PathVariable long id,
                          @RequestParam(defaultValue = "true") boolean archived,
                          RedirectAttributes ra) {
        try {
            categoryService.setArchived(me.getFamilyId(), id, archived);
            ra.addFlashAttribute("catNote", archived
                    ? "停用了。新月份不再出现它,历史月份照旧显示。"
                    : "恢复了。");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("catError", human(e));
        }
        return "redirect:/expense/categories";
    }

    /** 删除确认页 —— 先说清会动多少期数据、搬到哪(FR-505) */
    @GetMapping("/expense/categories/{id}/delete")
    public String confirmDelete(@AuthenticationPrincipal MemberPrincipal me,
                                @PathVariable long id,
                                Model model, RedirectAttributes ra) {
        try {
            model.addAttribute("me", me);
            model.addAttribute("nav", navService.load(me));
            model.addAttribute("impact", categoryService.previewDelete(me.getFamilyId(), id));
            model.addAttribute("catId", id);
            return "expense/category-delete";
        } catch (RuntimeException e) {
            ra.addFlashAttribute("catError", human(e));
            return "redirect:/expense/categories";
        }
    }

    @PostMapping("/expense/categories/{id}/delete")
    public String delete(@AuthenticationPrincipal MemberPrincipal me,
                         @PathVariable long id,
                         RedirectAttributes ra) {
        try {
            var impact = categoryService.previewDelete(me.getFamilyId(), id);
            categoryService.delete(me.getFamilyId(), id);
            audit(me, "删除支出类目「" + impact.name() + "」,数据转入「" + impact.targetName() + "」");
            ra.addFlashAttribute("catNote", "删了「" + impact.name() + "」,它名下的钱转到「"
                    + impact.targetName() + "」—— 合计一分不变。");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("catError", human(e));
        }
        return "redirect:/expense/categories";
    }

    private void audit(MemberPrincipal me, String what) {
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.SYSTEM,
                "expense_category", null, what);
    }

    /** 业务异常给人话,其余兜底 —— 不让 DB 约束冒成 500(v1.20 的教训) */
    private static String human(RuntimeException e) {
        if (e instanceof ExpenseCategoryService.CategoryException) return e.getMessage();
        /* 非业务异常:给用户一句兜底,但【日志里留真相】——
         * 不然出问题时页面只有「没成功」,排查无从下手(v1.19 百炼那条教训的同一形状)。 */
        log.warn("支出类目操作失败", e);
        return "没成功。刷新一下再试。";
    }
}
