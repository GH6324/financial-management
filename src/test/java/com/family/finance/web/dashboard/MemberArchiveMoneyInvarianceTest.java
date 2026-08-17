package com.family.finance.web.dashboard;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.factview.AccountPerformance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.15 FR-381/382 · <b>归档一个人,不动一分钱,也不丢一个名字。</b>
 *
 * <p>归档的语义是「ta 不再打理家里的账」,不是「ta 的钱不算数了」。
 * 所有金额口径按的是 {@code account.archived_at},跟 {@code member.archived_at} 无关 ——
 * 一个人退出打理,他名下的账户和历史流水照旧计入家庭总账。
 *
 * <p>成员维度资产配置是这条不变量最容易破的地方:它既碰钱又碰名字。
 * 名字映射一旦用了「仅活跃」列表,归档后这块饼就从「李四」变成「成员#7」——
 * 金额还对,但用户看到的是一个不认识的人占着家里 40% 的资产。
 */
class MemberArchiveMoneyInvarianceTest {

    private static Account acc(long id, Long ownerId, AccountType type) {
        return Account.builder().id(id).familyId(1L).type(type).primaryOwnerMemberId(ownerId).build();
    }

    private static AccountPerformance perf(long id, AccountType type, String value) {
        return AccountPerformance.basic(id, "账户" + id, type, "CNY", new BigDecimal(value), null, List.of());
    }

    /** 名录出口给的映射 —— 含已归档的李四(7)。 */
    private static Map<Long, String> directoryNames() {
        Map<Long, String> m = new LinkedHashMap<>();
        m.put(3L, "张三");
        m.put(7L, "李四");   // 已归档,但名字还在
        return m;
    }

    @Test
    void archivedOwnerKeepsBothTheirMoneyAndTheirName() {
        List<Account> accounts = List.of(
                acc(101, 3L, AccountType.CASH),
                acc(102, 7L, AccountType.STOCK),     // 主理人已归档
                acc(103, null, AccountType.WEALTH),  // 共同
                acc(104, 7L, AccountType.LOAN));     // 负债 · 不进资产配置
        List<AccountPerformance> rows = List.of(
                perf(101, AccountType.CASH, "10000"),
                perf(102, AccountType.STOCK, "40000"),
                perf(103, AccountType.WEALTH, "50000"),
                perf(104, AccountType.LOAN, "-200000"));

        var alloc = DashboardController.computeMemberAllocation(directoryNames(), accounts, rows);

        assertThat(alloc).containsEntry("张三", new BigDecimal("10000"));
        assertThat(alloc).containsEntry("李四", new BigDecimal("40000"));   // 钱在,名字也在
        assertThat(alloc).containsEntry("共同", new BigDecimal("50000"));
        assertThat(alloc).doesNotContainKey("成员#7");
        // 负债不计入资产配置(归档与否都一样)
        assertThat(alloc.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100000");
    }

    @Test
    void anActiveOnlyNameMap_wouldHaveBrokenIt() {
        // 反证:这就是 v1.15 之前那种「仅活跃」映射的效果 —— 金额一分不差,名字没了。
        // 留着这条是为了让后人一眼看出,把 nameMap 换回 findActiveByFamily 到底会发生什么。
        Map<Long, String> activeOnly = Map.of(3L, "张三");

        var alloc = DashboardController.computeMemberAllocation(
                activeOnly,
                List.of(acc(101, 3L, AccountType.CASH), acc(102, 7L, AccountType.STOCK)),
                List.of(perf(101, AccountType.CASH, "10000"), perf(102, AccountType.STOCK, "40000")));

        assertThat(alloc).containsEntry("成员#7", new BigDecimal("40000"));
        assertThat(alloc).doesNotContainKey("李四");
    }

    @Test
    void accountsWithNoValuationAreSkipped_notCountedAsZeroUnderTheArchivedOwner() {
        var alloc = DashboardController.computeMemberAllocation(
                directoryNames(),
                List.of(acc(102, 7L, AccountType.STOCK)),
                List.of(AccountPerformance.basic(102L, "账户102", AccountType.STOCK, "CNY", null, null, List.of())));

        assertThat(alloc).isEmpty();
    }
}
