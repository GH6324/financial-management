package com.family.finance.service.lens;

import com.family.finance.calc.lens.Position;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.domain.lens.AssetClass;
import com.family.finance.domain.lens.IndustryTag;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.ProductCategoryService;
import com.family.finance.service.stock.AccountValuationService;
import com.family.finance.service.stock.StockHoldingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * v1.1 · 资产透视头寸组装(tech-design v1.1 决策 1 · 实时组装,不物化)。
 *
 * <p>手填账户 = 1 头寸(factview 账户现值 · 本位币);持仓账户 = N 头寸
 * (perHoldingLines 账户币种现值 × 统一因子缩放到本位币,Σ头寸 ≡ 账户现值,口径与仪表盘一致)。
 * LOAN 排除(负债不进资产分母);未填报(无现值)账户跳过。维度标签在此算好,引擎只分组。</p>
 */
@Service
@RequiredArgsConstructor
public class LensQueryService {

    private final AccountMapper accountMapper;
    private final MemberMapper memberMapper;
    private final ProductCategoryService productCategoryService;
    private final FactViewService factViewService;
    private final AccountValuationService valuationService;

    /** 头寸快照缓存:family → (positions, 组装时刻)。TTL 60s(数据每月才变,零风险);
     *  打标/持仓行业修改走 {@link #evict} 即时失效;余额/估值变化靠 TTL 收敛(≤60s 延迟,
     *  填报后立刻看透视的场景极罕见,不为此引入 AccountValuationService→本类 的循环依赖)。
     *  per-family 锁 + 双检:页面初载 3 个并发查询只组装一遍,其余等锁后命中。 */
    private static final long CACHE_TTL_MS = 60_000;
    private record CacheEntry(List<Position> positions, long at) {}
    private final java.util.concurrent.ConcurrentHashMap<Long, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();

    public List<Position> positions(long familyId) {
        CacheEntry e = cache.get(familyId);
        if (fresh(e)) return e.positions();
        synchronized (locks.computeIfAbsent(familyId, k -> new Object())) {
            e = cache.get(familyId);
            if (fresh(e)) return e.positions();
            List<Position> ps = List.copyOf(assemble(familyId));
            cache.put(familyId, new CacheEntry(ps, System.currentTimeMillis()));
            return ps;
        }
    }

    /** 打标保存 / 持仓行业修改后调用 · 下次查询重组装 */
    public void evict(long familyId) {
        cache.remove(familyId);
    }

    private static boolean fresh(CacheEntry e) {
        return e != null && System.currentTimeMillis() - e.at() < CACHE_TTL_MS;
    }

