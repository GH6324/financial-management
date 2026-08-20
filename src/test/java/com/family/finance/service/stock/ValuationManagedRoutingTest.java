package com.family.finance.service.stock;

import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.18.1 · 「这个账户的余额归谁管」只许有<b>一份判据</b>。
 *
 * <p><b>起因是一次真实的丢钱</b>:生产上两笔划转共 7.5w 进了一个挂着基金持仓的理财账户
 * (WEALTH),划转把快照加上去了,但钱<b>没进该账户的现金行</b>;当天 06:15 自动估值按
 * 「持仓合计」重算并覆盖快照 —— 那 7.5w 从余额里消失,家庭净资产少算同额,
 * 而且每跑一次估值就再抹一次。</p>
 *
 * <p>根因是<b>两处判据不一致</b>:</p>
 * <ul>
 *   <li>录入侧(v0.12)判「余额变动要不要落到现金行」用的是 {@code type == STOCK}</li>
 *   <li>估值侧判「要不要接管这个账户的余额」用的是「支持持仓的类型 <b>且</b> 真的有持仓」</li>
 * </ul>
 *
 * <p>于是 WEALTH / CRYPTO / METAL 且有持仓的账户落进了缝里:估值会覆盖它,录入却不给它记现金行。
 * 现在两侧都走 {@link StockHoldingService#valuationManaged(AccountType, List)}。</p>
 *
 * <p>另一半同样要钉住:<b>没有持仓的账户绝不能被判成托管</b> —— 那些账户的快照就是余额真值,
 * 给它凭空造一行「现金」会让用户在持仓页看到一笔他从没建过的东西。</p>
 */
class ValuationManagedRoutingTest {

    private static List<StockHolding> oneHolding() {
        return List.of(StockHolding.builder()
                .id(1L).accountId(7L).displayName("某基金")
                .valuationMode(ValuationMode.MANUAL).build());
    }

    private static List<StockHolding> oneCashRow() {
        return List.of(StockHolding.builder()
                .id(2L).accountId(7L).displayName("CNY 现金")
                .valuationMode(ValuationMode.CASH).currency("CNY").build());
    }

    /**
     * 生产上出事的正是这一格:WEALTH + 有持仓。
     * 老判据 {@code type == STOCK} 会漏掉它,而估值确确实实接管了它。
     */
    @Test
    void 有持仓的非STOCK账户也算托管_这正是漏掉的那一格() {
        assertThat(StockHoldingService.valuationManaged(AccountType.WEALTH, oneHolding()))
                .as("挂着基金持仓的理财账户 —— 生产上丢掉 7.5w 的就是它").isTrue();
        assertThat(StockHoldingService.valuationManaged(AccountType.CRYPTO, oneHolding())).isTrue();
        assertThat(StockHoldingService.valuationManaged(AccountType.METAL, oneHolding())).isTrue();
        assertThat(StockHoldingService.valuationManaged(AccountType.CASH, oneHolding())).isTrue();
        assertThat(StockHoldingService.valuationManaged(AccountType.STOCK, oneHolding())).isTrue();
    }

    /** 现金行本身也是持仓行 —— 只有一行现金的账户同样由估值接管。 */
    @Test
    void 只有现金行的账户也算托管() {
        assertThat(StockHoldingService.valuationManaged(AccountType.WEALTH, oneCashRow())).isTrue();
    }

    /**
     * 红线的另一半:没有持仓 = 不接管。
     * 判成托管会让录入侧给一个普通现金账户凭空建一行「CNY 现金」持仓 —— 用户在持仓页
     * 会看到一笔他从没建过的东西,而且这个账户的快照本来就是余额真值,根本不需要它。
     */
    @Test
    void 没有持仓一律不算托管_不许凭空造现金行() {
        for (AccountType t : AccountType.values()) {
            assertThat(StockHoldingService.valuationManaged(t, List.of()))
                    .as(t + " · 空持仓").isFalse();
            assertThat(StockHoldingService.valuationManaged(t, null))
                    .as(t + " · null 持仓").isFalse();
        }
    }

    /** 类型红线仍在:不支持持仓的类型,哪怕库里有脏数据也不接管。 */
    @Test
    void 不支持持仓的类型即使有持仓也不接管() {
        assertThat(StockHoldingService.valuationManaged(AccountType.LOAN, oneHolding())).isFalse();
        assertThat(StockHoldingService.valuationManaged(AccountType.PROPERTY, oneHolding())).isFalse();
        assertThat(StockHoldingService.valuationManaged(AccountType.INSURANCE, oneHolding())).isFalse();
    }

    /**
     * 判据必须与 {@link StockHoldingService#supportsHoldings} 保持「与」关系 ——
     * 加新账户类型时,只要它进了 supportsHoldings,就自动同时进托管判据,
     * 不会出现「估值接管了、录入侧不认」的第二次缝隙。
     */
    @Test
    void 托管判据严格等于_支持持仓_且_有持仓() {
        for (AccountType t : AccountType.values()) {
            assertThat(StockHoldingService.valuationManaged(t, oneHolding()))
                    .as(t + " · 有持仓时应等于 supportsHoldings")
                    .isEqualTo(StockHoldingService.supportsHoldings(t));
        }
    }
}
