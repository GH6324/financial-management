package com.family.finance.service.llmtrace;

import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;

/**
 * v1.28 · 卡片头上 {@code >_} 指向哪里(PRD FR-900 / FR-910 / FR-911 / FR-914)。
 *
 * <p>返回 null = 不显示图标(开关关了)。有记录 → {@code /ai/prompt/{id}};没有记录 → 说明面板
 * (老结果 / 没调用 AI),<b>不拿现在的数据重拼</b>。</p>
 */
@Component("promptPeek")
@RequiredArgsConstructor
public class PromptPeekLinks {

    private final FamilyConfigService configService;

    /** 管理 → AI 接入 的开关(缺省 = 开) */
    public boolean on(Long familyId) {
        return familyId != null && configService.getBoolean(familyId, FamilyConfigService.K_AI_PROMPT_PEEK, true);
    }

    /** 有记录:面板地址;开关关了:null */
    public String of(Long familyId, Long recordId) {
        if (!on(familyId) || recordId == null) return null;
        return "/ai/prompt/" + recordId;
    }

    /**
     * 按结果的情况给地址:有记录 → 面板;没记录但有生成时间 → 「那时还没开始记录」;
     * 这次没调用 AI → 说明原因。
     */
    public String of(Long familyId, Long recordId, PromptSurface surface, LocalDateTime generatedAt, String skippedWhy) {
        if (!on(familyId)) return null;
        if (recordId != null) return "/ai/prompt/" + recordId;
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/ai/prompt/none").queryParam("for", surface.name());
        if (skippedWhy != null) {
            b.queryParam("why", "skipped").queryParam("msg", skippedWhy);
        } else {
            b.queryParam("why", "legacy");
            if (generatedAt != null) b.queryParam("at", generatedAt.withNano(0).toString());
        }
        return b.build().encode().toUriString();
    }

    /**
     * v1.28 FR-919 · 超级 Agent 历史:每条回答 → 它前面那一问的面板地址(提问那条消息上记着编号)。
     * 本版之前的问答没有记录 → 不出图标(一问一答没有「生成时间」可说,说不清就不说)。
     */
    public java.util.Map<Long, String> forAsk(Long familyId, java.util.List<com.family.finance.domain.ask.AskMessage> messages) {
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        if (!on(familyId) || messages == null) return out;
        Long last = null;
        for (var m : messages) {
            if ("user".equals(m.getRole())) {
                last = m.getPromptRecordId();
            } else if ("assistant".equals(m.getRole()) && last != null) {
                out.put(m.getId(), "/ai/prompt/" + last);
            }
        }
        return out;
    }
}
