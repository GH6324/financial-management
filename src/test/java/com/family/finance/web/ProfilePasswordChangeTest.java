package com.family.finance.web;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.member.Member;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.web.profile.ProfileController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * issue #1 回归:改密成功后必须<b>真正作废 session</b>,而不是只 clearContext()。
 * Spring Security 6 下 clearContext() 不写回 session,旧 principal(must_change_pw=true)残留
 * → 死循环卡死首登。修复后这里断言 session.invalidate() 被调用、库标记置 0、跳 /login。
 */
class ProfilePasswordChangeTest {

    private final MemberMapper memberMapper = mock(MemberMapper.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ProfileController controller = new ProfileController(memberMapper, encoder, audit);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private MemberPrincipal me() {
        return new MemberPrincipal(Member.builder()
                .id(2L).familyId(1L).username("diwa").passwordHash("OLDHASH").mustChangePw(true).build());
    }

    @Test
    void successfulChange_invalidatesSession_clearsFlag_redirectsToLogin() {
        MemberPrincipal me = me();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(me, "x", List.of()));

        when(memberMapper.findById(2L)).thenReturn(Optional.of(
                Member.builder().id(2L).familyId(1L).passwordHash("OLDHASH").mustChangePw(true).build()));
        when(encoder.matches("demo1234", "OLDHASH")).thenReturn(true);   // 原密码对
        when(encoder.matches("newpass88", "OLDHASH")).thenReturn(false); // 新≠旧
        when(encoder.encode("newpass88")).thenReturn("NEWHASH");

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);
        Model model = new ExtendedModelMap();

        String view = controller.submit(me, "demo1234", "newpass88", "newpass88", model, req, resp);

        assertThat(view).isEqualTo("redirect:/login?passwordChanged");
        verify(memberMapper).updatePasswordHash(2L, "NEWHASH", false);   // 标记置 0
        verify(session).invalidate();                                    // ★ 真作废 session(修复核心)
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void wrongOldPassword_returnsForm_noChange_noLogout() {
        MemberPrincipal me = me();
        when(memberMapper.findById(2L)).thenReturn(Optional.of(
                Member.builder().id(2L).passwordHash("OLDHASH").build()));
        when(encoder.matches("wrong", "OLDHASH")).thenReturn(false);

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        Model model = new ExtendedModelMap();

        String view = controller.submit(me, "wrong", "newpass88", "newpass88", model, req, resp);

        assertThat(view).isEqualTo("profile/password");
        assertThat(model.getAttribute("error")).isEqualTo("原密码不正确");
        verify(memberMapper, never()).updatePasswordHash(anyLong(), anyString(), eq(false));
        verify(req, never()).getSession(false);   // 没改成功就不该动 session
    }
}
