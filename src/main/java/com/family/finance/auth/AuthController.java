package com.family.finance.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal MemberPrincipal me,
                        @RequestParam(value = "error", required = false) String error,
                        @RequestParam(value = "logout", required = false) String logout,
                        @RequestParam(value = "stale", required = false) String stale,
                        @RequestParam(value = "expired", required = false) String expired,
                        Model model) {
        // 已登录用户访问 /login(常见:书签 = /login)· 直接送去 /dashboard,
        // 不再展示登录表单。注:logout 后 Spring 已清 session · 此时 me == null · 正常落到表单。
        // 若用户 must_change_pw,MustChangePasswordInterceptor 会在 /dashboard 那一层
        // 再次拦截并送去 /profile/password,这里不需要特殊判断。
        if (me != null && logout == null && stale == null && expired == null) {
            return "redirect:/dashboard";
        }
        if (error != null)  model.addAttribute("error", "用户名或密码错误");
        if (logout != null) model.addAttribute("logout", "已退出");
        // CSRF token 失效(页面开太久 / 服务重启过 / 点了后退再提交)· 见 SecurityConfig.staleFormAccessDeniedHandler
        if (stale != null)  model.addAttribute("stale", "这个页面停留太久了,请重新登录一次。");
        // v1.15 FR-380/381 · 会话被就地作废:管理员改了你的登录名,或把这个成员归档了。
        // 落点由 SecurityConfig 的 expiredUrl 指定 —— 不说清原因的话,用户只会觉得"莫名其妙被踢了"。
        if (expired != null) model.addAttribute("stale",
                "登录状态已失效 —— 可能是登录名被修改,或该成员已被归档。请用最新的登录名重新登录。");
        return "auth/login";
    }
}
