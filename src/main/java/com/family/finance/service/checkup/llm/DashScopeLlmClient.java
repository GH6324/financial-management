package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阿里云百炼(DashScope)客户端 · v0.2 FR-40c · v0.6 多模型额度兜底(FR-108)· v1.13 平台化改名。
 *
 * <p>v1.13 之前叫 {@code QwenLlmClient} —— 那个名字把「平台」和「模型系列」当成了一件事。
 * 百炼上不只有通义千问,方舟上也有 DeepSeek,所以类名跟着平台走,系列由
 * {@link LlmInvocation} 传进来。</p>
 *
 * <p><b>多型号轮询是百炼专属</b>({@link LlmCatalog.Platform#modelRotation()}):它的免费额度
 * <b>按型号各给一份</b>,用尽某个型号返回 429 {@code Throttling.AllocationQuota / insufficient_quota}
 * 或 403 {@code AllocationQuota.FreeTierOnly}。此时切下一个型号(各自独立额度),把用尽的那个
 * 冷却 6h。账户级故障(欠费 / 账单过期)对所有型号一致 —— 立刻抛出,由 {@link LlmRouter}
 * 切到备选平台,不浪费在切型号上。</p>
 *
 * <p>型号池 {@link FamilyConfigService#K_LLM_QWEN_MODELS} 运营可配(≤10 · 逗号分隔有序)。
 * 只在「型号=自动」时生效;管理页钉死了具体型号就只用那一个。</p>
 */
@Component
@Slf4j
public class DashScopeLlmClient extends AbstractOpenAiCompatibleClient {

    /** 目录里的通义千问系列 · 默认型号池与兜底型号都从这里取,不在本类再抄一份 */
    private static final LlmCatalog.Family QWEN = LlmCatalog.DASHSCOPE.family("qwen")
            .orElseThrow(() -> new IllegalStateException("目录里没有 dashscope 的「qwen」系列 · 轮询池无从取值"));

    /**
     * 默认型号池 = {@link LlmCatalog} 里该系列的推荐清单(运营可在管理页覆盖)。
     * <b>这里不再抄一份型号名</b>:抄一份的后果是目录里加/删型号时轮询池悄悄跟不上 ——
     * 不报错,只是某个型号永远轮不到、或者轮到一个已下架的(FR-364 型号清单唯一一份)。
     *
     * <p>每次调用<b>随机</b>选一个,把流量均匀摊到各型号的免费额度上,避免总砸在一个上、用超后被静默计费。
     * 稳定别名 qwen-max/plus/flash 永远指向当前各档最新版,是三档能力梯度、三个独立额度池。
     * <b>关键</b>:控制台开「免费额度用完即停」→ 用尽返回 403 AllocationQuota.FreeTierOnly(本类已识别→切下个型号)→ 永不计费。</p>
     */
    private static final String DEFAULT_MODELS =
            String.join(",", QWEN.models().stream().map(LlmCatalog.Model::id).toList());
    private static final int MAX_MODELS = 10;
    /** 某型号免费额度用尽后的冷却(免费额度按周期重置 · 6h 内不再尝试该型号) */
    private static final long MODEL_EXHAUST_COOLDOWN_MS = 6L * 60 * 60 * 1000;

    /** 型号 → 额度用尽冷却到期时刻 */
    private final ConcurrentHashMap<String, Instant> modelExhaustedUntil = new ConcurrentHashMap<>();

    public DashScopeLlmClient(FamilyConfigService configService, RestTemplateBuilder builder) {
        super(configService, builder);
    }

    @Override
    protected LlmCatalog.Platform platformDef() { return LlmCatalog.DASHSCOPE; }

    /** 解析有序型号列表(去重 · 去空 · 上限 10)· 配置空则用默认 */
    List<String> currentModels() {
        String raw = configService.getString(FAMILY_ID, FamilyConfigService.K_LLM_QWEN_MODELS, "");
        if (raw == null || raw.isBlank()) raw = DEFAULT_MODELS;
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String m = part.trim();
            if (!m.isEmpty()) ordered.add(m);
            if (ordered.size() >= MAX_MODELS) break;
        }
        if (ordered.isEmpty() && QWEN.defaultModel() != null) ordered.add(QWEN.defaultModel());
        return new ArrayList<>(ordered);
    }

    @Override
    protected List<String> attemptModels(LlmInvocation inv) {
        if (inv.model() != null) return List.of(inv.model());   // 钉死型号 → 只用它
        List<String> pool = new ArrayList<>(currentModels());
        Collections.shuffle(pool);                              // 自动 → 摊到各型号独立免费额度
        return pool;
    }

    @Override
    protected boolean modelAvailable(String model) {
        Instant until = modelExhaustedUntil.get(model);
        return until == null || Instant.now().isAfter(until);
    }

    @Override
    protected void markModelExhausted(String model) {
        modelExhaustedUntil.put(model, Instant.now().plusMillis(MODEL_EXHAUST_COOLDOWN_MS));
    }

    @Override
    public boolean available() {
        // 除了 key + 熔断,还要求至少有一个型号不在额度冷却里
        return super.available() && currentModels().stream().anyMatch(this::modelAvailable);
    }

    /** 百炼把「单型号免费额度用尽」也放在 403/429 里,必须看 body 才能和账户级故障区分开。 */
    @Override
    protected Fault classify(int status, String body) {
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        // 账户级 · 所有型号一致(欠费 / 账单过期)
        if (b.contains("arrearage") || b.contains("billoverdue")
                || b.contains("prepaid bill") || b.contains("postpaid bill")) {
            return Fault.ACCOUNT_FATAL;
        }
        // 单型号免费额度用尽 → 切下一型号
        if (b.contains("allocationquota") || b.contains("insufficient_quota")
                || b.contains("freetieronly") || b.contains("free allocated quota")
                || (b.contains("free") && b.contains("quota") && b.contains("exhaust"))) {
            return Fault.MODEL_QUOTA;
        }
        return super.classify(status, body);
    }
}
