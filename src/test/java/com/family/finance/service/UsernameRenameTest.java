package com.family.finance.service;

import com.family.finance.auth.SessionKiller;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.member.Member;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.member.MemberReferenceScanner;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * v1.15 FR-380 · 改登录名。
 *
 * <p>核心断言是<b>顺序</b>:{@code persistent_logins} 那张表按 username 记账。
 * 名字改完再去清票根,清的是新名字 —— 旧行谁也删不掉,而那张票根照样能把人自动登回来。
 * 这不是理论风险:「记住我」的 cookie 有效期以周计。
 */
class UsernameRenameTest {

    private final MemberMapper memberMapper = mock(MemberMapper.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final SessionKiller sessionKiller = mock(SessionKiller.class);
    private final MemberReferenceScanner scanner = mock(MemberReferenceScanner.class);

    private final AdminService svc =
            new AdminService(memberMapper, encoder, audit, sessionKiller, scanner);

    private void existing(String username) {
        when(memberMapper.findById(7L)).thenReturn(Optional.of(Member.builder()
                .id(7L).familyId(1L).username(username).displayName("李四").build()));
    }

    @Test
    void rememberMeTokensAreClearedBeforeTheUsernameChanges() {
        existing("lisi");

        svc.renameUsername(1L, 7L, "li_si", 1L);

        InOrder order = inOrder(sessionKiller, memberMapper);
        order.verify(sessionKiller).killRememberMe("lisi");     // 先按旧名清票根
        order.verify(memberMapper).updateUsername(7L, "li_si"); // 再改名
        // 事务外直调 → afterCommit 就地执行 → 在线会话当场作废
        verify(sessionKiller).killAllSessions(7L);
        verify(audit).record(eq(1L), eq(1L), eq(AuditLogType.MEMBER_RENAME),
                eq("member"), eq(7L), contains("lisi → li_si"));
    }

    @Test
    void unchangedUsername_isANoOp_andLeavesNoFakeAuditTrail() {
        existing("lisi");

        svc.renameUsername(1L, 7L, "  lisi  ", 1L);   // 只是多敲了空格

        verify(memberMapper, never()).updateUsername(anyLong(), anyString());
        verify(sessionKiller, never()).killRememberMe(anyString());
        verify(sessionKiller, never()).killAllSessions(anyLong());
        verifyNoInteractions(audit);   // 不留「lisi → lisi」这种假留痕
    }

    @Test
    void illegalFormat_isRejectedBeforeAnythingHappens() {
        existing("lisi");

        assertThatThrownBy(() -> svc.renameUsername(1L, 7L, "li si", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字母");
        assertThatThrownBy(() -> svc.renameUsername(1L, 7L, "ab", 1L))
                .isInstanceOf(IllegalArgumentException.class);

        verify(memberMapper, never()).updateUsername(anyLong(), anyString());
        verify(sessionKiller, never()).killRememberMe(anyString());
    }

    @Test
    void takenUsername_getsAPlainSentence_notAStackTrace() {
        existing("lisi");
        when(memberMapper.existsUsername("diwa")).thenReturn(1);

        assertThatThrownBy(() -> svc.renameUsername(1L, 7L, "diwa", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已被占用");

        verify(memberMapper, never()).updateUsername(anyLong(), anyString());
    }

    @Test
    void raceOnTheUniqueIndex_isAlsoTurnedIntoAPlainSentence() {
        existing("lisi");
        when(memberMapper.existsUsername("diwa")).thenReturn(0);   // 应用层检查这一刻还没人占
        doThrow(new DuplicateKeyException("Duplicate entry 'diwa' for key 'uk_member_username'"))
                .when(memberMapper).updateUsername(7L, "diwa");

        // 两个人同时改成同一个名字 —— 应用层检查和 UPDATE 之间有窗口,兜底靠数据库唯一索引
        assertThatThrownBy(() -> svc.renameUsername(1L, 7L, "diwa", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已被占用");
    }

    @Test
    void memberFromAnotherFamily_isNotReachable() {
        when(memberMapper.findById(7L)).thenReturn(Optional.of(Member.builder()
                .id(7L).familyId(999L).username("lisi").displayName("李四").build()));

        assertThatThrownBy(() -> svc.renameUsername(1L, 7L, "li_si", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成员不存在");

        verify(memberMapper, never()).updateUsername(anyLong(), anyString());
        verify(sessionKiller, never()).killRememberMe(anyString());
    }
}
