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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v1.19 · 产品内「问一问」。
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
        model.addAttribute("recent", conversations.recent(fam, 12));
        model.addAttribute("blocked", conversations.blockedReason(fam));
        model.addAttribute("runtimeLabel", conversations.runtime().label());
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
                             @RequestParam String q) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        if (inFlight.get() >= MAX_CONCURRENT) {
            send(emitter, "failed", Map.of("message",
                    "同时进行的对话太多了,等一会儿再问。"));
            emitter.complete();
            return emitter;
        }

        long fam = me.getFamilyId();
        inFlight.incrementAndGet();
        try {
            pool.execute(() -> {
                try {
                    conversations.ask(fam, id, q, new EmitterSink(emitter));
                } catch (Exception e) {
                    log.warn("问一问 SSE 异常:{}", e.toString());
                    send(emitter, "failed", Map.of("message", "出了点问题,重试一下。"));
                    emitter.complete();
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            send(emitter, "failed", Map.of("message", "同时进行的对话太多了,等一会儿再问。"));
            emitter.complete();
        }
        return emitter;
    }

    /** 把 sink 的回调转成 SSE 事件 */
    private final class EmitterSink implements AskSink {
        private final SseEmitter emitter;
        EmitterSink(SseEmitter emitter) { this.emitter = emitter; }

        @Override public void status(String t) { send(emitter, "status", Map.of("text", t)); }

        @Override
        public void toolStart(String tool, String label) {
            send(emitter, "tool", Map.of("tool", tool, "label", label, "phase", "start"));
        }

        @Override
        public void toolDone(String tool, String label, int ms, boolean ok,
                             Map<String, AskToolResult.Cite> citable) {
            send(emitter, "tool", Map.of("tool", tool, "label", label, "phase", "done",
                    "ms", ms, "ok", ok));
            citable.forEach((k, c) -> send(emitter, "cite", Map.of(
                    "key", k, "value", c.valueText(), "label", c.label(),
                    "href", c.targetHref() == null ? "" : c.targetHref(),
                    "inProgress", c.inProgress(),
                    "explain", c.metricKey() == null ? "" : c.metricKey())));
        }

        @Override public void textDelta(String d) { send(emitter, "delta", Map.of("t", d)); }

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
        public void failed(String msg) {
            send(emitter, "failed", Map.of("message", msg));
            emitter.complete();
        }
    }

    private void send(SseEmitter emitter, String event, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(
                    json.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 用户关了页面 —— 正常情况,不是错误。继续跑完当前轮并落库,别把半截答案丢了
            log.debug("SSE 已断开:{}", e.toString());
        } catch (Exception e) {
            log.warn("SSE 发送失败:{}", e.toString());
        }
    }
}
