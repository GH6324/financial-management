package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.allocation.AnchorCode;
import com.family.finance.domain.allocation.RiskAppetite;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.AllocationAnchorMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.RebalanceAdviceCacheMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.FamilyService;
import com.family.finance.service.NavService;
import com.family.finance.service.allocation.AllocationService;
import com.family.finance.service.analysis.AnalysisPreferenceService;
import com.family.finance.service.analysis.AnalysisPromptBlocks;
import com.family.finance.service.analysis.AnalysisScopeService;
import com.family.finance.service.analysis.AnalysisTemplate;
import com.family.finance.service.analysis.AnalysisTemplateService;
import com.family.finance.service.analysis.BuiltinTemplates;
import com.family.finance.service.analysis.ScopeKind;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.web.analysis.AnalysisUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.27 · 管理 → 分析设置(PRD §3.7 · FR-890 ~ FR-892)。
 *
 * <p>五块都在回答「怎么分析我家」:① 默认分析范围 ② 分析模板 ③ 不参与配置分析的账户 ④ 配置锚(含自定义)⑤ 分析偏好。
 * 按 L9「按用户要完成的事分入口」新开一页,不塞进家庭设置(那页管的是「家里有谁、叫什么」· PRD §13 ⑫)。</p>
 *
 * <p>模板定制页也在这里(FR-882 ~ FR-884):从体检 / 报表 / 超级 Agent 点进来,带 {@code back};
 * 保存后回到原页面并换上新模板 —— 那边的 AI 区块按新模板当场重跑。回跳只收站内几页的相对路径。</p>
 */
@Controller
@RequestMapping("/admin/analysis")
@RequiredArgsConstructor
public class AnalysisSettingsController {

    private final NavService navService;
    private final AnalysisScopeService scopeService;
    private final AnalysisTemplateService templateService;
    private final AnalysisPreferenceService preferenceService;
    private final AllocationService allocationService;
    private final AllocationAnchorMapper anchorMapper;
    private final AccountMapper accountMapper;
    private final FamilyMapper familyMapper;
    private final FamilyService familyService;
    private final RebalanceAdviceCacheMapper rebalanceCacheMapper;
    private final MemberDirectory memberDirectory;
    private final AuditLogService auditLogService;

    @GetMapping
    public String page(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam(name = "back", required = false) String back,
                       Model model) {
        long fid = me.getFamilyId();
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("back", AnalysisUrls.safeBack(back));

        // ① 默认范围(只列与「全部资产」有区别的;只有一个时这一节只说明现状)
        model.addAttribute("scopeOptions", scopeService.options(fid, null));
        model.addAttribute("scopeDefault", scopeService.familyDefault(fid));

        // ② 模板
        model.addAttribute("templates", templateService.list(fid));
        model.addAttribute("templateDefaultKey", templateService.familyDefault(fid).key());
        model.addAttribute("customCount", templateService.list(fid).stream().filter(AnalysisTemplate::isCustom).count());
        model.addAttribute("maxCustom", AnalysisTemplateService.MAX_CUSTOM);
        var family = familyService.require(fid);
        model.addAttribute("riskAppetite", family.getRiskAppetite());
        model.addAttribute("riskAppetites", RiskAppetite.values());

        // ③ 不参与配置分析的账户 + 可以标的候选(房产类、其他类还没标的)
        List<Account> active = accountMapper.findActiveByFamily(fid);
        model.addAttribute("markedAccounts", active.stream()
                .filter(a -> a.isAnalysisExcluded() && a.getType() != AccountType.LOAN).toList());
        model.addAttribute("candidateAccounts", active.stream()
                .filter(a -> !a.isAnalysisExcluded()
                        && (a.getType() == AccountType.PROPERTY || a.getType() == AccountType.OTHER)).toList());

        // ④ 配置锚
        model.addAttribute("anchors", anchorMapper.findAll());
        model.addAttribute("anchorCurrent", family.getAllocationAnchor() == null ? "SP_4321" : family.getAllocationAnchor());
        Map<String, BigDecimal> custom = allocationService.customAnchor(fid);
        model.addAttribute("customAnchor", custom);
        model.addAttribute("customFilled", !custom.isEmpty());

        // ⑤ 偏好(谁在什么时候加的)
        model.addAttribute("preferences", preferenceService.list(fid));
        model.addAttribute("memberNames", memberNames(fid));
        model.addAttribute("prefMax", AnalysisPreferenceService.MAX_COUNT);
        model.addAttribute("prefMaxChars", AnalysisPreferenceService.MAX_CHARS);
        return "admin/analysis";
    }

    // ───────────────────────── ① 默认范围 ─────────────────────────

