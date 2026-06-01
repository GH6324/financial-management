package com.family.finance.service.stock;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.StockHoldingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 股票持仓 CRUD · v0.3 FR-52。
 *
 * <p>负责:</p>
 * <ul>
 *   <li>持仓级 AUTO/MANUAL 模式校验(必填字段)</li>
 *   <li>账户类型校验(只允许 STOCK 类型加持仓)</li>
 *   <li>权限校验(account 必须属于当前 family)</li>
 *   <li>ticker 规范化(US 大写 / CN 6 位 / HK 5 位前导零)</li>
 *   <li>AUTO/MANUAL 模式互转(用户从自动改手填时,manual_value 由 caller 传当前估值)</li>
 * </ul>
 *
 * <p>不负责:实际拉价(那是 StockPriceFetcher 的事)/ 估值算账户余额(AccountValuationService)。</p>
 */
@Service
@RequiredArgsConstructor
public class StockHoldingService {

    private final StockHoldingMapper holdingMapper;
    private final AccountMapper accountMapper;
    // v0.5 FR-78/79 · 现金联动(FX 换算 + 市价加回)
    private final com.family.finance.service.FxService fxService;
    private final com.family.finance.repository.PeriodMapper periodMapper;
    private final StockPriceFetcher stockPriceFetcher;

    public List<StockHolding> findActiveByAccount(long familyId, long accountId) {
        requireStockAccount(familyId, accountId);
        return holdingMapper.findActiveByAccount(accountId);
    }

    public List<StockHolding> findAllByAccount(long familyId, long accountId) {
        requireStockAccount(familyId, accountId);
        return holdingMapper.findAllByAccount(accountId);
    }

    public StockHolding require(long familyId, long holdingId) {
        StockHolding h = holdingMapper.findById(holdingId)
            .orElseThrow(() -> new IllegalArgumentException("持仓不存在: " + holdingId));
        // 校验账户属于家庭
        Account acc = accountMapper.findById(h.getAccountId())
            .orElseThrow(() -> new IllegalArgumentException("账户不存在: " + h.getAccountId()));
        if (!acc.getFamilyId().equals(familyId)) {
            throw new IllegalArgumentException("无权访问持仓");
        }
        return h;
    }

    @Transactional
    public StockHolding createAuto(long familyId, long accountId,
                                   String displayName, String ticker, Market market,
                                   BigDecimal shares, BigDecimal costBasis, String currency) {
        return createAuto(familyId, accountId, displayName, ticker, market, shares, costBasis, currency, false);
    }

    /**
     * v0.5 FR-78 · 录 AUTO 持仓 · deductCash=true 时从账户现金划转买入。
     * 勾选 → 强制 costBasis;按 股数×成本 经 FX 换到账户币种,从账户币种现金行扣减(可为负 · 卖空/融资)。
     * 这是账户内「现金 → 股票」再分配,买入当刻净值中性。
     */
    @Transactional
    public StockHolding createAuto(long familyId, long accountId,
                                   String displayName, String ticker, Market market,
                                   BigDecimal shares, BigDecimal costBasis, String currency, boolean deductCash) {
        requireStockAccount(familyId, accountId);
        String normalizedTicker = normalizeTicker(market, ticker);
        validateAuto(normalizedTicker, market, shares, currency);
        String holdingCcy = currency != null && !currency.isBlank()
            ? currency.toUpperCase(Locale.ROOT) : market.defaultCurrency();
        if (deductCash && (costBasis == null || costBasis.signum() <= 0)) {
            throw new IllegalArgumentException("勾选「从账户现金划转买入」时,买入成本必填且 > 0");
        }
        StockHolding h = StockHolding.builder()
            .accountId(accountId)
            .displayName(blankToDefault(displayName, normalizedTicker))
            .valuationMode(ValuationMode.AUTO)
            .ticker(normalizedTicker)
            .market(market)
            .shares(shares)
            .costBasis(costBasis)
            .currency(holdingCcy)
            .cashLinked(deductCash)
            .build();
        holdingMapper.insert(h);
        if (deductCash) {
            // 买入成本(持仓币种)→ FX 到账户币种 → 扣账户币种现金行(可为负)
            BigDecimal costInHoldingCcy = costBasis.multiply(shares);
            adjustAccountCash(familyId, accountId, holdingCcy, costInHoldingCcy.negate());
        }
        return h;
    }

