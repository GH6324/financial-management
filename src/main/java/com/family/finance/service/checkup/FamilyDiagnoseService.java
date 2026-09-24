package com.family.finance.service.checkup;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.ProductCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 全家体检主聚合 · v0.2 · FR-40a
 *
 * 用 {@link FactViewService} 已有方法取 KPI / Allocation,补充风险分布与家庭加权年化。
 */
@Service
@RequiredArgsConstructor
public class FamilyDiagnoseService {

    private final FactViewService factViewService;
    private final ProductCategoryService productCategoryService;
    private final FamilyMapper familyMapper;
    private final PeriodMapper periodMapper;
    private final com.family.finance.repository.AccountMapper accountMapper;   // 2026-09-24 · 风险等级读账户自己的类目

    /**
     * v1.6 UED review A2 · 与 {@code DashboardController.resolveAsOf} 完全同口径的 anchor 选取:
     * ① 优先进行中(OPEN)周期 → ② 否则「不晚于今天」的最新期(排除未来测试期)→ ③ 兜底最新期。
     * <p>public 是为了让 CheckupController 把这一期回显到页面("数据截至 X"),
     * 使用户能看出体检与仪表盘是否同期 —— 此前 checkup 完全不显示账期,数值不一致时无从分辨。
     */
    public Period resolveAnchor(long familyId) {
        // v1.23 · 体检看的是**存量**(净资产 / 配置 / 风险分布),锚「当前余额所在期」= 最新 OPEN。
        //   双活跃窗口下这里取进行期是对的:补录期的余额是上月末的事实,不是「现在」。
        Period open = periodMapper.findBalancePeriod(familyId).orElse(null);
        if (open != null) return open;
        List<Period> all = periodMapper.findAllByFamily(familyId);
        java.time.LocalDate today = java.time.LocalDate.now();
        return all.stream()
                .filter(p -> p.getPeriodStart() != null && !p.getPeriodStart().isAfter(today))
                .max(java.util.Comparator.comparing(Period::getPeriodStart))
                .orElseGet(() -> all.stream().max(java.util.Comparator.comparing(Period::getPeriodStart))
                        .orElseThrow(() -> new IllegalStateException("尚未创建周期")));
    }

