package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.NavService;
import com.family.finance.service.broker.BrokerClient;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.scheduling.DynamicScheduleConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;

/**
 * v1.26 · /admin/broker · 券商同步(富途 / 老虎 / 盈透)自己一页。
 *
 * <p>原来是「数据源接入」页的第 ④ 节。2026-09-27 维护者在 beta 上找 IBKR 的配置没找到:
 * 管理首页那张卡只写了「券商同步」四个字、没写是哪几家,页头是「行情 · 汇率 · 券商」,券商那节排第四要往下滚。
 * 维护者定:按「用户要完成的事」分入口 —— 连自己的券商账户(填的是个人凭据)和系统拉公共行情 / 汇率是两件事,
 * 券商单独成入口(和 v1.24.5「AI 的东西放一处」同一个判据)。方法体从 {@code IntegrationsController} 逐字搬过来,
 * 配置键一个没动;端点 {@code /admin/integrations/broker*} → {@code /admin/broker*}。</p>
 *
 * <p>可带 {@code ?account=<id>}:从某个账户的「券商关联」页点过来时,页头显示「正在为 X 关联」并给一键回跳
 * (照 OpenD 向导 v1.6.24 的做法)—— 用户是为了关联某个账户才来配凭据的,配完得能回去。
 * account 只用于显示与回跳,不参与任何配置逻辑;不属于本家庭的当没传。</p>
 *
 * <p>私密红线:私钥 / 报表口令留空 = 保原值、永不回显、审计只记「已配 / 未配」不记明文;
 * 只读铁律:这里不存交易密码、不申请任何写权限。</p>
 */
@Controller
@RequestMapping("/admin/broker")
@RequiredArgsConstructor
public class BrokerSettingsController {

    private final FamilyConfigService configService;
    private final DynamicScheduleConfig schedulerConfig;
    private final AuditLogService auditLogService;
    private final NavService navService;
    private final AccountMapper accountMapper;
    private final List<BrokerClient> brokerClients;

