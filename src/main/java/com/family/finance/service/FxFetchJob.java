package com.family.finance.service;

import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.PeriodMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每月 1 日 02:30 拉取所有家庭最新汇率(FR-15)+ 应用启动时拉一次兜底。
 * v0.2.1 BUG-FIX(2026-05-11):启动时为每个家庭走 ensureForAccountCurrencies,
 * 保证 fx_rate 表有当期所有非 base 账户币种的行(否则 FactMapper.queryBase
 * SQL JOIN miss → fx_to_base 落 1.0 兜底,USD 余额被当 CNY 累加)。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FxFetchJob {

    private final FamilyService familyService;
    private final FxService fxService;
    private final PeriodMapper periodMapper;

    /**
     * v0.4.18:cron 改由 DynamicScheduleConfig 注册(读 family_runtime_config.fx_cron · 默认 `0 30 2 1 * ?` 月初 02:30)。
     * 此方法保持 public · 调度入口与 admin 手动触发共用。
     */
    public void runMonthly() {
        runForAllFamilies("monthly-cron");
    }

    /** 应用 ready 时跑一次,确保 prod 首次启动后 fx_rate 立刻完整 */
    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        runForAllFamilies("startup");
    }

    private void runForAllFamilies(String trigger) {
        List<Family> families = familyService.findAll();
        log.info("[FxFetchJob] {} tick · families={}", trigger, families.size());
        for (Family f : families) {
            try {
                // 1. 拉所有 supported quote(USD/HKD)的当期汇率
                fxService.fetchForLatestPeriods(f.getId());
                // 2. 然后给每个非 base 账户币种确保当期 fx_rate 存在(防 SQL JOIN miss)
                Period latest = periodMapper.findLatest(f.getId(), 1).stream().findFirst().orElse(null);
                if (latest != null) {
                    fxService.ensureForAccountCurrencies(f.getId(), f.getBaseCurrency(), latest.getId());
                }
            } catch (Exception e) {
                log.warn("[FxFetchJob] {} failed familyId={}: {}", trigger, f.getId(), e.toString());
            }
        }
    }
}