    @PostMapping("/scope")
    public String setScope(@AuthenticationPrincipal MemberPrincipal me, @RequestParam("scope") String scope,
                           RedirectAttributes ra) {
        ScopeKind k = ScopeKind.parse(scope);
        if (k != null) {
            scopeService.setFamilyDefault(me.getFamilyId(), k);
            audit(me, "默认分析范围 → " + k.getLabel());
            ra.addFlashAttribute("flash", "家里的默认分析范围改成了「" + k.getLabel() + "」");
        }
        return "redirect:/admin/analysis#scope";
    }

    // ───────────────────────── ② 模板 ─────────────────────────

    @PostMapping("/template/default")
    public String setDefaultTemplate(@AuthenticationPrincipal MemberPrincipal me, @RequestParam("key") String key,
                                     RedirectAttributes ra) {
        try {
            templateService.setFamilyDefault(me.getFamilyId(), key);
            String name = templateService.find(me.getFamilyId(), key).map(AnalysisTemplate::name).orElse(key);
            audit(me, "默认分析模板 → " + name);
            ra.addFlashAttribute("flash", "家里的默认模板改成了「" + name + "」");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/analysis#templates";
    }

    @PostMapping("/template/{id}/delete")
    public String deleteTemplate(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id,
                                 RedirectAttributes ra) {
        String name = templateService.find(me.getFamilyId(), BuiltinTemplates.CUSTOM_PREFIX + id)
                .map(AnalysisTemplate::name).orElse(null);
        if (name != null) {
            templateService.delete(me.getFamilyId(), id);
            audit(me, "删除我的模板「" + name + "」");
            ra.addFlashAttribute("flash", "删掉了「" + name + "」");
        }
        return "redirect:/admin/analysis#templates";
    }

    /** 定制页:基于某个模板(FR-843 · 预填它的全部设置) */
    @GetMapping("/template/new")
    public String newTemplate(@AuthenticationPrincipal MemberPrincipal me,
                              @RequestParam(name = "from", required = false) String from,
                              @RequestParam(name = "back", required = false) String back,
                              Model model) {
        AnalysisTemplate source = templateService.find(me.getFamilyId(), from).orElse(BuiltinTemplates.general());
        addEditorModel(me, model, source, null, AnalysisTemplateService.draftFrom(source), back);
        return "admin/analysis-template";
    }

    @GetMapping("/template/{id}/edit")
    public String editTemplate(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id,
                               @RequestParam(name = "back", required = false) String back,
                               Model model) {
        AnalysisTemplate t = templateService.find(me.getFamilyId(), BuiltinTemplates.CUSTOM_PREFIX + id)
                .orElse(null);
        if (t == null) return "redirect:/admin/analysis#templates";
        AnalysisTemplate source = BuiltinTemplates.find(t.sourceKey()).orElse(null);
        addEditorModel(me, model, source, t, AnalysisTemplateService.draftFrom(t), back);
        return "admin/analysis-template";
    }

    /** 保存定制(新建或编辑)→ 回到来处并换上这个模板(FR-882 · 那边的 AI 区块按新模板当场重跑) */
    @PostMapping("/template")
    public String saveTemplate(@AuthenticationPrincipal MemberPrincipal me,
                               @RequestParam(name = "id", required = false) Long id,
                               @RequestParam(name = "from", required = false) String from,
                               @RequestParam(name = "name", required = false) String name,
                               @RequestParam(name = "focus", required = false) List<String> focus,
                               @RequestParam(name = "stance", required = false) String stance,
                               @RequestParam(name = "scope", required = false) String scope,
                               @RequestParam(name = "anchor", required = false) String anchor,
                               @RequestParam(name = "extra", required = false) String extra,
                               @RequestParam(name = "back", required = false) String back,
                               RedirectAttributes ra) {
        long fid = me.getFamilyId();
        var draft = new AnalysisTemplateService.Draft(name,
                AnalysisTemplate.Focus.parseCsv(focus == null ? "" : String.join(",", focus)),
                AnalysisTemplate.Stance.parse(stance), ScopeKind.parse(scope), anchor, extra);
        AnalysisTemplate saved;
        try {
            if (id != null) {
                saved = templateService.update(fid, me.getMemberId(), id, draft);
                audit(me, "改我的模板「" + saved.name() + "」");
            } else {
                AnalysisTemplate source = templateService.find(fid, from).orElse(BuiltinTemplates.general());
                saved = templateService.customize(fid, me.getMemberId(), source, draft);
                audit(me, "基于「" + source.name() + "」定制了「" + saved.name() + "」");
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            ra.addFlashAttribute("draftName", name);
            ra.addFlashAttribute("draftExtra", extra);
            String safe = AnalysisUrls.safeBack(back);
            String again = id != null ? "/admin/analysis/template/" + id + "/edit" : "/admin/analysis/template/new";
            return "redirect:" + org.springframework.web.util.UriComponentsBuilder.fromPath(again)
                    .queryParamIfPresent("from", java.util.Optional.ofNullable(id == null ? from : null))
                    .queryParamIfPresent("back", java.util.Optional.ofNullable(safe))
                    .build().encode().toUriString();
        }
        String to = AnalysisUrls.withTemplate(back, saved.key());
        if (to == null) {
            ra.addFlashAttribute("flash", "存好了「" + saved.name() + "」· 可以设为家里的默认,或在体检 / 报表里临时换用");
            return "redirect:/admin/analysis#templates";
        }
        // 体检页的两个 AI 区块一打开就按新模板重跑;报表的调仓是按钮触发的,说清楚下一步
        ra.addFlashAttribute("templateFlash", to.startsWith("/reports")
                ? "已换上「" + saved.name() + "」· 点「让 AI 给出具体调仓步骤」就按它来"
                : "已按「" + saved.name() + "」重新分析");
        return "redirect:" + to;
    }

    /** 定制页右侧「AI 会收到的要求」(FR-883)—— 与真正发出去的同一份({@link AnalysisPromptBlocks#templatePreview}) */
    @GetMapping(value = "/template/preview", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String preview(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(name = "name", required = false) String name,
                          @RequestParam(name = "from", required = false) String from,
                          @RequestParam(name = "focus", required = false) List<String> focus,
                          @RequestParam(name = "stance", required = false) String stance,
                          @RequestParam(name = "extra", required = false) String extra) {
        String n = name == null || name.isBlank() ? "我的模板" : name.strip();
        var t = new AnalysisTemplate(BuiltinTemplates.CUSTOM_PREFIX + "0", n, false, null,
                AnalysisTemplate.Focus.parseCsv(focus == null ? "" : String.join(",", focus)),
                AnalysisTemplate.Stance.parse(stance), null, null,
                extra == null || extra.isBlank() ? null : extra.strip(), 1, from, null, null, null);
        String sourceName = BuiltinTemplates.find(from).map(AnalysisTemplate::name).orElse(null);
        String stanceLabel = AnalysisPromptBlocks.riskAppetiteLabel(
                familyService.require(me.getFamilyId()).getRiskAppetite());
        return AnalysisPromptBlocks.templatePreview(t, sourceName, stanceLabel);
    }

    /** 立场「跟随家里的风险偏好」按这个(v0.4 起就有这一列、有接口,一直没有页面能改) */
    @PostMapping("/risk-appetite")
    public String setRiskAppetite(@AuthenticationPrincipal MemberPrincipal me,
                                  @RequestParam("appetite") String appetite, RedirectAttributes ra) {
        if (RiskAppetite.isValid(appetite)) {
            familyMapper.updateRiskAppetite(me.getFamilyId(), appetite.toUpperCase());
            rebalanceCacheMapper.deleteByFamily(me.getFamilyId());
            audit(me, "家里的风险偏好 → " + AnalysisPromptBlocks.riskAppetiteLabel(appetite));
            ra.addFlashAttribute("flash", "家里的风险偏好改成了「" + AnalysisPromptBlocks.riskAppetiteLabel(appetite) + "」");
        }
        return "redirect:/admin/analysis#templates";
    }

    // ───────────────────────── ④ 配置锚 ─────────────────────────

    @PostMapping("/anchor")
    public String setAnchor(@AuthenticationPrincipal MemberPrincipal me, @RequestParam("anchor") String anchor,
                            RedirectAttributes ra) {
        if (AnchorCode.isValid(anchor)) {
            familyMapper.updateAllocationAnchor(me.getFamilyId(), anchor.toUpperCase());
            rebalanceCacheMapper.deleteByFamily(me.getFamilyId());   // 切锚 = 调仓缓存失效(与报表下拉同一规矩)
            audit(me, "配置锚 → " + anchor.toUpperCase());
            ra.addFlashAttribute("flash", "CUSTOM".equalsIgnoreCase(anchor)
                    ? "选了自定义 —— 在下面填四个目标(合计 100)" : "配置锚已切换");
        }
        return "redirect:/admin/analysis#anchor";
    }

    /** FR-871 · 自定义锚:四个目标,合计必须 100 */
    @PostMapping("/anchor/custom")
    public String saveCustomAnchor(@AuthenticationPrincipal MemberPrincipal me,
                                   @RequestParam(name = "cash", required = false) BigDecimal cash,
                                   @RequestParam(name = "invest", required = false) BigDecimal invest,
                                   @RequestParam(name = "property", required = false) BigDecimal property,
                                   @RequestParam(name = "insurance", required = false) BigDecimal insurance,
                                   @RequestParam(name = "back", required = false) String back,
                                   RedirectAttributes ra) {
        try {
            allocationService.saveCustomAnchor(me.getFamilyId(), nz(cash), nz(invest), nz(property), nz(insurance));
            rebalanceCacheMapper.deleteByFamily(me.getFamilyId());
            audit(me, "自定义配置锚 → 现金 " + plain(cash) + " / 投资 " + plain(invest)
                    + " / 房产 " + plain(property) + " / 保险 " + plain(insurance));
            ra.addFlashAttribute("flash", "自定义配置锚存好了");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/admin/analysis" + (AnalysisUrls.safeBack(back) == null ? "" : "?back="
                    + java.net.URLEncoder.encode(back, java.nio.charset.StandardCharsets.UTF_8)) + "#anchor";
        }
        String to = AnalysisUrls.safeBack(back);
        return "redirect:" + (to != null ? to : "/admin/analysis#anchor");
    }

    // ───────────────────────── ⑤ 分析偏好 ─────────────────────────

    @PostMapping("/preferences")
    public String addPreference(@AuthenticationPrincipal MemberPrincipal me,
                                @RequestParam("content") String content, RedirectAttributes ra) {
        try {
            preferenceService.add(me.getFamilyId(), me.getMemberId(), content, AnalysisPreferenceService.SOURCE_SETTINGS);
            audit(me, "加了一条分析偏好");
            ra.addFlashAttribute("flash", "记下了 · 下一次 AI 分析就会参考它");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/analysis#preferences";
    }

    @PostMapping("/preferences/{id}/toggle")
    public String togglePreference(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id,
                                   @RequestParam("enabled") boolean enabled) {
        preferenceService.setEnabled(me.getFamilyId(), id, enabled);
        audit(me, (enabled ? "启用" : "停用") + "了一条分析偏好");
        return "redirect:/admin/analysis#preferences";
    }

    @PostMapping("/preferences/{id}/delete")
    public String deletePreference(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id) {
        preferenceService.delete(me.getFamilyId(), id);
        audit(me, "删了一条分析偏好");
        return "redirect:/admin/analysis#preferences";
    }

    // ───────────────────────── 内部 ─────────────────────────

    private void addEditorModel(MemberPrincipal me, Model model, AnalysisTemplate source, AnalysisTemplate editing,
                                AnalysisTemplateService.Draft draft, String back) {
        long fid = me.getFamilyId();
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("source", source);
        model.addAttribute("editing", editing);
        model.addAttribute("draft", draft);
        model.addAttribute("focusAll", AnalysisTemplate.Focus.values());
        model.addAttribute("stanceAll", AnalysisTemplate.Stance.values());
        model.addAttribute("scopeAll", ScopeKind.values());
        model.addAttribute("anchors", anchorMapper.findAll());
        model.addAttribute("scopeDefault", scopeService.familyDefault(fid));
        var family = familyService.require(fid);
        model.addAttribute("anchorFamily", family.getAllocationAnchor());
        model.addAttribute("riskAppetiteLabel", AnalysisPromptBlocks.riskAppetiteLabel(family.getRiskAppetite()));
        model.addAttribute("back", AnalysisUrls.safeBack(back));
        model.addAttribute("maxExtra", AnalysisTemplate.MAX_EXTRA_CHARS);
        String sourceName = source == null ? null : source.name();
        AnalysisTemplate previewT = new AnalysisTemplate(BuiltinTemplates.CUSTOM_PREFIX + "0", draft.name(), false, null,
                draft.focus(), draft.stance(), null, null, draft.extra(), 1,
                source == null ? null : source.key(), null, null, null);
        model.addAttribute("previewText", AnalysisPromptBlocks.templatePreview(previewT,
                source != null && source.builtin() ? sourceName : null,
                AnalysisPromptBlocks.riskAppetiteLabel(family.getRiskAppetite())));
        model.addAttribute("customCount", templateService.list(fid).stream().filter(AnalysisTemplate::isCustom).count());
        model.addAttribute("maxCustom", AnalysisTemplateService.MAX_CUSTOM);
    }

    private Map<Long, String> memberNames(long fid) {
        Map<Long, String> m = new LinkedHashMap<>();
        for (var mem : memberDirectory.listAll(fid)) m.put(mem.getId(), mem.getDisplayName());
        return m;
    }

    private void audit(MemberPrincipal me, String summary) {
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.ANALYSIS_SETTINGS,
                "analysis", null, summary);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String plain(BigDecimal v) { return v == null ? "0" : v.stripTrailingZeros().toPlainString(); }
}