    private List<Position> assemble(long familyId) {
        Map<Long, String> memberName = memberMapper.findActiveByFamily(familyId).stream()
                .collect(Collectors.toMap(Member::getId, Member::getDisplayName));
        Map<Long, AccountPerformance> perf = factViewService
                .accountPerformance(factViewService.loadDefault(familyId)).stream()
                .collect(Collectors.toMap(AccountPerformance::accountId, Function.identity(), (a, b) -> a));

        List<Position> out = new ArrayList<>();
        for (Account acc : accountMapper.findActiveByFamily(familyId)) {
            if (acc.getType() == AccountType.LOAN) continue;      // 负债不进资产透视
            AccountPerformance p = perf.get(acc.getId());
            if (p == null || p.currentValue() == null) continue;  // 未填报 · 无现值

            ProductCategory cat = productCategoryService.findByCode(acc.getProductCategoryCode()).orElse(null);
            String risk = riskLabel(acc.getRiskLevelOverride() != null
                    ? acc.getRiskLevelOverride()
                    : (cat == null ? null : cat.getRiskLevel()));
            String liquidity = liquidityLabel(acc, cat);
            String owner = acc.getPrimaryOwnerMemberId() == null ? "共同"
                    : memberName.getOrDefault(acc.getPrimaryOwnerMemberId(), "成员#" + acc.getPrimaryOwnerMemberId());
            String assetClass = assetClassLabel(acc);
            String platform = blankToNull(acc.getPlatformTag());
            String acctIndustry = nullIfEmpty(IndustryTag.labelOf(acc.getIndustryTag()));
            String purpose = nullIfEmpty(com.family.finance.domain.lens.PurposeTag.labelOf(acc.getPurposeTag()));
            String typeLabel = acc.getType().getLabel();

            boolean split = false;
            if (StockHoldingService.supportsHoldings(acc.getType())) {
                List<AccountValuationService.HoldingLine> lines = valuationService.perHoldingLines(acc);
                BigDecimal sumAcctCcy = lines.stream().map(AccountValuationService.HoldingLine::valueAcctCcy)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (!lines.isEmpty() && sumAcctCcy.signum() > 0 && p.currentValue().signum() > 0) {
                    // 统一 FX 因子:Σ头寸(本位币)≡ factview 账户现值,与仪表盘同源
                    BigDecimal factor = p.currentValue().divide(sumAcctCcy, MathContext.DECIMAL64);
                    for (AccountValuationService.HoldingLine line : lines) {
                        StockHolding h = line.holding();
                        BigDecimal valueBase = line.valueAcctCcy().multiply(factor).setScale(2, RoundingMode.HALF_EVEN);
                        BigDecimal costBase = line.costAcctCcy() == null ? null
                                : line.costAcctCcy().multiply(factor).setScale(2, RoundingMode.HALF_EVEN);
                        BigDecimal holdingCumPnl = costBase == null ? null : valueBase.subtract(costBase);
                        boolean cashRow = h.getValuationMode() != null && "CASH".equals(h.getValuationMode().name());
                        out.add(new Position(
                                acc.getId(), h.getId(), h.getDisplayName(), acc.getDisplayName(), valueBase,
                                typeLabel, risk, liquidity, acc.getCurrency(), owner,
                                assetClass, platform,
                                cashRow ? null : nullIfEmpty(IndustryTag.labelOf(h.getIndustryTag())),
                                cashRow ? null : regionLabel(h.getMarket()),
                                purpose,
                                p.latestPnl(), p.cumPnl(), p.netPrincipal(), holdingCumPnl));
                    }
                    split = true;
                }
            }
            if (!split) {
                out.add(new Position(
                        acc.getId(), null, acc.getDisplayName(), acc.getDisplayName(), p.currentValue(),
                        typeLabel, risk, liquidity, acc.getCurrency(), owner,
                        assetClass, platform, acctIndustry, null, purpose,
                        p.latestPnl(), p.cumPnl(), p.netPrincipal(), p.cumPnl()));
            }
        }
        return out;
    }

    // ---------- 标签口径 ----------

    /** 风险等级 1-6 → 三档(与预览口径一致);未评级 → null(未分类) */
    static String riskLabel(Integer level) {
        if (level == null || level <= 0) return null;
        if (level <= 2) return "低风险";
        if (level <= 4) return "中风险";
        return "高风险";
    }

    private static String liquidityLabel(Account acc, ProductCategory cat) {
        var liq = acc.getLiquidity(cat == null ? null : cat.getLiquidityClass());
        return switch (liq) {
            case LIQUID -> "灵活取用";
            case SEMI_LIQUID -> "半灵活";
            case ILLIQUID -> "低流动";
            case NA -> null;
        };
    }

    private static String assetClassLabel(Account acc) {
        AssetClass c = AssetClass.fromName(acc.getAssetClass());
        if (c == null) c = AssetClass.defaultFor(acc.getType(), acc.getProductCategoryCode());
        return c == null ? null : c.getLabel();
    }

    static String regionLabel(Market m) {
        if (m == null) return null;
        return switch (m) {
            case CN -> "A股";
            case US -> "美股";
            case HK -> "港股";
            case CRYPTO -> "加密";
            case METAL -> "贵金属";
        };
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String nullIfEmpty(String s) { return s == null || s.isEmpty() ? null : s; }
}
