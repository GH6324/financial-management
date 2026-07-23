package com.family.finance.service.penetration;

import com.family.finance.domain.lens.AssetClass;
import com.family.finance.domain.lens.IndustryTag;
import com.family.finance.domain.penetration.FundPenetrationCache;
import com.family.finance.domain.penetration.HoldingAllocation;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.repository.FundPenetrationMapper;
import com.family.finance.repository.HoldingAllocationMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * v1.5 · 基金穿透编排:资产配置(股/债/现金)+ 前十大→申万行业 + 其他持仓残差
 * → 归一化到万分比 → 全局缓存 + 物化成该持仓的持仓方向(holding_allocation)。
 *
 * <p>穿透是增强非核心:拿不到数据 → 标 UNPENETRABLE / 静默降级,不阻断。金额不参与,只用公开代码。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundPenetrationService {

    private final EastMoneyFundClient client;
    private final FundPenetrationMapper cacheMapper;
    private final HoldingAllocationMapper allocMapper;
    private final StockHoldingMapper holdingMapper;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final ObjectMapper json = new ObjectMapper();

    private static final Duration FRESH = Duration.ofDays(80);   // 缓存有效期(季度)

    /** 缓存里存的每个方向 */
    public record AllocPart(String kind, String assetClass, String industry, int weightBp) {}

    // ---------- 基金级:算穿透 + 缓存 ----------

    /** 拿某基金穿透结果(缓存新鲜则复用,否则抓东财算)· 失败/穿不透返回带 status 的记录 */
    public FundPenetrationCache penetrateFund(String code) {
        var cached = cacheMapper.findByCode(code).orElse(null);
        if (cached != null && cached.getFetchedAt() != null
                && cached.getFetchedAt().isAfter(LocalDateTime.now().minus(FRESH))) {
            return cached;
        }
        FundPenetrationCache fresh = compute(code);
        cacheMapper.upsert(fresh);
        return fresh;
    }

    private FundPenetrationCache compute(String code) {
        EastMoneyFundClient.AssetAlloc aa = client.assetAllocation(code);
        if (aa == null) {
            return FundPenetrationCache.builder().fundCode(code).status(FundPenetrationCache.UNPENETRABLE).build();
        }
        BigDecimal stock = nz(aa.stockPct()), bond = nz(aa.bondPct()), cash = nz(aa.cashPct());

        // 前十大 → 行业权重(占净值 · %)
        Map<IndustryTag, BigDecimal> byInd = new LinkedHashMap<>();
        BigDecimal coveredStock = BigDecimal.ZERO;
        EastMoneyFundClient.TopHoldings top = client.topHoldings(code);
        String period = top != null ? top.period() : null;
        if (top != null) {
            for (EastMoneyFundClient.TopHolding h : top.stocks()) {
                String eastInd = client.stockIndustry(h.stockCode());
                IndustryTag tag = client.mapIndustry(eastInd);
                byInd.merge(tag, h.pctOfNav(), BigDecimal::add);
                coveredStock = coveredStock.add(h.pctOfNav());
            }
        }
        // 前十大覆盖股票仓位的比例(相对该基金 stock%)
        BigDecimal coveredPct = stock.signum() > 0
                ? coveredStock.divide(stock, 4, RoundingMode.HALF_EVEN).multiply(BigDecimal.valueOf(100)).min(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        // 组装方向(占净值 · %),再归一化到万分比
        List<AllocPart> parts = new ArrayList<>();
        for (var e : byInd.entrySet()) {
            parts.add(new AllocPart(HoldingAllocation.KIND_STOCK, AssetClass.EQUITY.name(), e.getKey().name(),
                    pctToBp(e.getValue())));
        }
        BigDecimal otherStock = stock.subtract(coveredStock).max(BigDecimal.ZERO);
        if (otherStock.signum() > 0)
            parts.add(new AllocPart(HoldingAllocation.KIND_OTHER, AssetClass.EQUITY.name(), IndustryTag.OTHER.name(), pctToBp(otherStock)));
        if (bond.signum() > 0)
            parts.add(new AllocPart(HoldingAllocation.KIND_BOND, AssetClass.FIXED_INCOME.name(), IndustryTag.FIXED_BOND.name(), pctToBp(bond)));
        if (cash.signum() > 0)
            parts.add(new AllocPart(HoldingAllocation.KIND_CASH, AssetClass.CASH_EQ.name(), IndustryTag.MONEY_CASH.name(), pctToBp(cash)));

        parts = normalize(parts);
        String allocJson;
        try { allocJson = json.writeValueAsString(parts); } catch (Exception e) { allocJson = "[]"; }

        return FundPenetrationCache.builder()
                .fundCode(code).reportPeriod(period)
                .stockPct(stock).bondPct(bond).cashPct(cash).coveredPct(coveredPct)
                .allocJson(allocJson).status(FundPenetrationCache.OK).build();
    }

    // ---------- 持仓级:物化成持仓方向 ----------

    /** 拉取某持仓:解析代码 → 穿透基金 → 物化 PENETRATED 方向(保留 MANUAL)· 返回穿透后状态 */
    @Transactional
    public String penetrateHolding(long holdingId) {
        StockHolding h = holdingMapper.findById(holdingId).orElse(null);
        if (h == null) return "NOT_FOUND";
        String code = client.resolveCode(h.getFundCode(), h.getDisplayName());
        if (code == null) {
            holdingMapper.updatePenetrate(holdingId, null, "UNPENETRATED");
            return "UNPENETRATED";
        }
        FundPenetrationCache fp = penetrateFund(code);
        if (!FundPenetrationCache.OK.equals(fp.getStatus()) || fp.getAllocJson() == null) {
            holdingMapper.updatePenetrate(holdingId, code, "UNPENETRATED");
            return "UNPENETRATED";
        }
        List<AllocPart> parts;
        try { parts = Arrays.asList(json.readValue(fp.getAllocJson(), AllocPart[].class)); }
        catch (Exception e) { return "FAILED"; }

        // 保留 MANUAL,重建 PENETRATED · 预留 MANUAL 已占权重
        int manualBp = allocMapper.manualWeightBp(holdingId);
        allocMapper.deleteNonManual(holdingId);
        int budget = Math.max(0, 10000 - manualBp);
        List<AllocPart> scaled = scaleTo(parts, budget);
        for (AllocPart p : scaled) {
            if (p.weightBp() <= 0) continue;
            allocMapper.insert(HoldingAllocation.builder()
                    .holdingId(holdingId).weightBp(p.weightBp())
                    .assetClass(p.assetClass()).industry(p.industry()).kind(p.kind())
                    .source(HoldingAllocation.SRC_PENETRATED).reportPeriod(fp.getReportPeriod()).build());
        }
        holdingMapper.updatePenetrate(holdingId, code, "RESOLVED");
        return "RESOLVED";
    }

    /** v1.5 · 批量穿透某家庭全部活持仓(后台跑 · 首次略慢,之后走缓存)· 完成失效 lens 缓存 */
    @org.springframework.scheduling.annotation.Async
    public void penetrateFamilyAsync(long familyId) {
        List<Long> ids = holdingMapper.findActiveHoldingIdsByFamily(familyId);
        int ok = 0;
        for (Long id : ids) {
            try { if ("RESOLVED".equals(penetrateHolding(id))) ok++; }
            catch (Exception e) { log.warn("穿透持仓 {} 失败 · {}", id, e.toString()); }
        }
        log.info("穿透批量 · family={} · {}/{} 支成功穿透", familyId, ok, ids.size());
        events.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId));
    }

    // ---------- 归一化 / 工具 ----------

    private static int pctToBp(BigDecimal pct) {
        return pct == null ? 0 : pct.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_EVEN).intValue();
    }

    /** 归一化到合计 10000(最后一项吸收舍入残差) */
    static List<AllocPart> normalize(List<AllocPart> parts) { return scaleTo(parts, 10000); }

    static List<AllocPart> scaleTo(List<AllocPart> parts, int target) {
        int sum = parts.stream().mapToInt(AllocPart::weightBp).sum();
        if (sum <= 0 || parts.isEmpty()) return parts;
        List<AllocPart> out = new ArrayList<>();
        int acc = 0;
        for (int i = 0; i < parts.size(); i++) {
            AllocPart p = parts.get(i);
            int w = i == parts.size() - 1 ? target - acc
                    : (int) Math.round((double) p.weightBp() * target / sum);
            acc += w;
            out.add(new AllocPart(p.kind(), p.assetClass(), p.industry(), w));
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }
}
