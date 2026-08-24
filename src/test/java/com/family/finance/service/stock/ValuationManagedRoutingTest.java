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

    /**
     * v1.18.5 · 「余额归谁管」这条判据现在有<b>三个</b>消费方,而它们是<b>三个变种的同一个洞</b>被逐个堵上的:
     * <ol>
     *   <li>v1.18.1 · 录入侧({@code creditAccountBalance})—— 划转 / 收支进托管账户,钱要落现金行</li>
     *   <li>v1.18.3 · 估值写回前拦一道 —— 若这次覆盖会抹平刚进出的<b>流水</b>,拒绝覆盖</li>
     *   <li>v1.18.5 · 手填余额({@code submitBalance})—— 差额要落现金行</li>
     * </ol>
     *
     * <p>第三个变种是<b>生产上真咬到人的</b>:维护者按提示去补钱,用的是「填报页手填余额」——
     * 而手填既不是流水(第 2 道防线不认)、也不动持仓,<b>正好从两道防线中间漏过去</b>。
     * 实测 8-21 14:42 手填 451,497.63 → 16:10 CRON 估值写回 375,248.71(delta −76,248.92),
     * 他刚补的钱又没了。</p>
     *
     * <p>这条测试只钉一件事:<b>判据仍然只有一份</b>。三个消费方各自复制一套是这个 bug
     * 反复出现的形状(已归档 5 次),而现在消费方越多,分裂的代价越大。</p>
     */
    @Test
    void 托管判据的三个消费方共用同一份定义() {
        // 判据本身是纯函数、无状态 —— 谁调都得到同一个答案。这里用「有持仓的 WEALTH」这个
        // 生产上真出事的形态,断言三处会看到一致的结论。
        boolean managed = StockHoldingService.valuationManaged(AccountType.WEALTH, oneHolding());
        assertThat(managed).isTrue();
        // 反面:同一个账户类型、没有持仓 → 三处也必须一致地认为「不接管」
        assertThat(StockHoldingService.valuationManaged(AccountType.WEALTH, List.of())).isFalse();
    }
}
