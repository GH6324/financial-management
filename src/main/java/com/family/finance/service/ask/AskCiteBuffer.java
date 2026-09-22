package com.family.finance.service.ask;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 托管 Agent 模式下,把工具产出的<b>引用</b>从 MCP 请求带回正在进行的那一轮问答。
 *
 * <h3>为什么需要这么一个中转</h3>
 *
 * <p>两种 runtime 的工具在<b>不同的地方</b>跑:</p>
 *
 * <ul>
 *   <li><b>本机直连</b>({@link com.family.finance.service.ask.runtime.LocalToolLoopRuntime}) ——
 *       工具在同一个循环里执行,引用直接进
 *       {@code sink.toolDone(..., citable)},一路传到落库。</li>
 *   <li><b>百炼托管</b>({@link com.family.finance.service.ask.runtime.ManagedAgentRuntime}) ——
 *       编排在阿里云那边,工具是百炼<b>回头发 HTTP 请求</b>到
 *       {@link com.family.finance.web.ask.McpEndpoint} 执行的。那是<b>另一个请求、另一个线程</b>,
 *       和正在跑的那轮问答之间没有任何共享对象。</li>
 * </ul>
 *
 * <p>于是托管模式下 {@code sink.toolDone} 只能传 {@code Map.of()} ——
 * <b>而这正是线上那个 bug 的全部原因</b>:模型把 {@code {{cite:nw}}} 这类标记老老实实写进了正文,
 * 库里 {@code ask_citation} 却一行都没有,渲染时无可替换,于是用户看到的是
 * 「全家现在的净资产是这个数:」后面<b>什么都没有</b>。整段回答只剩叙述、一个数字都没有,
 * 而这个功能的全部价值就是那些数字。</p>
 *
 * <h3>为什么按 familyId 寄存,而不是 conversationId</h3>
 *
 * <p>MCP 请求携带的是<b>接入口令</b>,口令是家庭级的 —— 百炼不知道、也不需要知道
 * 我们这边的会话 id。所以能拿到的最细粒度就是家庭。</p>
 *
 * <p>这不会串味,原因是落库那一步<b>还有一道过滤</b>:只有正文真的引用到的 key 才会写进
 * {@code ask_citation}(见 {@code AskConversationService.Collector.referenced})。
 * 同家庭并发问两句这种极端情况下,最坏结果是某个没被引用的引用被多带了一程,然后被过滤掉。</p>
 *
 * <p>每轮开始时 {@link #clear(long)} 一次,避免上一轮残留的引用被下一轮的正文误命中
 * —— 语义化的 key(nw / ta / r0_0)在不同轮次里会重复出现,这一步不能省。</p>
 */
@Slf4j
@Component
public class AskCiteBuffer {

    /** familyId → 本轮到目前为止,工具产出过的引用(key → Cite) */
    private final ConcurrentHashMap<Long, Map<String, AskToolResult.Cite>> pending = new ConcurrentHashMap<>();

    /** 一轮开始 —— 丢掉上一轮的残留 */
    public void clear(long familyId) {
        pending.remove(familyId);
    }

    /** MCP 工具刚跑完 —— 把它产出的引用寄存起来 */
    public void put(long familyId, Iterable<AskToolResult.Cite> cites) {
        if (cites == null) return;
        Map<String, AskToolResult.Cite> m =
                pending.computeIfAbsent(familyId, k -> new ConcurrentHashMap<>());
        for (AskToolResult.Cite c : cites) {
            if (c != null && c.key() != null && !c.key().isBlank()) m.put(c.key(), c);
        }
    }

    /**
     * 取走这一轮攒下的全部引用。
     *
     * <p>取走即清空:一轮问答只落一条 assistant 消息,取完就没有第二个消费者了;
     * 留着反而会被下一轮捡到。</p>
     */
    public Map<String, AskToolResult.Cite> drain(long familyId) {
        Map<String, AskToolResult.Cite> m = pending.remove(familyId);
        return m == null || m.isEmpty() ? Map.of() : new LinkedHashMap<>(m);
    }
}
