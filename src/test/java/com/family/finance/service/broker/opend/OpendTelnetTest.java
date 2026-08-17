package com.family.finance.service.broker.opend;

import com.family.finance.service.broker.opend.OpendTelnet.Step;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.17 · 控制口登录状态机护栏。
 *
 * <p>样例文本取自 beta 实测(2026-08-17):连上控制口发一个换行,收到的就是版本号横幅 + 当前在等什么。</p>
 *
 * <p><b>网关容器里那份状态机是 bash 写的</b>(app 连不到容器内的控制口),两处必须认同一批关键词。
 * qa-run 的 {@code v117-CTL-KEYWORDS} 负责钉住,不靠自觉。</p>
 */
class OpendTelnetTest {

    @Test
    void real_banner_from_beta_is_recognised_as_waiting_password() {
        String real = "Futu OpenD版本信息: 10.10.7008(20260811202500), 输入help获取更多信息\r\n请输入密码\r\n";
        assertThat(OpendTelnet.stepFromPrompt(real)).isEqualTo(Step.WANT_PASSWORD);
    }

    @Test
    void account_prompt_is_recognised() {
        assertThat(OpendTelnet.stepFromPrompt("请输入账号")).isEqualTo(Step.WANT_ACCOUNT);
        assertThat(OpendTelnet.stepFromPrompt("请输入帐号")).isEqualTo(Step.WANT_ACCOUNT);   // 富途两种写法都见过
        assertThat(OpendTelnet.stepFromPrompt("Please input account")).isEqualTo(Step.WANT_ACCOUNT);
    }

    /** 顺序陷阱:「验证码错误」里也含「验证码」,失败必须先判,否则会把失败当成"再要一次码"。 */
    @Test
    void failure_wins_over_sms_and_success_keywords() {
        assertThat(OpendTelnet.stepFromPrompt("验证码错误,请重新输入")).isEqualTo(Step.FAILED);
        assertThat(OpendTelnet.stepFromPrompt("密码错误")).isEqualTo(Step.FAILED);
        assertThat(OpendTelnet.stepFromPrompt("登录失败")).isEqualTo(Step.FAILED);
    }

    @Test
    void sms_and_success_are_recognised() {
        assertThat(OpendTelnet.stepFromPrompt("请输入手机验证码")).isEqualTo(Step.WANT_SMS);
        assertThat(OpendTelnet.stepFromPrompt("need verify code")).isEqualTo(Step.WANT_SMS);
        assertThat(OpendTelnet.stepFromPrompt("登录成功")).isEqualTo(Step.LOGGED_IN);
        assertThat(OpendTelnet.stepFromPrompt("Login success")).isEqualTo(Step.LOGGED_IN);
    }

    @Test
    void unknown_output_does_not_move_the_state_machine() {
        assertThat(OpendTelnet.stepFromPrompt("Futu OpenD运行中")).isEqualTo(Step.UNKNOWN);
        assertThat(OpendTelnet.stepFromPrompt("")).isEqualTo(Step.UNKNOWN);
        assertThat(OpendTelnet.stepFromPrompt(null)).isEqualTo(Step.UNKNOWN);
    }

    @Test
    void ops_commands_match_official_names() {
        assertThat(OpendTelnet.CMD_REQ_SMS).isEqualTo("req_phone_verify_code");
        assertThat(OpendTelnet.cmdInputSms(" 428139 ")).isEqualTo("input_phone_verify_code -code=428139");
    }
}
