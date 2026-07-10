package com.family.finance.web.help;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.service.NavService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 帮助 / 图文教程 · v0.15。
 *
 * <p>路由:</p>
 * <ul>
 *   <li>GET /help/broker-sync · 券商同步凭据获取图文向导(富途 / 老虎)</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
public class HelpController {

    private final NavService navService;

    @GetMapping("/help/broker-sync")
    public String brokerSync(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        return "help/broker-sync";
    }
}
