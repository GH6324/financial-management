package com.family.finance.web.ask;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.analysis.AnalysisPreferenceService;
import com.family.finance.service.analysis.AnalysisTemplateService;
import com.family.finance.service.analysis.BuiltinTemplates;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * v1.27 · 超级 Agent 确认卡的三个动作(PRD FR-854 / FR-849)。
 *
 * <p><b>只有这里能把偏好存下来,而且只能是人点的</b>:走站内会话 + CSRF;
 * {@code /mcp} 与 {@code /api/v1/ask/**}(只读口令)没有任何写入口(FR-855 · 护栏 v127-NO-WRITE-TOOLS)。
 * 返回的都是片段:前端把整张卡换成「已记住 · 在分析设置里管理 →」这一行。</p>
 */
@Controller
@RequiredArgsConstructor
public class AskRememberController {

    private final AskRememberView rememberView;
    private final AnalysisPreferenceService preferenceService;
    private final AnalysisTemplateService templateService;
    private final AuditLogService auditLogService;

    /** 刚流完的那条回答里有标记:前端把标记里那段交过来,换一张服务端渲染的卡(和历史消息同一个片段) */
    @GetMapping("/ask/remember/card")
    public String card(@AuthenticationPrincipal MemberPrincipal me, @RequestParam("m") String inner, Model model) {
        model.addAttribute("rc", rememberView.ofInner(me.getFamilyId(), inner));
        return "ask/fragments/_remember :: card";
    }

    /** 「记住」—— 原文用户可以改了再记 */
    @PostMapping("/ask/remember")
    public String remember(@AuthenticationPrincipal MemberPrincipal me, @RequestParam("text") String text, Model model) {
        try {
            preferenceService.add(me.getFamilyId(), me.getMemberId(), text, AnalysisPreferenceService.SOURCE_AGENT);
            auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.ANALYSIS_SETTINGS,
                    "analysis", null, "在超级 Agent 对话里记住了一条分析偏好");
            model.addAttribute("doneText", "已记住 · 以后所有 AI 分析都会参考它");
        } catch (IllegalArgumentException e) {
            model.addAttribute("doneError", e.getMessage());
        }
        return "ask/fragments/_remember :: done";
    }

    /** 「切到「稳健守护」」—— 把家里的默认模板换成这个内置模板 */
    @PostMapping("/ask/remember/template")
    public String switchTemplate(@AuthenticationPrincipal MemberPrincipal me, @RequestParam("key") String key, Model model) {
        var t = BuiltinTemplates.find(key).orElse(null);
        if (t == null) {
            model.addAttribute("doneError", "没有这个模板");
        } else {
            templateService.setFamilyDefault(me.getFamilyId(), t.key());
            auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.ANALYSIS_SETTINGS,
                    "analysis", null, "在超级 Agent 对话里把默认分析模板切到「" + t.name() + "」");
            model.addAttribute("doneText", "已切到「" + t.name() + "」· 以后 AI 分析默认按它");
        }
        return "ask/fragments/_remember :: done";
    }
}
