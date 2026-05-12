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
        requireStockAccount(familyId, accountId);
        String normalizedTicker = normalizeTicker(market, ticker);
        validateAuto(normalizedTicker, market, shares, currency);
        StockHolding h = StockHolding.builder()
            .accountId(accountId)
            .displayName(blankToDefault(displayName, normalizedTicker))
            .valuationMode(ValuationMode.AUTO)
            .ticker(normalizedTicker)
            .market(market)
            .shares(shares)
            .costBasis(costBasis)
            .currency(currency != null && !currency.isBlank() ? currency.toUpperCase(Locale.ROOT) : market.defaultCurrency())
            .build();
        holdingMapper.insert(h);
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
        require(familyId, holdingId); // 权限校验
        holdingMapper.archive(holdingId);
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
