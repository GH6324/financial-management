package com.family.finance.web.lens;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.lens.AssetClass;
import com.family.finance.domain.lens.IndustryTag;
import com.family.finance.domain.lens.PurposeTag;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1 · 多维打标页(/lens/tags)· prd v1.1 FR-1/FR-1b(评审修订:树状 + 单行 AI + 用途维度)。
 *
 * <p><b>树状</b>:账户行(大类 / 平台 / 用途;无持仓账户另有行业粗标)→ 子行 = 具体持仓(行业 · 准)。
 * 「AI 推荐」支持整页或<b>单行</b>(`only` 参数);结果只预填,<b>显式保存</b>才落库。
 * 用途 = 个人意图,AI 不猜,纯手标。保存只写非空白名单值(留空 = 保持现状)。</p>
 */
@Controller
@RequiredArgsConstructor
public class LensTagController {

    private final AccountMapper accountMapper;
    private final StockHoldingService holdingService;
    private final LensAiTagService aiTagService;
    private final NavService navService;
    private final com.family.finance.repository.MemberMapper memberMapper;   // 账户 meta 行:主理人名(维度来源示意)
    private final com.family.finance.service.lens.LensQueryService lensQueryService; // 打标保存后失效头寸缓存
    private final com.family.finance.service.penetration.FundPenetrationService penetrationService; // v1.5 穿透
    private final com.family.finance.repository.HoldingAllocationMapper allocMapper;                // v1.5 持仓方向读

    /** 持仓方向视图行(打标页展示) */
    public record AllocView(String label, String assetClassLabel, int pct, String source, boolean manual) {}

    /** 树节点:账户 + 持仓子行(holdingAccount=true 时账户级行业不标,归子行);
     *  cashHoldings = 券商现金部分,只读展示(系统定死 现金活钱·货币基金/存款·低风险,不可标) */
    public record TreeNode(Account account, boolean holdingAccount, List<StockHolding> holdings,
                           List<StockHolding> cashHoldings) {}

