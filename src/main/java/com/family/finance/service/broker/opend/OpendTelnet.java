package com.family.finance.service.broker.opend;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * OpenD 控制口(telnet)会话 + 登录状态机(v1.17)。
 *
 * <p>10.x 起登录是<b>交互式</b>的:进程起来后在控制口上依次要账号、密码,必要时要手机验证码。
 * 实测(2026-08-17)连上控制口发一个换行就会收到:</p>
 * <pre>
 * Futu OpenD版本信息: 10.10.7008(20260811202500), 输入help获取更多信息
 * 请输入密码
 * </pre>
 *
 * <p><b>提示词判定表集中在这里</b>。网关容器里那份是 bash 实现(app 连不到容器内的控制口),
 * 两处必须认同一批关键词 —— 靠 qa-run 护栏钉住,别指望自觉。</p>
 *
 * <p><b>凭据</b>:账号/密码只经过这里的 socket,不落盘、不进日志(日志只记"已发送账号/密码")。</p>
 */
public final class OpendTelnet {

    /** 控制口正在等什么。 */
    public enum Step { WANT_ACCOUNT, WANT_PASSWORD, WANT_SMS, LOGGED_IN, FAILED, UNKNOWN }

    private OpendTelnet() {}

    /**
     * 从控制口输出判断它在等什么(纯函数 · 单测)。
     *
     * <p>顺序要紧:失败关键词先判(「验证码错误」也含「验证码」),再成功,再具体等待项。</p>
     */
    public static Step stepFromPrompt(String out) {
        if (out == null) return Step.UNKNOWN;
        String s = out.toLowerCase(Locale.ROOT);
        if (s.contains("验证码错误") || s.contains("密码错误") || s.contains("账号错误")
                || s.contains("登录失败") || s.contains("login failed")) return Step.FAILED;
        if (s.contains("登录成功") || s.contains("login success") || s.contains("login succeed")
                || s.contains("已登录")) return Step.LOGGED_IN;
        if (s.contains("验证码") || s.contains("verify code") || s.contains("verifycode")) return Step.WANT_SMS;
        if (s.contains("请输入密码") || s.contains("input password") || s.contains("enter password")) return Step.WANT_PASSWORD;
        if (s.contains("请输入账号") || s.contains("请输入帐号") || s.contains("input account")
                || s.contains("enter account")) return Step.WANT_ACCOUNT;
        return Step.UNKNOWN;
    }

    /** 运维命令:让 OpenD 重发一条短信验证码(实测限流 1 分钟 1 次)。 */
    public static final String CMD_REQ_SMS = "req_phone_verify_code";
    /** 运维命令:提交手机验证码。 */
    public static String cmdInputSms(String code) { return "input_phone_verify_code -code=" + code.trim(); }

    /** 打开一个控制口会话(连不上抛 IOException)。 */
    public static Session open(String host, int port, int timeoutMs) throws IOException {
        return new Session(host, port, timeoutMs);
    }

    /** 控制口会话:短命令 + 读回显。调用方负责 close。 */
    public static final class Session implements Closeable {
        private final Socket sock;
        private final InputStream in;
        private final OutputStream out;

        Session(String host, int port, int timeoutMs) throws IOException {
            this.sock = new Socket();
            this.sock.connect(new InetSocketAddress(host, port), timeoutMs);
            this.sock.setSoTimeout(timeoutMs);
            this.in = sock.getInputStream();
            this.out = sock.getOutputStream();
        }

        /** 发一行(自动补 CRLF)。<b>不要在调用处把内容写进日志</b>。 */
        public void sendLine(String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** 读到超时为止,返回这段时间里收到的文本(可能为空)。 */
        public String readFor(long waitMs) {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + waitMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    int n = in.read(buf);
                    if (n < 0) break;
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                } catch (java.net.SocketTimeoutException te) {
                    if (sb.length() > 0) break;      // 已经收到东西且对方停了 → 够了
                } catch (IOException e) {
                    break;
                }
            }
            return sb.toString();
        }

        public boolean isOpen() { return sock.isConnected() && !sock.isClosed(); }

        @Override public void close() {
            try { sock.close(); } catch (IOException ignored) { }
        }
    }
}
