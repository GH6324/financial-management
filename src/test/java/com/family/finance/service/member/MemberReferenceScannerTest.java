package com.family.finance.service.member;

import com.family.finance.repository.MemberReferenceMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * v1.15 FR-383 · 引用扫描。
 *
 * <p>最要紧的一条是 {@link #scan_queriesEveryCountMethodOnTheMapper()}:
 * 漏掉一处引用的后果不是「扫描结果不全」,而是<b>放行了一次本不该发生的删除</b> ——
 * 那条外键要么把删除拦在数据库层(用户看到 500),要么根本没有外键(v19/v24/v25/family_goal
 * 这四处就没有),于是历史数据里留下一个指向不存在成员的悬空 id。
 *
 * <p>所以这里用反射逐个比对 mapper 上的方法:以后有人给 mapper 加了一个 count 方法却忘了
 * 挂进 {@code scan()},这个测试当场红。
 */
class MemberReferenceScannerTest {

    private final MemberReferenceMapper mapper = mock(MemberReferenceMapper.class, RETURNS_DEFAULTS);
    private final MemberReferenceScanner scanner = new MemberReferenceScanner(mapper);

    @Test
    void scan_queriesEveryCountMethodOnTheMapper() throws Exception {
        scanner.scan(1L, 7L);

        Set<String> declared = Arrays.stream(MemberReferenceMapper.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(n -> n.startsWith("count"))
                .collect(Collectors.toSet());
        Set<String> called = mockingDetails(mapper).getInvocations().stream()
                .map(i -> i.getMethod().getName())
                .collect(Collectors.toSet());

        assertThat(declared).as("mapper 上应有 13 处引用查询").hasSize(13);
        assertThat(called)
                .as("mapper 上每个 count 方法都必须被 scan() 调到 —— 漏一个就是放行一次本不该发生的删除")
                .containsAll(declared);
    }

    @Test
    void zeroEverywhere_isDeletable() {
        // RETURNS_DEFAULTS → 所有 count 返回 0
        MemberReferenceScanner.Scan scan = scanner.scan(1L, 7L);

        assertThat(scan.total()).isZero();
        assertThat(scan.refs()).isEmpty();
        assertThat(scan.deletable()).isTrue();
    }

    @Test
    void anySingleReference_blocksDeletion_andIsNamedForTheUser() {
        when(mapper.countCashFlow(7L)).thenReturn(12);

        MemberReferenceScanner.Scan scan = scanner.scan(1L, 7L);

        assertThat(scan.deletable()).isFalse();
        assertThat(scan.total()).isEqualTo(12);
        // 只保留命中的那几项 —— 页面上不该铺 13 行「0 条」
        assertThat(scan.refs()).singleElement()
                .satisfies(r -> {
                    assertThat(r.label()).isEqualTo("记的收支流水");
                    assertThat(r.count()).isEqualTo(12);
                });
    }

    @Test
    void fkLessReferences_alsoBlockDeletion() {
        // 这四处**没有外键**:数据库不会替我们拦,漏扫 = 悬空 id
        when(mapper.countPeriodMemberCashflow(7L)).thenReturn(1);   // V19 period_member_cashflow
        when(mapper.countStockValuationEvent(7L)).thenReturn(2);    // V24 stock_valuation_event
        when(mapper.countReportReminderLog(7L)).thenReturn(3);      // V25 report_reminder_log
        when(mapper.countGoalChildRef(1L, 7L)).thenReturn(4);       // V14 family_goal.params_json

        MemberReferenceScanner.Scan scan = scanner.scan(1L, 7L);

        assertThat(scan.deletable()).isFalse();
        assertThat(scan.total()).isEqualTo(10);
        assertThat(scan.refs()).extracting(MemberReferenceScanner.Ref::label)
                .containsExactlyInAnyOrder("期间现金流", "估值变动事件", "提醒发送记录", "教育目标里的孩子");
    }
}
