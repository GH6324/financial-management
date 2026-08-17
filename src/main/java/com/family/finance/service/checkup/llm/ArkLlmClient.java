package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 火山方舟(Ark)客户端 · v1.13 FR-361 新增。
 *
 * <p>端点 {@code https://ark.cn-beijing.volces.com/api/v3/chat/completions} · OpenAI 兼容 · Bearer 用
 * {@link FamilyConfigService#K_LLM_ARK_KEY}(DB > env {@code ARK_API_KEY} · 私密)。</p>
 *
 * <p><b>型号必须手工填</b>:方舟的 {@code model} 要么是控制台建的接入点 ID({@code ep-} 开头),
 * 要么是带日期版本的模型 ID。前者是每个账号各自生成的,后者会随版本过期 —— 两种我们都没法预置。
 * 所以目录里方舟的系列 {@code defaultModel} 为 null,路由在编排阶段就会把「没填型号的方舟」
 * 剔出候选,而不是让它变成一次注定 404 的出网调用。</p>
 *
 * <p><b>不复制百炼的多型号轮询</b>(PRD 拍板 3):轮询是为了摊百炼「按型号各给一份」的免费额度,
 * 方舟不是这个计费形态,照搬只会凭空多一个没人看得懂的配置项。</p>
 */
@Component
@Slf4j
public class ArkLlmClient extends AbstractOpenAiCompatibleClient {

    public ArkLlmClient(FamilyConfigService configService, RestTemplateBuilder builder) {
        super(configService, builder);
    }

    @Override
    protected LlmCatalog.Platform platformDef() { return LlmCatalog.ARK; }

    /**
     * 方舟把「接入点不存在 / 没开通该模型」放在 404 与 400 里(错误体带 {@code ModelNotOpen} /
     * {@code InvalidEndpointOrModel} 之类)。这类是<b>配置错</b>,重试多少次都一样 ——
     * 归成账户级,立刻切备选,别把体检卡在这儿等超时。
     */
    @Override
    protected Fault classify(int status, String body) {
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (b.contains("modelnotopen") || b.contains("endpointisinvalid")
                || b.contains("invalidendpointormodel") || b.contains("model not found")
                || b.contains("accessdenied") || b.contains("authenticationerror")) {
            return Fault.ACCOUNT_FATAL;
        }
        return super.classify(status, body);
    }
}
