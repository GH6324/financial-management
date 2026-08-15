package com.family.finance.service;

import com.family.finance.auth.SessionKiller;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.member.Member;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.member.MemberReferenceScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Admin 操作的横切服务:重置密码、编辑成员资料 等。
 * 操作权限:v0.1 任何成员都可触发,但每次都写 audit_log。
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    /** 临时密码字符表 — 去掉易混淆字符(0/O, 1/l, I) */
    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    /** v1.15 FR-380 · 登录名格式 —— 与添加成员表单(members.html)的 pattern 保持一致 */
    private static final Pattern USERNAME_RE = Pattern.compile("[a-zA-Z0-9_]{3,40}");

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final SessionKiller sessionKiller;
    private final MemberReferenceScanner referenceScanner;

    /** 生成一段 12 字符的临时密码,落入 password_hash 并 must_change_pw=1。返回明文(只显示一次)。 */
    public String resetPassword(long familyId, long targetMemberId, Long actorMemberId) {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)]);
        }
        String plain = sb.toString();
        String hash = passwordEncoder.encode(plain);
        memberMapper.updatePasswordHash(targetMemberId, hash, true);
        auditLogService.record(familyId, actorMemberId, AuditLogType.PASSWORD_RESET,
                "member", targetMemberId,
                "重置密码 · 临时密码已生成(only-once,管理员当面/微信告诉对方)");
        return plain;
    }

    public void updateMemberProfile(long familyId, long targetMemberId, String displayName, String roleLabel,
                                    Long actorMemberId) {
        memberMapper.updateProfile(targetMemberId, displayName, roleLabel);
        auditLogService.record(familyId, actorMemberId, AuditLogType.FAMILY_UPDATE,
                "member", targetMemberId,
                "成员资料更新:%s · %s".formatted(displayName, roleLabel == null ? "—" : roleLabel));
    }

    /**
     * 添加新成员(同一家庭内)。生成 12 位临时密码 + must_change_pw=1,
     * 返回明文供管理员当面/即时通讯告知对方。
     */
    public String createMember(long familyId, String username, String displayName, String roleLabel,
                                Long actorMemberId) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名必填");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("显示名必填");
        }
        if (memberMapper.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名已被占用");
        }
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)]);
        }
        String plain = sb.toString();
        Member m = Member.builder()
                .familyId(familyId)
                .username(username)
                .passwordHash(passwordEncoder.encode(plain))
                .displayName(displayName)
                .roleLabel(roleLabel)
                .mustChangePw(true)
                .build();
        memberMapper.insert(m);
        auditLogService.record(familyId, actorMemberId, AuditLogType.FAMILY_UPDATE,
                "member", m.getId(),
                "添加成员 · " + displayName + " · 临时密码已生成(only-once)");
        return plain;
    }

    // =====================================================================
    // v1.15 · 会员身份:改登录名 / 归档 / 撤销归档 / 有条件删除
    // =====================================================================

    /**
     * v1.15 FR-380 · 修改登录名。
     *
     * <p>顺序是有讲究的,不能换:
     * <ol>
     *   <li>先清 {@code persistent_logins} —— 那张表按 <b>username</b> 记账。名字改完再清就晚了:
     *       旧行变成谁也删不掉的孤儿,而那张票根照样能把人自动登回来。</li>
     *   <li>再 UPDATE username。</li>
     *   <li>提交之后才踢会话 —— 事务万一回滚,人不该白掉线。</li>
     * </ol>
     *
     * <p>唯一性检查做了两遍,不是冗余:{@code existsUsername} 是为了给用户一句人话
     * (「这个登录名已经有人用了」),抓 {@code DuplicateKeyException} 是为了正确性
     * (两个人同时改成同一个名字,应用层的检查中间有窗口,兜底得靠数据库的唯一索引)。
     */
    @Transactional
    public void renameUsername(long familyId, long targetMemberId, String rawUsername, Long actorMemberId) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        if (!USERNAME_RE.matcher(username).matches()) {
            throw new IllegalArgumentException("登录名只能用字母、数字、下划线,长度 3–40");
        }
        Member m = requireMember(familyId, targetMemberId);
        String old = m.getUsername();
        if (username.equals(old)) {
            return; // 没变就什么都不做,别留一条「a → a」的假留痕
        }
        // 查的是全表:归档的人也还占着他的登录名
        if (memberMapper.existsUsername(username) > 0) {
            throw new IllegalArgumentException("登录名「" + username + "」已被占用");
        }
        sessionKiller.killRememberMe(old);
        try {
            memberMapper.updateUsername(targetMemberId, username);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("登录名「" + username + "」已被占用");
        }
        auditLogService.record(familyId, actorMemberId, AuditLogType.MEMBER_RENAME,
                "member", targetMemberId, "登录名变更:" + old + " → " + username);
        afterCommit(() -> sessionKiller.killAllSessions(targetMemberId));
    }

    /**
     * v1.15 FR-381 · 归档成员。
     *
     * <p>三条硬约束(PRD §FR-381),都在这里守,不能只靠前端不渲染按钮:
     * 不许归档自己、不许归档最后一个活跃成员、归档当场生效(踢会话 + 清票根)。
     *
     * <p>归档<b>不动钱</b>:所有金额口径按的是账户的 {@code archived_at},不是成员的 ——
     * 一个人退出打理,他名下的账户和历史流水照旧计入家庭总账。这一点由
     * {@code MemberArchiveMoneyInvarianceTest} 守着。
     */
    @Transactional
    public void archiveMember(long familyId, long targetMemberId, Long actorMemberId) {
        Member m = requireMember(familyId, targetMemberId);
        if (actorMemberId != null && actorMemberId == targetMemberId) {
            throw new IllegalArgumentException("不能归档自己 —— 那会把自己锁在门外");
        }
        if (m.isArchived()) {
            return;
        }
        if (memberMapper.countActiveByFamily(familyId) <= 1) {
            throw new IllegalArgumentException("这是最后一个活跃成员,归档之后就没人能登录了");
        }
        memberMapper.archive(targetMemberId);
        auditLogService.record(familyId, actorMemberId, AuditLogType.MEMBER_ARCHIVE,
                "member", targetMemberId, "归档成员:" + m.getDisplayName() + "(" + m.getUsername() + ")");
        sessionKiller.killRememberMe(m.getUsername());
        afterCommit(() -> sessionKiller.killAllSessions(targetMemberId));
    }

    /** v1.15 FR-381 · 撤销归档 —— 归档是可逆的,这是它敢做成一键的前提。 */
    @Transactional
    public void restoreMember(long familyId, long targetMemberId, Long actorMemberId) {
        Member m = requireMember(familyId, targetMemberId);
        if (!m.isArchived()) {
            return;
        }
        memberMapper.restore(targetMemberId);
        auditLogService.record(familyId, actorMemberId, AuditLogType.MEMBER_RESTORE,
                "member", targetMemberId, "撤销归档:" + m.getDisplayName() + "(" + m.getUsername() + ")");
    }

    /**
     * v1.15 FR-383 · 物理删除 —— <b>只有零引用的成员才删得掉</b>。
     *
     * <p>不做级联删除:一个人身上挂着三年的流水,「删掉这个人」绝不能顺手把那三年的钱一起删了。
     * 有引用就只能归档 —— 归档能达到同样的目的(名单里不再出现),而且可逆。
     *
     * <p>扫描在事务内重做一遍:页面上显示的数字是上一次请求时的,中间可能又新增了引用。
     */
    @Transactional
    public void deleteMember(long familyId, long targetMemberId, String confirmUsername, Long actorMemberId) {
        Member m = requireMember(familyId, targetMemberId);
        if (actorMemberId != null && actorMemberId == targetMemberId) {
            throw new IllegalArgumentException("不能删除自己");
        }
        if (!m.getUsername().equals(confirmUsername == null ? null : confirmUsername.trim())) {
            throw new IllegalArgumentException("确认用的登录名对不上,没有删除");
        }
        if (!m.isArchived() && memberMapper.countActiveByFamily(familyId) <= 1) {
            throw new IllegalArgumentException("这是最后一个活跃成员,删了就没人能登录了");
        }
        MemberReferenceScanner.Scan scan = referenceScanner.scan(familyId, targetMemberId);
        if (!scan.deletable()) {
            throw new IllegalArgumentException(
                    "ta 名下还有 " + scan.total() + " 条记录(" + describe(scan) + "),不能删除 —— 请改用归档");
        }
        sessionKiller.killRememberMe(m.getUsername());
        memberMapper.deleteById(targetMemberId);
        auditLogService.record(familyId, actorMemberId, AuditLogType.MEMBER_DELETE,
                "member", targetMemberId,
                "删除成员:" + m.getDisplayName() + "(" + m.getUsername() + ")· 删除前零引用");
        afterCommit(() -> sessionKiller.killAllSessions(targetMemberId));
    }

    private static String describe(MemberReferenceScanner.Scan scan) {
        StringBuilder sb = new StringBuilder();
        for (MemberReferenceScanner.Ref r : scan.refs()) {
            if (!sb.isEmpty()) sb.append("、");
            sb.append(r.label()).append(' ').append(r.count());
        }
        return sb.toString();
    }

    /** 家庭内取成员 —— 顺手挡住「拿别人家的 id 来改」。 */
    private Member requireMember(long familyId, long memberId) {
        Member m = memberMapper.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("成员不存在"));
        if (m.getFamilyId() == null || m.getFamilyId() != familyId) {
            throw new IllegalArgumentException("成员不存在");
        }
        return m;
    }

    /**
     * 事务提交后再执行(踢会话这类"对外副作用"不该在回滚时白发生)。
     * 没有活动事务时(单测直调)就地执行,语义一致。
     */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }
}
