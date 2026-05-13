package com.family.finance.service.stock;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.StockPriceSnapshot;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.repository.StockPriceSnapshotMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.FxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * 股票账户自动估值 · v0.3 FR-52 · 决策 26。
 *
 * <p>核心 backward compat 红线(已设计):</p>
 * <ul>
 *   <li>**只对"有 stock_holding 持仓"的 STOCK 账户写回 account_balance**</li>
 *   <li>v0.2 用户没创建过持仓的老账户 · 完全不动 · 沿用手填行为</li>
 *   <li>用户创建持仓后,系统才接管自动估值</li>
 * </ul>
 *
 * <p>账户余额 = SUM(AUTO 持仓.shares × latest_price × fx_to_base)
 *            + SUM(MANUAL 持仓.manual_value)</p>
 *
 * <p>价格降级链:今日价 → 历史 snapshot 最近行(stale)→ 跳过该持仓(全局 partial-valued 标识)。</p>
 */
@Service
@Slf4j
public class AccountValuationService {

    private final AccountMapper accountMapper;
    private final StockHoldingMapper holdingMapper;
    private final StockPriceSnapshotMapper priceMapper;
    private final SnapshotMapper snapshotMapper;
    private final PeriodMapper periodMapper;
    private final MemberMapper memberMapper;
    private final FxService fxService;
    private final FamilyService familyService;

    public AccountValuationService(AccountMapper accountMapper,
                                   StockHoldingMapper holdingMapper,
                                   StockPriceSnapshotMapper priceMapper,
                                   SnapshotMapper snapshotMapper,
                                   PeriodMapper periodMapper,
                                   MemberMapper memberMapper,
                                   FxService fxService,
                                   FamilyService familyService) {
        this.accountMapper = accountMapper;
        this.holdingMapper = holdingMapper;
        this.priceMapper = priceMapper;
        this.snapshotMapper = snapshotMapper;
        this.periodMapper = periodMapper;
        this.memberMapper = memberMapper;
        this.fxService = fxService;
        this.familyService = familyService;
    }

