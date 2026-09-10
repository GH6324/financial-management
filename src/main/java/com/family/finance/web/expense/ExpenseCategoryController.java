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
        model.addAttribute("starter", ExpenseCategoryService.STARTER);
        return "expense/categories";
    }

    /**
     * 一键起步:{@code deep=true} 连细类一起建。
     *
     * <p>第 2 稿里这<b>不是</b>一个「以后按哪层填」的设置 —— 它只影响这一次建了什么。
     * 录入时点大类就能提交,想细分再多点一下细类(FR-552)。</p>
     */
    @PostMapping("/expense/categories/seed")
    public String seed(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam(defaultValue = "false") boolean deep,
                       RedirectAttributes ra) {
        try {
            categoryService.seed(me.getFamilyId(), deep);
            audit(me, "用" + (deep ? "带细类的" : "只有大类的") + "起步包建了支出类目");
            ra.addFlashAttribute("catNote", deep
                    ? "建好了。记一笔时点大类就能提交,想细分再多点一下细类 —— 不强制。"
                    : "建好了。以后想细分,随时给某个大类「加细类」即可,历史一分不丢。");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("catError", human(e));
        }
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
