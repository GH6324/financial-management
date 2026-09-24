package com.family.finance.service.checkup;

import com.family.finance.service.checkup.rule.Advice;
import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * issue #22 · 体检「值得做的事」的「不适用」。
 *
 * <p>原来那个按钮调用一个从没写过的前端函数,点了没反应。现在「不适用」存在家庭级配置里,
 * 这里把存取规则钉住:按「规则 + 账户」记、刷新后仍然生效、能恢复、恢复只恢复这一页的。</p>
 */
class AdviceDismissServiceTest {

    private final Map<String, String> store = new HashMap<>();
    private AdviceDismissService svc;

    @BeforeEach
    void setUp() {
        FamilyConfigService cfg = mock(FamilyConfigService.class);
        when(cfg.getString(anyLong(), anyString(), anyString()))
                .thenAnswer(i -> store.getOrDefault(i.getArgument(1), i.getArgument(2)));
        doAnswer(i -> { store.put(i.getArgument(1), i.getArgument(2)); return null; })
                .when(cfg).set(anyLong(), anyString(), anyString());
        svc = new AdviceDismissService(cfg);
    }

    private static Advice fam(String rule) {
        return Advice.of(rule, Advice.Scope.FAMILY, null, Advice.Dimension.LIQUIDITY,
                Advice.Severity.WARN, "c", "t", "b", null);
    }

    private static Advice acct(String rule, long id) {
        return Advice.of(rule, Advice.Scope.ACCOUNT, id, Advice.Dimension.LIQUIDITY,
                Advice.Severity.WARN, "c", "t", "b", null);
    }

    @Test
    @DisplayName("标成不适用 → 这条不再显示,别的照常;而且是存下来的(新的一次读取也生效)")
    void dismissHidesAndPersists() {
        List<Advice> all = List.of(fam("F1"), fam("F2"));
        svc.dismiss(1L, "F1", null);
        assertThat(svc.visible(1L, all)).extracting(Advice::ruleId).containsExactly("F2");
        assertThat(svc.hiddenCount(1L, all)).isEqualTo(1);
        assertThat(store.get(FamilyConfigService.K_CHECKUP_ADVICE_DISMISSED)).isEqualTo("F1|*");
    }

    /** 同一条规则在 A 账户不适用,不代表在 B 账户也不适用 */
    @Test
    @DisplayName("按「规则 + 账户」记:A 账户标了,B 账户同一条规则照常显示")
    void perAccount() {
        svc.dismiss(1L, "A1", 10L);
        assertThat(svc.visible(1L, List.of(acct("A1", 10L)))).isEmpty();
        assertThat(svc.visible(1L, List.of(acct("A1", 20L)))).hasSize(1);
    }

    @Test
    @DisplayName("家庭页的「恢复」只恢复家庭级的,账户页的只恢复那个账户的")
    void restoreIsScoped() {
        svc.dismiss(1L, "F1", null);
        svc.dismiss(1L, "A1", 10L);
        svc.dismiss(1L, "A1", 20L);
        svc.restoreAll(1L, null);
        assertThat(svc.dismissed(1L)).containsExactlyInAnyOrder("A1|10", "A1|20");
        svc.restoreAll(1L, 10L);
        assertThat(svc.dismissed(1L)).containsExactly("A1|20");
    }

    @Test
    @DisplayName("重复标记不重复记;非法规则编号(含分隔符)不收")
    void idempotentAndSafe() {
        svc.dismiss(1L, "F1", null);
        svc.dismiss(1L, "F1", null);
        svc.dismiss(1L, "bad|id", null);
        svc.dismiss(1L, "bad\nid", null);
        svc.dismiss(1L, "  ", null);
        assertThat(svc.dismissed(1L)).containsExactly("F1|*");
    }

    /** 配置值是 512 字符的列,超出时丢最早标记的,不能存失败 */
    @Test
    @DisplayName("超长时丢最早的那些,存得下")
    void capsLength() {
        for (int i = 0; i < 80; i++) svc.dismiss(1L, "RULE_NUMBER_" + i, (long) i);
        String v = store.get(FamilyConfigService.K_CHECKUP_ADVICE_DISMISSED);
        assertThat(v.length()).isLessThanOrEqualTo(500);
        assertThat(svc.dismissed(1L)).contains("RULE_NUMBER_79|79").doesNotContain("RULE_NUMBER_0|0");
    }
}
