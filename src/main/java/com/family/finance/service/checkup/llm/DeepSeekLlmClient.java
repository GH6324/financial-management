package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 官方平台客户端 · v0.2 FR-40c · v1.13 收进 {@link AbstractOpenAiCompatibleClient}。
 *
 * <p>端点 {@code https://api.deepseek.com/chat/completions} · OpenAI 兼容 · Bearer 用
 * {@link FamilyConfigService#K_LLM_DEEPSEEK_KEY}(DB > env · 私密)。</p>
 *
 * <p><b>注意和「方舟上的 DeepSeek」不是一回事</b>:同一个模型系列在两个平台上是两套端点、
 * 两把 key、两份账单。所以它们在目录里是两个平台各自的 {@code deepseek} 系列,不是一个东西 ——
 * 这正是 v1.13 把 vendor 拆成三级的原因。</p>
 *
 * <p>无多型号轮询:DeepSeek 没有「按型号各给一份免费额度」这回事,轮询在这里没有意义。</p>
 */
@Component
@Slf4j
public class DeepSeekLlmClient extends AbstractOpenAiCompatibleClient {

    public DeepSeekLlmClient(FamilyConfigService configService, RestTemplateBuilder builder) {
        super(configService, builder);
    }

    @Override
    protected LlmCatalog.Platform platformDef() { return LlmCatalog.DEEPSEEK; }
}
