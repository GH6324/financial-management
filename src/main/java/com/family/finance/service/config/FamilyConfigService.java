package com.family.finance.service.config;

import com.family.finance.domain.config.FamilyRuntimeConfig;
import com.family.finance.repository.FamilyRuntimeConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.4.18 · 运营级配置统一读取服务(详 prd/v0.4.md §22)。
 *
 * <h3>三层 fallback 链(§22.7.1)</h3>
 * <pre>
 *   1. DB family_runtime_config(用户在管理页改的值)
 *   2. env / yml @Value(deploy.sh 种子前的 prod env 值 · 或 application.yml 默认)
 *   3. 调用方传入的 codeDefault(代码级最终兜底)
 * </pre>
 *
 * <h3>缓存(§22.7.2)</h3>
 * 5 秒 TTL per (family, key) · 写穿透时立即 invalidate · 避免 checkup 规则/cron trigger 每命中都查 DB。
 *
 * <h3>私密红线</h3>
 * LLM API key 等敏感字段:get* 方法日常返回原值 ·
 * 调用方(QwenLlmClient / DeepSeekLlmClient / 短信渠道)只在出网 HTTP 调用时用 ·
 * **绝不打 log / 进 LLM prompt / 进 audit_log 明文**。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FamilyConfigService {

    private final FamilyRuntimeConfigMapper mapper;

    // ========== Key 字符串常量(避免拼写错) ==========
    // Integrations · LLM
    public static final String K_LLM_QWEN_KEY      = "llm_qwen_api_key";
    public static final String K_LLM_DEEPSEEK_KEY  = "llm_deepseek_api_key";
    public static final String K_LLM_MAX_TOKENS    = "llm_max_tokens";
    public static final String K_LLM_TIMEOUT_SECS  = "llm_timeout_seconds";
    /** v0.6 · Qwen 多模型兜底:逗号分隔有序模型列表(≤10)· 某模型免费额度用尽自动切下一个 */
    public static final String K_LLM_QWEN_MODELS   = "llm_qwen_models";
    // Integrations · 股票拉取
    public static final String K_STOCK_ENABLED     = "stock_fetch_enabled";
    public static final String K_STOCK_CRON_US     = "stock_cron_us";
    public static final String K_STOCK_CRON_CN     = "stock_cron_cn";
    public static final String K_STOCK_CRON_HK     = "stock_cron_hk";
    // Integrations · FX
    public static final String K_FX_CRON           = "fx_cron";
    // 提醒 cron
    public static final String K_REPORT_REMIND_CRON = "report_remind_cron";
    // calc-tweaks 体检阈值
    public static final String K_CHECKUP_CONCENTRATION = "checkup_concentration_threshold";
    public static final String K_CHECKUP_HIGH_RISK     = "checkup_high_risk_threshold";
    public static final String K_LIQUID_BUFFER         = "liquid_buffer_ratio";
    public static final String K_EMERGENCY_MONTHS      = "emergency_fund_months";
    // 录入 epsilon / 阈值(已有 3 项 · 之前 hardcode)
    public static final String K_SMART_TRANSFER       = "smart_transfer_threshold";
    public static final String K_LOAN_ABNORMAL        = "loan_abnormal_threshold";
    public static final String K_UNEXPLAINED_EPSILON  = "unexplained_epsilon";
    // 会话
    public static final String K_REMEMBER_ME_SECONDS  = "remember_me_validity_seconds";

    // ========== env / yml @Value fallback ==========
    @Value("${finance.llm.qwen.api-key:}")
    private String envQwenKey;
    @Value("${finance.llm.deepseek.api-key:}")
    private String envDeepseekKey;
    @Value("${finance.stock.fetch-enabled:false}")
    private boolean envStockEnabled;
    @Value("${app.remember-me-validity-seconds:2592000}")
    private long envRememberMeSeconds;

    // ========== Cache · 5s TTL ==========
    private static final long CACHE_TTL_MILLIS = 5_000L;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Optional<String> value, long expireAt) {
        boolean isFresh() { return System.currentTimeMillis() < expireAt; }
    }

    /** 内部:DB 查找 + cache · 返回 Optional · 不做 fallback */
    private Optional<String> dbGet(long familyId, String key) {
        String cacheKey = familyId + "|" + key;
        CacheEntry e = cache.get(cacheKey);
        if (e != null && e.isFresh()) return e.value();
        Optional<String> v = mapper.findValue(familyId, key);
        cache.put(cacheKey, new CacheEntry(v, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        return v;
    }

    /** 写入并立刻 invalidate cache · 触发动态 cron 重排由调用方决定 */
    public void set(long familyId, String key, String value) {
        mapper.upsert(familyId, key, value == null ? "" : value);
        cache.remove(familyId + "|" + key);
        log.info("config updated · family={} key={}", familyId,
                key.contains("key") || key.contains("secret") ? key + "=*** (private)" : key + "=" + value);
    }

    /** 批量获取整个 family 的 config(管理页一次性 render 用)· 不走 cache */
    public Map<String, String> getAll(long familyId) {
        List<FamilyRuntimeConfig> rows = mapper.findByFamily(familyId);
        Map<String, String> result = new HashMap<>();
        for (FamilyRuntimeConfig r : rows) result.put(r.getKeyName(), r.getValueText());
        return result;
    }

    // ========== 类型化访问 ==========

    /** 字符串 · DB > env(部分 key)> codeDefault */
    public String getString(long familyId, String key, String codeDefault) {
        Optional<String> db = dbGet(familyId, key);
        if (db.isPresent() && !db.get().isEmpty()) return db.get();
        // env fallback for known keys
        return switch (key) {
            case K_LLM_QWEN_KEY     -> isBlank(envQwenKey)     ? codeDefault : envQwenKey;
            case K_LLM_DEEPSEEK_KEY -> isBlank(envDeepseekKey) ? codeDefault : envDeepseekKey;
            default -> codeDefault;
        };
    }

    /** boolean · 字符串 "true"/"false" */
    public boolean getBoolean(long familyId, String key, boolean codeDefault) {
        Optional<String> db = dbGet(familyId, key);
        if (db.isPresent() && !db.get().isEmpty()) return Boolean.parseBoolean(db.get());
        return switch (key) {
            case K_STOCK_ENABLED -> envStockEnabled;
            default -> codeDefault;
        };
    }

    /** int · 整数解析失败走 codeDefault */
    public int getInt(long familyId, String key, int codeDefault) {
        Optional<String> db = dbGet(familyId, key);
        if (db.isPresent() && !db.get().isEmpty()) {
            try { return Integer.parseInt(db.get().trim()); }
            catch (NumberFormatException e) { /* 走 fallback */ }
        }
        return codeDefault;
    }

    /** long · remember-me 等 */
    public long getLong(long familyId, String key, long codeDefault) {
        Optional<String> db = dbGet(familyId, key);
        if (db.isPresent() && !db.get().isEmpty()) {
            try { return Long.parseLong(db.get().trim()); }
            catch (NumberFormatException e) { /* 走 fallback */ }
        }
        return switch (key) {
            case K_REMEMBER_ME_SECONDS -> envRememberMeSeconds;
            default -> codeDefault;
        };
    }

    /** double · checkup 阈值 / LIQUID buffer 等 */
    public double getDouble(long familyId, String key, double codeDefault) {
        Optional<String> db = dbGet(familyId, key);
        if (db.isPresent() && !db.get().isEmpty()) {
            try { return Double.parseDouble(db.get().trim()); }
            catch (NumberFormatException e) { /* 走 fallback */ }
        }
        return codeDefault;
    }

    /** "已配置(隐藏)" / "未配置" 状态查询(管理页显)· 不返回 value 本身 */
    public boolean isPrivateKeyConfigured(long familyId, String key) {
        String v = getString(familyId, key, "");
        return v != null && !v.isBlank();
    }

    /** 触发 cache 全部 invalidate(deploy.sh seed 后 / 测试用) */
    public void invalidateAll() {
        int n = cache.size();
        cache.clear();
        log.info("config cache invalidated · {} entries", n);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
