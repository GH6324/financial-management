package com.family.finance.common;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 落地页路由。v0.7 第三批(FR-133):
 * <p>未初始化(零周期 或 零账户)的家庭 → 渲染首次引导页 {@code onboarding/index};
 * 否则 → redirect /dashboard(原行为)。
 * <p>这同时修了一个首登崩溃:旧实现无脑转 /dashboard,而 dashboard 在零周期时
 * {@code anchorPeriod()} 抛异常 → 全新部署首次登录 500(见 tech-design §九)。
 */
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final PeriodMapper periodMapper;
    private final AccountMapper accountMapper;

    @GetMapping("/")
    public String home(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        long fid = me.getFamilyId();
        boolean hasPeriod = periodMapper.countByFamily(fid) > 0;
        boolean hasAccount = !accountMapper.findActiveByFamily(fid).isEmpty();
        if (hasPeriod && hasAccount) {
            return "redirect:/dashboard";
        }
        model.addAttribute("hasAccount", hasAccount);
        model.addAttribute("hasPeriod", hasPeriod);
        return "onboarding/index";
    }
}