    @Transactional
    public StockHolding createManual(long familyId, long accountId,
                                     String displayName, BigDecimal manualValue) {
        requireStockAccount(familyId, accountId);
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName 必填");
        }
        if (manualValue == null || manualValue.signum() < 0) {
            throw new IllegalArgumentException("manualValue 必须 ≥ 0");
        }
        StockHolding h = StockHolding.builder()
            .accountId(accountId)
            .displayName(displayName.trim())
            .valuationMode(ValuationMode.MANUAL)
            .manualValue(manualValue)
            .manualValueAt(LocalDateTime.now())
            .cashLinked(false)
            .build();
        holdingMapper.insert(h);
        return h;
    }

    /**
     * 更新 MANUAL 持仓的市值(刷新 manual_value_at)。
     */
    @Transactional
    public StockHolding updateManualValue(long familyId, long holdingId, BigDecimal newValue) {
        StockHolding h = require(familyId, holdingId);
        if (h.getValuationMode() != ValuationMode.MANUAL) {
            throw new IllegalArgumentException("仅 MANUAL 持仓可手动更新市值");
        }
        if (newValue == null || newValue.signum() < 0) {
            throw new IllegalArgumentException("市值必须 ≥ 0");
        }
        h.setManualValue(newValue);
        h.setManualValueAt(LocalDateTime.now());
        holdingMapper.update(h);
        return h;
    }

    /**
     * 创建 CASH 行(账户内某币种闲置现金)· v0.3 FR-52e。
     *
     * @param currency 现金币种(必填 · USD/CNY/HKD/JPY/...)· 可以与账户币种不同 · 估值时自动 FX
     * @param amount   该币种金额(必填 · ≥ 0)
     */
    @Transactional
    public StockHolding createCash(long familyId, long accountId,
                                   String displayName, String currency, BigDecimal amount) {
        requireStockAccount(familyId, accountId);
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency 必填(USD/CNY/HKD/...)");
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("amount 必须 ≥ 0");
        }
        String normCcy = currency.trim().toUpperCase(Locale.ROOT);
        String label = (displayName == null || displayName.isBlank())
            ? normCcy + " 现金"
            : displayName.trim();
        StockHolding h = StockHolding.builder()
            .accountId(accountId)
            .displayName(label)
            .valuationMode(ValuationMode.CASH)
            .currency(normCcy)
            .manualValue(amount)
            .manualValueAt(LocalDateTime.now())
            .cashLinked(false)
            .build();
        holdingMapper.insert(h);
        return h;
    }

    /**
     * 更新 CASH 行的金额(刷新 manual_value_at)· 币种不变。
     * 想改币种?archive 后重建。
     */
    @Transactional
    public StockHolding updateCashAmount(long familyId, long holdingId, BigDecimal newAmount) {
        StockHolding h = require(familyId, holdingId);
        if (h.getValuationMode() != ValuationMode.CASH) {
            throw new IllegalArgumentException("仅 CASH 行可用此接口更新金额");
        }
        if (newAmount == null || newAmount.signum() < 0) {
            throw new IllegalArgumentException("金额必须 ≥ 0");
        }
        h.setManualValue(newAmount);
        h.setManualValueAt(LocalDateTime.now());
        holdingMapper.update(h);
        return h;
    }

    /**
     * AUTO → MANUAL 转换 · 用户决定不再拉价(如停牌 / 退市 / 用户不信任数据源)。
     * 当前估值由 caller 计算后传入(AccountValuationService 会算)。
     */
    @Transactional
    public StockHolding convertToManual(long familyId, long holdingId, BigDecimal currentValueAsBaseline) {
        StockHolding h = require(familyId, holdingId);
        if (h.getValuationMode() != ValuationMode.AUTO) return h;
        h.setValuationMode(ValuationMode.MANUAL);
        h.setManualValue(currentValueAsBaseline == null ? BigDecimal.ZERO : currentValueAsBaseline);
        h.setManualValueAt(LocalDateTime.now());
        holdingMapper.update(h);
        return h;
    }

    @Transactional
    public void archive(long familyId, long holdingId) {
        StockHolding h = require(familyId, holdingId); // 权限校验
        // v0.5 FR-79 · 现金联动持仓归档 → 对称按市价把现金加回账户币种现金行(全对称)
        if (Boolean.TRUE.equals(h.getCashLinked()) && h.getValuationMode() == ValuationMode.AUTO
                && h.getTicker() != null && h.getMarket() != null && h.getShares() != null) {
            BigDecimal proceeds = sellProceedsInHoldingCcy(h);
            if (proceeds != null && proceeds.signum() > 0) {
                adjustAccountCash(familyId, h.getAccountId(), h.getCurrency(), proceeds);
            }
        }
        holdingMapper.archive(holdingId);
    }

    /** 卖出收回金额(持仓币种)= 股数 × 当前市价;无价时退回成本价。 */
    private BigDecimal sellProceedsInHoldingCcy(StockHolding h) {
        var snap = stockPriceFetcher.findLatestKnown(h.getTicker(), h.getMarket());
        BigDecimal price = snap != null ? snap.getClosePrice() : h.getCostBasis();
        if (price == null) return null;
        return price.multiply(h.getShares());
    }

    /**
     * v0.5 FR-78/79 · 调整账户币种现金行 · deltaInFromCcy 为正=加回 / 负=扣减(持仓币种口径)。
     * 经 FX 换到账户币种 → 找该币种 CASH 行调整 · 没有则新建(可为负 · 卖空/融资/保证金)。
     */
    private void adjustAccountCash(long familyId, long accountId, String fromCcy, BigDecimal deltaInFromCcy) {
        var account = accountMapper.findById(accountId).orElse(null);
        String acctCcy = account != null && account.getCurrency() != null
            ? account.getCurrency().toUpperCase(Locale.ROOT) : fromCcy;
        BigDecimal deltaInAcctCcy = fxConvert(familyId, deltaInFromCcy, fromCcy, acctCcy);

        StockHolding cashRow = holdingMapper.findActiveByAccount(accountId).stream()
            .filter(x -> x.getValuationMode() == ValuationMode.CASH
                && acctCcy.equalsIgnoreCase(x.getCurrency()))
            .findFirst().orElse(null);
        if (cashRow != null) {
            BigDecimal base = cashRow.getManualValue() == null ? BigDecimal.ZERO : cashRow.getManualValue();
            cashRow.setManualValue(base.add(deltaInAcctCcy).setScale(2, java.math.RoundingMode.HALF_EVEN));
            cashRow.setManualValueAt(LocalDateTime.now());
            holdingMapper.update(cashRow);
        } else {
            StockHolding row = StockHolding.builder()
                .accountId(accountId)
                .displayName(acctCcy + " 现金")
                .valuationMode(ValuationMode.CASH)
                .currency(acctCcy)
                .manualValue(deltaInAcctCcy.setScale(2, java.math.RoundingMode.HALF_EVEN))
                .manualValueAt(LocalDateTime.now())
                .cashLinked(false)
                .build();
            holdingMapper.insert(row);
        }
    }

    /** FX 换算(持仓币种 → 账户币种)· 同币种直接返回 · 走当前 OPEN 周期汇率 · 缺则 1:1 兜底。 */
    private BigDecimal fxConvert(long familyId, BigDecimal amount, String from, String to) {
        if (amount == null) return BigDecimal.ZERO;
        if (from == null || to == null || from.equalsIgnoreCase(to)) return amount;
        var period = periodMapper.findCurrentOpen(familyId).orElse(null);
        if (period == null) return amount;
        var rate = fxService.getOrFetchRate(familyId, from, to, period.getId());
        if (rate.isPresent() && rate.get().getRate() != null && rate.get().getRate().signum() > 0) {
            return amount.multiply(rate.get().getRate()).setScale(2, java.math.RoundingMode.HALF_EVEN);
        }
        var inv = fxService.getOrFetchRate(familyId, to, from, period.getId());
        if (inv.isPresent() && inv.get().getRate() != null && inv.get().getRate().signum() > 0) {
            return amount.divide(inv.get().getRate(), 2, java.math.RoundingMode.HALF_EVEN);
        }
        return amount; // 兜底 1:1
    }

    @Transactional
    public void restore(long familyId, long holdingId) {
        StockHolding h = holdingMapper.findById(holdingId).orElseThrow(
            () -> new IllegalArgumentException("持仓不存在: " + holdingId));
        Account acc = accountMapper.findById(h.getAccountId()).orElseThrow();
        if (!acc.getFamilyId().equals(familyId)) {
            throw new IllegalArgumentException("无权访问持仓");
        }
        holdingMapper.restore(holdingId);
    }

    // ---------- 校验 ----------

    private void requireStockAccount(long familyId, long accountId) {
        Account acc = accountMapper.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("账户不存在: " + accountId));
        if (!acc.getFamilyId().equals(familyId)) {
            throw new IllegalArgumentException("无权访问账户");
        }
        if (acc.getType() == null || !"STOCK".equals(acc.getType().name())) {
            throw new IllegalArgumentException("仅 STOCK 类型账户可加持仓 · 当前类型 " + acc.getType());
        }
    }

    private void validateAuto(String ticker, Market market, BigDecimal shares, String currency) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker 必填");
        if (market == null) throw new IllegalArgumentException("market 必填");
        if (shares == null || shares.signum() <= 0) throw new IllegalArgumentException("shares 必须 > 0");
        // currency 可空 · 用 market.defaultCurrency() 兜底
    }

    /**
     * Ticker 规范化:
     *   US 大写字母(BABA)
     *   CN 6 位数字(600519 / 000001)
     *   HK 5 位前导零(0700 → 00700)
     */
    static String normalizeTicker(Market market, String raw) {
        if (raw == null) return null;
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (market == null) return t;
        return switch (market) {
            case US -> t;
            case CN -> t.replaceAll("\\D", ""); // 移除非数字字符
            case HK -> {
                String digits = t.replaceAll("\\D", "");
                if (digits.length() < 5) {
                    digits = "0".repeat(5 - digits.length()) + digits;
                }
                yield digits;
            }
        };
    }

    private static String blankToDefault(String name, String ticker) {
        return name == null || name.isBlank() ? ticker : name.trim();
    }
}