    public FamilyDiagnose diagnose(long familyId) {
        // v0.2 bug 修(2026-05-10): 旧实现 factViewService.loadDefault 用 LocalDate.now() 作 end,
        // 当用户测试期间生成了未来期(>当前日期),那些期会被排除,与 /dashboard 不一致。
        //
        // v1.6 UED review A2(2026-07-26)· 上面那次修错了 dashboard 的口径:
        //   dashboard 的 resolveAsOf 是「优先进行中(OPEN)→ 否则不晚于今天的最新期 → 兜底最新期」,
        //   会**排除未来期**;而这里写成了裸 findLatest(含未来期)。
        //   beta/测试库里存在未来账期(如 2029-09)时,两页 anchor 落到不同账期 →
        //   净资产/总资产/紧急储备等同名指标数值不同(实测差 119 万、紧急储备 7.2 月 vs 723 月),
        //   用户无从分辨谁对,直接击穿对全部数字的信任。
        //   现改为与 dashboard 完全同规则,并把 anchor 期回传给页面显示(见 CheckupController)。
        Family family = familyMapper.findById(familyId)
                .orElseThrow(() -> new IllegalArgumentException("家庭不存在: " + familyId));
        Period anchor = resolveAnchor(familyId);
        java.time.LocalDate end = anchor.getPeriodStart();
        // v1.6.30 · 锚点期若还在填报(OPEN),minusMonths(11) 的窗口里只剩 11 个已关账期 →
        //   收益类落到「不满 12 期」的累计口径,而 reports(窗口锚最新已关账期)拿得到 12 个 → 走年化。
        //   同一个「家庭 XIRR」两页给出 −0.21% 与 +1.11%。多回一个月,让两处取到同一批已关账期。
        boolean anchorOpen = anchor.getStatus() != null && !"CLOSED".equals(anchor.getStatus().name());
        java.time.LocalDate start = end.minusMonths(anchorOpen ? 12 : 11);
        FactSlice slice = factViewService.load(new FactFilter(
                familyId, family.getPeriodType(), start, end, false, null, family.getBaseCurrency()));
        KpiSnapshot kpi = factViewService.kpis(slice);
        List<AllocationSlice> allocation = factViewService.allocationByType(slice, slice.lastPeriodId());
        BigDecimal familyXirr = factViewService.familyXirr(slice);
        BigDecimal familyTwr = factViewService.familyTwr(slice);

        // 风险分布:用最后一期账户余额 × fxToBase + product_category.risk_level
        Map<String, ProductCategory> categoriesByCode = productCategoryService.listAll().stream()
                .collect(java.util.stream.Collectors.toMap(ProductCategory::getCode, java.util.function.Function.identity()));

        List<AccountPeriodFact> lastRows = slice.rows().stream()
                .filter(r -> Objects.equals(r.periodId(), slice.lastPeriodId()))
                .filter(r -> r.accountClass() == AccountClass.ASSET)
                .filter(r -> r.endBalanceBase() != null)
                .toList();

        // 2026-09-24 · 风险等级改读账户自己的(手工改过的 → 产品类目 → 按类型的默认类目),
        //   原来这里是按账户类型写死的兜底表,加密 / 贵金属 / 保险全落进 default 成了「无风险」,
        //   没有任何类型到 5 级 → FAM-RISK-1 从来触发不了。详见 RiskLevels 类注释。
        Map<Long, com.family.finance.domain.account.Account> accountById = new java.util.HashMap<>();
        for (var a : accountMapper.findAllByFamily(familyId)) accountById.put(a.getId(), a);
        java.util.function.Function<String, Integer> riskOfCategory = code -> {
            ProductCategory c = categoriesByCode.get(code);
            return c == null ? null : c.getRiskLevel();
        };

        Map<Integer, BigDecimal> riskAmountByLevel = new java.util.TreeMap<>();
        BigDecimal totalAssetBase = BigDecimal.ZERO;
        java.util.Set<Long> estimatedAccounts = new java.util.HashSet<>();
        for (AccountPeriodFact row : lastRows) {
            BigDecimal v = row.endBalanceBase();
            if (v == null) continue;
            totalAssetBase = totalAssetBase.add(v);
            var acc = accountById.get(row.accountId());
            RiskLevels.Resolved r = RiskLevels.resolve(
                    acc == null ? null : acc.getRiskLevelOverride(),
                    acc == null ? null : acc.getProductCategoryCode(),
                    row.accountType(), riskOfCategory);
            if (r.estimated() && v.signum() != 0) estimatedAccounts.add(row.accountId());
            riskAmountByLevel.merge(r.level(), v, BigDecimal::add);
        }

        List<FamilyDiagnose.RiskBucket> riskDist = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> e : riskAmountByLevel.entrySet()) {
            BigDecimal amt = e.getValue().setScale(2, RoundingMode.HALF_EVEN);
            // 余额为 0 的账户也会在它那一档记一笔 0 —— 原来它们都挤在「无风险」里看不出来,
            //   分档读类目之后就会冒出一个「中低 0.00%」的空扇区。整档为 0 的不画。
            if (amt.signum() == 0) continue;
            BigDecimal ratio = totalAssetBase.signum() == 0 ? BigDecimal.ZERO :
                    amt.divide(totalAssetBase, 6, RoundingMode.HALF_EVEN);
            riskDist.add(new FamilyDiagnose.RiskBucket(e.getKey(), riskLabel(e.getKey()), amt, ratio));
        }

        BigDecimal liquidAssets = slice.rows().stream()
                .filter(r -> Objects.equals(r.periodId(), slice.lastPeriodId()))
                .filter(r -> r.accountLiquidity() == AccountLiquidity.LIQUID)
                .map(AccountPeriodFact::endBalanceBase)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);

        BigDecimal cumulativeYtdPnl = slice.rows().stream()
                .filter(r -> r.periodStart() != null
                        && r.periodStart().getYear() == java.time.LocalDate.now().getYear())
                .map(AccountPeriodFact::periodPnlBase)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);

        int accountCount = (int) slice.rows().stream()
                .filter(r -> Objects.equals(r.periodId(), slice.lastPeriodId()))
                .map(AccountPeriodFact::accountId)
                .distinct()
                .count();

        return new FamilyDiagnose(
                kpi,
                allocation,
                riskDist,
                liquidAssets,
                kpi.emergencyFundMonths(),
                familyXirr,
                familyTwr,
                cumulativeYtdPnl,
                accountCount,
                0,  // pending TODO 接入 SnapshotTodoMapper(此值仅 banner 用,现阶段不阻塞)
                estimatedAccounts.size()
        );
    }

    private static String riskLabel(int level) {
        return switch (level) {
            case 0 -> "未评级";
            case 1 -> "极低";
            case 2 -> "低";
            case 3 -> "中低";
            case 4 -> "中";
            case 5 -> "中高";
            case 6 -> "极高";
            default -> "未知";
        };
    }
}
