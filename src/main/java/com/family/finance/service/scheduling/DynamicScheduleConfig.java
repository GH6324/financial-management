package com.family.finance.service.scheduling;

import com.family.finance.service.FxFetchJob;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.notify.ReportReminderScheduler;
import com.family.finance.service.stock.StockPriceScheduler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;

/**
 * v0.4.18 · 动态 cron 配置中心(详 prd/v0.4.md §22.4)。
 *
 * <p>Spring {@code @Scheduled} 注解是启动时绑定的 · 不能运行时改 cron。
 * 本类用 {@link TaskScheduler#schedule(Runnable, org.springframework.scheduling.Trigger)}
 * 动态注册 · 配置改变时 cancel 旧 future + 重新 schedule。</p>
 *
 * <h3>受管 cron 任务</h3>
 * <ul>
 *   <li>{@code stock-us} · 美股拉价 · 默认 `0 5 6 * * *`</li>
 *   <li>{@code stock-cn} · A 股拉价 · 默认 `0 10 16 * * MON-FRI`</li>
 *   <li>{@code stock-hk} · 港股拉价 · 默认 `0 30 16 * * MON-FRI`</li>
 *   <li>{@code fx} · 汇率拉取 · 默认 `0 30 2 1 * ?`(月初 02:30)</li>
 *   <li>{@code report-remind} · 填报提醒 · 默认 `0 0 10,20 * * *`(每天 10:00/20:00)</li>
 * </ul>
 *
 * <p>{@link com.family.finance.service.PeriodOpener} 的 {@code @Scheduled(cron = "0 30 0 * * *")}
 * 不动 · 用户决定留代码(prd §22.3 类 A · 周期开账深夜任务)。</p>
 *
 * <h3>重排触发</h3>
 * 管理员在 /admin/integrations 或 /admin/reminders 改 cron 配置后 ·
 * 调用 {@link #rescheduleAll()} 立即生效不重启。
 */
@Configuration
@EnableScheduling
@Slf4j
public class DynamicScheduleConfig {

    /** 单家庭模式 · 见 prd §22.3 类 A */
    private static final long FAMILY_ID = 1L;
    private static final String ZONE_ID = "Asia/Shanghai";

    // 默认 cron(代码兜底 · DB 无配置时用)
    private static final String DEFAULT_STOCK_CRON_US     = "0 5 6 * * *";
    private static final String DEFAULT_STOCK_CRON_CN     = "0 10 16 * * MON-FRI";
    private static final String DEFAULT_STOCK_CRON_HK     = "0 30 16 * * MON-FRI";
    private static final String DEFAULT_FX_CRON           = "0 30 2 1 * ?";
    private static final String DEFAULT_REPORT_REMIND_CRON = "0 0 10,20 * * *";

    private final FamilyConfigService configService;
    private final StockPriceScheduler stockScheduler;
    private final FxFetchJob fxFetchJob;
    private final ReportReminderScheduler reminderScheduler;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> active = new HashMap<>();

    public DynamicScheduleConfig(FamilyConfigService configService,
                                 StockPriceScheduler stockScheduler,
                                 FxFetchJob fxFetchJob,
                                 ReportReminderScheduler reminderScheduler) {
        this.configService = configService;
        this.stockScheduler = stockScheduler;
        this.fxFetchJob = fxFetchJob;
        this.reminderScheduler = reminderScheduler;
        this.taskScheduler = createScheduler();
    }

    /** 暴露 TaskScheduler 给 Spring(其它 @Async/@Scheduled 也用同一池) */
    @Bean
    public TaskScheduler taskScheduler() {
        return taskScheduler;
    }

    private TaskScheduler createScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("dyn-sched-");
        s.setRemoveOnCancelPolicy(true);  // cancel 后从队列移除 · 避免 leak
        s.initialize();
        return s;
    }

    /** 应用启动后立刻按当前 DB 配置注册所有受管 cron */
    @PostConstruct
    public void registerAll() {
        log.info("DynamicScheduleConfig · register all managed cron tasks");
        rescheduleAll();
    }

    /**
     * 重排所有受管任务 · 由 admin controller 在配置改后调用。
     * 同步方法保证旧 future 全部 cancel 之后再 schedule 新的 · 避免重复执行。
     */
    public synchronized void rescheduleAll() {
        // cancel 所有旧 future
        for (Map.Entry<String, ScheduledFuture<?>> e : active.entrySet()) {
            if (e.getValue() != null && !e.getValue().isCancelled()) {
                e.getValue().cancel(false);
            }
        }
        active.clear();

        // 重排 5 个受管任务
        schedule("stock-us", configService.getString(FAMILY_ID,
                FamilyConfigService.K_STOCK_CRON_US, DEFAULT_STOCK_CRON_US),
                stockScheduler::fetchUsStocks);

        schedule("stock-cn", configService.getString(FAMILY_ID,
                FamilyConfigService.K_STOCK_CRON_CN, DEFAULT_STOCK_CRON_CN),
                stockScheduler::fetchCnStocks);

        schedule("stock-hk", configService.getString(FAMILY_ID,
                FamilyConfigService.K_STOCK_CRON_HK, DEFAULT_STOCK_CRON_HK),
                stockScheduler::fetchHkStocks);

        schedule("fx", configService.getString(FAMILY_ID,
                FamilyConfigService.K_FX_CRON, DEFAULT_FX_CRON),
                fxFetchJob::runMonthly);

        schedule("report-remind", configService.getString(FAMILY_ID,
                FamilyConfigService.K_REPORT_REMIND_CRON, DEFAULT_REPORT_REMIND_CRON),
                reminderScheduler::scheduled);
    }

    private void schedule(String name, String cronExpr, Runnable task) {
        try {
            CronTrigger trigger = new CronTrigger(cronExpr, TimeZone.getTimeZone(ZONE_ID));
            ScheduledFuture<?> future = taskScheduler.schedule(task, trigger);
            active.put(name, future);
            log.info("[dyn-sched] {} scheduled · cron={}", name, cronExpr);
        } catch (Exception ex) {
            // cron 表达式非法 · 回落到代码默认并继续 · 不让单条配置错误把所有任务都拖死
            log.warn("[dyn-sched] {} INVALID cron `{}` · err={} · 用代码默认兜底", name, cronExpr, ex.toString());
        }
    }
}
