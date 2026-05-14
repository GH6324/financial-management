package com.family.finance.web.stock;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.StockPriceSnapshotMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.stock.AccountValuationService;
import com.family.finance.service.stock.StockHoldingService;
import com.family.finance.service.stock.StockPriceScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 股票持仓管理 · v0.3 FR-52。
 *
 * <p>路由清单:</p>
 * <ul>
 *   <li>GET  /accounts/{id}/holdings                  · 持仓列表 + 估值结果</li>
 *   <li>GET  /accounts/{id}/holdings/new-auto         · 添加 AUTO 持仓表单</li>
 *   <li>POST /accounts/{id}/holdings/new-auto         · 创建</li>
 *   <li>GET  /accounts/{id}/holdings/new-manual       · 添加 MANUAL 持仓表单</li>
 *   <li>POST /accounts/{id}/holdings/new-manual       · 创建</li>
 *   <li>GET  /accounts/{id}/holdings/new-cash         · 添加 CASH 现金行表单(v0.3 FR-52e)</li>
 *   <li>POST /accounts/{id}/holdings/new-cash         · 创建</li>
 *   <li>POST /accounts/{id}/holdings/{hid}/update     · 更新 MANUAL 市值</li>
 *   <li>POST /accounts/{id}/holdings/{hid}/update-cash · 更新 CASH 现金金额</li>
 *   <li>POST /accounts/{id}/holdings/{hid}/to-manual  · AUTO→MANUAL 转换</li>
 *   <li>POST /accounts/{id}/holdings/{hid}/archive    · 软删</li>
 *   <li>POST /accounts/{id}/holdings/refresh          · 手动刷价(单账户)</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class StockHoldingController {

    private final StockHoldingService holdingService;
    private final AccountValuationService valuationService;
    private final StockPriceScheduler scheduler;
    private final StockPriceSnapshotMapper priceMapper;
    private final AccountMapper accountMapper;
    private final NavService navService;

    @GetMapping("/accounts/{accountId}/holdings")
    public String list(@AuthenticationPrincipal MemberPrincipal me,
                       @PathVariable long accountId,
                       Model model) {
        Account account = requireAccount(me.getFamilyId(), accountId);
        List<StockHolding> active = holdingService.findActiveByAccount(me.getFamilyId(), accountId);
        AccountValuationService.ValuationResult valuation = valuationService.valuate(me.getFamilyId(), accountId);

        // 为每个持仓附加"最新已知价"(给 UI 显示陈旧天数)
        Map<Long, Map<String, Object>> latestPrices = new HashMap<>();
        for (StockHolding h : active) {
            if (h.getValuationMode() == ValuationMode.AUTO && h.getTicker() != null) {
                priceMapper.findLatest(h.getTicker(), h.getMarket().name()).ifPresent(p -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("closePrice", p.getClosePrice());
                    info.put("tradeDate", p.getTradeDate());
                    info.put("source", p.getSource());
                    info.put("staleDays", java.time.temporal.ChronoUnit.DAYS.between(p.getTradeDate(), java.time.LocalDate.now()));
                    latestPrices.put(h.getId(), info);
                });
            }
        }

        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        model.addAttribute("holdings", active);
        model.addAttribute("valuation", valuation);
        model.addAttribute("latestPrices", latestPrices);
        return "stock/holdings";
    }

    @GetMapping("/accounts/{accountId}/holdings/new-auto")
    public String newAutoForm(@AuthenticationPrincipal MemberPrincipal me,
                              @PathVariable long accountId, Model model) {
        Account account = requireAccount(me.getFamilyId(), accountId);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        model.addAttribute("markets", List.of(Market.US, Market.CN, Market.HK));
        return "stock/holding-new-auto";
    }

    @PostMapping("/accounts/{accountId}/holdings/new-auto")
    public String createAuto(@AuthenticationPrincipal MemberPrincipal me,
                             @PathVariable long accountId,
                             @RequestParam(required = false) String displayName,
                             @RequestParam String ticker,
                             @RequestParam String market,
                             @RequestParam BigDecimal shares,
                             @RequestParam(required = false) BigDecimal costBasis,
                             @RequestParam(required = false) String currency) {
        Market mk = parseMarket(market);
        holdingService.createAuto(me.getFamilyId(), accountId, displayName, ticker, mk, shares, costBasis, currency);
        // 立即刷一次价(让用户看到当前估值)· 失败容忍
        try {
            scheduler.fetchMarket(mk);
            // v0.4.1 · 持仓增改 → trigger=HOLDING_CHANGE · 用户感知此次估值因 holding 变动
            valuationService.refreshAllForFamily(me.getFamilyId(),
                AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId());
        } catch (Exception e) {
            log.warn("post-create refresh failed: {}", e.toString());
        }
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @GetMapping("/accounts/{accountId}/holdings/new-manual")
    public String newManualForm(@AuthenticationPrincipal MemberPrincipal me,
                                @PathVariable long accountId, Model model) {
        Account account = requireAccount(me.getFamilyId(), accountId);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        return "stock/holding-new-manual";
    }

    @PostMapping("/accounts/{accountId}/holdings/new-manual")
    public String createManual(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long accountId,
                               @RequestParam String displayName,
                               @RequestParam BigDecimal manualValue) {
        holdingService.createManual(me.getFamilyId(), accountId, displayName, manualValue);
        try {
            // v0.4.1 · 持仓增改 → trigger=HOLDING_CHANGE · 用户感知此次估值因 holding 变动
            valuationService.refreshAllForFamily(me.getFamilyId(),
                AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId());
        } catch (Exception e) {
            log.warn("post-create refresh failed: {}", e.toString());
        }
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/{hid}/update")
    public String updateManualValue(@AuthenticationPrincipal MemberPrincipal me,
                                    @PathVariable long accountId,
                                    @PathVariable long hid,
                                    @RequestParam BigDecimal manualValue) {
        holdingService.updateManualValue(me.getFamilyId(), hid, manualValue);
        try { valuationService.refreshAllForFamily(me.getFamilyId(),
            AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId()); } catch (Exception ignored) {}
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    // ---------- v0.3 FR-52e · CASH 现金行(账户内某币种闲置资金)----------

    @GetMapping("/accounts/{accountId}/holdings/new-cash")
    public String newCashForm(@AuthenticationPrincipal MemberPrincipal me,
                              @PathVariable long accountId, Model model) {
        Account account = requireAccount(me.getFamilyId(), accountId);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        // 常用币种 · 顺序按账户币种优先
        java.util.LinkedHashSet<String> currencies = new java.util.LinkedHashSet<>();
        if (account.getCurrency() != null) currencies.add(account.getCurrency());
        currencies.addAll(List.of("CNY", "USD", "HKD", "JPY", "EUR", "GBP"));
        model.addAttribute("currencies", currencies);
        return "stock/holding-new-cash";
    }

    @PostMapping("/accounts/{accountId}/holdings/new-cash")
    public String createCash(@AuthenticationPrincipal MemberPrincipal me,
                             @PathVariable long accountId,
                             @RequestParam(required = false) String displayName,
                             @RequestParam String currency,
                             @RequestParam BigDecimal amount) {
        holdingService.createCash(me.getFamilyId(), accountId, displayName, currency, amount);
        try { valuationService.refreshAllForFamily(me.getFamilyId(),
            AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId()); } catch (Exception ignored) {}
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/{hid}/update-cash")
    public String updateCashAmount(@AuthenticationPrincipal MemberPrincipal me,
                                   @PathVariable long accountId,
                                   @PathVariable long hid,
                                   @RequestParam BigDecimal amount) {
        holdingService.updateCashAmount(me.getFamilyId(), hid, amount);
        try { valuationService.refreshAllForFamily(me.getFamilyId(),
            AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId()); } catch (Exception ignored) {}
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/{hid}/to-manual")
    public String convertToManual(@AuthenticationPrincipal MemberPrincipal me,
                                  @PathVariable long accountId,
                                  @PathVariable long hid) {
        // 把当前估值传入 · 作 MANUAL 起始值
        AccountValuationService.ValuationResult cur = valuationService.valuate(me.getFamilyId(), accountId);
        // 简化:把整个账户 AUTO 部分平均下到该 holding · 实际中由 holding 自身的最新价 × shares 算最准
        // 这里给个保守值 0 让用户重新填(更安全)· 用户在 UI 上看到当前估值后再填
        holdingService.convertToManual(me.getFamilyId(), hid, BigDecimal.ZERO);
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/{hid}/archive")
    public String archive(@AuthenticationPrincipal MemberPrincipal me,
                          @PathVariable long accountId,
                          @PathVariable long hid) {
        holdingService.archive(me.getFamilyId(), hid);
        try { valuationService.refreshAllForFamily(me.getFamilyId(),
            AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId()); } catch (Exception ignored) {}
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/refresh")
    public String refresh(@AuthenticationPrincipal MemberPrincipal me,
                          @PathVariable long accountId) {
        try {
            // 全市场都拉一次 · 简单粗暴
            scheduler.fetchMarket(Market.US);
            scheduler.fetchMarket(Market.CN);
            scheduler.fetchMarket(Market.HK);
            // v0.4.1 · 用户主动 click → trigger=MANUAL · 写 valuation event 含用户 ID
            valuationService.refreshAllForFamily(me.getFamilyId(),
                AccountValuationService.TriggerKind.MANUAL, me.getMemberId());
        } catch (Exception e) {
            log.warn("refresh failed: {}", e.toString());
        }
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    // ---------- helpers ----------

    private Account requireAccount(long familyId, long accountId) {
        Account acc = accountMapper.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        if (!acc.getFamilyId().equals(familyId)) {
            throw new IllegalArgumentException("无权访问账户");
        }
        if (acc.getType() == null || !"STOCK".equals(acc.getType().name())) {
            throw new IllegalArgumentException("仅 STOCK 类型账户支持持仓管理");
        }
        return acc;
    }

    private Market parseMarket(String raw) {
        try {
            return Market.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 market: " + raw);
        }
    }
}
