package com.family.finance.service.broker.ibkr;

/**
 * IBKR Flex Web Service 的失败 · v1.26。
 *
 * <p><b>三样东西分开存</b>:IBKR 的错误码、IBKR 的原话、给用户看的人话。{@link #getMessage()} 把人话和原话拼在一起 ——
 * 它会原样出现在管理页的测试结果和券商卡片上。本项目犯过三次「上游原话被一句兜底文案盖住」
 * (v1.19.4 / v1.19.11 / v1.19.14),所以这里<b>不许只给人话</b>。</p>
 *
 * <p>{@code retryable} 区分「过一会儿就好」(报表还在生成、限流、IBKR 繁忙)和「用户得去做点什么」(口令过期 / 不对)。</p>
 */
public class IbkrFlexException extends RuntimeException {

    /** IBKR 的 ErrorCode;网络 / HTTP / 报表形状问题为 null */
    private final String code;
    /** IBKR 的原话(或底层异常的原话) */
    private final String upstream;
    /** 给用户看的一句话 */
    private final String human;
    private final boolean retryable;

    public IbkrFlexException(String code, String upstream, String human, boolean retryable) {
        super(compose(code, upstream, human));
        this.code = code;
        this.upstream = upstream;
        this.human = human;
        this.retryable = retryable;
    }

    public IbkrFlexException(String code, String upstream, String human, boolean retryable, Throwable cause) {
        super(compose(code, upstream, human), cause);
        this.code = code;
        this.upstream = upstream;
        this.human = human;
        this.retryable = retryable;
    }

    static String compose(String code, String upstream, String human) {
        if (upstream == null || upstream.isBlank()) return human;
        return human + "(IBKR 原话:" + (code == null ? "" : code + " · ") + upstream.trim() + ")";
    }

    public String code() { return code; }
    public String upstream() { return upstream; }
    public String human() { return human; }
    public boolean retryable() { return retryable; }
}
