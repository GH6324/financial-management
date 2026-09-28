package com.family.finance.service.allocation;

import com.family.finance.calc.AllocationDiff;
import com.family.finance.calc.AllocationDiff.AllocationEntry;
import com.family.finance.calc.AllocationDiff.Bucket;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.allocation.AllocationAnchor;
import com.family.finance.domain.allocation.AnchorCode;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.domain.family.Family;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactSlice;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.AllocationAnchorMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.ProductCategoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * v0.4 FR-62a · 资产配置 diff 服务。
 *
 * <p>组装当前配置 + 模板配置 + diff,给报表层用。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AllocationService {

    private final FamilyService familyService;
    private final AllocationAnchorMapper anchorMapper;
    private final AccountMapper accountMapper;
    private final ProductCategoryService productCategoryService;
    private final com.family.finance.repository.FamilyMapper familyMapper;   // v1.27 · 自定义锚写入
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 列出所有预置锚 + CUSTOM 占位(给 UI 下拉) */
    public List<AllocationAnchor> listAnchors() {
        return anchorMapper.findAll();
    }

    /** 按全部资产、家里的锚算(v1.26 签名 · 不跟随范围的调用方用) */
    public DiffResult compute(long familyId, FactSlice slice) {
        return compute(familyId, slice, com.family.finance.service.analysis.AnalysisScope.all(), null);
    }

    /**
     * 计算给定家庭的 diff。
     *
     * <p>v1.27:</p>
     * <ul>
     *   <li><b>范围</b>(FR-824 / FR-827):只拿范围内账户的行算当前配置({@code scope.apply(slice)});
     *       范围为空 → {@code scopeEmpty},不退回全部。</li>
     *   <li><b>有效目标</b>(FR-870):范围内没有房产 / 保险的,该桶不参与、其余放大 —— 见
     *       {@link AllocationDiff#effectiveTarget}。{@code targetPct / currentPct / diffPct} 只含参与的桶。</li>
     *   <li><b>「其他」类</b>(FR-873):不进四桶,{@code otherAmount} 单独给。</li>
     *   <li><b>自定义锚没填</b>(FR-871):{@code customUnset},不出对照(免得拿 0 去比)。</li>
     * </ul>
     *
     * @param anchorOverride 模板指定的锚;null = 家里的
     * @return DiffResult · 永不 null
     */
    public DiffResult compute(long familyId, FactSlice slice,
                              com.family.finance.service.analysis.AnalysisScope scope, String anchorOverride) {
        Family f = familyService.require(familyId);
        String anchorCode = anchorOverride != null && AnchorCode.isValid(anchorOverride)
                ? anchorOverride.toUpperCase()
                : (f.getAllocationAnchor() == null ? "SP_4321" : f.getAllocationAnchor());
        Map<Bucket, BigDecimal> rawTarget = resolveTarget(anchorCode, f.getAllocationAnchorCustom());
        boolean customUnset = "CUSTOM".equalsIgnoreCase(anchorCode) && !customFilled(f.getAllocationAnchorCustom());
        com.family.finance.service.analysis.AnalysisScope sc =
                scope == null ? com.family.finance.service.analysis.AnalysisScope.all() : scope;

        // 用 last period 的 fact 计算当前配置
        Long lastPeriodId = slice.lastPeriodId();
        if (lastPeriodId == null || sc.empty()) {
            Map<String, BigDecimal> empty = toStringKeys(emptyPctMap());
            return new DiffResult(anchorCode, toStringKeys(rawTarget), empty, empty,
                    toStringKeys(rawTarget), List.of(), false, false, BigDecimal.ZERO, customUnset, sc.empty(), sc);
        }
        FactSlice scoped = sc.apply(slice);

        List<Account> accounts = accountMapper.findActiveByFamily(familyId);
        Map<Long, String> pcCodeByAccountId = new HashMap<>();
        for (Account a : accounts) pcCodeByAccountId.put(a.getId(), a.getProductCategoryCode());

        // 每个账户的 liquidity_class 来自 product_category(若有)
        Map<String, String> liqClassByPcCode = new HashMap<>();
        for (Account a : accounts) {
            String pcCode = a.getProductCategoryCode();
            if (pcCode != null && !liqClassByPcCode.containsKey(pcCode)) {
                Optional<ProductCategory> pcOpt = productCategoryService.findByCode(pcCode);
                pcOpt.ifPresent(pc -> liqClassByPcCode.put(pcCode, pc.getLiquidityClass()));
            }
        }

        List<AllocationEntry> entries = scoped.rows().stream()
            .filter(r -> Objects.equals(r.periodId(), lastPeriodId))
            .map(r -> new AllocationEntry(
                r.endBalanceBase(),
                r.accountType() == null ? null : r.accountType().name(),
                resolveLiquidity(r, pcCodeByAccountId, liqClassByPcCode)
            ))
            .toList();

        AllocationDiff.EffectiveTarget eff = AllocationDiff.effectiveTarget(rawTarget, AllocationDiff.bucketAmounts(entries));
        Map<Bucket, BigDecimal> current = AllocationDiff.computeCurrentPct(entries);
        Map<Bucket, BigDecimal> target = eff.target();
        Map<Bucket, BigDecimal> diff = AllocationDiff.diff(current, target);
        // 不参与的桶从三张表里拿掉(页面按「参与的桶」逐行画,RebalanceDrift 按目标表遍历)
        for (Bucket b : eff.dropped()) { current.remove(b); diff.remove(b); }
        return new DiffResult(
            anchorCode,
            toStringKeys(target),
            toStringKeys(current),
            toStringKeys(diff),
            toStringKeys(rawTarget),
            eff.dropped().stream().map(Enum::name).toList(),
            eff.rescaled(),
            eff.degenerate(),
            AllocationDiff.otherAmount(entries),
            customUnset,
            false,
            sc);
    }

    /** v1.27 FR-871 · 自定义锚:存四个目标(合计必须 100)· 顺带清掉调仓缓存 */
    public void saveCustomAnchor(long familyId, BigDecimal cash, BigDecimal invest,
                                 BigDecimal property, BigDecimal insurance) {
        BigDecimal[] vs = {cash, invest, property, insurance};
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : vs) {
            if (v == null || v.signum() < 0 || v.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("每一类填 0 到 100 之间的数");
            }
            sum = sum.add(v);
        }
        if (sum.compareTo(new BigDecimal("100")) != 0) {
            BigDecimal gap = new BigDecimal("100").subtract(sum);
            throw new IllegalArgumentException("四类合计要等于 100,现在是 " + sum.stripTrailingZeros().toPlainString()
                    + "(" + (gap.signum() > 0 ? "还差 " : "多了 ") + gap.abs().stripTrailingZeros().toPlainString() + ")");
        }
        String json = "{\"cash\":" + plain(cash) + ",\"invest\":" + plain(invest)
                + ",\"property\":" + plain(property) + ",\"insurance\":" + plain(insurance) + "}";
        familyMapper.updateAllocationAnchorCustom(familyId, json);
    }

    /** 自定义锚四个值(没填 → 空 map) */
    public Map<String, BigDecimal> customAnchor(long familyId) {
        Family f = familyService.require(familyId);
        if (!customFilled(f.getAllocationAnchorCustom())) return Map.of();
        return toStringKeys(parseCustomJson(f.getAllocationAnchorCustom()));
    }

    /** 自定义锚填过没有:有值且合计 &gt; 0 */
    boolean customFilled(String json) {
        if (json == null || json.isBlank()) return false;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : parseCustomJson(json).values()) if (v != null) sum = sum.add(v);
        return sum.signum() > 0;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static Map<String, BigDecimal> toStringKeys(Map<Bucket, BigDecimal> m) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map.Entry<Bucket, BigDecimal> e : m.entrySet()) {
            out.put(e.getKey().name(), e.getValue());
        }
        return out;
    }

    private String resolveLiquidity(AccountPeriodFact r, Map<Long, String> pcByAcc, Map<String, String> liqByPc) {
        String pcCode = pcByAcc.get(r.accountId());
        if (pcCode != null) {
            String liq = liqByPc.get(pcCode);
            if (liq != null) return liq;
        }
        // fallback: AccountLiquidity enum(v0.3.3 也有自动 fallback)
        return r.accountLiquidity() == null ? null : r.accountLiquidity().name();
    }

    /** 把 family.allocation_anchor / custom 解析成 4 bucket pct map */
    Map<Bucket, BigDecimal> resolveTarget(Family f) {
        return resolveTarget(f.getAllocationAnchor() == null ? "SP_4321" : f.getAllocationAnchor(),
                f.getAllocationAnchorCustom());
    }

    Map<Bucket, BigDecimal> resolveTarget(String anchorCode, String customJson) {
        if ("CUSTOM".equalsIgnoreCase(anchorCode)) {
            return parseCustomJson(customJson);
        }
        Optional<AllocationAnchor> opt = anchorMapper.findByCode(anchorCode);
        AllocationAnchor a = opt.orElseGet(() -> anchorMapper.findByCode("SP_4321")
            .orElseThrow(() -> new IllegalStateException("SP_4321 不存在 · V22 未应用?")));
        Map<Bucket, BigDecimal> m = new HashMap<>();
        m.put(Bucket.CASH, a.getCashPct());
        m.put(Bucket.INVEST, a.getInvestPct());
        m.put(Bucket.PROPERTY, a.getPropertyPct());
        m.put(Bucket.INSURANCE, a.getInsurancePct());
        return m;
    }

    Map<Bucket, BigDecimal> parseCustomJson(String json) {
        Map<Bucket, BigDecimal> m = emptyPctMap();
        if (json == null || json.isBlank()) return m;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            putIfPresent(m, raw, "cash", Bucket.CASH);
            putIfPresent(m, raw, "invest", Bucket.INVEST);
            putIfPresent(m, raw, "property", Bucket.PROPERTY);
            putIfPresent(m, raw, "insurance", Bucket.INSURANCE);
        } catch (Exception e) {
            log.warn("parse allocation_anchor_custom 失败 · 返回 0/0/0/0: {}", e.toString());
        }
        return m;
    }

    private static void putIfPresent(Map<Bucket, BigDecimal> m, Map<String, Object> raw, String key, Bucket b) {
        Object v = raw.get(key);
        if (v != null) m.put(b, new BigDecimal(v.toString()));
    }

    private static Map<Bucket, BigDecimal> emptyPctMap() {
        Map<Bucket, BigDecimal> m = new HashMap<>();
        for (Bucket b : Bucket.values()) m.put(b, BigDecimal.ZERO);
        return m;
    }

    /**
     * @param targetPct       参与对照的桶的目标 %(已按 FR-870 放大)
     * @param currentPct      参与对照的桶的当前 %
     * @param diffPct         参与对照的桶的偏离
     * @param rawTargetPct    锚的原目标(4 桶,页面写「原目标 → 放大后」)
     * @param droppedBuckets  不参与对照的桶(范围内没有)
     * @param rescaled        目标是否按比例放大过
     * @param degenerate      参与的桶目标全是 0,没法对照
     * @param otherAmount     「其他」类合计(本位币)· 不进四桶(FR-873)
     * @param customUnset     选了自定义但没填(FR-871)
     * @param scopeEmpty      范围里一个账户都不剩(FR-826)
     * @param scope           这次用的范围
     */
    public record DiffResult(
        String anchorCode,
        Map<String, BigDecimal> targetPct,
        Map<String, BigDecimal> currentPct,
        Map<String, BigDecimal> diffPct,
        Map<String, BigDecimal> rawTargetPct,
        List<String> droppedBuckets,
        boolean rescaled,
        boolean degenerate,
        BigDecimal otherAmount,
        boolean customUnset,
        boolean scopeEmpty,
        com.family.finance.service.analysis.AnalysisScope scope
    ) {
        /** v1.27 之前的 4 参签名(老测试)—— 四桶全参与、没放大 */
        public DiffResult(String anchorCode, Map<String, BigDecimal> targetPct,
                          Map<String, BigDecimal> currentPct, Map<String, BigDecimal> diffPct) {
            this(anchorCode, targetPct, currentPct, diffPct, targetPct, List.of(), false, false,
                    BigDecimal.ZERO, false, false, com.family.finance.service.analysis.AnalysisScope.all());
        }

        /** 页面与提示词都按这个顺序逐桶画 / 写:只含参与对照的桶 */
        public List<String> activeBuckets() {
            List<String> out = new java.util.ArrayList<>();
            for (String b : List.of("CASH", "INVEST", "PROPERTY", "INSURANCE")) {
                if (targetPct != null && targetPct.containsKey(b)) out.add(b);
            }
            return out;
        }

        /** 能不能出对照:有数据、范围不空、自定义锚填过、目标不是全 0 */
        public boolean comparable() {
            return !scopeEmpty && !customUnset && !degenerate && currentPct != null && !currentPct.isEmpty()
                    && currentPct.values().stream().anyMatch(v -> v != null && v.signum() != 0);
        }

        /** 放大说明(FR-870 关键文案):「你家没有房产 · 标普 4321 按其余三类放大 → 现金 17% · 投资 50% · 保险 33%」 */
        public String rescaleNote(String anchorName) {
            if (droppedBuckets == null || droppedBuckets.isEmpty()) return null;
            List<String> gone = droppedBuckets.stream().map(DiffResult::bucketCn).toList();
            String head = (scope != null && !scope.isAll() ? "范围内没有" : "你家没有") + String.join("、", gone);
            if (!rescaled) return head + " · " + (gone.size() == 1 ? "这一类" : "这两类") + "不参与对照";
            List<String> parts = new java.util.ArrayList<>();
            for (String b : activeBuckets()) {
                parts.add(bucketCn(b) + " " + targetPct.get(b).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString() + "%");
            }
            return head + " · " + anchorName + " 按其余" + (activeBuckets().size() == 2 ? "两" : "三") + "类放大 → "
                    + String.join(" · ", parts);
        }

        public static String bucketCn(String b) {
            return switch (b) {
                case "CASH" -> "现金";
                case "INVEST" -> "投资";
                case "PROPERTY" -> "房产";
                case "INSURANCE" -> "保险";
                default -> b;
            };
        }
    }
}
