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
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 列出所有预置锚 + CUSTOM 占位(给 UI 下拉) */
    public List<AllocationAnchor> listAnchors() {
        return anchorMapper.findAll();
    }

    /**
     * 计算给定家庭的 diff。
     *
     * @param familyId 家庭 ID
     * @param slice    fact slice(用 endBalanceBase + accountId 关联 product_category)
     * @return DiffResult · 永不 null
     */
    public DiffResult compute(long familyId, FactSlice slice) {
        Family f = familyService.require(familyId);
        Map<Bucket, BigDecimal> target = resolveTarget(f);

        // 用 last period 的 fact 计算当前配置
        Long lastPeriodId = slice.lastPeriodId();
        if (lastPeriodId == null) {
            Map<String, BigDecimal> empty = toStringKeys(emptyPctMap());
            return new DiffResult(f.getAllocationAnchor(), toStringKeys(target), empty, empty);
        }

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

        List<AllocationEntry> entries = slice.rows().stream()
            .filter(r -> Objects.equals(r.periodId(), lastPeriodId))
            .map(r -> new AllocationEntry(
                r.endBalanceBase(),
                r.accountType() == null ? null : r.accountType().name(),
                resolveLiquidity(r, pcCodeByAccountId, liqClassByPcCode)
            ))
            .toList();

        Map<Bucket, BigDecimal> current = AllocationDiff.computeCurrentPct(entries);
        Map<Bucket, BigDecimal> diff = AllocationDiff.diff(current, target);
        return new DiffResult(
            f.getAllocationAnchor(),
            toStringKeys(target),
            toStringKeys(current),
            toStringKeys(diff));
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
        String anchorCode = f.getAllocationAnchor() == null ? "SP_4321" : f.getAllocationAnchor();
        if ("CUSTOM".equalsIgnoreCase(anchorCode)) {
            return parseCustomJson(f.getAllocationAnchorCustom());
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

    public record DiffResult(
        String anchorCode,
        Map<String, BigDecimal> targetPct,
        Map<String, BigDecimal> currentPct,
        Map<String, BigDecimal> diffPct
    ) {}
}
