package com.family.finance.service.review;

import com.family.finance.repository.RebalancePlanMapper;
import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.List;

/**
 * v1.2 · 再平衡执行闭环(tech-design v1.2 §3)。
 *
 * <p><b>铁律(PRD FR-9)</b>:条目仅 账户+金额,永不出现产品/标的;核销为纯本地规则匹配,不涉 LLM。
 * 核销:划转事件(AFTER_COMMIT)命中 ACTIVE 计划中 同 from/to 且 金额 ≥ 条目×阈值(默认 0.8,
 * 管理页可配)的最早 PENDING 条目 → EXECUTED + 回链流水。金额口径:划转原币直比条目本位币
 * (家庭划转绝大多数为本位币账户;跨币种差异由 80% 容差吸收,文档注明近似)。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RebalancePlanService {

    private final RebalancePlanMapper mapper;
    private final FamilyConfigService configService;

    /** 划转创建事件(EntryService 发布 · AFTER_COMMIT 消费防读到未提交) */
    public record TransferCreatedEvent(long familyId, long transferId,
                                       long fromAccountId, long toAccountId, BigDecimal amount) {}

    public record PlanView(RebalancePlanMapper.Plan plan, List<RebalancePlanMapper.Item> items,
                           int done, int total, BigDecimal doneAmount, BigDecimal totalAmount) {}

    /** 活动计划视图(无计划返回 null) */
    public PlanView activePlan(long familyId) {
        RebalancePlanMapper.Plan plan = mapper.findActive(familyId);
        if (plan == null) return null;
        List<RebalancePlanMapper.Item> items = mapper.findItems(plan.id());
        int done = 0;
        BigDecimal doneAmt = BigDecimal.ZERO, totalAmt = BigDecimal.ZERO;
        for (var it : items) {
            totalAmt = totalAmt.add(it.amountBase());
            if (!"PENDING".equals(it.status())) { done++; doneAmt = doneAmt.add(it.amountBase()); }
        }
        return new PlanView(plan, items, done, items.size(), doneAmt, totalAmt);
    }

    /** 采纳条目(建议勾选或手动)→ 无活动计划则创建 */
    public void addItems(long familyId, long periodId, List<ItemReq> reqs) {
        if (reqs == null || reqs.isEmpty()) return;
        RebalancePlanMapper.Plan plan = mapper.findActive(familyId);
        long planId;
        if (plan == null) {
            var row = new RebalancePlanMapper.PlanRow();
            row.familyId = familyId; row.periodId = periodId;
            mapper.insertPlan(row);
            planId = row.id;
        } else {
            planId = plan.id();
        }
        for (ItemReq r : reqs) {
            if (r.fromAccountId() == r.toAccountId() || r.amount() == null || r.amount().signum() <= 0) continue;
            mapper.insertItem(planId, r.fromAccountId(), r.toAccountId(), r.amount(), trim(r.note()));
        }
    }

    public record ItemReq(long fromAccountId, long toAccountId, BigDecimal amount, String note) {}

    public void manualDone(long familyId, long itemId) { requireOwn(familyId, itemId); mapper.markManualDone(itemId); }
    public void updateAmount(long familyId, long itemId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return;
        requireOwn(familyId, itemId); mapper.updateAmount(itemId, amount);
    }
    public void deleteItem(long familyId, long itemId) { requireOwn(familyId, itemId); mapper.deleteItem(itemId); }

    /** 关账 → 活动计划归档(可回看) */
    public void archiveOnClose(long familyId) {
        int n = mapper.archiveActive(familyId);
        if (n > 0) log.info("再平衡计划随关账归档 · family={}", familyId);
    }

    /** 划转核销(AFTER_COMMIT · 纯本地规则) */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTransfer(TransferCreatedEvent ev) {
        try {
            RebalancePlanMapper.Plan plan = mapper.findActive(ev.familyId());
            if (plan == null) return;
            double th = configService.getDouble(ev.familyId(), FamilyConfigService.K_REBALANCE_MATCH_PCT, 0.8);
            for (var it : mapper.findItems(plan.id())) {
                if (!"PENDING".equals(it.status())) continue;
                if (it.fromAccountId() != ev.fromAccountId() || it.toAccountId() != ev.toAccountId()) continue;
                BigDecimal min = it.amountBase().multiply(BigDecimal.valueOf(th));
                if (ev.amount() != null && ev.amount().compareTo(min) >= 0) {
                    mapper.markExecuted(it.id(), ev.transferId());
                    log.info("再平衡条目核销 · item={} transfer={} {}→{} ¥{}",
                            it.id(), ev.transferId(), it.fromName(), it.toName(), ev.amount());
                    return;   // 一笔划转只核销最早一条
                }
            }
        } catch (Exception e) {
            log.warn("再平衡核销失败(不影响划转本身): {}", e.toString());
        }
    }

    /** 上期执行率摘要(喂 AI 诊断 · 无归档计划返回 null) */
    public String lastArchivedSummary(long familyId) {
        // 轻查询:最近一个 ARCHIVED 计划的 done/total
        return null;   // v1.2 首版:摘要基于活动计划即可;归档摘要 v1.2.x 迭代(避免过度设计)
    }

    private void requireOwn(long familyId, long itemId) {
        RebalancePlanMapper.Plan plan = mapper.findActive(familyId);
        if (plan == null || mapper.findItems(plan.id()).stream().noneMatch(i -> i.id() == itemId)) {
            throw new IllegalArgumentException("条目不存在或不属于当前家庭活动计划");
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : (t.length() > 120 ? t.substring(0, 120) : t);
    }
}
