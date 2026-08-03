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

    /**
     * 使用手册 · 主流程与分析路径(v1.6.32)。
     *
     * <p>起因 issue #9:用户自述没有财会背景,「理财的买入卖出、借钱等如何体现,还是有些不好理解」。
     * 查证属实 —— 此前站内帮助页只有券商同步一篇,docs/faq.md 全是部署/备份/登录/AI 这类运维题,
     * 一条「业务上该怎么记」都没有。正文同 docs/how-to-use.md,页面额外带两处解释性动画
     * (填报顺序为何不能反 / 明细抽屉只能从透视格子点开)。</p>
     */
    @GetMapping("/help/how-to-use")
    public String howToUse(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        // v1.7 · 本页**免登录**:潜在用户在决定要不要自建之前就该能读懂它怎么用。
        //   匿名访问时 me / nav 为 null,模板里用 th:block 包住 nav 片段做条件渲染
        //   (注意:th:replace 优先级高于 th:if,直接在 <header> 上写 th:if 拦不住)。
        model.addAttribute("me", me);
        model.addAttribute("nav", me == null ? null : navService.load(me));
        return "help/how-to-use";
    }

    @GetMapping("/help/broker-sync")
    public String brokerSync(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        return "help/broker-sync";
    }
}
