package com.family.finance.service.ask;

import com.family.finance.domain.ask.AskAccessToken;
import com.family.finance.domain.ask.AskAuditResult;
import com.family.finance.domain.ask.AskScope;
import com.family.finance.repository.AskAccessTokenMapper;
import com.family.finance.repository.AskAuditMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.19 · 接入凭据的行为护栏。
 *
 * <p>这一批测试守的不是「函数返回对不对」,是<b>四件会直接导致用户丢数据或不敢用的事</b>:</p>
 * <ol>
 *   <li><b>换绑期间不许断服务</b> —— 凭据存在百炼那一侧,而百炼改配置要「停止部署 → 改 → 重新部署」。
 *       如果生成新密钥的瞬间就吊销旧的,每次轮换必然断一次;那会让人干脆不轮换,<b>反而更不安全</b>。</li>
 *   <li><b>换绑必须能收尾</b> —— 新密钥第一次被真的用了,旧的就得立刻失效。
 *       用<b>事实</b>收尾,不是定时器猜。</li>
 *   <li><b>续期不许换密钥</b> —— 换了就意味着用户又得去百炼跑一趟。
 *       多数人点「重新生成」只是因为看到「即将过期」,这条把那类需求变成零成本。</li>
 *   <li><b>紧急断开要断干净</b> —— 含换绑中的那把新密钥。漏一把等于没断。</li>
 * </ol>
 *
 * <p>另外钉住:明文<b>只出现一次</b>、库里<b>只有 hash</b>、未通过的几种判定<b>在审计里分得开</b>
 * (对外都是 404,但用户要看得懂是「过期了」还是「填错了」)。</p>
 */
class AccessTokenServiceTest {

    private static final long FAM = 1L;

    private final AskAccessTokenMapper tokenMapper = mock(AskAccessTokenMapper.class);
    private final AskAuditMapper auditMapper = mock(AskAuditMapper.class);
    private AccessTokenService svc;

    /** 用一个内存表模拟 mapper —— 换绑逻辑跨多行,纯 stub 表达不了 */
    private final List<AskAccessToken> rows = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    @BeforeEach
    void setUp() {
        rows.clear();
        seq.set(0);

        when(tokenMapper.insert(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            AskAccessToken t = inv.getArgument(0);
            t.setId(seq.incrementAndGet());
            t.setCreatedAt(LocalDateTime.now());
            rows.add(t);
            return 1;
        });
        when(tokenMapper.findByHash(anyString())).thenAnswer(inv -> rows.stream()
                .filter(t -> t.getTokenHash().equals(inv.getArgument(0))).findFirst());
        when(tokenMapper.findByAccessPoint(anyLong())).thenAnswer(inv -> rows.stream()
                .filter(t -> t.getAccessPointId().equals(inv.getArgument(0))).toList());
        when(tokenMapper.maxAccessPointId(anyLong())).thenAnswer(inv -> rows.stream()
                .mapToLong(AskAccessToken::getAccessPointId).max().orElse(0L));
        when(tokenMapper.revoke(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            rows.stream().filter(t -> t.getId() == id && t.getRevokedAt() == null)
                    .forEach(t -> t.setRevokedAt(LocalDateTime.now()));
            return 1;
        });
        when(tokenMapper.revokeAccessPoint(anyLong())).thenAnswer(inv -> {
            long pid = inv.getArgument(0);
            long n = rows.stream().filter(t -> t.getAccessPointId() == pid && t.getRevokedAt() == null).count();
            rows.stream().filter(t -> t.getAccessPointId() == pid)
                    .forEach(t -> { if (t.getRevokedAt() == null) t.setRevokedAt(LocalDateTime.now()); });
            return (int) n;
        });
        when(tokenMapper.markSuperseded(anyLong(), anyLong())).thenAnswer(inv -> {
            long oldId = inv.getArgument(0), newId = inv.getArgument(1);
            rows.stream().filter(t -> t.getId() == oldId).forEach(t -> t.setSupersededBy(newId));
            return 1;
        });
        when(tokenMapper.renew(anyLong(), org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            rows.stream().filter(t -> t.getId() == id).forEach(t -> t.setExpiresAt(inv.getArgument(1)));
            return 1;
        });
        when(tokenMapper.countUsable(anyLong())).thenAnswer(inv -> (int) rows.stream()
                .filter(t -> t.getRevokedAt() == null && t.getExpiresAt().isAfter(LocalDateTime.now())).count());
        // 单家庭部署,与 countUsable 同解;verify 用它判「功能压根没开」
        when(tokenMapper.countUsableAll()).thenAnswer(inv -> (int) rows.stream()
                .filter(t -> t.getRevokedAt() == null && t.getExpiresAt().isAfter(LocalDateTime.now())).count());

        svc = new AccessTokenService(tokenMapper, auditMapper);
    }

