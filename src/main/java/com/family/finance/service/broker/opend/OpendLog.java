package com.family.finance.service.broker.opend;

import java.util.Locale;

/**
 * OpenD 日志 → 阶段的纯判定(v1.17 从 FutuOpendManager 提出来)。
 *
 * <p>两种通道都要用它:本机子进程读的是进程 stdout,网关容器读的是 {@code GTWLog_*.log}
 * —— 文案是同一套(OpenD 自己打的),判定逻辑不该有两份。</p>
 */
public final class OpendLog {

    private OpendLog() {}

    /** 从一行 OpenD 日志推断阶段迁移(best-effort · 认不出返回 null,由调用方保持原阶段)。 */
    public static OpendChannel.Phase phaseFromLog(String line) {
        if (line == null) return null;
        String s = line.toLowerCase(Locale.ROOT);
        // 顺序要紧:先判失败(「验证码错误」也含「验证码」),再成功,最后「需要验证码」
        if (s.contains("验证码错误") || s.contains("密码错误") || s.contains("登录失败")
                || s.contains("login failed") || (s.contains("password") && s.contains("error"))) return OpendChannel.Phase.ERROR;
        if (s.contains("登录成功") || s.contains("login success") || s.contains("login succeed")
                || s.contains("已登录") || s.contains("initconnect success")
                || s.contains("拉取用户信息成功") || s.contains("行情权限")) return OpendChannel.Phase.RUNNING;
        // 只认 OpenD 的提示行,别被回显的 input_phone_verify_code 命令带偏
        if (s.contains("需要手机验证码") || s.contains("req_phone_verify_code") || s.contains("verify code")
                || s.contains("请输入验证码")) return OpendChannel.Phase.NEEDS_SMS;
        return null;
    }
}
