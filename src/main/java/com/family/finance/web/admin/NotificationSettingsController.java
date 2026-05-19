package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.family.ReportingTemplate;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.notify.FamilyNotifyConfig;
import com.family.finance.domain.notify.ReportReminderLog;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.FamilyNotifyConfigMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.ReportReminderLogMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.notify.ReminderMessage;
import com.family.finance.service.notify.ReportReminderScheduler;
import com.family.finance.service.notify.SmsAliyunChannel;
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

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

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
    private final PeriodMapper periodMapper;
    private final ReportReminderLogMapper reminderLogMapper;
    private final AuditLogService auditLogService;
    private final ReportReminderScheduler reminderScheduler;
    private final SmsAliyunChannel smsChannel;

    /** 提醒日志默认每页 20 条 */
    private static final int LOG_PAGE_SIZE = 20;

    /** §20.5.1 限流:每管理员每分钟最多 3 次测试短信(防误点 / 防刷成本) */
    private static final int TEST_RATE_LIMIT_PER_MIN = 3;
    private final java.util.Map<Long, Deque<Instant>> testRateBucket = new ConcurrentHashMap<>();

    @GetMapping
    public String page(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam(value = "page", defaultValue = "1") int page,
                       Model model) {
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

        // ⑥ 提醒发送日志 · 分页(默认 20/页)
        int safePage = Math.max(1, page);
        int totalLogs = reminderLogMapper.countByFamily(me.getFamilyId());
        int totalPages = Math.max(1, (totalLogs + LOG_PAGE_SIZE - 1) / LOG_PAGE_SIZE);
        if (safePage > totalPages) safePage = totalPages;
        int offset = (safePage - 1) * LOG_PAGE_SIZE;
        java.util.List<ReportReminderLog> logs =
                reminderLogMapper.findByFamily(me.getFamilyId(), LOG_PAGE_SIZE, offset);

        // member_id → displayName(含已归档成员 fallback "已离开")
        java.util.Map<Long, String> memberName = new java.util.HashMap<>();
        memberMapper.findActiveByFamily(me.getFamilyId())
                .forEach(m -> memberName.put(m.getId(), m.getDisplayName()));
        // 涉及历史成员补查
        for (ReportReminderLog l : logs) {
            if (!memberName.containsKey(l.getMemberId())) {
                memberMapper.findById(l.getMemberId())
                        .ifPresent(m -> memberName.put(m.getId(), m.getDisplayName() + "(已归档)"));
            }
        }
        // period_id → "yyyy-MM-dd"(截止日)
        java.util.Map<Long, String> periodLabel = new java.util.HashMap<>();
        logs.stream().map(ReportReminderLog::getPeriodId).distinct().forEach(pid ->
                periodMapper.findById(pid).ifPresent(p ->
                        periodLabel.put(pid, p.getPeriodEnd().toString())));

        model.addAttribute("logs", logs);
        model.addAttribute("logMemberName", memberName);
        model.addAttribute("logPeriodLabel", periodLabel);
        model.addAttribute("logPage", safePage);
        model.addAttribute("logTotalPages", totalPages);
        model.addAttribute("logTotal", totalLogs);
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

    /**
     * v0.4.14 §20.5.1 ⊕ 一键测试短信 · 用测试专用变量(brand=家庭别名 · period=配置测试 ·
     * days=99 · progress=测试模式)走 SmsAliyunChannel · 返回 BizId / Code 给管理员查阿里云控制台。
     *
     * <p>限流 3 次/分/管理员;**走 audit_log(非 report_reminder_log)记录** —— report_reminder_log
     * 的 UNIQUE(family,period,member,channel,date) 不适合每分钟多次的测试场景。
     */
    @PostMapping("/sms-test")
    public String smsTest(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(value = "targetMemberId", required = false) Long targetMemberId,
                          @RequestParam(value = "customPhone", required = false) String customPhone,
                          RedirectAttributes ra) {
        if (!allowRate(me.getMemberId())) {
            ra.addFlashAttribute("flashError",
                    "测试频率过高 · 每分钟最多 " + TEST_RATE_LIMIT_PER_MIN + " 次,请稍后再试");
            return "redirect:/admin/reminders";
        }

        // 解析目标手机号:成员下拉 优先于 临时输入
        String phone = null;
        String targetLabel;
        if (targetMemberId != null && targetMemberId > 0) {
            var m = memberMapper.findById(targetMemberId)
                    .filter(x -> x.getFamilyId().equals(me.getFamilyId()))
                    .orElse(null);
            if (m == null) {
                ra.addFlashAttribute("flashError", "目标成员不属于本家庭");
                return "redirect:/admin/reminders";
            }
            phone = m.getPhone();
            targetLabel = "成员 " + m.getDisplayName();
            if (phone == null || phone.isBlank()) {
                ra.addFlashAttribute("flashError", "该成员尚未配置手机号");
                return "redirect:/admin/reminders";
            }
        } else if (customPhone != null && !customPhone.isBlank()) {
            phone = customPhone.trim();
            if (!phone.matches("\\d{6,15}")) {
                ra.addFlashAttribute("flashError", "手机号格式不正确(应为 6-15 位数字)");
                return "redirect:/admin/reminders";
            }
            targetLabel = "临时手机号";
        } else {
            ra.addFlashAttribute("flashError", "请选择成员或输入临时手机号");
            return "redirect:/admin/reminders";
        }

        Family family = familyMapper.findById(me.getFamilyId())
                .orElseThrow(() -> new IllegalArgumentException("家庭不存在"));
        // §20.5.1 · 测试短信用真实账期 + days=-1 标识 · 没 OPEN 周期就先 refuse
        Period currentPeriod = periodMapper.findCurrentOpen(me.getFamilyId()).orElse(null);
        if (currentPeriod == null) {
            ra.addFlashAttribute("flashError",
                    "当前家庭无 OPEN 周期 · 请先去『管理 → 周期』开启本期,再测试短信");
            return "redirect:/admin/reminders";
        }

        ReminderMessage msg = ReminderMessage.forSmsTest(
                family.getName(), family.getBrandText(), currentPeriod);
        SmsAliyunChannel.SendResult r = smsChannel.sendForTest(family, phone, msg);

        // 审计:走 audit_log · detail 不含 phone 明文 / aksk(私密红线)
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.SYSTEM,
                "family_notify_config", me.getFamilyId(),
                "短信测试 · " + targetLabel + " · " + (r.ok() ? "SENT" : "FAILED")
                        + " · code=" + r.code() + " · bizId=" + (r.bizId() == null ? "-" : r.bizId()));

        if (r.ok()) {
            ra.addFlashAttribute("flashSuccess",
                    "✓ 测试短信已发送 · 请查收 · 阿里云 BizId: " + r.bizId());
        } else {
            ra.addFlashAttribute("flashError",
                    "✗ 测试失败 · Code = " + r.code() + " · " + r.detail());
        }
        return "redirect:/admin/reminders";
    }

    /** 滑动窗口限流(60s 内最多 N 次)· in-memory · 单 JVM 足够(本项目单实例) */
    private synchronized boolean allowRate(long adminId) {
        Deque<Instant> q = testRateBucket.computeIfAbsent(adminId, k -> new ArrayDeque<>());
        Instant cutoff = Instant.now().minusSeconds(60);
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
        if (q.size() >= TEST_RATE_LIMIT_PER_MIN) return false;
        q.addLast(Instant.now());
        return true;
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
