package com.family.finance.service;

import com.family.finance.domain.snapshot.SnapshotTodo;
import com.family.finance.domain.snapshot.TodoStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.17.x · 贷款趋势预测提示条显示闸(兼容性核心)。
 * committed==上月值 天然区分「新默认态」(出提示)与「老账期已填预测值」(不出、不回改)。
 *
 * <p>v1.16(issue #15)补:开账起 todo 就是 DONE 了,闸门改看「有没有<b>人</b>确认过」
 * ({@code done_by_member_id != null}),不再看状态列 —— 否则这一版会把提示条整个静默弄丢(FR-392)。</p>
 */
class EntryLoanPromptTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void newDefault_committedEqualsPrev_showsPrompt() {
        // 新代码开的账期:committed = 上月值 -990000,有建议 -980000,未确认 → 出提示
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), bd("-990000"), false)).isTrue();
    }

    @Test
    void legacyPeriod_committedEqualsPredicted_hidden() {
        // 老账期:旧代码已把 committed 写成预测值 -980000(≠上月),不打扰、不回改 → 不出提示
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), bd("-980000"), false)).isFalse();
    }

    @Test
    void userConfirmed_hidden() {
        // 用户已确认(接受/保持上月/手填)→ todo DONE → 不再出提示
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), bd("-990000"), true)).isFalse();
    }

    @Test
    void noRealSuggestion_predictedEqualsPrev_hidden() {
        // 预测 == 上月(已还平 / 仅一期历史)→ 无建议 → 不出提示
        assertThat(EntryService.loanPromptVisible(bd("0"), bd("0"), bd("0"), false)).isFalse();
    }

    @Test
    void committedManuallyChanged_hidden() {
        // 用户手改成别的值(committed ≠ 上月)→ 非默认态 → 不出提示
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), bd("-985000"), false)).isFalse();
    }

    @Test
    void nullGuards() {
        assertThat(EntryService.loanPromptVisible(null, bd("-990000"), bd("-990000"), false)).isFalse();
        assertThat(EntryService.loanPromptVisible(bd("-980000"), null, bd("-990000"), false)).isFalse();
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), null, false)).isFalse();
    }

    // ---- v1.16 FR-392(issue #15)· 「谁把它标成 DONE 的」决定提示条出不出 ----

    private static SnapshotTodo todo(TodoStatus status, Long doneBy) {
        return SnapshotTodo.builder().status(status).doneByMemberId(doneBy).build();
    }

    @Test
    void systemCarriedDone_isNotHumanConfirmation() {
        // v1.16 起开账就把 todo 标 DONE(done_by_member_id = NULL)。
        // 这不是「人做过决定」,提示条必须照旧出现 —— 这条丢了,贷款趋势预测就等于被这一版静默删掉。
        assertThat(EntryService.confirmedByHuman(todo(TodoStatus.DONE, null))).isFalse();
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), bd("-990000"),
                EntryService.confirmedByHuman(todo(TodoStatus.DONE, null)))).isTrue();
    }

    @Test
    void humanConfirmedDone_hidesPrompt() {
        // 有人点过「接受 / 保持上月」或手填过 → done_by_member_id 记名 → 不再打扰
        assertThat(EntryService.confirmedByHuman(todo(TodoStatus.DONE, 7L))).isTrue();
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), bd("-990000"),
                EntryService.confirmedByHuman(todo(TodoStatus.DONE, 7L)))).isFalse();
    }

    @Test
    void pendingOrMissingTodo_isNotHumanConfirmation() {
        // 无历史 → todo 仍 PENDING;todo 还没建出来 → null。两者都不算人确认过
        assertThat(EntryService.confirmedByHuman(todo(TodoStatus.PENDING, null))).isFalse();
        assertThat(EntryService.confirmedByHuman(null)).isFalse();
    }
}
