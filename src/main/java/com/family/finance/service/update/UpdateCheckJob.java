package com.family.finance.service.update;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * v1.9 · 版本检查的定时入口(挂 {@code DynamicScheduleConfig},与汇率/股价同一套)。
 *
 * <p><b>第一件事就是判 enabled,而且判定在任何 HTTP 构造之前</b> ——
 * PRD FR-303 验收第 6 条:关掉开关后「无后台请求」。守护 {@code v19-UPD-OFF-NO-CALL}
 * 盯着这个顺序,别在判定之前先把 URL/请求对象拼好(那样看着没发,其实已经在准备发了,
 * 而且后人很容易把顺序改反)。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateCheckJob {

    /** 单家庭模式 · 与 DynamicScheduleConfig 同一约定 */
    private static final long FAMILY_ID = 1L;

    private final UpdateCheckService updateCheckService;

    @Value("${app.version:dev}")
    private String appVersion;

    /** 定时触发。失败不抛 —— 调度器不该因为 GitHub 抽风而记一条 ERROR。 */
    public void run() {
        if (!updateCheckService.enabled(FAMILY_ID)) {
            log.debug("update check · 已关闭,跳过");
            return;
        }
        try {
            var info = updateCheckService.checkNow(FAMILY_ID, appVersion);
            log.info("update check · current={} latest={} behind={} mig={}",
                    info.current(), info.latest(), info.behind(),
                    info.migrations().known() ? info.migrations().count() : "未知");
        } catch (Exception e) {
            // checkNow 内部已经吞了异常并写了 lastAttempt,这里只是兜底
            log.info("update check · 定时检查异常: {}", e.toString());
        }
    }

    /**
     * 启动后把上次的结果读进内存缓存。
     *
     * <p>**只读库、不出网** —— 启动过程不该被外部网络拖住。真正的首次检查交给定时器
     * (延迟 2 分钟,见 DynamicScheduleConfig)。</p>
     */
    public void warmUp() {
        try {
            updateCheckService.reloadMemo(FAMILY_ID);
        } catch (Exception e) {
            log.info("update check · 预热失败(不影响启动): {}", e.toString());
        }
    }
}
