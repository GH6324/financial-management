package com.family.finance.service.broker.ibkr;

import java.util.Map;
import java.util.Set;

/**
 * IBKR Flex Web Service 错误码 → 人话 · v1.26。
 *
 * <p>码表来自 IBKR 官方「Flex Web Service Version 3」(ibkrguides.com/clientportal/flex3.htm,2025-10-03 版)。
 * 用户最常撞上的是 1012(口令过期 —— 口令最长一年有效,<b>到期后同步会停</b>)与 1015(口令不对)。</p>
 */
public final class IbkrErrors {
    private IbkrErrors() {}

    /** 过一会儿再试就好的:报表还在生成、限流、IBKR 繁忙 / 数据未就绪 */
    static final Set<String> RETRYABLE = Set.of(
            "1001", "1004", "1005", "1006", "1007", "1008", "1009", "1018", "1019", "1021");

    private static final Map<String, String> HUMAN = Map.ofEntries(
            Map.entry("1012", "报表口令已过期 —— 去 IBKR 后台重新生成(有效期选 1 年),再粘到这里"),
            Map.entry("1015", "报表口令不对 —— 在 IBKR 后台重新复制一次(注意:重新生成会让旧口令立刻失效)"),
            Map.entry("1014", "找不到这个查询号 —— 确认是 Activity Flex Query 那一栏的数字"),
            Map.entry("1013", "IBKR 拒绝了本服务器的地址 —— 口令绑定的 IP 和这台服务器不一致"),
            Map.entry("1019", "IBKR 还在生成报表,稍后会自动再取一次"),
            Map.entry("1018", "IBKR 限流了(每分钟最多 10 次),稍后再试"),
            Map.entry("1003", "这份报表取不到 —— 确认它在 IBKR 后台能正常运行"),
            Map.entry("1010", "这是旧版 Flex 报表 —— 在 IBKR 后台新建一份 Activity Flex Query"),
            Map.entry("1011", "IBKR 账户未激活"),
            Map.entry("1016", "IBKR 认为这个账户无效"),
            Map.entry("1017", "报表回执已失效,下次同步会重新请求"),
            Map.entry("1020", "IBKR 无法校验这次请求 —— 检查口令和查询号有没有多粘空格"));

    /** 错误码 → 人话;不认识的码也要给一句,并且带上码(原话由 {@link IbkrFlexException} 附上)。 */
    public static String human(String code) {
        if (code == null) return "IBKR 返回了错误";
        String h = HUMAN.get(code.trim());
        if (h != null) return h;
        if (RETRYABLE.contains(code.trim())) return "IBKR 暂时生成不了报表,稍后再试";
        return "IBKR 返回了错误(代码 " + code.trim() + ")";
    }

    public static boolean retryable(String code) {
        return code != null && RETRYABLE.contains(code.trim());
    }

    /** 由 IBKR 的失败信封构造异常 */
    public static IbkrFlexException fromEnvelope(String code, String message) {
        return new IbkrFlexException(code, message, human(code), retryable(code));
    }

    public static IbkrFlexException network(Throwable cause) {
        String raw = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        return new IbkrFlexException(null, raw,
                "连不上 IBKR(网络不通或超时)—— 如果服务器在大陆,可能需要能访问海外的网络", true, cause);
    }

    public static IbkrFlexException notConfigured() {
        return new IbkrFlexException(null, null, "IBKR 报表口令 / 查询号未配置 —— 到「管理 → 券商同步」填好", false);
    }
}
