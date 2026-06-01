package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLog;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.backup.BackupLog;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.fx.FxRate;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.AccountTemplateMapper;
import com.family.finance.repository.AuditMapper;
import com.family.finance.repository.BackupLogMapper;
import com.family.finance.repository.CashFlowCategoryMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.AdminService;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.FamilyService;
import com.family.finance.service.FxService;
import com.family.finance.service.PeriodService;
import com.family.finance.service.ProductCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * /admin · 管理总入口 + 12 个子页(详见 PRD § FR-20)。
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final FamilyService familyService;
    private final FamilyMapper familyMapper;
    private final MemberMapper memberMapper;
    private final AccountTemplateMapper accountTemplateMapper;
    private final CashFlowCategoryMapper cashFlowCategoryMapper;
    private final PeriodService periodService;
    private final BackupLogMapper backupLogMapper;
    private final AuditMapper auditMapper;
    private final FxService fxService;
    private final AdminService adminService;
    private final com.family.finance.service.PeriodOpener periodOpener;
    private final AuditLogService auditLogService;
    private final ProductCategoryService productCategoryService;
    // v0.4
    private final com.family.finance.repository.RebalanceAdviceCacheMapper rebalanceCacheMapper;
    // v0.4.18 · 数值阈值可编辑(详 prd §22)
    private final com.family.finance.service.config.FamilyConfigService configService;

    // ---------------------------------------------------------------------
    // 1. /admin · 总览
    // ---------------------------------------------------------------------
    @GetMapping
    public String hub(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        Family family = familyService.require(me.getFamilyId());
        Period current = periodService.findCurrentOpen(me.getFamilyId()).orElse(null);
        BackupLog lastBackup = backupLogMapper.latest(me.getFamilyId()).orElse(null);
        List<FxRate> fx = fxService.recent(me.getFamilyId(), 5);
        model.addAttribute("family", family);
        model.addAttribute("currentPeriod", current);
        model.addAttribute("lastBackup", lastBackup);
        model.addAttribute("fxRecent", fx);
        return "admin/index";
    }

    // ---------------------------------------------------------------------
    // 2. /admin/family · 家庭设置(品牌名 + 本位币 + 周期类型 + Logo)
    // ---------------------------------------------------------------------
    @GetMapping("/family")
    public String family(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("family", familyService.require(me.getFamilyId()));
        // v0.2 FR-1/FR-34 · 预设图标白名单(顺序固定供 gallery 渲染)
        model.addAttribute("logoPresets", java.util.List.of("icon1", "icon2", "icon3", "icon4"));
        return "admin/family";
    }

    // v0.4 FR-61a · 切换 CPI 假设(2/3/5%)· 从 dashboard 顶部 widget POST
    @PostMapping("/family/cpi")
    public String updateCpi(@AuthenticationPrincipal MemberPrincipal me,
                            @RequestParam("cpi") java.math.BigDecimal cpi) {
        if (cpi == null || cpi.signum() < 0 || cpi.compareTo(new java.math.BigDecimal("20")) > 0) {
            return "redirect:/dashboard";
        }
        familyMapper.updateCpiAssumption(me.getFamilyId(), cpi);
        return "redirect:/dashboard";
    }

    // v0.4 FR-62a · 切换配置锚(预置 5 选 1)· 从 reports 配置 diff 下拉 POST
    @PostMapping("/family/anchor")
    public String updateAnchor(@AuthenticationPrincipal MemberPrincipal me,
                               @RequestParam("anchor") String anchor) {
        if (!com.family.finance.domain.allocation.AnchorCode.isValid(anchor)) {
            return "redirect:/reports";
        }
        familyMapper.updateAllocationAnchor(me.getFamilyId(), anchor.toUpperCase());
        // 切锚 = AI 调仓缓存失效
        rebalanceCacheMapper.deleteByFamily(me.getFamilyId());
        return "redirect:/reports#allocation-diff";
    }

    // v0.4 FR-62b · 切换风险偏好(LLM 调仓 prompt 输入)· 从 /admin/family 下拉
    @PostMapping("/family/risk-appetite")
    public String updateRiskAppetite(@AuthenticationPrincipal MemberPrincipal me,
                                     @RequestParam("appetite") String appetite,
                                     RedirectAttributes ra) {
        if (!com.family.finance.domain.allocation.RiskAppetite.isValid(appetite)) {
            ra.addFlashAttribute("flash", "非法的风险偏好: " + appetite);
            return "redirect:/admin/family";
        }
        familyMapper.updateRiskAppetite(me.getFamilyId(), appetite.toUpperCase());
        rebalanceCacheMapper.deleteByFamily(me.getFamilyId());
        ra.addFlashAttribute("flash", "已保存");
        return "redirect:/admin/family";
    }

    @PostMapping("/family")
    public String updateFamily(@AuthenticationPrincipal MemberPrincipal me,
                               @RequestParam String name,
                               @RequestParam String brandText,
                               @RequestParam String baseCurrency,
                               @RequestParam String periodType,
                               RedirectAttributes ra) {
        Family f = familyService.require(me.getFamilyId());
        PeriodType newType = PeriodType.valueOf(periodType);
        // PRD FR-4:切换周期类型前,必须确认无 OPEN 周期
        if (f.getPeriodType() != newType && periodService.findCurrentOpen(me.getFamilyId()).isPresent()) {
            ra.addFlashAttribute("flash", "切换周期类型失败:存在 OPEN 周期,请先关闭");
            return "redirect:/admin/family";
        }
        f.setName(name);
        f.setBrandText(brandText);
        f.setBaseCurrency(baseCurrency);
        f.setPeriodType(newType);
        familyMapper.update(f);
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family", me.getFamilyId(), "家庭设置更新:%s · %s · %s".formatted(name, baseCurrency, periodType));
        ra.addFlashAttribute("flash", "已保存");
        return "redirect:/admin/family";
    }

    // ---------------------------------------------------------------------
    // 3. /admin/members · 成员
    // ---------------------------------------------------------------------
    @GetMapping("/members")
    public String members(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("members", memberMapper.findActiveByFamily(me.getFamilyId()));
        return "admin/members";
    }

    @PostMapping("/members/{memberId}")
    public String updateMember(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long memberId,
                               @RequestParam String displayName,
                               @RequestParam(required = false) String roleLabel,
                               RedirectAttributes ra) {
        adminService.updateMemberProfile(me.getFamilyId(), memberId,
                displayName, blankToNull(roleLabel), me.getMemberId());
        // BUG-FIX(2026-05-11):若改的是自己,刷新 SecurityContext 的 MemberPrincipal,
        // 让顶栏 me.displayName 立刻看到新名(否则要等会话过期重登才生效)
        if (memberId == me.getMemberId()) {
            Member fresh = memberMapper.findById(memberId).orElseThrow();
            MemberPrincipal refreshed = new MemberPrincipal(fresh);
            Authentication old = SecurityContextHolder.getContext().getAuthentication();
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(refreshed, old.getCredentials(), refreshed.getAuthorities()));
        }
        ra.addFlashAttribute("flash", "已保存:" + displayName);
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{memberId}/reset-password")
    public String resetPassword(@AuthenticationPrincipal MemberPrincipal me,
                                @PathVariable long memberId,
                                RedirectAttributes ra) {
        String temp = adminService.resetPassword(me.getFamilyId(), memberId, me.getMemberId());
        Member m = memberMapper.findById(memberId).orElseThrow();
        ra.addFlashAttribute("tempPassword", temp);
        ra.addFlashAttribute("tempPasswordFor", m.getDisplayName());
        ra.addFlashAttribute("tempPasswordMemberId", memberId);
        return "redirect:/admin/members";
    }

    /** 添加成员(PRD §7.9 维护性变更:把原"v0.3 才开放"提前到 v0.1 末)。 */
    @PostMapping("/members")
    public String createMember(@AuthenticationPrincipal MemberPrincipal me,
                               @RequestParam String username,
                               @RequestParam String displayName,
                               @RequestParam(required = false) String roleLabel,
                               RedirectAttributes ra) {
        try {
            String temp = adminService.createMember(me.getFamilyId(),
                    username, displayName, blankToNull(roleLabel), me.getMemberId());
            ra.addFlashAttribute("tempPassword", temp);
            ra.addFlashAttribute("tempPasswordFor", displayName);
            ra.addFlashAttribute("flash", "已添加成员:" + displayName);
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("flash", "添加失败:" + ex.getMessage());
        }
        return "redirect:/admin/members";
    }

    // ---------------------------------------------------------------------
    // 4. /admin/accounts · 直接复用 /accounts(已存在)
    // ---------------------------------------------------------------------
    @GetMapping("/accounts")
    public String adminAccountsRedirect() {
        return "redirect:/accounts";
    }

    // ---------------------------------------------------------------------
    // 5. /admin/account-templates · 只读
    // ---------------------------------------------------------------------
    @GetMapping("/account-templates")
    public String accountTemplates(Model model) {
        model.addAttribute("templates", accountTemplateMapper.listOrdered());
        return "admin/account-templates";
    }

    // ---------------------------------------------------------------------
    // 6. /admin/cash-flow-categories · 只读
    // ---------------------------------------------------------------------
    @GetMapping("/cash-flow-categories")
    public String cashFlowCategories(Model model) {
        model.addAttribute("categories", cashFlowCategoryMapper.listOrdered());
        return "admin/cash-flow-categories";
    }

    // ---------------------------------------------------------------------
    // 6b. /admin/product-categories · v0.2 产品类目(只读)
    // ---------------------------------------------------------------------
    @GetMapping("/product-categories")
    public String productCategories(Model model) {
        model.addAttribute("categories", productCategoryService.listAll());
        return "admin/product-categories";
    }

    // ---------------------------------------------------------------------
    // 7. /admin/periods · 周期管理 + 重新打开
    // ---------------------------------------------------------------------
    @GetMapping("/periods")
    public String periods(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(value = "page", defaultValue = "0") int page,
                          Model model) {
        int pageSize = 24;
        int total = periodService.countPeriods(me.getFamilyId());
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        List<Period> periods = periodService.findPaged(me.getFamilyId(), pageSize, safePage * pageSize);
        model.addAttribute("periods", periods);
        model.addAttribute("currentPeriod", periodService.findCurrentOpen(me.getFamilyId()).orElse(null));
        // v0.5 修 · 分页(beta 测试数据曾到 2032 · 88 期翻不到当前)
        model.addAttribute("page", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalPeriods", total);
        return "admin/periods";
    }

    @PostMapping("/periods/open-next")
    public String openNextPeriod(@AuthenticationPrincipal MemberPrincipal me,
                                 RedirectAttributes ra) {
        try {
            Period period = periodOpener.openNextNow(me.getFamilyId());
            ra.addFlashAttribute("flash",
                    "已开启新周期:" + period.getPeriodStart() + "(snapshot_todo / LOAN 预填已生成)");
        } catch (Exception ex) {
            ra.addFlashAttribute("flash", "开启失败:" + ex.getMessage());
        }
        return "redirect:/admin/periods";
    }

    @PostMapping("/periods/{periodId}/force-close")
    public String forceClose(@AuthenticationPrincipal MemberPrincipal me,
                              @PathVariable long periodId,
                              RedirectAttributes ra) {
        try {
            int filled = periodService.forceClose(periodId, me.getMemberId());
            ra.addFlashAttribute("flash", "已强制关账,代填 " + filled + " 个账户的余额(延续上期末);触发指标重算");
        } catch (Exception ex) {
            ra.addFlashAttribute("flash", "强制关账失败:" + ex.getMessage());
        }
        return "redirect:/admin/periods";
    }

    @PostMapping("/periods/{periodId}/reopen")
    public String reopenPeriod(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long periodId,
                               @RequestParam String reason,
                               RedirectAttributes ra) {
        if (reason == null || reason.isBlank()) {
            ra.addFlashAttribute("flash", "重开失败:必须填理由");
            return "redirect:/admin/periods";
        }
        periodService.reopen(periodId, reason);
        ra.addFlashAttribute("flash", "周期已重新打开");
        return "redirect:/admin/periods";
    }

    // 8. /admin/reminders · v0.4.14 FR-63d 迁至 NotificationSettingsController(填报模板 + 短信强提醒)

    // ---------------------------------------------------------------------
    // 9. /admin/fx · 汇率维护
    // ---------------------------------------------------------------------
    @GetMapping("/fx")
    public String fx(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("family", familyService.require(me.getFamilyId()));
        model.addAttribute("rates", fxService.recent(me.getFamilyId(), 24));
        model.addAttribute("periods", periodService.findLatest(me.getFamilyId(), 12));
        return "admin/fx";
    }

    @PostMapping("/fx/override")
    public String overrideFx(@AuthenticationPrincipal MemberPrincipal me,
                             @RequestParam long periodId,
                             @RequestParam String quoteCurrency,
                             @RequestParam BigDecimal rate,
                             RedirectAttributes ra) {
        fxService.manualOverride(me.getFamilyId(), periodId, quoteCurrency, rate, me.getMemberId());
        ra.addFlashAttribute("flash", "已手填覆盖 %s @ period#%d".formatted(quoteCurrency, periodId));
        return "redirect:/admin/fx";
    }

    @PostMapping("/fx/fetch")
    public String fetchFx(@AuthenticationPrincipal MemberPrincipal me, RedirectAttributes ra) {
        try {
            fxService.fetchForLatestPeriods(me.getFamilyId());
            ra.addFlashAttribute("flash", "拉取完成");
        } catch (Exception e) {
            ra.addFlashAttribute("flash", "拉取失败:" + e.getMessage());
        }
        return "redirect:/admin/fx";
    }

    // ---------------------------------------------------------------------
    // 10. /admin/backup · 备份状态
    // ---------------------------------------------------------------------
    @GetMapping("/backup")
    public String backup(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("logs", backupLogMapper.recent(me.getFamilyId(), 8));
        model.addAttribute("now", LocalDateTime.now());
        return "admin/backup";
    }

    // ---------------------------------------------------------------------
    // 11. /admin/audit · 审计日志
    // ---------------------------------------------------------------------
    @GetMapping("/audit")
    public String audit(@AuthenticationPrincipal MemberPrincipal me,
                        @RequestParam(required = false) String type,
                        Model model) {
        List<AuditLog> rows = auditMapper.findByFamily(me.getFamilyId(), blankToNull(type), 100);
        // v0.2 · "由谁" 列展示成员真名而非 #id
        java.util.Map<Long, String> memberNames = memberMapper.findActiveByFamily(me.getFamilyId()).stream()
                .collect(java.util.stream.Collectors.toMap(Member::getId, Member::getDisplayName));
        model.addAttribute("rows", rows);
        model.addAttribute("memberNames", memberNames);
        model.addAttribute("filterType", type);
        model.addAttribute("availableTypes", AuditLogType.values());
        return "admin/audit";
    }

    // ---------------------------------------------------------------------
    // 12. /admin/calc-tweaks · v0.4.18 · 数值阈值可编辑(详 prd §22)
    // ---------------------------------------------------------------------
    @GetMapping("/calc-tweaks")
    public String calcTweaks(@org.springframework.security.core.annotation.AuthenticationPrincipal
                             com.family.finance.auth.MemberPrincipal me, Model model) {
        long fid = me.getFamilyId();
        com.family.finance.service.config.FamilyConfigService cs = configService;
        // ① 录入提示阈值(老 3 项 · 之前只读 · v0.4.18 起可编辑)
        model.addAttribute("smartTransfer", cs.getLong(fid, com.family.finance.service.config.FamilyConfigService.K_SMART_TRANSFER, 3000L));
        model.addAttribute("loanAbnormal",  cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_LOAN_ABNORMAL, 3.0));
        model.addAttribute("unexplainedEps", cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_UNEXPLAINED_EPSILON, 0.01));
        // ② 体检阈值(新 4 项)
        model.addAttribute("checkupConcentration", cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_CHECKUP_CONCENTRATION, 0.40));
        model.addAttribute("checkupHighRisk",      cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_CHECKUP_HIGH_RISK, 0.40));
        model.addAttribute("liquidBuffer",          cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_LIQUID_BUFFER, 1.5));
        model.addAttribute("emergencyMonths",       cs.getInt(fid, com.family.finance.service.config.FamilyConfigService.K_EMERGENCY_MONTHS, 6));
        // ③ 会话
        model.addAttribute("rememberMeSeconds",     cs.getLong(fid, com.family.finance.service.config.FamilyConfigService.K_REMEMBER_ME_SECONDS, 2592000L));
        return "admin/calc-tweaks";
    }

    /** v0.4.18 · ① 录入提示阈值保存 */
    @PostMapping("/calc-tweaks/entry-thresholds")
    public String saveEntryThresholds(@org.springframework.security.core.annotation.AuthenticationPrincipal
                                      com.family.finance.auth.MemberPrincipal me,
                                      @org.springframework.web.bind.annotation.RequestParam("smartTransfer") long smartTransfer,
                                      @org.springframework.web.bind.annotation.RequestParam("loanAbnormal") double loanAbnormal,
                                      @org.springframework.web.bind.annotation.RequestParam("unexplainedEps") double unexplainedEps,
                                      org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        long fid = me.getFamilyId();
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_SMART_TRANSFER, String.valueOf(Math.max(0L, smartTransfer)));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_LOAN_ABNORMAL,  String.valueOf(Math.max(1.0, loanAbnormal)));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_UNEXPLAINED_EPSILON, String.valueOf(Math.max(0.0, unexplainedEps)));
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE, "family_runtime_config", fid,
                "录入阈值 · smartTransfer=" + smartTransfer + " · loanAbnormal=" + loanAbnormal + " · epsilon=" + unexplainedEps);
        ra.addFlashAttribute("flash", "录入阈值已保存");
        return "redirect:/admin/calc-tweaks";
    }

    /** v0.4.18 · ② 体检阈值保存 */
    @PostMapping("/calc-tweaks/checkup-thresholds")
    public String saveCheckupThresholds(@org.springframework.security.core.annotation.AuthenticationPrincipal
                                        com.family.finance.auth.MemberPrincipal me,
                                        @org.springframework.web.bind.annotation.RequestParam("concentration") double concentration,
                                        @org.springframework.web.bind.annotation.RequestParam("highRisk") double highRisk,
                                        @org.springframework.web.bind.annotation.RequestParam("liquidBuffer") double liquidBuffer,
                                        @org.springframework.web.bind.annotation.RequestParam("emergencyMonths") int emergencyMonths,
                                        org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        long fid = me.getFamilyId();
        // 防呆:百分比合理区间 / 月数 ≥ 1
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_CHECKUP_CONCENTRATION, String.valueOf(clamp01(concentration)));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_CHECKUP_HIGH_RISK,     String.valueOf(clamp01(highRisk)));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_LIQUID_BUFFER,         String.valueOf(Math.max(1.0, liquidBuffer)));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_EMERGENCY_MONTHS,      String.valueOf(Math.max(1, Math.min(emergencyMonths, 24))));
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE, "family_runtime_config", fid,
                "体检阈值 · concentration=" + concentration + " · highRisk=" + highRisk + " · liquidBuffer=" + liquidBuffer + "x · emergencyMonths=" + emergencyMonths);
        ra.addFlashAttribute("flash", "体检阈值已保存 · 下次诊断生效");
        return "redirect:/admin/calc-tweaks";
    }

    /** v0.4.18 · ③ 会话有效期保存(说明:下次 login 生效 · 在世 cookie 仍按旧时长) */
    @PostMapping("/calc-tweaks/session")
    public String saveSession(@org.springframework.security.core.annotation.AuthenticationPrincipal
                              com.family.finance.auth.MemberPrincipal me,
                              @org.springframework.web.bind.annotation.RequestParam("rememberMeSeconds") long rememberMeSeconds,
                              org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        long fid = me.getFamilyId();
        long safe = Math.max(86400L, Math.min(rememberMeSeconds, 365 * 86400L)); // 1 天 ~ 1 年
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_REMEMBER_ME_SECONDS, String.valueOf(safe));
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE, "family_runtime_config", fid,
                "remember-me 有效期 = " + safe + "s · 下次 login 生效");
        ra.addFlashAttribute("flash", "会话有效期已保存 · 注意:已 login 用户的旧 cookie 仍按旧时长(下次 login 才用新值)");
        return "redirect:/admin/calc-tweaks";
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(v, 1.0));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
