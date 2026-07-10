package com.family.finance.web.broker;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.NavService;
import com.family.finance.service.broker.opend.FutuOpendManager;
import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 富途 OpenD 傻瓜安装向导 · v0.15。
 *
 * <p>路由(挂 /admin 下,继承管理页鉴权):</p>
 * <ul>
 *   <li>GET  /admin/broker/opend           · 向导页</li>
 *   <li>GET  /admin/broker/opend/status    · 状态(JSON,前端轮询推进步骤)</li>
 *   <li>GET  /admin/broker/opend/deps       · 依赖体检(JSON)</li>
 *   <li>POST /admin/broker/opend/download   · 下载 + 解压(后台线程,状态轮询)</li>
 *   <li>POST /admin/broker/opend/config-start · 傻瓜配置 + 启动(密码服务端 MD5)</li>
 *   <li>POST /admin/broker/opend/sms        · 中继手机短信验证码</li>
 *   <li>POST /admin/broker/opend/stop       · 停止</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin/broker/opend")
@RequiredArgsConstructor
@Slf4j
public class FutuOpendController {

    private final FutuOpendManager opend;
    private final FamilyConfigService configService;
    private final AuditLogService auditLog;
    private final NavService navService;

    @GetMapping
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("status", opend.status());
        model.addAttribute("osTag", opend.detectedOsTag());
        // 版本号会随官网更新 → 给个占位示例,让用户去官网确认最新号
        model.addAttribute("versionExample", "9.3.5308");
        return "broker/opend-wizard";
    }

    @GetMapping("/status")
    @ResponseBody
    public FutuOpendManager.Status status() { return opend.status(); }

    @GetMapping("/deps")
    @ResponseBody
    public FutuOpendManager.Deps deps() { return opend.checkDeps(); }

    @PostMapping("/download")
    @ResponseBody
    public String download(@RequestParam String version,
                           @RequestParam(required = false) String osTag,
                           @RequestParam(required = false) String url) {
        String tag = (osTag == null || osTag.isBlank()) ? opend.detectedOsTag() : osTag;
        // 后台线程跑下载/解压,页面轮询 /status
        new Thread(() -> {
            try { opend.download(version, tag, url); }
            catch (Exception e) { log.warn("opend download failed: {}", e.toString()); }
        }, "opend-download").start();
        return "started";
    }

    @PostMapping("/config-start")
    public String configStart(@AuthenticationPrincipal MemberPrincipal me,
                              @RequestParam String account,
                              @RequestParam String loginPwd,
                              @RequestParam(defaultValue = "11111") int port,
                              org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        try {
            opend.configureAndStart(account, loginPwd, port);
            // 目标 host:port 已确定 → 回填券商配置(登录成败与否,地址不变)
            configService.set(me.getFamilyId(), FamilyConfigService.K_BROKER_FUTU_HOST, "127.0.0.1");
            configService.set(me.getFamilyId(), FamilyConfigService.K_BROKER_FUTU_PORT, String.valueOf(port));
            auditLog.record(me.getFamilyId(), me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                    "family_runtime_config", me.getFamilyId(),
                    "富途 OpenD 托管 · 账号=已填 · 端口=" + port + "(密码仅存 MD5)");
            ra.addFlashAttribute("flash", "已启动 OpenD · 若需短信验证码请在向导里输入");
        } catch (Exception e) {
            log.warn("opend config-start failed: {}", e.toString());
            ra.addFlashAttribute("flashError", "启动失败:" + e.getMessage());
        }
        return "redirect:/admin/broker/opend";
    }

    @PostMapping("/sms")
    @ResponseBody
    public String sms(@RequestParam String code) {
        return opend.submitSmsCode(code) ? "ok" : "fail";
    }

    @PostMapping("/stop")
    public String stop(org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        opend.stop();
        ra.addFlashAttribute("flash", "已停止 OpenD");
        return "redirect:/admin/broker/opend";
    }
}
