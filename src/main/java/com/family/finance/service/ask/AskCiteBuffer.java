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

    /**
     * 每个家庭这一轮里「第几次工具调用」。
     *
     * <p>存在的理由是一个实测出来的错:工具返回的引用 key 是<b>工具内部的行号</b>
     * (`r0_0` = 第 0 行第 0 列、`nw` = 净资产)。一轮问答里百炼可能调五六次工具,
     * 于是<b>多次调用的 key 必然重复</b> —— 「按资产类型」的 `r0_0` 和
     * 「按平台」的 `r0_0` 是两个完全不同的数。</p>
     *
     * <p>2026-09-22 在 beta 上真实撞到:正文写「最大的一块是<b>债券理财</b>」,
     * 挂上去的引用卡却是「<b>支付宝·蚂蚁财富</b> 48.34%」—— 因为后一次调用的
     * `r0_0` 把前一次的覆盖掉了。<b>这比没有数字更糟</b>:它给了一个看起来合理、
     * 实际错位的数,而用户没有任何办法发现。</p>
     *
     * <p>本机直连那条路没这个问题,因为它在循环里重编成 c1 / c2 / c3…;
     * 托管这条路的 key 直接来自工具,所以要在<b>发给百炼之前</b>就唯一化 ——
     * 模型只有拿到互不相同的 key,才可能正确地引用它们。</p>
     */
    private final ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger> seq =
            new ConcurrentHashMap<>();

    /** 这一轮的下一个工具调用序号(从 1 开始)*/
    public int nextCallSeq(long familyId) {
        return seq.computeIfAbsent(familyId, k -> new java.util.concurrent.atomic.AtomicInteger())
                  .incrementAndGet();
    }

    /** 把工具内的 key 变成这一轮里全局唯一的 key */
    public static String scopedKey(int callSeq, String rawKey) {
        return "t" + callSeq + "_" + rawKey;
    }

    /** 一轮开始 —— 丢掉上一轮的残留 */
    public void clear(long familyId) {
        pending.remove(familyId);
        seq.remove(familyId);
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
    /** 只看不取:流式推送用。落库那一步仍然用 {@link #drain} 取走 */
    public Map<String, AskToolResult.Cite> snapshot(long familyId) {
        Map<String, AskToolResult.Cite> m = pending.get(familyId);
        return m == null || m.isEmpty() ? Map.of() : new LinkedHashMap<>(m);
    }

    public Map<String, AskToolResult.Cite> drain(long familyId) {
        Map<String, AskToolResult.Cite> m = pending.remove(familyId);
        return m == null || m.isEmpty() ? Map.of() : new LinkedHashMap<>(m);
    }
}
