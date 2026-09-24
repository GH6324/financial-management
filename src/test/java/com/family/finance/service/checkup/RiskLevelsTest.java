package com.family.finance.service.checkup;

import com.family.finance.domain.account.AccountType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 家庭风险分布里一个账户算几级。
 *
 * <p>2026-09-24 之前按账户类型写死:加密 / 贵金属 / 保险全是「无风险」,没有任何类型到 5 级,
 * FAM-RISK-1 从来触发不了。这里守的是「等级来自账户自己的类目」这个意图,不守某个具体数字。</p>
 */
class RiskLevelsTest {

    /** 与种子数据一致的类目等级(V11 / V38 / V44);测试只借它当查表函数 */
    private static final Map<String, Integer> SEED = Map.of(
            "CASH_DEPOSIT", 1, "BANK_WEALTH", 2, "PROPERTY_RES", 2, "SAVINGS_INSURANCE", 2,
            "GOLD", 3, "OTHER", 3, "PRECIOUS_METAL", 4, "A_STOCK", 5, "US_STOCK", 5, "CRYPTO", 6);
    private static final Map<String, Integer> SEED_WITH_LIABILITY;
    static {
        var m = new java.util.HashMap<>(SEED);
        m.put("LIABILITY", 0);
        SEED_WITH_LIABILITY = Map.copyOf(m);
    }
    private static final Function<String, Integer> LOOKUP = SEED_WITH_LIABILITY::get;

    @Test
    void 手工改过的等级优先() {
        var r = RiskLevels.resolve(6, "CASH_DEPOSIT", AccountType.CASH, LOOKUP);
        assertThat(r.level()).isEqualTo(6);
        assertThat(r.source()).isEqualTo(RiskLevels.Source.OVERRIDE);
        assertThat(r.estimated()).isFalse();
    }

    @Test
    void 有产品类目就用类目的等级_而不是账户类型() {
        // 股票账户里放的是黄金 ETF:类目 GOLD(3 级),不该按「股票」算
        var r = RiskLevels.resolve(null, "GOLD", AccountType.STOCK, LOOKUP);
        assertThat(r.level()).isEqualTo(3);
        assertThat(r.source()).isEqualTo(RiskLevels.Source.CATEGORY);
    }

    @Test
    void 旧缺陷_加密贵金属保险不再是无风险() {
        assertThat(RiskLevels.resolve(null, null, AccountType.CRYPTO, LOOKUP).level()).isEqualTo(6);
        assertThat(RiskLevels.resolve(null, null, AccountType.METAL, LOOKUP).level()).isEqualTo(4);
        assertThat(RiskLevels.resolve(null, null, AccountType.INSURANCE, LOOKUP).level()).isEqualTo(2);
        // 没设类目的股票账户按 A 股估算 → 5 级,FAM-RISK-1 终于有东西可数
        assertThat(RiskLevels.resolve(null, null, AccountType.STOCK, LOOKUP).level()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void 按类型估算的要标成估算_页面据此提示去补类目() {
        var r = RiskLevels.resolve(null, null, AccountType.WEALTH, LOOKUP);
        assertThat(r.source()).isEqualTo(RiskLevels.Source.TYPE_DEFAULT);
        assertThat(r.estimated()).isTrue();
    }

    @Test
    void 类目码在表里查不到时回落到按类型估算() {
        var r = RiskLevels.resolve(null, "NO_SUCH_CODE", AccountType.CASH, LOOKUP);
        assertThat(r.level()).isEqualTo(1);
        assertThat(r.source()).isEqualTo(RiskLevels.Source.TYPE_DEFAULT);
    }

    @Test
    void 手工等级为0视为没改过() {
        // 与账户页、透视同一个判据:> 0 才算改过
        var r = RiskLevels.resolve(0, "A_STOCK", AccountType.STOCK, LOOKUP);
        assertThat(r.source()).isEqualTo(RiskLevels.Source.CATEGORY);
    }

    @Test
    void 每个账户类型的默认类目都真实存在于种子数据里() throws IOException {
        // 默认类目写错一个字母,那个类型的账户就会整体落进「未评级」—— 不报错,只是图不对。
        String seeds;
        try (Stream<Path> files = Files.list(Path.of("db/migration"))) {
            seeds = files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new RuntimeException(e); } })
                    .reduce("", String::concat);
        }
        for (AccountType t : AccountType.values()) {
            String code = RiskLevels.defaultCategoryCode(t);
            assertThat(seeds).as("账户类型 %s 的默认类目 %s 必须在 product_category 种子里", t, code)
                    .contains("('" + code + "'");
        }
    }
}