    // ─────────────────────── 生成与存储 ───────────────────────

    /** 明文只在返回值里出现一次;库里只有 hash,没有任何地方能倒回明文。 */
    @Test
    void 明文只出现一次_库里只有hash() {
        var issued = svc.create(FAM, "百炼-家庭助手", AskScope.AGGREGATE, 90);

        assertThat(issued.plaintext()).startsWith(AccessTokenService.PREFIX);
        assertThat(issued.token().getTokenHash())
                .as("库里存的必须是 hash,不能等于明文").isNotEqualTo(issued.plaintext());
        assertThat(issued.token().getTokenHash()).hasSize(64);   // SHA-256 hex
        assertThat(rows).allSatisfy(t ->
                assertThat(t.getTokenHash()).doesNotContain(issued.plaintext()));
    }

    /**
     * 前缀有两个实际用途:被贴进公开仓库时 secret scanning 能按它告警;
     * 审计页能显示是哪一把 —— <b>而不必存明文</b>。
     */
    @Test
    void 前缀可识别且不足以还原明文() {
        var issued = svc.create(FAM, "x", AskScope.AGGREGATE, 90);
        String prefix = issued.token().getTokenPrefix();

        assertThat(prefix).startsWith(AccessTokenService.PREFIX);
        assertThat(prefix.length()).isLessThan(issued.plaintext().length() / 2)
                .as("前缀只能是识别用的一小段,不能泄露大半个口令");
    }

    /** 两把凭据不许撞 —— 随机源要真的随机。 */
    @Test
    void 每把凭据都不同() {
        var a = svc.create(FAM, "a", AskScope.AGGREGATE, 90);
        var b = svc.create(FAM, "b", AskScope.AGGREGATE, 90);
        assertThat(a.plaintext()).isNotEqualTo(b.plaintext());
        assertThat(a.token().getTokenHash()).isNotEqualTo(b.token().getTokenHash());
        assertThat(a.token().getAccessPointId()).isNotEqualTo(b.token().getAccessPointId());
    }

    // ─────────────────────── 校验 ───────────────────────

    @Test
    void 正确口令通过() {
        var issued = svc.create(FAM, "x", AskScope.AGGREGATE, 90);
        var v = svc.verify("Bearer " + issued.plaintext(), AskScope.AGGREGATE);
        assertThat(v.ok()).isTrue();
        assertThat(v.result()).isEqualTo(AskAuditResult.OK);
    }

    /**
     * 未通过的几种<b>对外都是 404</b>,但审计里必须分得开 ——
     * 否则用户只看到一片红,不知道该「续期」还是「重填」。
     */
    @Test
    void 未通过的几种在审计里分得开() {
        var issued = svc.create(FAM, "x", AskScope.AGGREGATE, 90);

        assertThat(svc.verify("Bearer fmk_wrongwrongwrong", AskScope.AGGREGATE).result())
                .isEqualTo(AskAuditResult.INVALID);
        assertThat(svc.verify(null, AskScope.AGGREGATE).result()).isEqualTo(AskAuditResult.INVALID);
        assertThat(svc.verify("Bearer 不带前缀的东西", AskScope.AGGREGATE).result())
                .isEqualTo(AskAuditResult.INVALID);

        rows.get(0).setExpiresAt(LocalDateTime.now().minusDays(1));
        assertThat(svc.verify("Bearer " + issued.plaintext(), AskScope.AGGREGATE).result())
                .as("过期要能和「填错了」分开").isEqualTo(AskAuditResult.EXPIRED);

        rows.get(0).setExpiresAt(LocalDateTime.now().plusDays(1));
        rows.get(0).setRevokedAt(LocalDateTime.now());
        assertThat(svc.verify("Bearer " + issued.plaintext(), AskScope.AGGREGATE).result())
                .isEqualTo(AskAuditResult.REVOKED);
    }

