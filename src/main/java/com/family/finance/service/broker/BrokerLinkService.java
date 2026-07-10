package com.family.finance.service.broker;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.broker.BrokerLink;
import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.BrokerLinkMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.stock.StockHoldingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 券商关联 / 解绑 · v0.15。
 *
 * <p>FR-B2/B3:关联(替换)= 高危不可自动回退 → <b>关联前落双份快照</b>(审计 JSON + 软归档),
 * 后悔了可 restore / 按审计还原。两步确认由 controller 强制。</p>
 */
@Service
@Slf4j
public class BrokerLinkService {

    private final BrokerLinkMapper linkMapper;
    private final StockHoldingMapper holdingMapper;
    private final AccountMapper accountMapper;
    private final AuditLogService auditLog;
    private final BrokerSyncService syncService;
    private final ObjectMapper om = new ObjectMapper();

    public BrokerLinkService(BrokerLinkMapper linkMapper, StockHoldingMapper holdingMapper,
                             AccountMapper accountMapper, AuditLogService auditLog,
                             BrokerSyncService syncService) {
        this.linkMapper = linkMapper;
        this.holdingMapper = holdingMapper;
        this.accountMapper = accountMapper;
        this.auditLog = auditLog;
        this.syncService = syncService;
    }

    /**
     * 关联账户到券商(替换策略):快照 + 软归档现有持仓 → 建绑定 → 首次同步(适配器未接线则绑定成功、同步待补)。
     * 两步确认由 controller 校验。
     */
    @Transactional
    public void link(long familyId, long accountId, BrokerVendor vendor, String brokerAccountId,
                     String opendHost, Integer opendPort, Long memberId) {
        Account acc = accountMapper.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        if (!acc.getFamilyId().equals(familyId)) throw new IllegalArgumentException("无权访问账户");
        if (!StockHoldingService.supportsHoldings(acc.getType())) {
            throw new IllegalArgumentException("仅持仓类账户可关联券商");
        }
        if (linkMapper.findByAccount(accountId).isPresent()) {
            throw new IllegalArgumentException("该账户已关联券商,请先解绑");
        }

        // FR-B3 · 关联前快照(审计 JSON)+ 软归档
        List<StockHolding> existing = holdingMapper.findActiveByAccount(accountId);
        auditLog.record(familyId, memberId, AuditLogType.BROKER_LINK, "account", accountId,
                "关联 " + vendor.getLabel() + " 前快照 · " + snapshotJson(existing));
        for (StockHolding h : existing) holdingMapper.archive(h.getId());

        linkMapper.insert(BrokerLink.builder()
                .accountId(accountId).vendor(vendor).brokerAccountId(brokerAccountId)
                .opendHost(opendHost).opendPort(opendPort).enabled(true).build());
        // 首次同步<b>不放在本事务里</b>:sync() 自带事务,若在此嵌套调用且其内部抛错,
        // 会把本事务标记 rollback-only,导致提交时 UnexpectedRollbackException(关联被误报失败)。
        // 由调用方在本事务提交后另起事务跑首次同步(见 initialSync)。
    }

    /**
     * 关联后的首次同步 · <b>必须在 link() 事务提交后调用</b>(不在本类事务上下文内):
     * 这样 sync() 的独立事务能读到已提交的 broker_link,且其失败不牵连关联本身。
     * 未接线适配器 / 未配凭据 → 绑定已成功、同步标"待完成",用户配好后手动同步即可。
     */
    public String initialSync(long familyId, long accountId, Long memberId, String vendorLabel) {
        try {
            return "已关联 " + vendorLabel + " · " + syncService.sync(familyId, accountId, memberId);
        } catch (Exception e) {
            log.warn("initial broker sync pending · account={}: {}", accountId, e.toString());
            try { linkMapper.markSynced(accountId, "待同步:" + e.getMessage()); } catch (Exception ignored) {}
            return "已关联 " + vendorLabel + " · 首次同步待完成 · 请到「管理 → 数据源接入 → 券商同步」配好凭据后回本页点「立即同步」";
        }
    }

    /** 解绑:删绑定 + 同步来的持仓清 sync_source 变普通持仓(被归档旧持仓仍可 restore)。 */
    @Transactional
    public void unlink(long familyId, long accountId, Long memberId) {
        Account acc = accountMapper.findById(accountId).orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        if (!acc.getFamilyId().equals(familyId)) throw new IllegalArgumentException("无权访问账户");
        linkMapper.deleteByAccount(accountId);
        holdingMapper.clearSyncSource(accountId);
        auditLog.record(familyId, memberId, AuditLogType.BROKER_LINK, "account", accountId, "解绑券商");
    }

    private String snapshotJson(List<StockHolding> holdings) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StockHolding h : holdings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getId());
            m.put("name", h.getDisplayName());
            m.put("mode", h.getValuationMode() == null ? null : h.getValuationMode().name());
            m.put("ticker", h.getTicker());
            m.put("market", h.getMarket() == null ? null : h.getMarket().name());
            m.put("shares", h.getShares());
            m.put("cost", h.getCostBasis());
            m.put("currency", h.getCurrency());
            m.put("manualValue", h.getManualValue());
            rows.add(m);
        }
        try { return om.writeValueAsString(rows); }
        catch (Exception e) { return "[]"; }
    }
}
