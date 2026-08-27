package com.family.finance.service.ask;

import com.family.finance.domain.ask.*;
import com.family.finance.repository.*;
import com.family.finance.service.ask.runtime.AgentRuntime;
import com.family.finance.service.ask.runtime.AskSink;
import com.family.finance.service.ask.runtime.LocalToolLoopRuntime;
import com.family.finance.service.config.FamilyConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.family.finance.service.config.FamilyConfigService.K_ASK_ENABLED;
import static com.family.finance.service.config.FamilyConfigService.K_ASK_RUNTIME;

/**
 * v1.19 · 对话编排。
 *
 * <p>这一层<b>不知道供应商是谁</b> —— 它只认识 {@link AgentRuntime}。
 * 也<b>不做业务计算</b> —— 那些在 {@code service/ask/tools} 里,而那些又只是调既有 service。</p>
 *
 * <p>它真正负责的是一件事:<b>把模型吐出来的东西,变成一条能重放、能核对的记录</b>。
 * 正文里的 {@code {{cite:c1}}} 标记原样落库,数值单独存进 {@link AskCitation}。
 * 于是三个月后重新打开这段对话,数字仍然是当时工具返回的那一个,
 * 口径说明却是<b>今天</b>的说法 —— 因为口径文案是渲染期现取的。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AskConversationService {

    /** 送进模型的历史轮数上限 —— 每一轮都要重新计费,不能无限带 */
    private static final int HISTORY_TURNS = 8;
    private static final int TITLE_MAX = 20;
    /** 单条提问长度上限:再长基本是粘贴了一段文档,不是提问 */
    public static final int QUESTION_MAX = 500;

    private static final long CFG_FAMILY = 1L;

    private final AskConversationMapper conversationMapper;
    private final AskMessageMapper messageMapper;
    private final AskCitationMapper citationMapper;
    private final AskToolCallMapper toolCallMapper;
    private final AskPromptBuilder promptBuilder;
    private final AskCitationRenderer renderer;
    private final FamilyConfigService configService;
    private final List<AgentRuntime> runtimes;
    private final ObjectMapper json = new ObjectMapper();

    // ──────────────────────── 开关与 runtime ────────────────────────

    public boolean enabled(long familyId) {
        return configService.getBoolean(CFG_FAMILY, K_ASK_ENABLED, false);
    }

    /** 当前选定的 runtime;配置里那个不认识就退回本机直连(它前置条件最少) */
    public AgentRuntime runtime() {
        String want = configService.getString(CFG_FAMILY, K_ASK_RUNTIME, LocalToolLoopRuntime.CODE);
        return runtimes.stream().filter(r -> r.code().equals(want)).findFirst()
                .orElseGet(() -> runtimes.stream()
                        .filter(r -> r.code().equals(LocalToolLoopRuntime.CODE))
                        .findFirst().orElse(runtimes.get(0)));
    }

    public List<AgentRuntime> allRuntimes() { return runtimes; }

    /** 不可用的人话原因;可用则 null */
    public String blockedReason(long familyId) {
        if (!enabled(familyId)) return "「问一问」还没打开。到「AI 接入」页开一下。";
        return runtime().unavailableReason(familyId);
    }

    // ──────────────────────── 会话 ────────────────────────

    public AskConversation start(long familyId, Long periodId, String currency) {
        AskConversation c = AskConversation.builder()
                .familyId(familyId)
                .title("新对话")
                .ctxPeriodId(periodId)
                .ctxCurrency(currency)
                .build();
        conversationMapper.insert(c);
        return c;
    }

    public List<AskConversation> recent(long familyId, int limit) {
        return conversationMapper.recent(familyId, limit);
    }

    /** 按 id 取,并校验归属 —— 不能靠「在最近列表里找得到」代替归属校验 */
    public AskConversation find(long familyId, long conversationId) {
        AskConversation c = conversationMapper.findById(conversationId);
        return c != null && c.getFamilyId() == familyId ? c : null;
    }

    /** 取一段会话并装配好引用块与工具摘要(一次查完,不按消息 N+1) */
    public List<AskMessage> history(long conversationId) {
        List<AskMessage> msgs = messageMapper.byConversation(conversationId);
        Map<Long, List<AskCitation>> cites = new LinkedHashMap<>();
        for (AskCitation c : citationMapper.byConversation(conversationId)) {
            cites.computeIfAbsent(c.getMessageId(), k -> new ArrayList<>()).add(renderer.decorate(c));
        }
        Map<Long, List<AskToolCall>> calls = new LinkedHashMap<>();
        for (AskToolCall t : toolCallMapper.byConversation(conversationId)) {
            t.setLabel(renderer.toolLabel(t.getToolName()));
            calls.computeIfAbsent(t.getMessageId(), k -> new ArrayList<>()).add(t);
        }
        for (AskMessage m : msgs) {
            m.setCitations(cites.getOrDefault(m.getId(), List.of()));
            m.setToolCalls(calls.getOrDefault(m.getId(), List.of()));
        }
        return msgs;
    }

    public boolean archive(long familyId, long conversationId) {
        return conversationMapper.archive(conversationId, familyId) > 0;
    }

    /** 上下文变了(换账期 / 换币种)→ 插一条旁白,<b>不新建会话</b> */
    public void noteContextChange(long conversationId, String text) {
        messageMapper.insert(AskMessage.builder()
                .conversationId(conversationId)
                .role(AskMessage.ROLE_NOTE)
                .contentText(text)
                .seq(messageMapper.nextSeq(conversationId))
                .build());
    }

    // ──────────────────────── 一轮提问 ────────────────────────

    /**
     * 提问。<b>同步阻塞</b>,跑在 SSE 线程上;产出经 {@code out} 出去,同时攒起来落库。
     *
     * <p>落库发生在 {@code done} / {@code failed} 时。失败也落 —— <b>半截答案要留住</b>:
     * 用户看得见「查到第二步断了」,比整段消失有用,重试时也知道上次到哪。</p>
     */
    public void ask(long familyId, long conversationId, String question, AskSink out) {
        AskConversation conv = conversationMapper.findById(conversationId);
        if (conv == null || conv.getFamilyId() != familyId) {
            out.failed("这段对话不在了。开一段新的吧。");
            return;
        }
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) { out.failed("先写点什么再问。"); return; }
        if (q.length() > QUESTION_MAX) q = q.substring(0, QUESTION_MAX);

        String blocked = blockedReason(familyId);
        if (blocked != null) { out.failed(blocked); return; }

        // 用户这条先落库 —— 上游炸了也不能把用户打的字弄丢
        int seq = messageMapper.nextSeq(conversationId);
        AskMessage userMsg = AskMessage.builder()
                .conversationId(conversationId).role(AskMessage.ROLE_USER)
                .contentText(q).seq(seq).build();
        messageMapper.insert(userMsg);
        if ("新对话".equals(conv.getTitle())) {
            String title = q.length() > TITLE_MAX ? q.substring(0, TITLE_MAX) : q;
            conversationMapper.updateTitle(conversationId, title);
        }

        List<AgentRuntime.Msg> history = messageMapper
                .recentForContext(conversationId, HISTORY_TURNS * 2).stream()
                .filter(m -> !m.getId().equals(userMsg.getId()))
                .sorted(Comparator.comparing(AskMessage::getSeq))
                .map(m -> new AgentRuntime.Msg(m.getRole(), m.getContentText()))
                .toList();

        String periodLabel = conv.getCtxPeriodId() == null ? null : renderer.periodLabel(conv.getCtxPeriodId());
        String systemPrompt = promptBuilder.build(familyId, periodLabel, conv.getCtxCurrency());

        Collector collector = new Collector(conversationId, out);
        AgentRuntime.AskTurn turn = new AgentRuntime.AskTurn(
                familyId, conversationId, conv.getProviderRef(), systemPrompt, history, q,
                AskScope.DETAIL,           // 产品内对话:数据本来就是用户自己的,不设限
                ref -> conversationMapper.updateProviderRef(conversationId, ref));

        runtime().run(turn, collector);
    }

    // ──────────────────────── 收集器 ────────────────────────

    /**
     * 既往浏览器转发,又往库里攒。
     *
     * <p>两件事必须在<b>同一个对象</b>里做:分开做的话,SSE 送出去的和落库的会有一方漏 ——
     * 而用户刷新页面后看到的是落库那份,对不上的时候会以为答案变了。</p>
     */
    private final class Collector implements AskSink {
        private final long conversationId;
        private final AskSink out;
        private final StringBuilder text = new StringBuilder();
        private final Map<String, AskToolResult.Cite> cites = new LinkedHashMap<>();
        private final List<AskToolCall> calls = new ArrayList<>();
        private boolean closed = false;

        Collector(long conversationId, AskSink out) {
            this.conversationId = conversationId;
            this.out = out;
        }

        @Override public void status(String t) { out.status(t); }

        @Override public void toolStart(String tool, String label) { out.toolStart(tool, label); }

        @Override
        public void toolDone(String tool, String label, int ms, boolean ok,
                             Map<String, AskToolResult.Cite> citable) {
            cites.putAll(citable);
            calls.add(AskToolCall.builder()
                    .toolName(tool).argsJson(null).durationMs(ms).ok(ok).build());
            out.toolDone(tool, label, ms, ok, citable);
        }

        @Override
        public void textDelta(String delta) {
            text.append(delta);
            out.textDelta(delta);
        }

        @Override
        public void rollback(String narration) {
            // 只砍缓冲区尾部那一段 —— 按内容匹配而不是清空,
            // 万一 runtime 传来的和实际收到的对不上,宁可多留一点也不能把真答案删了
            int at = text.lastIndexOf(narration);
            if (at >= 0 && at + narration.length() == text.length()) text.setLength(at);
            out.rollback(narration);
        }

        @Override
        public void done() {
            persist();
            out.done();
        }

        @Override
        public void failed(String msg) {
            // 已经吐出去的正文照样落库,后面补一条旁白说明断在哪
            if (!text.isEmpty()) persist();
            messageMapper.insert(AskMessage.builder()
                    .conversationId(conversationId).role(AskMessage.ROLE_NOTE)
                    .contentText(msg).seq(messageMapper.nextSeq(conversationId)).build());
            out.failed(msg);
        }

        private void persist() {
            if (closed) return;
            closed = true;
            String body = text.toString();
            if (body.isBlank() && calls.isEmpty()) return;

            AskMessage m = AskMessage.builder()
                    .conversationId(conversationId).role(AskMessage.ROLE_ASSISTANT)
                    .contentText(body).seq(messageMapper.nextSeq(conversationId)).build();
            messageMapper.insert(m);

            // 只存正文真的引用到的 —— 一轮里工具可能返回几十个可引用项,
            // 全存进去等于把整张透视表抄进库,而没被引用的那些没有任何用处
            cites.forEach((key, c) -> {
                if (!body.contains("{{cite:" + key + "}}")) return;
                citationMapper.insert(AskCitation.builder()
                        .messageId(m.getId()).citeKey(key).metricKey(c.metricKey()).label(c.label())
                        .periodId(c.periodId()).inProgress(c.inProgress())
                        .valueText(c.valueText()).currency(c.currency())
                        .targetHref(c.targetHref()).build());
            });
            for (AskToolCall t : calls) {
                t.setMessageId(m.getId());
                toolCallMapper.insert(t);
            }
        }
    }
}
