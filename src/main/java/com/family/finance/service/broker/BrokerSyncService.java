package com.family.finance.service.broker;

import com.family.finance.domain.broker.BrokerLink;
import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.BrokerLinkMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.stock.AccountValuationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 券商只读同步 · reconcile · v0.15。
 *
 * <p><b>只动带 sync_source=本 vendor 的持仓行,绝不碰用户手填持仓</b>:
 * 券商有我方无→建 AUTO;都有→更 shares/cost;我方有券商无→软归档;现金按币种 upsert CASH。
 * 持仓 currency 落 native,估值层按账户币种 FX 折算(不在此强转)。</p>
 */
@Service
@Slf4j
public class BrokerSyncService {

    private final BrokerLinkMapper linkMapper;
    private final StockHoldingMapper holdingMapper;
    private final List<BrokerClient> clients;
    private final AccountValuationService valuationService;

    public BrokerSyncService(BrokerLinkMapper linkMapper, StockHoldingMapper holdingMapper,
                             List<BrokerClient> clients, AccountValuationService valuationService) {
        this.linkMapper = linkMapper;
        this.holdingMapper = holdingMapper;
        this.clients = clients;
        this.valuationService = valuationService;
    }

    BrokerClient clientFor(BrokerVendor vendor) {
        return clients.stream().filter(c -> c.vendor() == vendor).findFirst()
                .orElseThrow(() -> new IllegalStateException("无券商客户端:" + vendor));
    }

    /** 同步单个已关联账户;返回状态摘要。 */
    @Transactional
    public String sync(long familyId, long accountId, Long memberId) {
        BrokerLink link = linkMapper.findByAccount(accountId)
                .orElseThrow(() -> new IllegalStateException("该账户未关联券商"));
        if (!link.isEnabled()) throw new IllegalStateException("该账户券商同步已停用");
        BrokerDtos.Snapshot snap = clientFor(link.getVendor()).fetch(familyId, link.getBrokerAccountId());
        String summary = reconcile(accountId, link.getVendor(), snap);
        linkMapper.markSynced(accountId, summary);
        try {
            valuationService.refreshAllForFamily(familyId,
                    AccountValuationService.TriggerKind.HOLDING_CHANGE, memberId);
        } catch (Exception e) {
            log.warn("post-broker-sync valuation refresh failed: {}", e.toString());
        }
        return summary;
    }

    /** cron 用:同步所有 enabled 的关联账户。 */
    public int syncAllEnabled(long familyId, Long memberId) {
        int ok = 0;
        for (BrokerLink link : linkMapper.findAllEnabled()) {
            try { sync(familyId, link.getAccountId(), memberId); ok++; }
            catch (Exception e) { log.warn("broker-sync fail · account={}: {}", link.getAccountId(), e.toString()); }
        }
        return ok;
    }

    /**
     * 对账:只动 sync_source=vendor 的行。返回摘要。包可见供单测。
     */
    String reconcile(long accountId, BrokerVendor vendor, BrokerDtos.Snapshot snap) {
        String src = vendor.name();
        List<StockHolding> existing = holdingMapper.findActiveByAccount(accountId).stream()
                .filter(h -> src.equals(h.getSyncSource())).toList();

        Set<Long> keepIds = new HashSet<>();
        int created = 0, updated = 0;

        // 持仓(AUTO)
        for (BrokerDtos.Position p : snap.positions()) {
            StockHolding match = existing.stream()
                    .filter(h -> h.getValuationMode() == ValuationMode.AUTO
                            && p.ticker().equalsIgnoreCase(h.getTicker())
                            && h.getMarket() != null && p.market().equals(h.getMarket().name()))
                    .findFirst().orElse(null);
            if (match != null) {
                match.setShares(p.shares());
                match.setCostBasis(p.costPrice());
                match.setCurrency(p.currency());
                holdingMapper.update(match);
                keepIds.add(match.getId());
                updated++;
            } else {
                StockHolding h = StockHolding.builder()
                        .accountId(accountId).displayName(p.ticker())
                        .valuationMode(ValuationMode.AUTO)
                        .ticker(p.ticker()).market(Market.valueOf(p.market()))
                        .shares(p.shares()).costBasis(p.costPrice()).currency(p.currency())
                        .syncSource(src).cashLinked(false).build();
                holdingMapper.insert(h);
                keepIds.add(h.getId());
                created++;
            }
        }
        // 现金(CASH · 按币种)
        for (BrokerDtos.Cash csh : snap.cash()) {
            StockHolding match = existing.stream()
                    .filter(h -> h.getValuationMode() == ValuationMode.CASH
                            && csh.currency().equalsIgnoreCase(h.getCurrency()))
                    .findFirst().orElse(null);
            if (match != null) {
                match.setManualValue(csh.amount());
                holdingMapper.update(match);
                keepIds.add(match.getId());
                updated++;
            } else {
                StockHolding h = StockHolding.builder()
                        .accountId(accountId).displayName(csh.currency() + " 现金")
                        .valuationMode(ValuationMode.CASH)
                        .currency(csh.currency()).manualValue(csh.amount())
                        .syncSource(src).cashLinked(false).build();
                holdingMapper.insert(h);
                keepIds.add(h.getId());
                created++;
            }
        }
        // 券商已无 → 软归档
        int archived = 0;
        for (StockHolding h : existing) {
            if (!keepIds.contains(h.getId())) { holdingMapper.archive(h.getId()); archived++; }
        }
        String summary = "同步 · 新增 " + created + " · 更新 " + updated + " · 归档 " + archived
                + (snap.skippedNonEquity() > 0 ? " · 跳过期权/期货 " + snap.skippedNonEquity() : "");
        log.info("broker reconcile · account={} vendor={} {}", accountId, vendor, summary);
        return summary;
    }
}
