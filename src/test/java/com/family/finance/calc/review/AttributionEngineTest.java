package com.family.finance.calc.review;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.2 · 归因引擎:恒等闭合 / 两步法汇率拆分 / 未归因显性 / 维度分组沉底 */
class AttributionEngineTest {

    private static AttributionEngine.AcctInput cny(long id, String name, String pnl) {
        return new AttributionEngine.AcctInput(id, name, "CNY",
                new BigDecimal(pnl), new BigDecimal(pnl),
                new BigDecimal("100000"), new BigDecimal("100000"),
                new BigDecimal("90000"), new BigDecimal("90000"),
                Map.of("assetClass", "股票股权"));
    }

    @Test
    void identityCloses_andCnyHasZeroFx() {
        // USD 账户:原币赚 1000,期末 fx=7.2 → 标的 7200;本位币 pnl 6800 → 汇率重估 -400(美元贬)
        var usd = new AttributionEngine.AcctInput(2L, "富途", "USD",
                new BigDecimal("6800"), new BigDecimal("1000"),
                new BigDecimal("72000"), new BigDecimal("10000"),
                new BigDecimal("64800"), new BigDecimal("9000"),
                Map.of("assetClass", "股票股权"));
        var r = AttributionEngine.attribute(List.of(cny(1L, "华泰", "-2000"), usd),
                new BigDecimal("14800"), new BigDecimal("9000"), new BigDecimal("1000"));
        // 钱赚合计 = -2000 + 6800
        assertThat(r.moneyEarnedTotal()).isEqualByComparingTo("4800");
        // CNY 账户 fx 恒 0;USD 拆分闭合:underlying+fx = pnlBase
        var futu = r.slices().stream().filter(s -> s.accountId() == 2L).findFirst().orElseThrow();
        assertThat(futu.underlying()).isEqualByComparingTo("7200");
        assertThat(futu.fxEffect()).isEqualByComparingTo("-400");
        var huatai = r.slices().stream().filter(s -> s.accountId() == 1L).findFirst().orElseThrow();
        assertThat(huatai.fxEffect()).isEqualByComparingTo("0");
        assertThat(r.fxTotal()).isEqualByComparingTo("-400");
        // 恒等:ΔNW 14800 = 人赚 9000 + 开账 1000 + 钱赚 4800 + 未归因 0
        assertThat(r.unattributed()).isEqualByComparingTo("0");
    }

    @Test
    void unattributedIsExplicit_notSwallowed() {
        var r = AttributionEngine.attribute(List.of(cny(1L, "A", "1000")),
                new BigDecimal("5000"), new BigDecimal("3000"), BigDecimal.ZERO);
        assertThat(r.unattributed()).isEqualByComparingTo("1000");   // 5000-3000-1000 = 1000 如实暴露
    }

    @Test
    void groupBy_dimension_andUnclassifiedSinksLast() {
        var noTag = new AttributionEngine.AcctInput(3L, "神秘", "CNY",
                new BigDecimal("50"), new BigDecimal("50"),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                new java.util.HashMap<>() {{ put("assetClass", null); }});
        var r = AttributionEngine.attribute(List.of(cny(1L, "华泰", "-2000"), noTag),
                new BigDecimal("-1950"), BigDecimal.ZERO, BigDecimal.ZERO);
        LinkedHashMap<String, BigDecimal> g = AttributionEngine.groupBy(r, "assetClass");
        assertThat(g.keySet()).containsExactly("股票股权", "未分类");   // 未分类沉底
        assertThat(g.get("股票股权")).isEqualByComparingTo("-2000");
        // 按账户分组(dimKey=null)
        LinkedHashMap<String, BigDecimal> byAcct = AttributionEngine.groupBy(r, null);
        assertThat(byAcct).containsKey("华泰");
    }

    @Test
    void zeroPnlAccountsExcluded_andClearedAccountFallsBackToPrevFx() {
        // 清仓账户:endOrig=0 → fxEnd 回退 prev(64800/9000=7.2)
        var cleared = new AttributionEngine.AcctInput(4L, "老虎", "USD",
                new BigDecimal("-720"), new BigDecimal("-100"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("64800"), new BigDecimal("9000"),
                Map.of());
        var zero = cny(5L, "沉睡", "0");
        var r = AttributionEngine.attribute(List.of(cleared, zero),
                new BigDecimal("-720"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(r.slices()).hasSize(1);   // 零贡献账户不进列表
        assertThat(r.slices().get(0).underlying()).isEqualByComparingTo("-720");  // -100×7.2
        assertThat(r.slices().get(0).fxEffect()).isEqualByComparingTo("0");
    }
}
