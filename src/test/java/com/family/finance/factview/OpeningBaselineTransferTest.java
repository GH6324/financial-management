package com.family.finance.factview;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.28.1 · 开账基线不含「从别的账户转进来的钱」(维护者 2026-09-30 · prod 实例)。
 *
 * <p>新开一个账户、余额 0,从老账户转进一笔钱:原来整笔算「开账基线」,老账户那边却是净资产减少同样的数,
 * 仪表盘「本月资产收益 · 剔除收入」凭空少这么多。<b>下面的金额全是编的。</b><b>只有新账户直接校准的余额才是开账基线。</b></p>
 */
class OpeningBaselineTransferTest {

    private static final BigDecimal Z = BigDecimal.ZERO;

    private static AccountPeriodFact fact(AccountType type, String end, String in, String out) {
        BigDecimal e = new BigDecimal(end);
        return new AccountPeriodFact(
                24L, "新账户", type, type.isLiability() ? AccountClass.LIABILITY : AccountClass.ASSET,
                AccountLiquidity.LIQUID, "CNY", null, 0, 5L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                null, e, null, e,
                Z, Z, Z, Z,
                new BigDecimal(in), new BigDecimal(in), new BigDecimal(out), new BigDecimal(out),
                null, null, BigDecimal.ONE);
    }

    @Test
    void 新开账户全靠转入_开账基线是0() {
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.STOCK, "30000", "30000", "0")))
                .isEqualByComparingTo("0");
    }

    @Test
    void 直接校准的余额才是开账基线() {
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.CASH, "80000", "0", "0")))
                .isEqualByComparingTo("80000");
    }

    @Test
    void 首期只有转出_不加回_与原来相同() {
        // 校准之后这一期转出 7000 到老账户 → 期末 2000。转出去的可能是这个月刚进来、已算进收入的工资,
        // 账本分不出来 —— 维持原口径(期末),不加回
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.CASH, "2000", "0", "7000")))
                .isEqualByComparingTo("2000");
    }

    @Test
    void 既有转入又有转出_只扣转入() {
        // 期末 32000,这一期转进 30000、转出 7000 → 开账基线只扣转入 = 2000(转出不加回,理由见 openingOf)
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.CASH, "32000", "30000", "7000")))
                .isEqualByComparingTo("2000");
    }

    @Test
    void 转进来的钱这一期亏了或花了_资产类不出现负的存量() {
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.STOCK, "28000", "30000", "0")))
                .isEqualByComparingTo("0");
    }

    @Test
    void 负债照常为负_还款转入从期末里扣掉() {
        // 新纳入一笔贷款,这一期还了 4820 → 期末 −1200000;带进来的是 −1204820
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.LOAN, "-1200000", "4820", "0")))
                .isEqualByComparingTo("-1204820");
    }

    @Test
    void 没有转账时与原来逐字相同_连负的期末也不动() {
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.WEALTH, "654321.00", "0", "0")))
                .isEqualByComparingTo("654321.00");
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.WEALTH, "-2468.10", "0", "0")))
                .as("怪数据(资产期末为负)也按原样 —— 不借这次改动顺手改口径").isEqualByComparingTo("-2468.10");
        assertThat(FactViewServiceImpl.openingOf(null)).isNull();
    }
}
