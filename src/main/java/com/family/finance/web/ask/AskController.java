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
                model.addAttribute("messages", conversations.history(conv));
                model.addAttribute("renderer", renderer);
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
                             @RequestParam(required = false, defaultValue = "new") String mode) {
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
            pool.execute(() -> {
                try {
                    conversations.ask(fam, id, q, m, new EmitterSink(emitter, abort));
                } catch (Exception e) {
                    log.warn("超级 Agent SSE 异常:{}", e.toString());
                    send(emitter, "failed", Map.of("message", "出了点问题,重试一下。"));
                    emitter.complete();
                } finally {
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