    @GetMapping
    public String page(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam(name = "account", required = false) Long accountId,
                       Model model) {
        long fid = me.getFamilyId();
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        if (accountId != null) {
            accountMapper.findById(fid, accountId)
                    .filter(a -> a.getFamilyId().equals(fid))   // 越权直接当没传
                    .ifPresent(a -> {
                        model.addAttribute("ctxAccountId", a.getId());
                        model.addAttribute("ctxAccountName", a.getDisplayName());
                    });
        }
        model.addAttribute("vendors", BrokerVendor.values());
        // 老虎 / 富途(私钥不回显)
        model.addAttribute("tigerId",               configService.getString(fid, FamilyConfigService.K_BROKER_TIGER_ID, ""));
        model.addAttribute("tigerKeyConfigured",    configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_BROKER_TIGER_KEY));
        model.addAttribute("tigerAccount",          configService.getString(fid, FamilyConfigService.K_BROKER_TIGER_ACCOUNT, ""));
        model.addAttribute("futuHost",              configService.getString(fid, FamilyConfigService.K_BROKER_FUTU_HOST, ""));
        model.addAttribute("futuPort",              configService.getString(fid, FamilyConfigService.K_BROKER_FUTU_PORT, "11111"));
        // 盈透 IBKR(Flex 报表口令 · 只能取报表)
        model.addAttribute("ibkrTokenConfigured",   configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_BROKER_IBKR_TOKEN));
        model.addAttribute("ibkrTokenMasked",       configService.maskedSecret(fid, FamilyConfigService.K_BROKER_IBKR_TOKEN));
        model.addAttribute("ibkrQuery",             configService.getString(fid, FamilyConfigService.K_BROKER_IBKR_QUERY, ""));
        model.addAttribute("ibkrExpires",           configService.getString(fid, FamilyConfigService.K_BROKER_IBKR_EXPIRES, ""));
        model.addAttribute("brokerSyncCron",        configService.getString(fid, FamilyConfigService.K_BROKER_SYNC_CRON, "0 45 16 * * MON-FRI"));
        return "admin/broker";
    }

    /**
     * 保存 · 老虎(tiger_id + RSA 私钥 + 账户)+ 富途(OpenD host/port)+ 盈透(报表口令 + 查询号 + 到期日)+ 同步 cron。
     */
    @PostMapping
    public String save(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam(value = "tigerId", required = false) String tigerId,
                       @RequestParam(value = "tigerKey", required = false) String tigerKey,
                       @RequestParam(value = "tigerAccount", required = false) String tigerAccount,
                       @RequestParam(value = "futuHost", required = false) String futuHost,
                       @RequestParam(value = "futuPort", required = false) String futuPort,
                       @RequestParam("brokerSyncCron") String brokerSyncCron,
                       @RequestParam(value = "ibkrToken", required = false) String ibkrToken,
                       @RequestParam(value = "ibkrQuery", required = false) String ibkrQuery,
                       @RequestParam(value = "ibkrExpires", required = false) String ibkrExpires,
                       @RequestParam(name = "account", required = false) Long accountId,
                       RedirectAttributes ra) {
        long fid = me.getFamilyId();
        // IBKR:口令留空 = 保原值(同老虎私钥);查询号只留数字;到期日只收 yyyy-MM-dd,填错就拒(不许存一个解析不了的值,
        //   否则到期提醒会静默失效)
        String ibkrExpiresNorm = "";
        if (ibkrExpires != null && !ibkrExpires.isBlank()) {
            try { ibkrExpiresNorm = java.time.LocalDate.parse(ibkrExpires.trim()).toString(); }
            catch (Exception bad) {
                ra.addFlashAttribute("flashError", "IBKR 口令到期日看不懂:「" + ibkrExpires.trim() + "」—— 请按 2027-09-24 这样填");
                return back(accountId);
            }
        }
        if (ibkrToken != null && !ibkrToken.isBlank()) {
            configService.set(fid, FamilyConfigService.K_BROKER_IBKR_TOKEN, ibkrToken.replaceAll("\\s+", ""));
        }
        if (ibkrQuery != null) {
            configService.set(fid, FamilyConfigService.K_BROKER_IBKR_QUERY, ibkrQuery.replaceAll("\\s+", ""));
        }
        configService.set(fid, FamilyConfigService.K_BROKER_IBKR_EXPIRES, ibkrExpiresNorm);
        configService.set(fid, FamilyConfigService.K_BROKER_TIGER_ID, tigerId == null ? "" : tigerId.trim());
        // 私钥:留空保原值(与 LLM key 同策略)
        if (tigerKey != null && !tigerKey.isBlank()) {
            configService.set(fid, FamilyConfigService.K_BROKER_TIGER_KEY, tigerKey.trim());
        }
        configService.set(fid, FamilyConfigService.K_BROKER_TIGER_ACCOUNT, tigerAccount == null ? "" : tigerAccount.trim());
        configService.set(fid, FamilyConfigService.K_BROKER_FUTU_HOST, futuHost == null ? "" : futuHost.trim());
        configService.set(fid, FamilyConfigService.K_BROKER_FUTU_PORT, sanitize(futuPort, "11111"));
        configService.set(fid, FamilyConfigService.K_BROKER_SYNC_CRON, sanitize(brokerSyncCron, "0 45 16 * * MON-FRI"));
        schedulerConfig.rescheduleAll();
        // 审计 · 不记私钥明文
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid,
                "券商同步配置 · tigerId=" + (tigerId != null && !tigerId.isBlank() ? "已填" : "空")
                + " · tigerKey=" + (configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_BROKER_TIGER_KEY) ? "已配" : "未配")
                + " · futuOpenD=" + (futuHost != null && !futuHost.isBlank() ? "已填" : "空")
                + " · ibkrToken=" + (configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_BROKER_IBKR_TOKEN) ? "已配" : "未配")
                + " · ibkrQuery=" + (ibkrQuery != null && !ibkrQuery.isBlank() ? "已填" : "空")
                + " · ibkrExpires=" + (ibkrExpiresNorm.isEmpty() ? "未填" : ibkrExpiresNorm)
                + " · cron=" + brokerSyncCron);
        ra.addFlashAttribute("flash", "券商同步配置已保存 · cron 已重排 · 只读、永不下单");
        return back(accountId);
    }

    /**
     * 一键测试连接 · 用<b>已保存</b>凭据只拉一次账户 / 资产,验证只读链路通不通。
     * <p>只读铁律:测试也只走查询接口,绝不下单;失败原因脱敏后展示。</p>
     */
    @PostMapping("/test")
    public String test(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam("vendor") String vendor,
                       @RequestParam(name = "account", required = false) Long accountId,
                       RedirectAttributes ra) {
        long fid = me.getFamilyId();
        BrokerVendor v;
        try {
            v = BrokerVendor.valueOf(vendor.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            ra.addFlashAttribute("flashError", "未知券商:" + vendor);
            return back(accountId);
        }
        BrokerClient client = brokerClients.stream().filter(c -> c.vendor() == v).findFirst().orElse(null);
        if (client == null) {
            ra.addFlashAttribute("flashError", v.getLabel() + " 客户端不可用");
            return back(accountId);
        }
        try {
            String detail = client.testConnection(fid, null).summary();   // 全局默认凭据(link=null)
            auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                    "family_runtime_config", fid, "券商测试连接 · " + v.getLabel() + " · 成功");
            ra.addFlashAttribute("flash", v.getLabel() + " 测试连接成功 · " + detail);
        } catch (Exception e) {
            // IBKR 的异常已经是「人话 + IBKR 原话」,原样给 —— brokerError 的几个桶是给 OpenD / 老虎写的,会把原话吞掉
            String reason = e instanceof com.family.finance.service.broker.ibkr.IbkrFlexException
                    ? e.getMessage() : brokerError(e.getMessage());
            auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                    "family_runtime_config", fid, "券商测试连接 · " + v.getLabel() + " · 失败:" + reason);
            ra.addFlashAttribute("flashError", v.getLabel() + " 测试失败 · " + reason);
        }
        return back(accountId);
    }

    /** 券商测试异常 message 归类成无敏感信息的友好原因(绝不含私钥 / 原始 body)。 */
    public static String brokerError(String rawMsg) {
        String m = rawMsg == null ? "" : rawMsg.toLowerCase(Locale.ROOT);
        if (m.contains("待真机接线") || m.contains("unsupported")) return "适配器待真机接线(需在你的环境接通 OpenD / 凭据)";
        if (m.contains("未配置") || m.contains("not configured") || m.contains("未配")) return "凭据未配置(请先填好并保存)";
        if (m.contains("timeout") || m.contains("超时") || m.contains("connect") || m.contains("i/o") || m.contains("unknownhost"))
            return "网络不通或超时(OpenD 未启动?)";
        if (m.contains("sign") || m.contains("invalid") || m.contains("401") || m.contains("403") || m.contains("unauthor") || m.contains("permission"))
            return "凭据无效或无权限";
        return "调用失败(已脱敏)";
    }

    /**
     * 回到本页,带上「正在为哪个账户关联」的上下文(只收数字 id,拼不出站外地址)。
     *
     * <p><b>不带锚点、落在页顶</b>:保存 / 测试的结果(flash)和「回该账户的券商关联」都在页顶。
     * 2026-09-27 截图验收时第一版带了 {@code #ibkr},测完页面停在盈透那一栏,测试结果和回跳按钮都在视口上方看不见 ——
     * e2e 只查页面文字,抓不到。进来时(从关联页点过来)才跳到那一栏。</p>
     */
    static String back(Long accountId) {
        return "redirect:/admin/broker" + (accountId == null ? "" : "?account=" + accountId);
    }

    private static String sanitize(String cron, String fallback) {
        return (cron == null || cron.isBlank()) ? fallback : cron.trim();
    }
}
