package com.family.finance.service;

import com.family.finance.config.AppProperties;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.fx.FxRate;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class FxService {

    private static final Set<String> SUPPORTED_QUOTES = Set.of("USD", "HKD");

    private final FxMapper fxMapper;
    private final AccountMapper accountMapper;
    private final FamilyService familyService;
    private final PeriodService periodService;
    private final AuditLogService auditLogService;
    private final AppProperties props;

    public List<FxRate> recent(long familyId, int limit) {
        return fxMapper.findLatestByFamily(familyId, limit);
    }

    /** 后台 cron / 手动按钮 都走这里:为指定家庭、指定周期拉所有 quote 币种的汇率 */
    public int fetchAndStore(long familyId, long periodId) {
        Family family = familyService.require(familyId);
        String base = family.getBaseCurrency();
        int ok = 0;
        RestClient client = RestClient.builder()
                .baseUrl(props.fxApiBase())
                .requestFactory(httpRequestFactory())
                .build();
        for (String quote : SUPPORTED_QUOTES) {
            try {
                BigDecimal rate = fetchRate(client, base, quote);
                fxMapper.upsert(familyId, base, quote, periodId, rate, "frankfurter.dev");
                ok++;
                log.info("[Fx] fetched {} {}->{} = {}", periodId, base, quote, rate);
            } catch (Exception e) {
                log.warn("[Fx] failed {} {}->{}: {}", periodId, base, quote, e.toString());
                auditLogService.systemAlert(familyId, AuditLogType.FX_FETCH,
                        "汇率拉取失败:%s→%s · %s".formatted(base, quote, e.getClass().getSimpleName()));
            }
        }
        return ok;
    }

    /** 手动覆盖(/admin/fx 表单)*/
    public void manualOverride(long familyId, long periodId, String quoteCurrency, BigDecimal rate, Long actorMemberId) {
        Family family = familyService.require(familyId);
        fxMapper.upsert(familyId, family.getBaseCurrency(), quoteCurrency, periodId, rate, "manual");
        auditLogService.write(familyId, actorMemberId, AuditLogType.FX_FETCH,
                "fx_rate", periodId,
                "手动覆盖 %s→%s = %s @ period#%d".formatted(family.getBaseCurrency(), quoteCurrency, rate, periodId),
                Map.of("base", family.getBaseCurrency(), "quote", quoteCurrency, "rate", rate, "periodId", periodId));
    }

    private BigDecimal fetchRate(RestClient client, String base, String quote) {
        // frankfurter.dev API: GET /v1/latest?base=CNY&symbols=USD,HKD
        // 响应:{"amount":1.0,"base":"CNY","date":"...","rates":{"USD":0.147,"HKD":1.151}}
        Map<?, ?> body = client.get()
                .uri("/v1/latest?base={base}&symbols={quote}", base, quote)
                .retrieve()
                .body(Map.class);
        if (body == null) throw new IllegalStateException("empty response");
        Object rates = body.get("rates");
        if (!(rates instanceof Map<?, ?> m)) throw new IllegalStateException("rates missing");
        Object r = m.get(quote);
        if (r == null) throw new IllegalStateException("quote " + quote + " missing");
        return new BigDecimal(r.toString());
    }

    private org.springframework.http.client.ClientHttpRequestFactory httpRequestFactory() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) props.fxFetchTimeoutMs());
        f.setReadTimeout((int) props.fxFetchTimeoutMs());
        return f;
    }

    /** 当前 OPEN 周期(若有)+ 上一个 CLOSED 周期都拉一下,作为初始/补全场景 */
    public void fetchForLatestPeriods(long familyId) {
        Period current = periodService.findCurrentOpen(familyId).orElse(null);
        if (current != null) fetchAndStore(familyId, current.getId());
    }

    /**
     * v0.2.1 BUG-FIX(2026-05-11):critical · 确保 fx_rate 表里
     * 对每个非 base 账户币种 + 指定周期都有一行,FactMapper.queryBase
     * 的 SQL JOIN 才能命中,否则 fx_to_base 走 ELSE 1.0 兜底,USD 余额
     * 会被当 CNY 直接累加到总资产。
     *
     * 调用方:Dashboard / Reports / Checkup load slice 前一次。
     */
    public void ensureForAccountCurrencies(long familyId, String baseCurrency, long periodId) {
        ensureForAccountCurrencies(familyId, baseCurrency, java.util.List.of(periodId));
    }

    /**
     * v0.8 BUG-FIX(v08-CCY-INV-2 · 2026-06-23):<b>跨期换算一致性</b>。
     *
     * <p>v0.8 筛选器重做后,Dashboard/Reports 的 MoM/YoY/净值趋势/TWR/sparkline/本月资产收益率
     * 都吃<b>多期</b> {@code endBalanceBase};但 ensure 仍只覆盖 anchor <b>一期</b>。其它期若缺
     * fx_rate 行,{@code FactMapper.queryBase} 的 SQL JOIN 落 {@code ELSE 1.0} → 那一期非本位币
     * 账户余额<b>未换算</b>。base 视图(无需换算)恒对,一切到 HKD/USD 就「末期换了上期没换」相减
     * = 垃圾,比值随币种乱漂(如本月资产收益率 CNY −18% / HKD −9% / USD −88%)。</p>
     *
     * <p>修:对一组账期(通常是 ≤ anchor 的全部期)统一 ensure。copy-from-latest 很便宜,
     * exact 命中直接早返不写;非本位币账户为空直接跳过。</p>
     */
    public void ensureForAccountCurrencies(long familyId, String baseCurrency, java.util.Collection<Long> periodIds) {
        if (periodIds == null || periodIds.isEmpty()) return;
        java.util.Set<String> nonBase = accountMapper.findActiveByFamily(familyId).stream()
                .map(com.family.finance.domain.account.Account::getCurrency)
                .filter(c -> c != null && !c.equalsIgnoreCase(baseCurrency))
                .map(String::toUpperCase)
                .collect(java.util.stream.Collectors.toSet());
        if (nonBase.isEmpty()) return;
        for (Long periodId : periodIds) {
            if (periodId == null) continue;
            for (String quote : nonBase) {
                Optional<FxRate> rate = getOrFetchRate(familyId, baseCurrency, quote, periodId);
                if (rate.isEmpty()) {
                    log.warn("[Fx] ensureForAccountCurrencies: 拉不到 {} → {} period#{} · "
                            + "总资产/跨期换算可能偏差(USD/HKD 余额被当 CNY 加)", baseCurrency, quote, periodId);
                }
            }
        }
    }

    /**
     * v0.8 BUG-FIX(v08-CCY-INV-2):对单个 quote 币种(典型为<b>视图币种</b>,可能无任何账户、
     * 故 {@link #ensureForAccountCurrencies} 不覆盖)在一组账期上补齐 {@code base→quote} 汇率行,
     * 使 {@code FactMapper} 的本位币三角换算每一期都命中(否则历史期落 1.0 未换算 → 切币种比值漂移)。
     */
    public void ensureRate(long familyId, String baseCurrency, String quoteCurrency, java.util.Collection<Long> periodIds) {
        if (quoteCurrency == null || baseCurrency == null
                || quoteCurrency.equalsIgnoreCase(baseCurrency) || periodIds == null) return;
        for (Long periodId : periodIds) {
            if (periodId != null) getOrFetchRate(familyId, baseCurrency, quoteCurrency, periodId);
        }
    }

    /**
     * v0.2 BUG-FIX(2026-05-10):用户切 viewCurrency 时按需获取汇率。
     * 顺序:DB 查 (familyId, base, quote, periodId) → DB 查最近一期 → 实时拉 frankfurter 写入并返回。
     * 全部失败返回空,调用方应回退到 base + 显示 banner。
     */
    public Optional<FxRate> getOrFetchRate(long familyId, String baseCurrency, String quoteCurrency, long periodId) {
        if (baseCurrency.equalsIgnoreCase(quoteCurrency)) {
            return Optional.empty();
        }
        // 1. 当期 exact 命中 → 返回(SQL JOIN 也能命中)
        Optional<FxRate> exact = fxMapper.findOne(familyId, baseCurrency, quoteCurrency, periodId);
        if (exact.isPresent()) return exact;

        // 2. 别的周期有行 → BUG-FIX(2026-05-11):copy 到当期 period_id,SQL JOIN 才能命中。
        //    用 source='copied-from-period-N' 标记,以便后续审计能区分原始 vs 衍生。
        Optional<FxRate> latest = fxMapper.findLatest(familyId, baseCurrency, quoteCurrency);
        if (latest.isPresent()) {
            FxRate src = latest.get();
            fxMapper.upsert(familyId, baseCurrency, quoteCurrency, periodId, src.getRate(),
                    "copied-from-period-" + src.getPeriodId());
            log.info("[Fx] copied {} {}->{} from period#{} to period#{}: rate={}",
                    familyId, baseCurrency, quoteCurrency, src.getPeriodId(), periodId, src.getRate());
            return fxMapper.findOne(familyId, baseCurrency, quoteCurrency, periodId);
        }

        // 3. DB 完全没行 → 调 frankfurter
        try {
            int wrote = fetchAndStore(familyId, periodId);
            if (wrote > 0) {
                Optional<FxRate> fresh = fxMapper.findOne(familyId, baseCurrency, quoteCurrency, periodId);
                if (fresh.isPresent()) {
                    log.info("[Fx] on-demand fetched rate for {} {}->{} period#{}: {}",
                            familyId, baseCurrency, quoteCurrency, periodId, fresh.get().getRate());
                    return fresh;
                }
            }
        } catch (Exception e) {
            log.warn("[Fx] on-demand fetch failed for {} {}->{} period#{}: {}",
                    familyId, baseCurrency, quoteCurrency, periodId, e.toString());
        }
        return Optional.empty();
    }
}
