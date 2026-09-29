package com.family.finance.service.llmtrace;

import com.family.finance.domain.account.Account;
import com.family.finance.service.analysis.AnalysisPromptBlocks;
import com.family.finance.service.analysis.PromptFixtures;
import com.family.finance.service.checkup.llm.PromptBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.28 FR-920 · 账户名换代号(护栏 v128-ACCOUNT-CODENAMES)。
 *
 * <p>最要紧的一条:<b>代号换回真名之后,提示词与 v1.26.1 金样本逐字相同</b> —— 证明这次只换了名字,
 * 别的一个字没动(金样本来自已发布的 tag,不是这一版自己生成的)。</p>
 */
class AccountCodenamesTest {

    private static Account acc(long id, String name) {
        Account a = new Account();
        a.setId(id);
        a.setDisplayName(name);
        return a;
    }

    /** 与 PromptFixtures.accounts() 同名;id 故意乱序给,代号按 id 升序编 */
    private static List<Account> fixtureAccounts() {
        return List.of(acc(15, "房贷"), acc(10, "招行工资卡"), acc(12, "券商主账户"),
                acc(11, "银行理财"), acc(13, "自住房"), acc(14, "储蓄险"));
    }

    private static String golden(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/golden/v1261/" + name + ".txt"), StandardCharsets.UTF_8);
    }

    @Test
    void 代号按账户id升序编_稳定() {
        var c = AccountCodenames.of(fixtureAccounts());
        assertThat(c.code(10L, null)).isEqualTo("账户A");
        assertThat(c.code(11L, null)).isEqualTo("账户B");
        assertThat(c.code(15L, null)).isEqualTo("账户F");
        assertThat(c.code(99L, "不认识")).as("不认识的账户不编代号,原样").isEqualTo("不认识");
        assertThat(c.codeForName("券商主账户")).isEqualTo("账户C");
        assertThat(c.codeForName("张三的账户组")).as("不是账户名(如账户组名)原样").isEqualTo("张三的账户组");
    }

    @Test
    void 超过26个账户用两个字母_换回来不会把账户AB当成账户A加B() {
        List<Account> many = new ArrayList<>();
        for (int i = 1; i <= 30; i++) many.add(acc(i, "户" + i));
        var c = AccountCodenames.of(many);
        assertThat(AccountCodenames.letters(25)).isEqualTo("Z");
        assertThat(AccountCodenames.letters(26)).isEqualTo("AA");
        assertThat(AccountCodenames.letters(27)).isEqualTo("AB");
        assertThat(c.reverse("从账户AB调到账户A。")).isEqualTo("从户28调到户1。");
        assertThat(PromptBuilder.reverseMapping("从账户AB调到账户A。", c.codeToReal()))
                .as("与成员名同一处反映射(长的代号先换)结果一致").isEqualTo("从户28调到户1。");
    }

    @Test
    void 全家诊断_代号换回真名后与v1261金样本逐字相同() throws Exception {
        var c = AccountCodenames.of(fixtureAccounts());
        List<PromptBuilder.AccountSummary> coded = new ArrayList<>();
        for (var s : PromptFixtures.accounts()) {
            coded.add(new PromptBuilder.AccountSummary(c.codeForName(s.accountName()), s.accountType(), s.categoryCode(),
                    s.riskLabel(), s.ownerCodename(), s.currentBalance(), s.annualizedReturn(), s.benchmarkLabel(), s.benchmarkPct()));
        }
        String user = PromptBuilder.userPromptForFamily("我们家", PromptFixtures.family(), coded,
                PromptFixtures.advice(), PromptFixtures.mapping(), "");
        assertThat(user).doesNotContain("招行工资卡").doesNotContain("券商主账户").contains("【账户A】");
        assertThat(PromptBuilder.reverseMapping(user, c.codeToReal())).isEqualTo(golden("diagnose-family-user"));
    }

    @Test
    void 单账户诊断_账户名一行写代号_换回来逐字相同() throws Exception {
        var c = AccountCodenames.of(List.of(acc(12, "券商主账户")));
        String user = PromptBuilder.userPromptForAccount("我们家", PromptFixtures.family(), PromptFixtures.stockAccount(),
                List.of(), PromptFixtures.mapping(), "成员B",
                AnalysisPromptBlocks.preferencesOnly(List.of(), PromptFixtures.mapping()), c.code(12L, null));
        assertThat(user).contains("- 账户名: 账户A\n").doesNotContain("券商主账户");
        assertThat(PromptBuilder.reverseMapping(user, c.codeToReal())).isEqualTo(golden("diagnose-account-user"));
    }

    @Test
    void 自己写的偏好不换账户名_普通词不会被误换() {
        var c = AccountCodenames.of(List.of(acc(1, "现金")));
        String prefs = AnalysisPromptBlocks.preferencesOnly(List.of("现金留足半年"), java.util.Map.of());
        assertThat(prefs).contains("现金留足半年").doesNotContain("账户A");
    }
}
