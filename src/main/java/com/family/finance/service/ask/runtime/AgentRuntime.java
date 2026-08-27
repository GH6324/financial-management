package com.family.finance.service.ask.runtime;

import com.family.finance.domain.ask.AskScope;

import java.util.List;

/**
 * v1.19 · 「谁来跑这个 agent」。
 *
 * <p><b>这是全项目唯一允许出现供应商字样的地方</b>(护栏 {@code v119-ASK-VENDOR-ISOLATED})。
 * 业务层只认识 {@link AskTurn} 和 {@link AskSink}。</p>
 *
 * <p>抽象本来是投机性的 —— 第一版只打算有一个实现。留下它的理由后来变成了现实需要:
 * Managed Agent 跑在百炼那边,要<b>回调</b>我们的 {@code /mcp},因此实例必须公网可达 + HTTPS;
 * 而自托管用户里相当一部分在 NAT 后面、没有域名。于是有了第二个实现
 * ({@link LocalToolLoopRuntime}):模型出网、工具在本进程里跑,<b>不需要任何入网</b>。
 * 两者对上层完全等价。</p>
 */
public interface AgentRuntime {

    /** 稳定标识,存进配置 */
    String code();

    /** 给用户看的名字 */
    String label();

    /** 现在能不能用(凭据齐不齐、前置条件满不满足) */
    boolean available(long familyId);

    /** 不可用时的人话原因 —— 直接展示给用户,所以不许写技术黑话 */
    String unavailableReason(long familyId);

    /**
     * 跑一轮。<b>同步阻塞</b>,在调用方给的线程里执行;产出全部经 {@code sink} 出去。
     *
     * <p>实现不许抛异常:任何失败都走 {@link AskSink#failed} —— 这个方法跑在 SSE 线程上,
     * 抛出去只会变成一个断掉的连接和一段没人看得懂的栈。</p>
     */
    void run(AskTurn turn, AskSink sink);

    /** 一轮提问的全部输入 */
    record AskTurn(
            long familyId,
            long conversationId,
            /** 云端会话 id;首轮为 null,实现可通过 {@code onProviderRef} 回填 */
            String providerRef,
            String systemPrompt,
            /** 历史,按时间正序,不含本轮提问 */
            List<Msg> history,
            String question,
            /** 产品内对话一律全量范围 —— 数据本来就是用户自己的,这里没有第三方 */
            AskScope scope,
            java.util.function.Consumer<String> onProviderRef
    ) {}

    /** {@code role} 取 {@code user} / {@code assistant} */
    record Msg(String role, String content) {}
}
