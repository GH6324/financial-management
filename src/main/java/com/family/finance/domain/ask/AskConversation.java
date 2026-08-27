package com.family.finance.domain.ask;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v1.19 · 一段对话。
 *
 * <p>{@code ctxPeriodId} / {@code ctxCurrency} 记的是<b>开这段对话时</b>用户所在的账期与视图币种。
 * 用户中途换了账期<b>不新开会话</b> —— 只往消息流里插一条 {@code system_note},
 * 因为「我刚才问的是 7 月,现在想看 8 月」是同一个话题的延续,断开会话等于把上下文丢了。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AskConversation {
    private Long id;
    private Long familyId;
    private String title;
    /** 云端 session / response id —— 只有 Managed Agent 路线有值 */
    private String providerRef;
    private Long ctxPeriodId;
    private String ctxCurrency;
    private LocalDateTime createdAt;
    private LocalDateTime archivedAt;

    public boolean archived() { return archivedAt != null; }
}
