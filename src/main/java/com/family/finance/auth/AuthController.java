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
                        Model model) {
        // 已登录用户访问 /login(常见:书签 = /login)· 直接送去 /dashboard,
        // 不再展示登录表单。注:logout 后 Spring 已清 session · 此时 me == null · 正常落到表单。
        // 若用户 must_change_pw,MustChangePasswordInterceptor 会在 /dashboard 那一层
        // 再次拦截并送去 /profile/password,这里不需要特殊判断。
        if (me != null && logout == null) {
            return "redirect:/dashboard";
        }
        if (error != null)  model.addAttribute("error", "用户名或密码错误");
        if (logout != null) model.addAttribute("logout", "已退出");
        return "auth/login";
    }
}
