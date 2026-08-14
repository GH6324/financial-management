package com.family.finance.domain.period;

import java.math.BigDecimal;

/**
 * v1.12 FR-350 · 某个已关账期里,某个账户的分类属性定格值。
 *
 * <p>为什么需要它:金额侧的事实早就按期落库了(余额 / 收支 / 汇率 / 持仓估值),分类侧没有 ——
 * {@code FactMapper.queryBase} 是拿<b>当前</b>的 {@code account} 行和 {@code product_category} 行
 * 去 join 每一期。于是改一个账户的类目,去年 12 月的集中度 / 流动性分层 / 大类分布当场跟着变,
 * 而 v1.10 对报表页的承诺是「已关账的月份是封板快照,不再变」。</p>
 *
 * <p>存的是<b>解析后的值</b>而不是只存 {@code productCategoryCode} ——
 * 因为改类目自己的 {@code benchmarkPct} / {@code liquidityClass} 同样会改写历史,只存 code 挡不住。
 * 同时存 {@code productCategoryName}:v0.14.1 专门修过「类目列裸露 code」,类目被删后仍要能显示中文名。</p>
 *
 * @param expectedReturnPct 当时的 {@code account.expected_return_pct}(账户级预期覆盖,可为 null)。
 *                          设计时漏了这个,施工中被正向漂移测试逼出来:三区的「预实」列不吃
 *                          {@code ReportsController} 那张基准 map,而是走
 *                          {@code FactViewServiceImpl.expectedReturnByAccount} 读当前账户行 →
 *                          只定格类目侧的话,用户改一次账户「预期年化」,历史每期预实照样重算。
 *                          存的是<b>原始覆盖值</b>不是解析后的最终预期 % —— 定格输入,不定格输出,
 *                          解析规则(覆盖优先、回落类目基准)留在 Java 一处。见 V54 注释。
 * @param source {@code CLOSE} = 关账时真定格 · {@code BACKFILL} = v1.12 上线时按当天属性回填。
 *               回填出来的行<b>不是真定格</b>(「当时的属性」从未被记录),这个字段让事后查历史的人看得出区别。
 */
public record PeriodAccountAttr(
        Long periodId,
        Long accountId,
        String accountType,
        String productCategoryCode,
        String productCategoryName,
        String liquidityClass,
        BigDecimal benchmarkPct,
        BigDecimal expectedReturnPct,
        String source
) {
}
