package com.family.finance.service;

import com.family.finance.auth.SessionKiller;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.member.Member;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.member.MemberReferenceScanner;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * v1.15 FR-381/383 · 归档与删除的三道闸门。
 *
 * <p>这三条都不能只靠前端不渲染按钮 —— 按钮不渲染,POST 照样发得出去:
 * <ol>
 *   <li>不能归档/删除自己 —— 那是把自己锁在门外</li>
 *   <li>不能动最后一个活跃成员 —— 之后没有人能登录,连撤销的入口都进不去</li>
 *   <li>有引用不能删 —— 删人不该顺手把这些年的账一起删了</li>
 * </ol>
 */
class MemberArchiveDeleteTest {

    private final MemberMapper memberMapper = mock(MemberMapper.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final SessionKiller sessionKiller = mock(SessionKiller.class);
    private final MemberReferenceScanner scanner = mock(MemberReferenceScanner.class);

    private final AdminService svc =
            new AdminService(memberMapper, encoder, audit, sessionKiller, scanner);

    private static final MemberReferenceScanner.Scan CLEAN =
            new MemberReferenceScanner.Scan(List.of(), 0);

    private void member(boolean archived) {
        when(memberMapper.findById(7L)).thenReturn(Optional.of(Member.builder()
                .id(7L).familyId(1L).username("lisi").displayName("李四")
                .archivedAt(archived ? LocalDateTime.of(2026, 8, 15, 10, 0) : null)
                .build()));
    }

    // ---------- 归档 ----------

    @Test
    void archive_kicksSessionsAndTokens_butTouchesNothingElse() {
        member(false);
        when(memberMapper.countActiveByFamily(1L)).thenReturn(3);

        svc.archiveMember(1L, 7L, 1L);

        verify(memberMapper).archive(7L);
        verify(sessionKiller).killRememberMe("lisi");
        verify(sessionKiller).killAllSessions(7L);
        verify(audit).record(eq(1L), eq(1L), eq(AuditLogType.MEMBER_ARCHIVE),
                eq("member"), eq(7L), contains("李四"));
        // 归档不动钱、不改归属:除了 archive 本身,一行数据都不该被改写
        verify(memberMapper, never()).deleteById(anyLong());
        verify(memberMapper, never()).updateUsername(anyLong(), anyString());
        verify(memberMapper, never()).updateProfile(anyLong(), anyString(), anyString());
    }

    @Test
    void cannotArchiveYourself() {
        member(false);
        when(memberMapper.countActiveByFamily(1L)).thenReturn(3);

        assertThatThrownBy(() -> svc.archiveMember(1L, 7L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自己");

        verify(memberMapper, never()).archive(anyLong());
    }

    @Test
    void cannotArchiveTheLastActiveMember() {
        member(false);
        when(memberMapper.countActiveByFamily(1L)).thenReturn(1);

        assertThatThrownBy(() -> svc.archiveMember(1L, 7L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最后一个活跃成员");

        verify(memberMapper, never()).archive(anyLong());
    }

    @Test
    void archivingTwice_isANoOp() {
        member(true);

        svc.archiveMember(1L, 7L, 1L);

        verify(memberMapper, never()).archive(anyLong());
        verifyNoInteractions(audit);   // 不刷第二条「已归档」留痕
    }

    @Test
    void restore_putsThemBack() {
        member(true);

        svc.restoreMember(1L, 7L, 1L);

        verify(memberMapper).restore(7L);
        verify(audit).record(eq(1L), eq(1L), eq(AuditLogType.MEMBER_RESTORE),
                eq("member"), eq(7L), contains("李四"));
    }

    // ---------- 删除 ----------

    @Test
    void delete_requiresZeroReferences_andTellsYouWhatIsInTheWay() {
        member(true);
        when(scanner.scan(1L, 7L)).thenReturn(new MemberReferenceScanner.Scan(
                List.of(new MemberReferenceScanner.Ref("名下账户", 3),
                        new MemberReferenceScanner.Ref("记的收支流水", 12)), 15));

        assertThatThrownBy(() -> svc.deleteMember(1L, 7L, "lisi", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("15")
                .hasMessageContaining("名下账户 3")
                .hasMessageContaining("记的收支流水 12")
                .hasMessageContaining("归档");

        verify(memberMapper, never()).deleteById(anyLong());
    }

    @Test
    void delete_rescansInsideTheTransaction_notTrustingThePageNumbers() {
        member(true);
        when(scanner.scan(1L, 7L)).thenReturn(CLEAN);

        svc.deleteMember(1L, 7L, "lisi", 1L);

        // 页面上的数字是上一次请求时的,中间可能又新增了引用 → 事务内必须重扫一遍
        verify(scanner).scan(1L, 7L);
        verify(sessionKiller).killRememberMe("lisi");
        verify(memberMapper).deleteById(7L);
        verify(audit).record(eq(1L), eq(1L), eq(AuditLogType.MEMBER_DELETE),
                eq("member"), eq(7L), contains("李四"));
    }

    @Test
    void delete_needsTheUsernameTypedBackExactly() {
        member(true);
        when(scanner.scan(1L, 7L)).thenReturn(CLEAN);

        assertThatThrownBy(() -> svc.deleteMember(1L, 7L, "LISI", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("对不上");
        assertThatThrownBy(() -> svc.deleteMember(1L, 7L, null, 1L))
                .isInstanceOf(IllegalArgumentException.class);

        verify(memberMapper, never()).deleteById(anyLong());
        verify(scanner, never()).scan(anyLong(), anyLong());   // 确认词都没对上,不必去扫库
    }

    @Test
    void cannotDeleteYourself() {
        member(true);

        assertThatThrownBy(() -> svc.deleteMember(1L, 7L, "lisi", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自己");

        verify(memberMapper, never()).deleteById(anyLong());
    }

    @Test
    void cannotDeleteTheLastActiveMember() {
        member(false);   // 还没归档就直接删
        when(memberMapper.countActiveByFamily(1L)).thenReturn(1);

        assertThatThrownBy(() -> svc.deleteMember(1L, 7L, "lisi", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最后一个活跃成员");

        verify(memberMapper, never()).deleteById(anyLong());
    }
}
