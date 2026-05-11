package com.family.finance.service.checkup.llm;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 综合诊断 OutputValidator 单测 · v0.2 FR-40c · 决策 20(2026-05-10)
 *
 * 校验策略(从"锁数字 100%"放宽为软校验):
 *   1. 长度 150-700 中文字符
 *   2. 担保性话术拒绝(保证 / 稳赚 / 一定能 / 必然 / 零风险 / 包赚 ...)
 *   3. 古典中式词拒绝(师傅 / 打理 / 挪 ...)
 *   4. 具体产品名 / 股票代码拒绝(余额宝 / 510300 / 茅台 / 6位数代码)
 *   5. 真名泄露拒绝(LLM 输出含原始成员真名)
 *   6. 过度客套拒绝(您 > 2 次)
 *   7. 至少一个金融术语
 */
class OutputValidatorTest {

    private static final Set<String> NO_NAMES = Set.of();
    private static final Set<String> NAMES_2 = Set.of("Alice", "Bob");

    /** 一段健康综合诊断模板,长度足、含金融术语、无禁词,用于"基础通过"测试 */
    private static final String VALID_DIAGNOSE =
            "整体配置偏稳健:全家流动性月数充裕,但权益类年化跑输基准约 0.5pp,集中度方面单账户占比偏高。" +
            "结合本期命中的 LIQ-2 与 RET-3 两条规则看,问题在于流动性配置过厚而风险敞口未充分利用。" +
            "建议把超额流动性的部分本金,通过分批方式再配置至跟踪基准的指数型仓位,降低主动选股偏差," +
            "同时关注短期回撤恢复节奏,逐步把加权年化拉回基准水平。后续可结合再平衡周期评估调整。";

    @Test
    void acceptsValidDiagnose() {
        var r = OutputValidator.check(VALID_DIAGNOSE, NO_NAMES);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void rejectsEmpty() {
        var r = OutputValidator.check("", NO_NAMES);
        assertThat(r.accepted()).isFalse();
    }

    @Test
    void rejectsBlank() {
        var r = OutputValidator.check("    \n  ", NO_NAMES);
        assertThat(r.accepted()).isFalse();
    }

    @Test
    void rejectsTooShort() {
        var r = OutputValidator.check("整体配置偏稳健,流动性月数较高,建议再平衡。", NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("过短");
    }

    @Test
    void rejectsTooLong() {
        String tooLong = "整体配置偏稳健,流动性配置充裕。" + "建议适度再平衡风险敞口降低主动选股偏差。".repeat(50);
        var r = OutputValidator.check(tooLong, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("过长");
    }

    @Test
    void rejectsGuaranteePhrase() {
        String bad = VALID_DIAGNOSE.replace("逐步把加权年化拉回基准水平", "保证年化收益率达 8%");
        var r = OutputValidator.check(bad, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("担保性");
    }

    @Test
    void rejectsArchaicPhrase() {
        String bad = VALID_DIAGNOSE.replace("整体配置偏稳健", "整体看,师傅这个家底配置偏稳健");
        var r = OutputValidator.check(bad, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("古典");
    }

    @Test
    void rejectsProductName() {
        String bad = VALID_DIAGNOSE.replace("跟踪基准的指数型仓位", "余额宝或者 510300 这种 ETF");
        var r = OutputValidator.check(bad, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("产品名");
    }

    @Test
    void rejectsAStockCode() {
        // 600519 是茅台的代码,纯 6 位数字必拦
        String bad = VALID_DIAGNOSE.replace("跟踪基准的指数型仓位", "可以考虑 600519 这只标的");
        var r = OutputValidator.check(bad, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("产品名");
    }

    @Test
    void rejectsRealNameLeak() {
        // LLM 输出含真名(成员代号映射理论上避免,但作防御深度)
        String bad = VALID_DIAGNOSE.replace("结合本期命中", "建议Alice把多余现金调到货币基金,结合本期命中");
        var r = OutputValidator.check(bad, NAMES_2);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("真名");
    }

    @Test
    void allowsCodenameMembers() {
        // 输出含「成员A」是预期的,不该拦
        String good = VALID_DIAGNOSE.replace("结合本期命中", "建议成员A 把多余现金再配置,结合本期命中");
        var r = OutputValidator.check(good, NAMES_2);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void rejectsExcessiveFormality() {
        String bad = VALID_DIAGNOSE.replace("整体配置偏稳健", "您好,您家整体配置偏稳健,请您注意");
        var r = OutputValidator.check(bad, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("客套");
    }

    @Test
    void rejectsNoFinanceTerm() {
        // 一段够长(>= 150 字)但不含任何金融术语 — 模拟 LLM 跑题成"心灵鸡汤"
        String bad = "生活总有起起落落,心情平静最重要。重要的不是赚多少,而是心安。" +
                "每个人都该过自己想要的生活,不必和别人去比较攀比，按自己的节奏过日子最实在。" +
                "保持初心,简单生活,顺其自然。今天的天气真好,适合出门散步,放松心情。" +
                "工作之余多陪陪家人，多看看孩子，多听听父母，这才是人生最大的精神财富，" +
                "千万不要因为忙碌而忽视身边的美好瞬间和点点滴滴的温暖。";
        var r = OutputValidator.check(bad, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("金融术语");
    }

    @Test
    void acceptsTwoYouOrLess() {
        // "您"出现 2 次以下应通过(不算过度客套)
        String ok = VALID_DIAGNOSE.replace("整体配置偏稳健", "整体配置偏稳健,您可参考");
        var r = OutputValidator.check(ok, NO_NAMES);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void shortNameNotConsideredLeak() {
        // 单字符名字不算泄露(防误伤,如名字"王"会和"王者"等无关词混淆)
        Set<String> shortName = Set.of("王");
        // VALID_DIAGNOSE 不含"王",但即使含也不该拦(realName.length() < 2)
        var r = OutputValidator.check(VALID_DIAGNOSE, shortName);
        assertThat(r.accepted()).isTrue();
    }
}