    /** 数据最小化:aggregate 的凭据够不到 detail。 */
    @Test
    void scope不够时拒绝() {
        var issued = svc.create(FAM, "x", AskScope.AGGREGATE, 90);
        assertThat(svc.verify("Bearer " + issued.plaintext(), AskScope.DETAIL).result())
                .isEqualTo(AskAuditResult.SCOPE);
        assertThat(svc.verify("Bearer " + issued.plaintext(), AskScope.AGGREGATE).ok()).isTrue();
    }

    @Test
    void detail覆盖aggregate() {
        var issued = svc.create(FAM, "x", AskScope.DETAIL, 90);
        assertThat(svc.verify("Bearer " + issued.plaintext(), AskScope.AGGREGATE).ok()).isTrue();
        assertThat(svc.verify("Bearer " + issued.plaintext(), AskScope.DETAIL).ok()).isTrue();
    }

    // ─────────────────── 换绑(本文件的重点)───────────────────

    /**
     * <b>换绑期间新旧都必须能用。</b>
     *
     * <p>凭据存在百炼那一侧,而百炼改配置要「停止部署 → 改 → 重新部署」。
     * 如果实现成「生成新的即吊销旧的」,用户在百炼操作的那几分钟里服务是断的 ——
     * 那会让人<b>干脆不轮换</b>,反而更不安全。</p>
     */
    @Test
    void 换绑期间新旧口令都能用_不许断服务() {
        var old = svc.create(FAM, "百炼", AskScope.AGGREGATE, 90);
        long point = old.token().getAccessPointId();
        var fresh = svc.rotate(FAM, point);

        assertThat(svc.verify("Bearer " + old.plaintext(), AskScope.AGGREGATE).ok())
                .as("旧口令在换绑期间必须仍然有效").isTrue();
        assertThat(svc.verify("Bearer " + fresh.plaintext(), AskScope.AGGREGATE).ok())
                .as("新口令当然也要能用").isTrue();
    }

    /** 换绑期间仍在用旧口令 → 审计标 OK_OLD,页面据此催用户去百炼换完。 */
    @Test
    void 还在用旧口令时审计标得出来() {
        var old = svc.create(FAM, "百炼", AskScope.AGGREGATE, 90);
        svc.rotate(FAM, old.token().getAccessPointId());
        assertThat(svc.verify("Bearer " + old.plaintext(), AskScope.AGGREGATE).result())
                .isEqualTo(AskAuditResult.OK_OLD);
    }

    /**
     * <b>换绑靠事实收尾,不靠定时器。</b>
     * 新口令第一次被真的用了 → 旧口令立刻失效。
     */
    @Test
    void 新口令首次被使用后旧口令立刻失效() {
        var old = svc.create(FAM, "百炼", AskScope.AGGREGATE, 90);
        var fresh = svc.rotate(FAM, old.token().getAccessPointId());

        var first = svc.verify("Bearer " + fresh.plaintext(), AskScope.AGGREGATE);
        assertThat(first.result()).isEqualTo(AskAuditResult.OK_NEW);

        assertThat(svc.verify("Bearer " + old.plaintext(), AskScope.AGGREGATE).result())
                .as("换绑完成后,旧口令必须立刻不能用").isEqualTo(AskAuditResult.REVOKED);
        assertThat(svc.verify("Bearer " + fresh.plaintext(), AskScope.AGGREGATE).ok()).isTrue();
    }

    /** 同一接入点最多两把并存 —— 不允许点几次「更换」就堆出一串有效口令。 */
    @Test
    void 不许无限堆积换绑() {
        var old = svc.create(FAM, "百炼", AskScope.AGGREGATE, 90);
        svc.rotate(FAM, old.token().getAccessPointId());
        assertThatThrownBySecondRotate(old.token().getAccessPointId());
    }

    private void assertThatThrownBySecondRotate(long point) {
        try {
            svc.rotate(FAM, point);
            org.assertj.core.api.Assertions.fail("第二次换绑应当被拒绝");
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("换绑");
        }
    }

    // ─────────────────── 续期 / 紧急断开 ───────────────────

