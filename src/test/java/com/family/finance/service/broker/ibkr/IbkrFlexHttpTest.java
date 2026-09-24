package com.family.finance.service.broker.ibkr;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IBKR 两步取数 · v1.26。用本机 HTTP 桩回放 IBKR 的应答,不打真实接口。
 *
 * <p>守:每个请求都带 User-Agent(不带是 403,beta 实测)· 1019 会等了再取、次数有上限(不撞每分钟 10 次)·
 * 口令无效时不去取报表 · 基址不能被改到别的域名(口令会被发出去)。</p>
 */
class IbkrFlexHttpTest {

    HttpServer server;
    String base;
    final List<String> userAgents = new CopyOnWriteArrayList<>();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final AtomicInteger getCalls = new AtomicInteger();
    volatile String sendBody;
    volatile int inProgressTimes;
    volatile String reportBody = "<FlexQueryResponse><FlexStatements count=\"0\"/></FlexQueryResponse>";

    @BeforeEach
    void up() throws Exception {
        IbkrFlexHttp.resetThrottle();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fws/SendRequest", ex -> {
            userAgents.add(String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")));
            paths.add("send");
            byte[] b = sendBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/fws/GetStatement", ex -> {
            userAgents.add(String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")));
            paths.add("get");
            int n = getCalls.incrementAndGet();
            String body = n <= inProgressTimes
                    ? "<FlexStatementResponse><Status>Warn</Status><ErrorCode>1019</ErrorCode><ErrorMessage>Statement generation in progress. Please try again shortly.</ErrorMessage></FlexStatementResponse>"
                    : reportBody;
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/fws";
        sendBody = "<FlexStatementResponse><Status>Success</Status><ReferenceCode>987654</ReferenceCode></FlexStatementResponse>";
    }

    @AfterEach
    void down() { server.stop(0); }

    IbkrFlexHttp http() {
        return new IbkrFlexHttp(HttpClient.newHttpClient(), base, "FamilyFinance/test Java/21", ms -> { }, 0, 0);
    }

    @Test
    void 每个请求都带UserAgent() {
        http().fetchStatementXml("tok", "123");
        assertThat(userAgents).isNotEmpty().allMatch(ua -> ua.startsWith("FamilyFinance/"));
    }

    @Test
    void 报表还在生成_等了再取_最后拿到报表() {
        inProgressTimes = 2;
        String xml = http().fetchStatementXml("tok", "123");
        assertThat(xml).contains("FlexQueryResponse");
        assertThat(paths).containsExactly("send", "get", "get", "get");
    }

    @Test
    void 一直在生成_有上限_总请求数低于每分钟10次() {
        inProgressTimes = 99;
        assertThatThrownBy(() -> http().fetchStatementXml("tok", "123"))
                .isInstanceOf(IbkrFlexException.class)
                .satisfies(e -> assertThat(((IbkrFlexException) e).code()).isEqualTo("1019"));
        assertThat(paths.size()).isLessThan(10);
    }

    @Test
    void 口令无效_不去取报表() {
        sendBody = IbkrFlexParserTest.REAL_1015;
        assertThatThrownBy(() -> http().fetchStatementXml("tok", "123"))
                .isInstanceOf(IbkrFlexException.class)
                .hasMessageContaining("Token is invalid.");
        assertThat(paths).containsExactly("send");
    }

    @Test
    void 基址只认IBKR自己的HTTPS域名或本机回环_口令不许被发到别处() {
        assertThat(IbkrFlexHttp.normalizeBase("https://evil.example.com/fws")).isEqualTo(IbkrFlexHttp.DEFAULT_BASE);
        assertThat(IbkrFlexHttp.normalizeBase("http://ndcdyn.interactivebrokers.com/x")).isEqualTo(IbkrFlexHttp.DEFAULT_BASE);   // 不是 https
        assertThat(IbkrFlexHttp.normalizeBase("https://interactivebrokers.com.evil.io/x")).isEqualTo(IbkrFlexHttp.DEFAULT_BASE);
        assertThat(IbkrFlexHttp.normalizeBase("https://gdcdyn.interactivebrokers.com/AccountManagement/FlexWebService/"))
                .isEqualTo("https://gdcdyn.interactivebrokers.com/AccountManagement/FlexWebService");
        assertThat(IbkrFlexHttp.normalizeBase("http://127.0.0.1:18080/fws")).isEqualTo("http://127.0.0.1:18080/fws");
        assertThat(IbkrFlexHttp.normalizeBase("")).isEqualTo(IbkrFlexHttp.DEFAULT_BASE);
    }

    @Test
    void 连不上_给人话并且带原话() {
        IbkrFlexHttp dead = new IbkrFlexHttp(HttpClient.newHttpClient(), "http://127.0.0.1:1/fws", "UA", ms -> { }, 0, 0);
        assertThatThrownBy(() -> dead.fetchStatementXml("tok", "123"))
                .isInstanceOf(IbkrFlexException.class)
                .hasMessageContaining("连不上 IBKR")
                .satisfies(e -> assertThat(((IbkrFlexException) e).upstream()).isNotBlank());
    }
}
