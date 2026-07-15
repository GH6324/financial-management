package com.family.finance.web.lens;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.lens.AssetClass;
import com.family.finance.domain.lens.IndustryTag;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.lens.LensAiTagService;
import com.family.finance.service.stock.StockHoldingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1 · 多维打标页(/lens/tags)· prd v1.1 FR-1/FR-1b。
 *
 * <p>集中打标:账户(大类/平台/行业)+ 个股持仓(行业)。「AI 推荐」把未打标名称交给
 * {@link LensAiTagService} 得到<b>待确认预填</b>(标黄提示),用户核对后<b>显式保存</b>才落库
 * —— 绝不静默写入。保存语义:只写非空提交值(留空 = 保持现状;清标去账户编辑页)。</p>
 */
@Controller
@RequiredArgsConstructor
public class LensTagController {

    private final AccountMapper accountMapper;
    private final StockHoldingService holdingService;
    private final LensAiTagService aiTagService;
    private final NavService navService;

    public record HoldingRow(long accountId, String accountName, StockHolding holding) {}

    @GetMapping("/lens/tags")
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        addModel(me, model, Map.of(), false);
        return "lens/tags";
    }

    /** AI 推荐(POST · 同页渲染预填,不落库) */
    @PostMapping("/lens/tags/ai")
    public String ai(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        List<Account> accounts = taggableAccounts(me.getFamilyId());
        List<HoldingRow> holdings = taggableHoldings(me.getFamilyId(), accounts);
        List<String> names = new ArrayList<>();
        accounts.forEach(a -> names.add(a.getDisplayName()));
        holdings.forEach(h -> { if (!names.contains(h.holding().getDisplayName())) names.add(h.holding().getDisplayName()); });
        Map<String, LensAiTagService.Tags> suggestions = aiTagService.suggest(names);
        addModel(me, model, suggestions, true);
        return "lens/tags";
    }

    /** 批量保存(显式接受)· 只写非空提交值 */
    @PostMapping("/lens/tags/save")
    public String save(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam MultiValueMap<String, String> params) {
        for (Account acc : taggableAccounts(me.getFamilyId())) {
            String cls = clean(params.getFirst("acct_class_" + acc.getId()));
            String platform = clean(params.getFirst("acct_platform_" + acc.getId()));
            String industry = clean(params.getFirst("acct_industry_" + acc.getId()));
            if (AssetClass.fromName(cls) == null) cls = null;               // 白名单
            if (IndustryTag.fromName(industry) == null) industry = null;
            if (platform != null && platform.length() > 40) platform = platform.substring(0, 40);
            String newCls = cls != null ? cls : acc.getAssetClass();        // 留空=保持现状
            String newPlatform = platform != null ? platform : acc.getPlatformTag();
            String newIndustry = industry != null ? industry : acc.getIndustryTag();
            if (!java.util.Objects.equals(newCls, acc.getAssetClass())
                    || !java.util.Objects.equals(newPlatform, acc.getPlatformTag())
                    || !java.util.Objects.equals(newIndustry, acc.getIndustryTag())) {
                accountMapper.updateLensTags(me.getFamilyId(), acc.getId(), newCls, newPlatform, newIndustry);
            }
        }
        for (HoldingRow row : taggableHoldings(me.getFamilyId(), taggableAccounts(me.getFamilyId()))) {
            String industry = clean(params.getFirst("hold_industry_" + row.holding().getId()));
            if (IndustryTag.fromName(industry) == null) continue;           // 留空/非法=不动
            if (!java.util.Objects.equals(industry.toUpperCase(), row.holding().getIndustryTag())) {
                holdingService.updateIndustry(me.getFamilyId(), row.holding().getId(), industry.toUpperCase());
            }
        }
        return "redirect:/lens/tags?saved=1";
    }

    // ---------- 内部 ----------

    private void addModel(MemberPrincipal me, Model model,
                          Map<String, LensAiTagService.Tags> suggestions, boolean aiRan) {
        List<Account> accounts = taggableAccounts(me.getFamilyId());
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("accounts", accounts);
        model.addAttribute("holdings", taggableHoldings(me.getFamilyId(), accounts));
        model.addAttribute("assetClasses", AssetClass.values());
        model.addAttribute("industryTags", IndustryTag.values());
        model.addAttribute("suggestions", suggestions);
        model.addAttribute("aiRan", aiRan);
        model.addAttribute("aiAvailable", aiTagService.available());
        model.addAttribute("defaults", accounts.stream().collect(
                LinkedHashMap<Long, String>::new,
                (m, a) -> {
                    AssetClass d = AssetClass.defaultFor(a.getType(), a.getProductCategoryCode());
                    m.put(a.getId(), d == null ? "未分类" : d.getLabel());
                },
                Map::putAll));
    }

    private List<Account> taggableAccounts(long familyId) {
        return accountMapper.findActiveByFamily(familyId).stream()
                .filter(a -> a.getType() != AccountType.LOAN)
                .toList();
    }

    private List<HoldingRow> taggableHoldings(long familyId, List<Account> accounts) {
        List<HoldingRow> out = new ArrayList<>();
        for (Account a : accounts) {
            if (!StockHoldingService.supportsHoldings(a.getType())) continue;
            for (StockHolding h : holdingService.findActiveByAccount(familyId, a.getId())) {
                if (h.getValuationMode() != null && "CASH".equals(h.getValuationMode().name())) continue;
                out.add(new HoldingRow(a.getId(), a.getDisplayName(), h));
            }
        }
        return out;
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
