package com.family.finance.service.ask.runtime;

/**
 * v1.19 · 一轮回答往外吐的东西。
 *
 * <p>runtime 只管「产生」,不管「怎么送到浏览器」——{@link com.family.finance.service.ask.AskConversationService}
 * 拿到之后既要转成 SSE 事件,也要攒起来落库。把这两件事塞进 runtime 会让每加一个供应商
 * 就重写一遍落库逻辑。</p>
 *
 * <p><b>顺序保证</b>:{@code status/tool*} 可以和 {@code textDelta} 交错;
 * {@code done} 或 {@code failed} 恰好发生一次,且是最后一个。</p>
 */
public interface AskSink {

    /** 阶段提示:「正在查资产分布」这类,给用户看的人话 */
    void status(String text);

    /** 某个工具开始跑 */
    void toolStart(String toolName, String label);

    /**
     * 某个工具跑完。
     *
     * @param citable 本次结果里可被引用的数字:全局 cite key → 展示用文案。
     *                正文里出现 {@code {{cite:key}}} 时,渲染取的就是这里的值。
     */
    void toolDone(String toolName, String label, int durationMs, boolean ok,
                  java.util.Map<String, com.family.finance.service.ask.AskToolResult.Cite> citable);

    /** 正文增量 —— 可能含 {{cite:xx}} 标记,原样传,不要在 runtime 里替换 */
    void textDelta(String delta);

    /**
     * 撤回刚才那段文字:它不是答案,是「我来查一下…」这类<b>调工具前的旁白</b>。
     *
     * <p>为什么不能等一等再决定发不发:一轮到底会不会调工具,要等这一轮流完才知道,
     * 而等着就没有流式了 —— 用户对着空白等十几秒。所以先照发,发现是旁白再撤回。</p>
     *
     * <p>撤回的后果是双份的:界面上把它从正文降级成一行灰字,<b>库里也不留</b>。
     * 不撤的话,存下来的答案会是「我来查一下平台分布。我来查一下资产情况。你的钱主要在…」——
     * 三个月后重看,前两句只会让人困惑。</p>
     */
    void rollback(String narration);

    /** 正常收尾 */
    void done();

    /**
     * 失败收尾。
     *
     * <p><b>已经吐出去的正文不回收</b> —— 半截答案 + 一句「后面断了」比整段消失有用,
     * 用户至少知道进行到哪一步。</p>
     */
    void failed(String humanMessage);
}
