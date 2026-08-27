package com.family.finance.domain.lens;

import com.family.finance.domain.account.AccountType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.19 · 托管形式的<b>结构性</b>护栏。
 *
 * <h3>这条测试要拦的是什么</h3>
 * <p>不是「判定算得对不对」,是<b>「加了新账户类型却没人来这里表态」</b>。
 * 这个形状在本项目栽过两次,而且两次编译器都一声不吭:</p>
 * <ul>
 *   <li><b>v0.14 加 METAL</b> → 资产体检的「投资类账户」判据仍写着 STOCK/WEALTH/CRYPTO,
 *       贵金属账户被三条规则静默跳过,到 v1.18.5 复盘才发现。</li>
 *   <li><b>v1.4 放开 supportsHoldings</b> → 录入侧仍写 {@code type == STOCK},生产上丢过钱。</li>
 * </ul>
 * <p>{@code CustodyForm.of} 用的是 {@code switch} 且<b>穷尽枚举</b>,所以加类型时编译器会红 ——
 * 但那只保证「有分支」,不保证「分对了」。所以下面还要有一条<b>语义</b>断言。</p>
 */
class CustodyFormTest {

    /** 每个账户类型都必须能判出托管形式,且不能是 null。 */
    @Test
    void 每个账户类型都判得出托管形式() {
        for (AccountType t : AccountType.values()) {
            assertThat(CustodyForm.of(t, false, false))
                    .as("新增账户类型 %s 还没在托管形式里表态 —— "
                        + "回 CustodyForm.of 补一个分支,并想清楚它的钱由谁做决定", t)
                    .isNotNull();
        }
    }

    /**
     * <b>语义断言</b>:投资类必须落进「自己盯」或「交给产品」,不能被判成「不动」。
     *
     * <p>这一条是为 v0.14 那次真实遗漏立的碑 —— 当时 METAL 就是被漏在投资类判据之外。
     * 若将来有人加了新的投资类型却随手写成 PARKED,这里会红。</p>
     */
    @Test
    void 投资类账户必须是自己盯或交给产品() {
        for (AccountType t : AccountType.values()) {
            if (!t.isInvestment()) continue;
            CustodyForm f = CustodyForm.of(t, false, false);
            assertThat(f)
                    .as("%s 是投资类账户,它的钱一定由某个人或某个产品在管,不可能是「不动」", t)
                    .isIn(CustodyForm.SELF, CustodyForm.DELEGATED);
        }
    }

    // ──────────────────── 具体判据 ────────────────────

    @Test
    void 自己建的标的算自己盯() {
        assertThat(CustodyForm.of(AccountType.STOCK, false, false)).isEqualTo(CustodyForm.SELF);
        assertThat(CustodyForm.of(AccountType.CRYPTO, false, false)).isEqualTo(CustodyForm.SELF);
        assertThat(CustodyForm.of(AccountType.METAL, false, false))
                .as("贵金属也是自己盯 —— v0.14 那次就是它被漏掉的").isEqualTo(CustodyForm.SELF);
    }

    @Test
    void 理财算交给产品() {
        assertThat(CustodyForm.of(AccountType.WEALTH, false, false)).isEqualTo(CustodyForm.DELEGATED);
    }

    /**
     * 货基是理财账户,但它的性质是「随时可取」——
     * 判据必须与<b>流动性</b>那一维同源,不能两处分家。
     */
    @Test
    void 货基类理财算随时可取_与流动性维度同源() {
        assertThat(CustodyForm.of(AccountType.WEALTH, false, true)).isEqualTo(CustodyForm.LIQUID);
    }

    @Test
    void 房产与保险算不动() {
        assertThat(CustodyForm.of(AccountType.PROPERTY, false, false)).isEqualTo(CustodyForm.PARKED);
        assertThat(CustodyForm.of(AccountType.INSURANCE, false, false)).isEqualTo(CustodyForm.PARKED);
    }

    @Test
    void 现金账户算随时可取() {
        assertThat(CustodyForm.of(AccountType.CASH, false, false)).isEqualTo(CustodyForm.LIQUID);
    }

    /**
     * <b>券商账户里的现金行</b>:挂在 STOCK 账户下,但它不是你「盯」的标的,是随时能取的钱。
     * 这一格容易漏 —— 它是 v1.4 那个丢钱 bug 的同一片区域。
     */
    @Test
    void 券商账户里的现金行算随时可取_不算自己盯() {
        assertThat(CustodyForm.of(AccountType.STOCK, true, false))
                .as("券商账户里的现金不是你在盯的标的").isEqualTo(CustodyForm.LIQUID);
        assertThat(CustodyForm.of(AccountType.CRYPTO, true, false)).isEqualTo(CustodyForm.LIQUID);
    }

    /** null 不许炸 —— 这个标签会进透视引擎,炸了整页就白了 */
    @Test
    void 类型为null不炸() {
        assertThat(CustodyForm.of(null, false, false)).isNotNull();
        assertThat(CustodyForm.labelOf(null, false, false)).isNotBlank();
    }

    /** 标签是给用户看的,不能出现技术词 */
    @Test
    void 标签是人话() {
        for (CustodyForm f : CustodyForm.values()) {
            assertThat(f.getLabel()).isNotBlank();
            assertThat(f.getLabel().toLowerCase())
                    .as("面向用户的标签不许出现技术词")
                    .doesNotContain("type").doesNotContain("mode").doesNotContain("flag");
        }
    }
}
