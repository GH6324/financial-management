package com.family.finance.service.ask;

import com.family.finance.domain.ask.AskScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * v1.19 · 工具分发:校验 → 执行 → 超时 → 包结果。
 *
 * <h3>三条刻意的行为</h3>
 * <ul>
 *   <li><b>参数非法不直接失败</b>,把「你可以用的取值是这些」回给模型让它改 ——
 *       允许试错好过一句「参数错误」把对话卡死。这是自由度设计的一部分。</li>
 *   <li><b>单个工具超时不拖垮整轮</b>:该项标失败,其余照常;
 *       答案里明说「这一项没查到」,<b>不编</b>。</li>
 *   <li><b>任何异常都不外抛</b> —— 这是给外部 agent 用的入口,
 *       一个未捕获异常会变成 500 并把栈打进日志(而栈里可能带数据)。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AskToolDispatcher {

    /** 单个工具的执行上限;查的都是本地已算好的口径,超过这个时间说明有别的问题 */
    public static final Duration TOOL_TIMEOUT = Duration.ofSeconds(10);

    private final AskToolRegistry registry;

    /** 工具执行用独立线程池:不占 SSE 的池,也不占主池 */
    private final ExecutorService pool = Executors.newFixedThreadPool(
            4, r -> {
                Thread t = new Thread(r, "ask-tool");
                t.setDaemon(true);
                return t;
            });

    /**
     * 执行一个工具。
     *
     * @param granted 调用方实际持有的数据范围;不足时直接拒绝,不进业务
     */
    public AskToolResult call(long familyId, String toolName, Map<String, Object> args, AskScope granted) {
        AskTool tool = registry.find(toolName).orElse(null);
        if (tool == null) {
            return AskToolResult.failed(toolName,
                    "没有这个工具。可用的是:" + String.join("、",
                            registry.all().stream().map(AskTool::name).toList()));
        }
        if (granted == null || !granted.covers(tool.requiredScope())) {
            return AskToolResult.failed(toolName,
                    "这个工具需要「" + tool.requiredScope().getLabel() + "」范围的接入凭据,当前凭据不够");
        }

        long t0 = System.currentTimeMillis();
        try {
            CompletableFuture<AskToolResult> f = CompletableFuture.supplyAsync(
                    () -> tool.execute(familyId, args == null ? Map.of() : args), pool);
            AskToolResult r = f.get(TOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            log.debug("ask tool {} ok in {}ms", toolName, System.currentTimeMillis() - t0);
            return r;
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("ask tool {} 超时({}s)", toolName, TOOL_TIMEOUT.toSeconds());
            return AskToolResult.failed(toolName,
                    "这一项查询超时了(超过 " + TOOL_TIMEOUT.toSeconds() + " 秒)。"
                  + "请如实告诉用户这一项没查到,不要凭印象补一个数。");
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof AskTool.AskParamException pe) {
                // 把可用取值回给模型 —— 让它自己改,而不是把对话卡死
                return new AskToolResult(toolName, Map.of(),
                        Map.of("allowed", pe.getAllowed()), java.util.List.of(),
                        false, "参数不对:" + pe.getMessage());
            }
            // 只记类名与消息,不打栈 —— 栈里可能带数据,而这是对外入口
            log.warn("ask tool {} 失败:{}", toolName, cause.toString());
            return AskToolResult.failed(toolName,
                    "这一项查询失败了。请如实告诉用户这一项没查到,不要编一个数。");
        }
    }
}
