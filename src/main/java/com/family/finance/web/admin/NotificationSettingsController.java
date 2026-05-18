package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.family.ReportingTemplate;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.notify.FamilyNotifyConfig;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.FamilyNotifyConfigMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.notify.ReportReminderScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * v0.4.14 FR-63d · /admin/reminders 提醒设置页(填报模板 + 提前天数 + 短信渠道 + 成员手机号)。
 *
 * <p>私密红线:sms aksk / 成员 phone 仅在本控制器 ↔ DB ↔ 短信渠道之间流转,
 * 绝不进 PromptBuilder / 任何 LLM prompt / audit_log 明文。页面回显一律掩码,
 * 表单留空 = 保持原值(不会被空串覆盖)。
 */
@Controller
@RequestMapping("/admin/reminders")
@RequiredArgsConstructor
public class NotificationSettingsController {

    private final FamilyMapper familyMapper;
    private final MemberMapper memberMapper;
    private final FamilyNotifyConfigMapper notifyConfigMapper;
    private final AuditLogService auditLogService;
    private final ReportReminderScheduler reminderScheduler;

    @GetMapping
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        Family family = familyMapper.findById(me.getFamilyId())
                .orElseThrow(() -> new IllegalArgumentException("家庭不存在"));
        FamilyNotifyConfig cfg = notifyConfigMapper.findByFamily(me.getFamilyId())
                .orElseGet(() -> FamilyNotifyConfig.builder()
                        .familyId(me.getFamilyId())
                        .smsEnabled(false)
                        .smsProvider("aliyun")
                        .build());

        model.addAttribute("family", family);
        model.addAttribute("templates", ReportingTemplate.values());
        model.addAttribute("currentTemplate",
                ReportingTemplate.fromCode(family.getReportingTemplate()));
        model.addAttribute("leadDays",
                family.getReportRemindLeadDays() == null ? 2 : family.getReportRemindLeadDays());
        model.addAttribute("cfg", cfg);
        model.addAttribute("akIdMasked", mask(cfg.getSmsAccessKeyId()));
        model.addAttribute("akSecretSet",
                cfg.getSmsAccessKeySecret() != null && !cfg.getSmsAccessKeySecret().isBlank());
        model.addAttribute("members", memberMapper.findActiveByFamily(me.getFamilyId()));
        return "admin/notification";
    }

    /** 填报模板 + 提醒提前天数 */
    @PostMapping("/template")
    public String saveTemplate(@AuthenticationPrincipal MemberPrincipal me,
                               @RequestParam("template") String template,
                               @RequestParam("leadDays") int leadDays,
                               RedirectAttributes ra) {
        ReportingTemplate t = ReportingTemplate.fromCode(template);
        int days = Math.max(0, Math.min(leadDays, 15));
        familyMapper.updateReportingTemplate(me.getFamilyId(), t.name());
        familyMapper.updateRemindLeadDays(me.getFamilyId(), days);
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family", me.getFamilyId(),
                "填报模板=" + t.name() + " · 提前提醒=" + days + "天");
        ra.addFlashAttribute("flash", "填报模板与提醒节奏已保存");
        return "redirect:/admin/reminders";
    }

    /** 短信渠道配置 · aksk 留空 = 保持原值(不被空串覆盖)· secret 绝不回显 */
    @PostMapping("/sms")
    public String saveSms(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(value = "smsEnabled", defaultValue = "false") boolean smsEnabled,
                          @RequestParam(value = "accessKeyId", required = false) String accessKeyId,
                          @RequestParam(value = "accessKeySecret", required = false) String accessKeySecret,
                          @RequestParam(value = "signName", required = false) String signName,
                          @RequestParam(value = "templateCode", required = false) String templateCode,
                          RedirectAttributes ra) {
        FamilyNotifyConfig existing = notifyConfigMapper.findByFamily(me.getFamilyId()).orElse(null);

        FamilyNotifyConfig cfg = FamilyNotifyConfig.builder()
                .familyId(me.getFamilyId())
                .smsEnabled(smsEnabled)
                .smsProvider("aliyun")
                .smsAccessKeyId(keepIfBlank(accessKeyId, existing == null ? null : existing.getSmsAccessKeyId()))
                .smsAccessKeySecret(keepIfBlank(accessKeySecret, existing == null ? null : existing.getSmsAccessKeySecret()))
                .smsSignName(trimToNull(signName))
                .smsTemplateCode(trimToNull(templateCode))
                .build();
        notifyConfigMapper.upsert(cfg);
        // 审计只记开关 + 是否已配密钥,绝不记 aksk 明文
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_notify_config", me.getFamilyId(),
                "短信" + (smsEnabled ? "启用" : "停用") + " · 密钥"
                        + (cfg.getSmsAccessKeySecret() != null ? "已配" : "未配"));
        ra.addFlashAttribute("flash", "短信渠道配置已保存");
        return "redirect:/admin/reminders";
    }

    /** 单成员手机号 · 私密 · 绝不进 LLM/审计明文 */
    @PostMapping("/member/{memberId}/phone")
    public String saveMemberPhone(@AuthenticationPrincipal MemberPrincipal me,
                                  @PathVariable("memberId") long memberId,
                                  @RequestParam(value = "phone", required = false) String phone,
                                  RedirectAttributes ra) {
        Member m = memberMapper.findById(memberId)
                .filter(x -> x.getFamilyId().equals(me.getFamilyId()))
                .orElseThrow(() -> new IllegalArgumentException("成员不属于本家庭"));
        memberMapper.updatePhone(m.getId(), trimToNull(phone));
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.SYSTEM,
                "member", m.getId(),
                "更新手机号(" + (trimToNull(phone) == null ? "清空" : "已设置") + ")");
        ra.addFlashAttribute("flash", "成员手机号已更新");
        return "redirect:/admin/reminders";
    }

    /** 手动触发一次调度(debug · 验证链路) */
    @PostMapping("/test")
    public String triggerNow(@AuthenticationPrincipal MemberPrincipal me, RedirectAttributes ra) {
        int armed = reminderScheduler.runNow();
        ra.addFlashAttribute("flash", "已手动触发提醒调度 · 本次触达 " + armed + " 条");
        return "redirect:/admin/reminders";
    }

    private static String mask(String s) {
        if (s == null || s.isBlank()) return "(未配置)";
        String t = s.trim();
        if (t.length() <= 6) return "******";
        return t.substring(0, 3) + "****" + t.substring(t.length() - 3);
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 表单留空 → 沿用旧值(私密字段不被空串清掉) */
    private static String keepIfBlank(String incoming, String old) {
        return (incoming == null || incoming.isBlank()) ? old : incoming.trim();
    }
}
