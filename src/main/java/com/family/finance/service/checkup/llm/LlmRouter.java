package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * v1.13 · <b>LLM 调用的唯一入口</b>:读配置 → 编出主备候选 → 依次调 → 谁成功用谁。
 *
 * <p>为什么必须收口:v1.12 有六处业务代码各自注入 {@code List<LlmClient>} 自己遍历,
 * 其中<b>两处忘了排序</b>(RebalanceAdvisorService / GoalLlmService)—— 它们永远按 Spring 的
 * {@code @Order} 走,也就是永远先打百炼,管理页上把主选改成 DeepSeek 对这两个功能<b>完全无效</b>。
 * 这个 bug 能活下来,是因为「主备顺序」是一段可以被复制、也可以被忘记复制的代码。
 * 现在它只有一份,而且 {@code @Order} 已经删掉了 —— 顺序只能来自配置。
 * 护栏 {@code v113-LLM-ROUTER-SINGLE-PATH} 钉死「{@code List<LlmClient>} 只许出现在本类」。</p>
 *
 * <p>候选编排规则:主选在前、备选在后,并剔掉三类不可能成功的:平台没有对应实现、
 * 配置不自洽(如方舟没填型号)、客户端当前不可用(key 没配 / 熔断中 / 型号全在额度冷却)。
 * <b>剔除发生在出网之前</b>,不让「明知会失败」的调用去占用户的等待时间。</p>
 */
@Service
@Slf4j
public class LlmRouter {

    /** 全项目唯一允许注入 {@code List<LlmClient>} 的地方 */
    private final List<LlmClient> clients;
    private final FamilyConfigService configService;

    public LlmRouter(List<LlmClient> clients, FamilyConfigService configService) {
        this.clients = clients;
        this.configService = configService;
    }

    /** 一次成功调用的结果 */
    public record Outcome(String text, LlmInvocation used) {}

    /**
     * 逐候选处理器。给需要「看到内容再决定收不收」的调用方用(体检要过输出校验、
     * AI 标签要能解析出白名单标签),不收就自动落到下一个候选。
     */
    public interface Handler<T> {
        /**
         * @return 接受则返回结果;<b>返回 null = 不接受这次输出</b>,路由继续试下一个候选
         */
        T onOutput(LlmInvocation invocation, String raw, long elapsedMs);

        /** 调用抛异常时回调(审计用)· 默认不处理 */
        default void onFailure(LlmInvocation invocation, Exception e, long elapsedMs) {}
    }

    /** 按配置编出本次可用的候选链(主 → 备)· 已剔除不可能成功的 */
    public List<LlmInvocation> plan(long familyId) {
        LlmSettings settings = LlmSettings.load(configService, familyId);
        List<LlmInvocation> usable = new ArrayList<>();
        for (LlmInvocation inv : settings.chain()) {
            if (!inv.resolvable()) {
                log.debug("跳过候选 {} · 配置不自洽(平台/系列不存在,或该平台要求手填型号但未填)", inv.label());
                continue;
            }
            Optional<LlmClient> c = clientFor(inv.platform());
            if (c.isEmpty()) {
                log.warn("跳过候选 {} · 没有对应的客户端实现", inv.label());
                continue;
            }
            if (!c.get().available()) {
                log.debug("跳过候选 {} · 当前不可用(key 未配 / 熔断中 / 型号额度冷却)", inv.label());
                continue;
            }
            usable.add(inv);
        }
        return usable;
    }

    /**
     * 这个家庭现在有没有能用的 AI(给「AI 解读」按钮判灰用)。
     *
     * <p>v1.12 这里问的是「有没有任何一个 client 的 key 配了」。v1.13 收严成「编排后还剩候选吗」——
     * 因为多了一种新的失败态:方舟配了 key 但没填型号。按老口径按钮是亮的,点下去必然失败;
     * 按新口径直接告诉用户去管理页补配置。</p>
     */
    public boolean available(long familyId) {
        return !plan(familyId).isEmpty();
    }

    /** 取某平台的客户端(管理页「测试连接」用;业务调用一律走 invoke) */
    public Optional<LlmClient> clientFor(String platform) {
        if (platform == null) return Optional.empty();
        return clients.stream().filter(c -> c.platform().equalsIgnoreCase(platform)).findFirst();
    }

    /** 已装载的平台 code(诊断 / 一致性护栏用) */
    public List<String> platforms() {
        return clients.stream().map(LlmClient::platform).toList();
    }

    /**
     * 简单调用:主选失败切备选,拿到非空输出即返回。
     *
     * @return 成功则 {@link Outcome};全部候选失败或没有可用候选则 {@link Optional#empty()}
     */
    public Optional<Outcome> invoke(long familyId, String systemPrompt, String userPrompt) {
        Outcome r = invoke(familyId, systemPrompt, userPrompt,
                (inv, raw, ms) -> new Outcome(raw, inv));
        return Optional.ofNullable(r);
    }

    /**
     * 带处理器的调用:每个候选调完把原始输出交给 {@code handler} 判收不收,不收就试下一个。
     *
     * @return handler 第一次接受的结果;无人接受 / 全部失败 / 无可用候选 → null
     */
    public <T> T invoke(long familyId, String systemPrompt, String userPrompt, Handler<T> handler) {
        for (LlmInvocation inv : plan(familyId)) {
            LlmClient client = clientFor(inv.platform()).orElse(null);
            if (client == null) continue;      // plan() 已过滤,兜底
            long start = System.currentTimeMillis();
            try {
                String raw = client.chat(inv, systemPrompt, userPrompt);
                long ms = System.currentTimeMillis() - start;
                if (raw == null || raw.isBlank()) {
                    handler.onFailure(inv, new IllegalStateException("空输出"), ms);
                    continue;
                }
                T accepted = handler.onOutput(inv, raw, ms);
                if (accepted != null) return accepted;
                log.warn("候选 {} 的输出未被接受 · 试下一个", inv.label());
            } catch (Exception e) {
                long ms = System.currentTimeMillis() - start;
                log.warn("候选 {} 调用失败 · {}", inv.label(), e.toString());
                handler.onFailure(inv, e, ms);
            }
        }
        return null;
    }
}