    @GetMapping("/lens/tags")
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        addModel(me, model, Map.of(), false);
        return "lens/tags";
    }

    /** AI 推荐(POST · 同页渲染预填,不落库)· only=单行,缺省=全部未打标 */
    @PostMapping("/lens/tags/ai")
    public String ai(@AuthenticationPrincipal MemberPrincipal me,
                     @RequestParam(value = "only", required = false) String only,
                     Model model) {
        List<TreeNode> tree = tree(me.getFamilyId());
        List<String> names = new ArrayList<>();
        if (only != null && !only.isBlank()) {
            names.add(only.trim());
        } else {
            for (TreeNode n : tree) {
                Account a = n.account();
                if (a.getAssetClass() == null || a.getPlatformTag() == null
                        || (!n.holdingAccount() && a.getIndustryTag() == null)) {
                    names.add(a.getDisplayName());
                }
                for (StockHolding h : n.holdings()) {
                    if (h.getIndustryTag() == null && !names.contains(h.getDisplayName())) {
                        names.add(h.getDisplayName());
                    }
                }
            }
        }
        Map<String, LensAiTagService.Tags> suggestions = aiTagService.suggest(me.getFamilyId(), names);
        addModel(me, model, suggestions, true);
        return "lens/tags";
    }

    /** 批量保存(显式接受)· 只写非空白名单值 */
    @PostMapping("/lens/tags/save")
    public String save(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam MultiValueMap<String, String> params) {
        for (TreeNode node : tree(me.getFamilyId())) {
            Account acc = node.account();
            String cls = clean(params.getFirst("acct_class_" + acc.getId()));
            String platform = clean(params.getFirst("acct_platform_" + acc.getId()));
            String industry = clean(params.getFirst("acct_industry_" + acc.getId()));
            String purpose = clean(params.getFirst("acct_purpose_" + acc.getId()));
            if (AssetClass.fromName(cls) == null) cls = null;               // 白名单
            if (IndustryTag.fromName(industry) == null) industry = null;
            if (PurposeTag.fromName(purpose) == null) purpose = null;
            if (platform != null && platform.length() > 40) platform = platform.substring(0, 40);
            String newCls = cls != null ? cls : acc.getAssetClass();        // 留空=保持现状
            String newPlatform = platform != null ? platform : acc.getPlatformTag();
            String newIndustry = industry != null ? industry : acc.getIndustryTag();
            String newPurpose = purpose != null ? purpose : acc.getPurposeTag();
            if (!java.util.Objects.equals(newCls, acc.getAssetClass())
                    || !java.util.Objects.equals(newPlatform, acc.getPlatformTag())
                    || !java.util.Objects.equals(newIndustry, acc.getIndustryTag())
                    || !java.util.Objects.equals(newPurpose, acc.getPurposeTag())) {
                accountMapper.updateLensTags(me.getFamilyId(), acc.getId(),
                        newCls, newPlatform, newIndustry, newPurpose);
            }
            for (StockHolding h : node.holdings()) {
                String hi = clean(params.getFirst("hold_industry_" + h.getId()));
                if (IndustryTag.fromName(hi) == null) continue;             // 留空/非法=不动
                if (!java.util.Objects.equals(hi.toUpperCase(), h.getIndustryTag())) {
                    holdingService.updateIndustry(me.getFamilyId(), h.getId(), hi.toUpperCase());
                }
            }
        }
        lensQueryService.evict(me.getFamilyId());   // 打标即时生效(透视缓存失效)
        return "redirect:/lens/tags?saved=1";
    }

    /** v1.5 · 单支持仓发起穿透拉取(核对/纠正后)· 同步 · 完成失效缓存 */
    @PostMapping("/lens/tags/penetrate/{holdingId}")
    public String penetrate(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long holdingId) {
        // 归属校验:该持仓必须属于本家庭
        boolean own = tree(me.getFamilyId()).stream().flatMap(n -> n.holdings().stream())
                .anyMatch(h -> h.getId() == holdingId);
        if (own) {
            penetrationService.penetrateHolding(holdingId);
            lensQueryService.evict(me.getFamilyId());
        }
        return "redirect:/lens/tags?penetrated=1";
    }

    /** v1.5 · 全家一键穿透(后台跑 · 完成自动失效缓存) */
    @PostMapping("/lens/tags/penetrate-all")
    public String penetrateAll(@AuthenticationPrincipal MemberPrincipal me) {
        penetrationService.penetrateFamilyAsync(me.getFamilyId());
        return "redirect:/lens/tags?penetrating=1";
    }

    // ---------- 内部 ----------

    private void addModel(MemberPrincipal me, Model model,
                          Map<String, LensAiTagService.Tags> suggestions, boolean aiRan) {
        List<TreeNode> tree = tree(me.getFamilyId());
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("tree", tree);
        model.addAttribute("assetClasses", AssetClass.values());
        model.addAttribute("industryTags", IndustryTag.values());
        model.addAttribute("purposeTags", PurposeTag.values());
        // v1.5 · 每持仓的穿透方向(有则展示明细 + 覆盖)
        Map<Long, List<AllocView>> allocsByHolding = new LinkedHashMap<>();
        for (TreeNode n : tree) {
            for (StockHolding h : n.holdings()) {
                var list = allocMapper.findByHolding(h.getId());
                if (list == null || list.isEmpty()) continue;
                List<AllocView> views = new java.util.ArrayList<>();
                for (var a : list) {
                    String lbl = a.getIndustry() != null ? IndustryTag.labelOf(a.getIndustry()) : "";
                    if (lbl == null || lbl.isBlank()) lbl = "其他";
                    if ("OTHER".equals(a.getKind())) lbl = "其他持仓";
                    views.add(new AllocView(lbl, AssetClass.labelOf(a.getAssetClass()),
                            (int) Math.round(a.getWeightBp() / 100.0), a.getSource(), "MANUAL".equals(a.getSource())));
                }
                allocsByHolding.put(h.getId(), views);
            }
        }
        model.addAttribute("allocsByHolding", allocsByHolding);
        model.addAttribute("suggestions", suggestions);
        model.addAttribute("aiRan", aiRan);
        model.addAttribute("aiAvailable", aiTagService.available());
        Map<Long, String> defaults = new LinkedHashMap<>();
        for (TreeNode n : tree) {
            AssetClass d = AssetClass.defaultFor(n.account().getType(), n.account().getProductCategoryCode());
            defaults.put(n.account().getId(), d == null ? "未分类" : d.getLabel());
        }
        model.addAttribute("defaults", defaults);
        // 维度来源示意(#5):风险/流动性/地域/账户类型/主理人/币种 来自账户资料,不在此打标 → meta 行展示 主理人+币种
        Map<Long, String> ownerNames = new LinkedHashMap<>();
        memberMapper.findActiveByFamily(me.getFamilyId()).forEach(m -> ownerNames.put(m.getId(), m.getDisplayName()));
        model.addAttribute("ownerNames", ownerNames);
    }

    private List<TreeNode> tree(long familyId) {
        List<TreeNode> out = new ArrayList<>();
        for (Account a : accountMapper.findActiveByFamily(familyId)) {
            if (a.getType() == AccountType.LOAN) continue;
            List<StockHolding> holdings = List.of();
            List<StockHolding> cashHoldings = List.of();
            boolean holdingAccount = StockHoldingService.supportsHoldings(a.getType());
            if (holdingAccount) {
                List<StockHolding> all = holdingService.findActiveByAccount(familyId, a.getId());
                holdings = all.stream()
                        .filter(h -> h.getValuationMode() == null || !"CASH".equals(h.getValuationMode().name()))
                        .toList();
                cashHoldings = all.stream()
                        .filter(h -> h.getValuationMode() != null && "CASH".equals(h.getValuationMode().name()))
                        .toList();
            }
            out.add(new TreeNode(a, holdingAccount, holdings, cashHoldings));
        }
        return out;
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
