package com.family.finance.web.ask;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.ask.AskConversation;
import com.family.finance.service.NavService;
import com.family.finance.service.ask.AskCitationRenderer;
import com.family.finance.service.ask.AskConversationService;
import com.family.finance.service.ask.AskToolResult;
import com.family.finance.service.ask.runtime.AskSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v1.19 · 产品内「超级 Agent」。
 *
 * <h3>两种壳,一个片段</h3>
 * <p>PC 上是右侧抽屉(不打断当前页),手机上是整页。两者<b>共用同一个 {@code _stream} 片段</b>
 * (护栏 {@code v119-ASK-TWO-SHELLS})—— 维护者的原话:「就是一个 sse 的对话流,
 * 那有必要区分移动端或者 PC 端嘛?」确实没必要,差别只在外面那层容器。</p>
 *
 * <h3>为什么用独立线程池</h3>
 * <p>一轮回答要占住一个线程一到两分钟(等模型 + 跑工具)。放主池里,几个人同时问就能把
 * Tomcat 的线程吃光,整站跟着卡住 —— 这不是「对话变慢」,是「记账页也打不开」。
 * 池满了<b>直接拒绝并说人话</b>,不排队:排队只会让用户对着转圈等更久,最后还是失败。</p>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AskController {

    /** 同时进行的对话上限。3.5G 小内存的自托管机器,再多就该换机器了 */
    private static final int MAX_CONCURRENT = 4;
    /** SSE 连接上限时间:比 runtime 的读超时略长,让上游的人话错误有机会送到 */
    private static final long SSE_TIMEOUT_MS = 200_000L;

    private final AskConversationService conversations;
    private final AskCitationRenderer renderer;
    private final NavService navService;
    private final ObjectMapper json = new ObjectMapper();

    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * SSE 心跳 —— 每 15 秒往连接里发一个注释行,让反向代理看不到「空闲」。
     *
     * <p>`proxy_read_timeout` 算的是<b>两次读之间的间隔</b>,不是总时长。
     * 所以只要这条连接上一直有字节流过,90s / 60s 那些默认值就都掐不到它 ——
     * 这比让用户去改 nginx 配置可靠得多(我们控制不了别人的反代)。</p>
     *
     * <p>发的是 SSE 注释(`: hb`),规范规定客户端<b>忽略</b>它,
     * 所以前端一行代码都不用改。</p>
     */
    private final java.util.concurrent.ScheduledExecutorService heartbeats =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "ask-sse-hb");
                t.setDaemon(true);
                return t;
            });
    private static final long HEARTBEAT_SECONDS = 15;
    private final ExecutorService pool = Executors.newFixedThreadPool(
            MAX_CONCURRENT, r -> {
                Thread t = new Thread(r, "ask-sse");
                t.setDaemon(true);
                return t;
            });

    /** 预置问题 —— 非技术家庭成员点一下就能开始,不用想「该问什么」 */
    public static final List<String> PRESETS = List.of(
            "我的钱都放在哪些平台?",
            "我的资产里有多少是自己在盯的,有多少交给产品了?",
            "这个月净资产变化,是我自己存下来的还是投资赚的?",
            "我的应急金够花几个月?");

    // ──────────────────────── 页面 ────────────────────────

    /** 手机:整页 */
    @GetMapping("/ask")
    public String page(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam(required = false) Long conv, Model model) {
        fill(me, conv, model);
        return "ask/index";
    }

    /** PC:抽屉里的内容,HTMX 局部加载 */
    @GetMapping("/ask/panel")
    public String panel(@AuthenticationPrincipal MemberPrincipal me,
                        @RequestParam(required = false) Long conv, Model model) {
        fill(me, conv, model);
        return "ask/fragments/_panel :: panel";
    }

    private void fill(MemberPrincipal me, Long conv, Model model) {
        long fam = me.getFamilyId();
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("presets", PRESETS);
        model.addAttribute("recent", conversations.recent(fam, 6));   // 6 条够「回到刚才那段」了;更早的属于历史列表,不该占空态半屏
        model.addAttribute("blocked", conversations.blockedReason(fam));
        model.addAttribute("runtimeLabel", conversations.runtime().label());
        model.addAttribute("ctxLabel", conversations.contextLabel(fam));
        model.addAttribute("greeting", com.family.finance.service.ask.AskGreetings.random());
        if (conv != null) {
            AskConversation c = conversations.find(fam, conv);
            if (c != null) {
                model.addAttribute("conv", c);
                model.addAttribute("messages", conversations.history(me.getFamilyId(), conv));
                model.addAttribute("renderer", renderer);
                // v1.24 · 家庭隔离:引用卡要查账期标签,SQL 现在按 family_id 过滤 —— 模板得把它带进去
                model.addAttribute("askFamilyId", me.getFamilyId());
            }
        }
    }

    @PostMapping("/ask/new")
    @ResponseBody
    public Map<String, Object> create(@AuthenticationPrincipal MemberPrincipal me,
                                      @RequestParam(required = false) Long periodId,
                                      @RequestParam(required = false) String currency) {
        AskConversation c = conversations.start(me.getFamilyId(), periodId, currency);
        return Map.of("id", c.getId());
    }

    @PostMapping("/ask/{id}/archive")
    @ResponseBody
    public Map<String, Object> archive(@AuthenticationPrincipal MemberPrincipal me,
                                       @PathVariable long id) {
        return Map.of("ok", conversations.archive(me.getFamilyId(), id));
    }

    // ──────────────────────── SSE ────────────────────────

    /**
     * 提问并流式返回。
     *
     * <p>用 GET 是因为 {@code EventSource} 只支持 GET —— 提问文本走 query。
     * 这里没有写操作意义上的副作用暴露给 CSRF:它只往<b>自己家庭的</b>会话里加消息,
     * 而 session 校验在前面。真正的写(账目)在别的地方,那些仍然是 POST + CSRF。</p>
     */
    @GetMapping(value = "/ask/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal MemberPrincipal me,
                             @PathVariable long id,
                             @RequestParam(required = false, defaultValue = "") String q,
                             @RequestParam(required = false, defaultValue = "new") String mode,
                             jakarta.servlet.http.HttpServletResponse response) {
        /* 【必须告诉反代别缓冲】—— 2026-09-22 在 beta 上实测出来的:
           从浏览器问一个稍微复杂点的问题(「资产多少 / 分布如何 / 有什么建议」),
           150 秒一个字都没回来,控制台一个 504。而直连应用是好的 ——
           问题全在反向代理那一层:

             ① nginx 默认【缓冲】上游响应,SSE 于是不再是流式:
                用户盯着空白等,而不是看着字一个个出来;
             ② nginx `proxy_read_timeout` 默认 60s(我们 beta 配的 90s),
                而这里的 SseEmitter 给的是 200s —— 两边不一致时,
                短的那个说了算,用户看到的是 504。

           `X-Accel-Buffering: no` 是 nginx 认的标准头,发了它就【不需要用户改配置】。
           自建用户的反代五花八门,我们不能假设他们配对了 —— 能在响应头里解决的,
           就不要写进部署文档里指望别人照做。 */
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        if (inFlight.get() >= MAX_CONCURRENT) {
            send(emitter, "failed", Map.of("message",
                    "同时进行的对话太多了,等一会儿再问。"));
            emitter.complete();
            return emitter;
        }

        long fam = me.getFamilyId();
        AskConversationService.Mode m = switch (mode) {
            case "regen" -> AskConversationService.Mode.REGENERATE;
            case "continue" -> AskConversationService.Mode.CONTINUE;
            default -> AskConversationService.Mode.NEW;
        };
        // 新一轮开始 = 清掉上一轮可能残留的停止位,否则这一轮一上来就被判成已叫停
        AtomicBoolean abort = new AtomicBoolean(false);
        aborts.put(id, abort);

        inFlight.incrementAndGet();
        try {
            // 心跳先起:一次 AI 问答里,「模型在想」与「工具在查」都可能安静几十秒,
            // 那正是反代判定超时的时间窗。
            java.util.concurrent.ScheduledFuture<?> hb = heartbeats.scheduleAtFixedRate(() -> {
                try { emitter.send(SseEmitter.event().comment("hb")); }
                catch (Exception ignored) { /* 连接已断:下面的 finally 会取消这个任务 */ }
            }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

            pool.execute(() -> {
                try {
                    conversations.ask(fam, id, q, m, new EmitterSink(emitter, abort));
                } catch (Exception e) {
                    log.warn("超级 Agent SSE 异常:{}", e.toString());
                    send(emitter, "failed", Map.of("message", "出了点问题,重试一下。"));
                    emitter.complete();
                } finally {
                    hb.cancel(true);
                    inFlight.decrementAndGet();
                    aborts.remove(id, abort);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            aborts.remove(id, abort);
            send(emitter, "failed", Map.of("message", "同时进行的对话太多了,等一会儿再问。"));
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 停止位:会话 id → 这一轮要不要停。
     *
     * <p>单家庭部署,一段会话同时只会有一轮在跑,所以按会话 id 键就够。
     * 值用 {@link AtomicBoolean} 而不是 Set:停止端点要能<b>认得出</b>自己停的是哪一轮,
     * 否则「上一轮刚结束、新一轮刚开始」这个窗口里的停止请求会把新一轮误杀。</p>
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, AtomicBoolean> aborts =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 用户按了停止。
     *
     * <p>只置位、不等它真的停 —— 停止是<b>协作式</b>的:runtime 在读流的循环里逐行看这个位,
     * 看到就 break、把已有的半截落库。强杀线程会让落库跑不完,那才是真的丢东西。</p>
     */
    @PostMapping("/ask/{id}/stop")
    @ResponseBody
    public Map<String, Object> stop(@AuthenticationPrincipal MemberPrincipal me,
                                    @PathVariable long id) {
        AtomicBoolean a = aborts.get(id);
        if (a != null) a.set(true);
        return Map.of("ok", a != null);
    }

    /** 把 sink 的回调转成 SSE 事件 */
    private final class EmitterSink implements AskSink {
        private final SseEmitter emitter;
        private final AtomicBoolean abort;
        /** SSE 已经断了(用户关了页面)—— 再往下跑就是在为没人看的回答花钱 */
        private volatile boolean gone = false;

        EmitterSink(SseEmitter emitter, AtomicBoolean abort) {
            this.emitter = emitter;
            this.abort = abort;
        }

        @Override public boolean cancelled() { return abort.get() || gone; }

        @Override public void status(String t) { send(emitter, "status", Map.of("text", t)); }

        @Override
        public void toolStart(String tool, String label, String args) {
            send(emitter, "tool", Map.of("tool", tool, "label", label, "phase", "start",
                    "args", args == null ? "" : args));
        }

        @Override
        public void toolDone(String tool, String label, int ms, boolean ok,
                             String summary, Map<String, AskToolResult.Cite> citable) {
            send(emitter, "tool", Map.of("tool", tool, "label", label, "phase", "done",
                    "ms", ms, "ok", ok, "summary", summary == null ? "" : summary));
            cites(citable);
        }

        @Override
        public void cites(Map<String, AskToolResult.Cite> citable) {
            citable.forEach((k, c) -> send(emitter, "cite", Map.of(
                    "key", k, "value", c.valueText(), "label", c.label(),
                    "href", c.targetHref() == null ? "" : c.targetHref(),
                    "inProgress", c.inProgress(),
                    "explain", c.metricKey() == null ? "" : c.metricKey())));
        }

        @Override
        public void textDelta(String d) {
            if (!send(emitter, "delta", Map.of("t", d))) gone = true;
        }

        @Override
        public void rollback(String narration) {
            send(emitter, "rollback", Map.of("t", narration));
        }

        @Override
        public void done() {
            send(emitter, "done", Map.of());
            emitter.complete();
        }

        @Override
        public void stopped() {
            send(emitter, "stopped", Map.of());
            emitter.complete();
        }

        @Override
        public void failed(String msg) {
            send(emitter, "failed", Map.of("message", msg));
            emitter.complete();
        }
    }

    /** @return 送出去了没有;送不出去说明对端已经走了 */
    private boolean send(SseEmitter emitter, String event, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(
                    json.writeValueAsString(data), MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            // 用户关了页面 —— 正常情况,不是错误。半截答案仍然会落库,
            // 但没必要继续往上游要 token:那是在为没人看的回答花钱。
            log.debug("SSE 已断开:{}", e.toString());
            return false;
        } catch (Exception e) {
            log.warn("SSE 发送失败:{}", e.toString());
            return false;
        }
    }
}
