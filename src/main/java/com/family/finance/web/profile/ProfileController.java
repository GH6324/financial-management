package com.family.finance.web.profile;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.member.Member;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户主动改密 + 临时密码强制改密入口。
 * PRD FR-1:首次登录或管理员重置后,member.must_change_pw=true 触发强制跳转(由
 * MustChangePasswordInterceptor 处理),用户必须先在此页改密才能访问其它路径。
 */
@Controller
@RequiredArgsConstructor
public class ProfileController {

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @GetMapping("/profile/password")
    public String form(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("forced", me != null && me.isMustChangePw());
        return "profile/password";
    }

    @PostMapping("/profile/password")
    public String submit(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam("oldPassword") String oldPassword,
                         @RequestParam("newPassword") String newPassword,
                         @RequestParam("confirmPassword") String confirmPassword,
                         Model model,
                         HttpServletRequest request,
                         HttpServletResponse response) {
        Member m = memberMapper.findById(me.getMemberId())
                .orElseThrow(() -> new IllegalStateException("成员不存在"));

        if (!passwordEncoder.matches(oldPassword, m.getPasswordHash())) {
            return back(model, me, "原密码不正确");
        }
        if (newPassword == null || newPassword.length() < 8) {
            return back(model, me, "新密码至少 8 位");
        }
        if (!newPassword.equals(confirmPassword)) {
            return back(model, me, "两次输入的新密码不一致");
        }
        if (passwordEncoder.matches(newPassword, m.getPasswordHash())) {
            return back(model, me, "新密码不能与原密码相同");
        }

        memberMapper.updatePasswordHash(me.getMemberId(), passwordEncoder.encode(newPassword), false);
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.PASSWORD_RESET,
                "member", me.getMemberId(), "成员主动修改密码");
        // 强制重新登录,拿到全新会话重新读库(issue #1):
        // Spring Security 6 默认 requireExplicitSave=true,SecurityContextHolder.clearContext()
        // 只清当前线程、不作废 HttpSession —— session 里仍是登录时的旧 principal(must_change_pw=true),
        // 于是 AuthController 把"已登录"用户跳 /dashboard、拦截器又弹回改密页 → 死循环。
        // 必须真正作废 session,下次 /login 才会 me==null 给登录表单 → 用新密码登 → 读到 must_change_pw=0。
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, auth);
        return "redirect:/login?passwordChanged";
    }

    private String back(Model model, MemberPrincipal me, String error) {
        model.addAttribute("me", me);
        model.addAttribute("forced", me != null && me.isMustChangePw());
        model.addAttribute("error", error);
        return "profile/password";
    }
}
