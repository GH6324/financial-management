package com.family.finance.web.broker;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.NavService;
import com.family.finance.service.broker.opend.FutuOpendManager;
import com.family.finance.service.config.FamilyConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
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
    private final java.util.List<com.family.finance.service.broker.BrokerClient> brokerClients; // v0.15.2 · OpenD 台测试连接

    @GetMapping
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        FutuOpendManager.Status st = opend.status();
        model.addAttribute("status", st);
        // step-by-step 首屏判定(JS 轮询后同步更新):装好第 1 步才展示第 2 步;运行中收起表单
        model.addAttribute("installed", st.version() != null);
        model.addAttribute("running", st.phase() == FutuOpendManager.Phase.RUNNING);
        model.addAttribute("channel", opend.env().name());
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

    /** 环境自检(可执行位/属主/家目录可写/OpenD 数据目录可写/依赖 · 结合 docker/linux/mac)。 */
    @GetMapping("/selfcheck")
    @ResponseBody
    public FutuOpendManager.SelfCheck selfCheck() { return opend.selfCheck(); }

    /**
     * OpenD 台测试连接:用全局默认凭据(link=null → 本机托管的 OpenD)只拉一次账户/资产验证只读链路。
     * <p>只读铁律:仅走查询接口,绝不下单;返回结构化报告(市场/户号尾4/持仓数/各币现金),失败原因脱敏。</p>
     */
    @PostMapping("/test")
    @ResponseBody
    public Object test(@AuthenticationPrincipal MemberPrincipal me) {
        long fid = me.getFamilyId();
        com.family.finance.service.broker.BrokerClient client = brokerClients.stream()
                .filter(c -> c.vendor() == com.family.finance.domain.broker.BrokerVendor.FUTU)
                .findFirst().orElse(null);
        if (client == null) return java.util.Map.of("error", "富途客户端不可用");
        try {
            com.family.finance.service.broker.BrokerDtos.TestReport report = client.testConnection(fid, null);
            auditLog.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                    "family_runtime_config", fid, "OpenD 台测试连接 · 富途 · 成功");
            return report;
        } catch (Exception e) {
            String reason = com.family.finance.web.admin.IntegrationsController.brokerError(e.getMessage());
            auditLog.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                    "family_runtime_config", fid, "OpenD 台测试连接 · 富途 · 失败:" + reason);
            return java.util.Map.of("error", reason);
        }
    }

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

    /** 重新获取手机验证码(OpenD 重发短信)。 */
    @PostMapping("/sms-request")
    @ResponseBody
    public String smsRequest() {
        return opend.requestSmsCode() ? "ok" : "fail";
    }

    /**
     * 上传已下好的 OpenD tar.gz(裸 octet-stream 流,绕过 multipart 200KB 限额 · 不依赖服务器连 CDN)。
     * CSRF 走 query 参数 {@code ?_csrf=};上限 500MB,后台不再依赖外网。
     */
    @PostMapping(value = "/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseBody
    public String upload(HttpServletRequest request) {
        try (var in = request.getInputStream()) {
            opend.installFromStream(in, 500L * 1024 * 1024);
            return "ok";
        } catch (Exception e) {
            log.warn("opend upload failed: {}", e.toString());
            return "fail:" + e.getMessage();
        }
    }

    /** 从服务器已有 tar.gz 路径导入(小 body 表单 POST · 免 HTTP 上传体积限额)。 */
    @PostMapping("/import-path")
    public String importPath(@RequestParam String path,
                             org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        try {
            opend.installFromServerPath(path);
            ra.addFlashAttribute("flash", "已从服务器路径导入并解压 · 请继续第 3 步启动");
        } catch (Exception e) {
            log.warn("opend import-path failed: {}", e.toString());
            ra.addFlashAttribute("flashError", "导入失败:" + e.getMessage());
        }
        return "redirect:/admin/broker/opend";
    }

    @PostMapping("/stop")
    public String stop(org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        opend.stop();
        ra.addFlashAttribute("flash", "已停止 OpenD");
        return "redirect:/admin/broker/opend";
    }
}