    /**
     * 单账户估值 · 用于手动触发(用户在持仓页 click [刷新])。
     *
     * @return 估值结果 + 是否含陈旧价格
     */
    public ValuationResult valuate(long familyId, long accountId) {
        Account acc = accountMapper.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("账户不存在: " + accountId));
        if (!acc.getFamilyId().equals(familyId)) {
            throw new IllegalArgumentException("无权访问账户");
        }
        return valuateInternal(acc);
    }

    /**
     * 全家所有 STOCK 账户 · cron 触发(StockPriceScheduler 调用)。
     * 仅遍历**有 holding** 的 STOCK 账户 · 写回 account_balance(当前 OPEN 周期)。
     */
    public int refreshAllForFamily(long familyId) {
        Period currentOpen = periodMapper.findCurrentOpen(familyId).orElse(null);
        if (currentOpen == null) {
            log.info("family={} no OPEN period · skip valuation refresh", familyId);
            return 0;
        }
        List<Account> accounts = accountMapper.findActiveByFamily(familyId);
        int refreshed = 0;
        for (Account acc : accounts) {
            if (acc.getType() == null || !"STOCK".equals(acc.getType().name())) continue;
            List<StockHolding> holdings = holdingMapper.findActiveByAccount(acc.getId());
            if (holdings.isEmpty()) {
                // backward compat 红线:无 holding 的老账户不接管 · 用户继续手填
                continue;
            }
            ValuationResult r = valuateInternal(acc);
            writeBackBalance(familyId, currentOpen.getId(), acc, r.totalBaseValue());
            refreshed++;
        }
        log.info("family={} valuation refresh · stockAccountsWithHoldings={}", familyId, refreshed);
        return refreshed;
    }

    /**
     * 跨家庭刷新(cron 每个市场拉完价后调用)。
     */
    public int refreshAll() {
        // 找所有有 holding 的家庭(distinct family_id from stock_holding → account.family_id)
        // 简化:遍历所有家庭(实际 v0.3 是单家庭)
        // TODO: 多家庭支持时优化为只刷新有 holding 的家庭
        List<Long> familyIds = accountMapper.findActiveByFamily(1L).stream()
            .map(Account::getFamilyId).distinct().toList();
        // 退化:本版只刷 family=1(单家庭设计 · 见 SECURITY.md)
        return refreshAllForFamily(1L);
    }

    // ---------- 内部 ----------

    private ValuationResult valuateInternal(Account acc) {
        List<StockHolding> holdings = holdingMapper.findActiveByAccount(acc.getId());
        if (holdings.isEmpty()) {
            return new ValuationResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }

        BigDecimal autoBase = BigDecimal.ZERO;
        BigDecimal manualBase = BigDecimal.ZERO;
        BigDecimal cashBase = BigDecimal.ZERO;
        int staleCount = 0;
        int missingCount = 0;

        for (StockHolding h : holdings) {
            switch (h.getValuationMode()) {
                case MANUAL -> {
                    if (h.getManualValue() != null) {
                        manualBase = manualBase.add(h.getManualValue());
                    }
                }
                case CASH -> {
                    // v0.3 FR-52e:账户内某币种现金 · 用 FX 换到账户币种
                    if (h.getManualValue() != null && h.getCurrency() != null) {
                        BigDecimal fxRate = resolveFxRate(acc.getFamilyId(), h.getCurrency(), acc.getCurrency());
                        BigDecimal v = h.getManualValue().multiply(fxRate).setScale(2, RoundingMode.HALF_EVEN);
                        cashBase = cashBase.add(v);
                    }
                }
                case AUTO -> {
                    Optional<StockPriceSnapshot> priceOpt = priceMapper.findLatest(h.getTicker(), h.getMarket().name());
                    if (priceOpt.isEmpty()) {
                        missingCount++;
                        continue;
                    }
                    StockPriceSnapshot price = priceOpt.get();
                    int staleDays = (int) java.time.temporal.ChronoUnit.DAYS.between(
                        price.getTradeDate(), java.time.LocalDate.now());
                    if (staleDays > 7) staleCount++;
                    BigDecimal originalMarketValue = price.getClosePrice().multiply(h.getShares());
                    BigDecimal fxRate = resolveFxRate(acc.getFamilyId(), h.getCurrency(), acc.getCurrency());
                    BigDecimal accCurrencyValue = originalMarketValue.multiply(fxRate)
                        .setScale(2, RoundingMode.HALF_EVEN);
                    autoBase = autoBase.add(accCurrencyValue);
                }
            }
        }

        BigDecimal total = autoBase.add(manualBase).add(cashBase);
        return new ValuationResult(total, autoBase, manualBase, cashBase, staleCount, missingCount);
    }

    /**
     * 把估值写回 account_balance(period_snapshot 表)· 复用 v0.2 upsert 逻辑。
     * 这是关键集成点:v0.2 下游 fact_view / dashboard / XIRR / 目标进度 自动反映 · 零改动。
     *
     * <p>v0.2 schema 把 submitted_by NOT NULL · 系统自动写入用 account.primary_owner_member_id 兜底;
     * 若该字段也为 null,用 family 第一个 member。区分系统/用户写入靠 note='auto-stock-valuation v0.3'。</p>
     */
    private void writeBackBalance(long familyId, long periodId, Account acc, BigDecimal balance) {
        Long submittedBy = acc.getPrimaryOwnerMemberId();
        if (submittedBy == null) {
            // 兜底:family 第一个 member
            submittedBy = memberMapper.findActiveByFamily(familyId).stream()
                .findFirst().map(m -> m.getId()).orElse(null);
        }
        if (submittedBy == null) {
            log.warn("can't resolve submittedBy for account={} · skip valuation writeback", acc.getId());
            return;
        }
        PeriodSnapshot snap = PeriodSnapshot.builder()
            .periodId(periodId)
            .accountId(acc.getId())
            .endBalance(balance)
            .submittedBy(submittedBy)
            .note("auto-stock-valuation v0.3")
            .build();
        snapshotMapper.upsert(snap);
    }

    /**
     * 取 fromCurrency → toCurrency 的汇率(可能跨币种 · 自动经 base 中转)。
     *
     * <p>解析顺序:</p>
     * <ol>
     *   <li>直接 from→to</li>
     *   <li>反向 to→from · 用 1/rate</li>
     *   <li>**经家庭 base 中转** · from→base × base→to(每段各自尝试直接 / 反向)</li>
     * </ol>
     *
     * <p>典型场景:账户 currency=HKD · 持仓 currency=USD · base=CNY · fx_rate 表只存 CNY→other</p>
     * <ul>
     *   <li>查 USD→HKD:直接 ✗ · 反向 ✗</li>
     *   <li>链式:USD→CNY(用 1 / (CNY→USD))× CNY→HKD = 6.80 × 1.152 ≈ 7.83</li>
     * </ul>
     */
    private BigDecimal resolveFxRate(long familyId, String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) {
            return BigDecimal.ONE;
        }
        Period current = periodMapper.findCurrentOpen(familyId).orElse(null);
        if (current == null) return BigDecimal.ONE;

        // 1 + 2: 直接 + 反向
        BigDecimal direct = directOrReverse(familyId, fromCurrency, toCurrency, current.getId());
        if (direct != null) return direct;

        // 3: 经家庭 base 中转
        String base = familyService.require(familyId).getBaseCurrency();
        if (base != null
                && !base.equalsIgnoreCase(fromCurrency)
                && !base.equalsIgnoreCase(toCurrency)) {
            BigDecimal fromToBase = directOrReverse(familyId, fromCurrency, base, current.getId());
            BigDecimal baseToOut  = directOrReverse(familyId, base, toCurrency, current.getId());
            if (fromToBase != null && baseToOut != null) {
                BigDecimal chained = fromToBase.multiply(baseToOut).setScale(8, RoundingMode.HALF_EVEN);
                log.debug("fx chained · {}→{}→{} = {}", fromCurrency, base, toCurrency, chained);
                return chained;
            }
        }

        log.warn("no fx rate · {}/{} · family={} · using 1.0 fallback", fromCurrency, toCurrency, familyId);
        return BigDecimal.ONE;
    }

    /** 尝试 from→to · 失败试反向 to→from 取倒数 · 都失败返回 null。 */
    private BigDecimal directOrReverse(long familyId, String from, String to, long periodId) {
        var d = fxService.getOrFetchRate(familyId, from, to, periodId);
        if (d.isPresent() && d.get().getRate() != null && d.get().getRate().signum() > 0) {
            return d.get().getRate();
        }
        var r = fxService.getOrFetchRate(familyId, to, from, periodId);
        if (r.isPresent() && r.get().getRate() != null && r.get().getRate().signum() > 0) {
            return BigDecimal.ONE.divide(r.get().getRate(), 8, RoundingMode.HALF_EVEN);
        }
        return null;
    }

    /**
     * 估值结果值对象。
     *
     * @param totalBaseValue  账户余额(账户币种 · 写入 account_balance)= auto+manual+cash
     * @param autoBaseValue   AUTO 持仓合计(账户币种)
     * @param manualBaseValue MANUAL 持仓合计(账户币种 · 用户直接填)
     * @param cashBaseValue   CASH 行合计(账户币种 · 各行 currency 已 FX 至账户币种)· v0.3 FR-52e
     * @param staleCount      陈旧 > 7 天的 AUTO 持仓数
     * @param missingCount    完全无价的 AUTO 持仓数
     */
    public record ValuationResult(
        BigDecimal totalBaseValue,
        BigDecimal autoBaseValue,
        BigDecimal manualBaseValue,
        BigDecimal cashBaseValue,
        int staleCount,
        int missingCount
    ) {
        public boolean hasIssues() { return staleCount > 0 || missingCount > 0; }
    }
}