    /**
     * <b>续期不许改密钥。</b>
     * 改了就意味着用户又得去百炼跑一趟 —— 而多数人点「重新生成」其实只是看到「即将过期」。
     */
    @Test
    void 续期不改密钥() {
        var issued = svc.create(FAM, "x", AskScope.AGGREGATE, 1);
        String hashBefore = rows.get(0).getTokenHash();
        LocalDateTime expBefore = rows.get(0).getExpiresAt();

        svc.renew(issued.token().getId(), 90);

        assertThat(rows.get(0).getTokenHash()).as("续期绝不能换密钥").isEqualTo(hashBefore);
        assertThat(rows.get(0).getExpiresAt()).isAfter(expBefore);
        assertThat(svc.verify("Bearer " + issued.plaintext(), AskScope.AGGREGATE).ok())
                .as("续期后原口令照常可用").isTrue();
    }

    /** 紧急断开要连换绑中的那把新密钥一起干掉 —— 漏一把等于没断。 */
    @Test
    void 紧急断开要断干净_含换绑中的新密钥() {
        var old = svc.create(FAM, "百炼", AskScope.AGGREGATE, 90);
        var fresh = svc.rotate(FAM, old.token().getAccessPointId());

        svc.killAccessPoint(old.token().getAccessPointId());

        assertThat(svc.verify("Bearer " + old.plaintext(), AskScope.AGGREGATE).ok()).isFalse();
        assertThat(svc.verify("Bearer " + fresh.plaintext(), AskScope.AGGREGATE).ok())
                .as("换绑中的新密钥也必须一起失效").isFalse();
    }

    /** 没有任何可用凭据 = 功能未启用 = 端点 404。 */
    @Test
    void 没有可用凭据时功能视为未启用() {
        assertThat(svc.enabled(FAM)).isFalse();
        var issued = svc.create(FAM, "x", AskScope.AGGREGATE, 90);
        assertThat(svc.enabled(FAM)).isTrue();
        svc.killAccessPoint(issued.token().getAccessPointId());
        assertThat(svc.enabled(FAM)).isFalse();
    }

    /** 到期提醒的判据 */
    @Test
    void 到期天数算得对() {
        var issued = svc.create(FAM, "x", AskScope.AGGREGATE, 90);
        assertThat(issued.token().daysToExpiry(LocalDateTime.now())).isBetween(88L, 90L);
        rows.get(0).setExpiresAt(LocalDateTime.now().plusDays(3));
        assertThat(rows.get(0).daysToExpiry(LocalDateTime.now()))
                .isLessThan(AccessTokenService.WARN_DAYS);
    }

    @Test
    @DisplayName("一把凭据都没发过 → 判 OFF,不是 INVALID")
    void 功能没开时判OFF() {
        // 对外都是 404,分不出差别;审计里必须分得清 ——
        // 一串 INVALID 是有人在探,一串 OFF 只是功能没开着。混在一起,被扫了也看不出来。
        var v = svc.verify("Bearer fmk_whatever", AskScope.AGGREGATE);
        assertThat(v.result()).isEqualTo(AskAuditResult.OFF);
        assertThat(v.ok()).isFalse();
    }

    @Test
    @DisplayName("功能开着但口令是错的 → INVALID(不能被 OFF 盖掉)")
    void 开着的时候错口令判INVALID() {
        svc.create(FAM, "x", AskScope.AGGREGATE, 90);
        assertThat(svc.verify("Bearer fmk_wrongwrongwrong", AskScope.AGGREGATE).result())
                .isEqualTo(AskAuditResult.INVALID);
    }

    @Test
    @DisplayName("唯一一把口令过期 → 仍报 EXPIRED,不能退化成 OFF")
    void 唯一口令过期仍报EXPIRED() {
        // 过期意味着用户**确实配过**,只是断了。报成 OFF 的话管理页没法提示他去续期,
        // 而那恰恰是最该被提示的场景。
        var issued = svc.create(FAM, "x", AskScope.AGGREGATE, 90);
        rows.get(0).setExpiresAt(LocalDateTime.now().minusDays(1));
        assertThat(svc.verify("Bearer " + issued.plaintext(), AskScope.AGGREGATE).result())
                .isEqualTo(AskAuditResult.EXPIRED);
    }
}
