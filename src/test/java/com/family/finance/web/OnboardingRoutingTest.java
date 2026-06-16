package com.family.finance.web;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.common.HomeController;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.member.Member;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v0.7 FR-133/134 · 落地页智能路由:
 * 未初始化(零周期 或 零账户)→ onboarding;有数据 → redirect dashboard。
 * 同时是首登 500 修复的回归保护(零周期不再无脑转 dashboard)。
 */
class OnboardingRoutingTest {

    private final PeriodMapper periodMapper = mock(PeriodMapper.class);
    private final AccountMapper accountMapper = mock(AccountMapper.class);
    private final HomeController controller = new HomeController(periodMapper, accountMapper);

    private MemberPrincipal principal() {
        return new MemberPrincipal(Member.builder().id(2L).familyId(1L).username("diwa").build());
    }

    @Test
    void zeroPeriodAndZeroAccount_goesToOnboarding() {
        when(periodMapper.countByFamily(1L)).thenReturn(0);
        when(accountMapper.findActiveByFamily(1L)).thenReturn(List.of());
        Model model = new ExtendedModelMap();
        assertThat(controller.home(principal(), model)).isEqualTo("onboarding/index");
        assertThat(model.getAttribute("hasAccount")).isEqualTo(false);
        assertThat(model.getAttribute("hasPeriod")).isEqualTo(false);
    }

    @Test
    void hasAccountButZeroPeriod_stillOnboarding() {
        when(periodMapper.countByFamily(1L)).thenReturn(0);
        when(accountMapper.findActiveByFamily(1L)).thenReturn(List.of(Account.builder().id(1L).build()));
        Model model = new ExtendedModelMap();
        assertThat(controller.home(principal(), model)).isEqualTo("onboarding/index");
        assertThat(model.getAttribute("hasAccount")).isEqualTo(true);
        assertThat(model.getAttribute("hasPeriod")).isEqualTo(false);
    }

    @Test
    void hasPeriodButZeroAccount_stillOnboarding() {
        when(periodMapper.countByFamily(1L)).thenReturn(2);
        when(accountMapper.findActiveByFamily(1L)).thenReturn(List.of());
        assertThat(controller.home(principal(), new ExtendedModelMap())).isEqualTo("onboarding/index");
    }

    @Test
    void hasPeriodAndAccount_redirectsToDashboard() {
        when(periodMapper.countByFamily(1L)).thenReturn(3);
        when(accountMapper.findActiveByFamily(1L)).thenReturn(List.of(Account.builder().id(1L).build()));
        assertThat(controller.home(principal(), new ExtendedModelMap())).isEqualTo("redirect:/dashboard");
    }
}
