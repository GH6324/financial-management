package com.family.finance.web.ask;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.analysis.AnalysisTemplateService;
import com.family.finance.service.ask.AskRememberParser;
import com.family.finance.web.analysis.AnalysisUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * v1.27 · 「记住这条分析偏好?」确认卡的视图模型(PRD FR-854 / FR-849)。
 *
 * <p>历史消息(Thymeleaf 直接渲染)和刚流完的那条(前端向 {@code /ask/remember/card} 要一段片段)
 * 走同一个方法、同一个片段 —— 两份实现会在「刷新一下样子变了」里露馅。</p>
 */
@Component("askRememberView")
@RequiredArgsConstructor
public class AskRememberView {

    private final AccountMapper accountMapper;
    private final AnalysisTemplateService templateService;
    private final com.family.finance.service.analysis.AnalysisPreferenceService preferenceService;

    /**
     * @param excludeAccountId 「把 X 标成不参与配置分析」直达哪个账户(对上本家在册、非贷款、还没标的账户才有)
     * @param customizeUrl     「基于当前模板定制 →」(以家里当前的默认模板为底,存完回超级 Agent)
     */
    public record Card(AskRememberParser.Proposal proposal, Long excludeAccountId, String excludeAccountName,
                       String customizeUrl, Long messageId) {
        public boolean showMore() {
            return proposal.suggestsTemplate() || proposal.suggestsExclude();
        }
    }

    /**
     * 正文 → 卡片;没有标记 → null(模板据此不渲染)。
     * 这句话已经记过了(回看历史对话时)→ 也不再出卡:再点一次「记住」什么都不会发生,留着只会让人以为没存上。
     */
    public Card of(long familyId, String body, Long messageId) {
        return AskRememberParser.first(body)
                .filter(p -> preferenceService.list(familyId).stream().noneMatch(r -> p.text().equals(r.content)))
                .map(p -> card(familyId, p, messageId)).orElse(null);
    }

    /** 标记里面那一段 → 卡片(前端流完之后来要) */
    public Card ofInner(long familyId, String inner, Long messageId) {
        return AskRememberParser.parse(inner).map(p -> card(familyId, p, messageId)).orElse(null);
    }

    private Card card(long familyId, AskRememberParser.Proposal p, Long messageId) {
        Long accId = null;
        String accName = null;
        if (p.suggestsExclude() && !p.excludeName().isBlank()) {
            Optional<Account> hit = accountMapper.findActiveByFamily(familyId).stream()
                    .filter(a -> a.getType() != AccountType.LOAN && !a.isAnalysisExcluded())
                    .filter(a -> a.getDisplayName() != null && a.getDisplayName().strip().equals(p.excludeName()))
                    .findFirst();
            if (hit.isPresent()) { accId = hit.get().getId(); accName = hit.get().getDisplayName(); }
        }
        String base = templateService.familyDefault(familyId).key();
        return new Card(p, accId, accName, AnalysisUrls.customize(base, "/ask"), messageId);
    }
}
