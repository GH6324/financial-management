package com.family.finance.factview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.10 · 归档过滤必须带**时间语义**。
 *
 * <p><b>为什么值得一条专门的测试</b>:{@code FactMapper.queryBase} 原来是裸的
 * {@code AND a.archived_at IS NULL}。归档动作没有时间概念,所以账户一归档,
 * 它的**全部历史事实行**立刻从切片里消失 —— 于是所有历史期的净资产、总资产、
 * 集中度分母、分层占比都跟着变小。</p>
 *
 * <p>换句话说:一个纯整理动作(归档一个已经不用的账户)就能改写<b>去年 12 月</b>的报表,
 * 把「封板快照不会二次变动」这句承诺直接证伪。这不是设计权衡,是 bug
 * (tech-design v1.10 §2.2 ①)。</p>
 *
 * <p>正确语义:归档<b>之前</b>的期照常计入,归档<b>之后</b>的期不计入。
 * 这条断言扫 SQL 本身 —— 口径写在 mapper 里,用单测跑 SQL 需要真库,
 * 而这里要守的恰恰是"那行 SQL 长什么样",静态断言比集成测试更贴近意图、也更不容易被绕过。
 * 实测证据(beta,4 个归档账户):修复后 range=ALL 的首点净资产从 2,290,051.41 涨到
 * 2,326,051.41,差额 36,000.00 与 DB 里「该期在册但后来归档」的账户余额分毫不差。</p>
 */
class ArchivedTimeSemanticsTest {

    private static final Path MAPPER = Path.of("src/main/resources/mapper/FactMapper.xml");

    private String sql() throws IOException {
        return Files.readString(MAPPER);
    }

    @Test
    void 归档账户在归档之前的期仍计入_之后不计入() throws IOException {
        String s = sql();
        assertThat(s)
                .as("必须用时间语义:归档之前的期照常计入")
                .contains("a.archived_at IS NULL OR a.archived_at &gt; p.period_end");
    }

    @Test
    void 不许再出现裸的归档过滤() throws IOException {
        // 裸写法一回来,历史就又会被归档动作抹掉。这条盯的是"别退回去"。
        assertThat(sql().lines()
                .map(String::strip)
                .filter(l -> l.equals("AND a.archived_at IS NULL"))
                .toList())
                .as("裸 `AND a.archived_at IS NULL` 会抹掉归档账户的全部历史")
                .isEmpty();
    }

    @Test
    void 时间语义只挂在非includeArchived分支下() throws IOException {
        // includeArchived=true 的调用方(如账户管理页)本来就要看全部,不该被时间语义限制
        String s = sql();
        int cond = s.indexOf("!f.includeArchived");
        int filt = s.indexOf("a.archived_at IS NULL OR a.archived_at &gt; p.period_end");
        assertThat(cond).isGreaterThan(0);
        assertThat(filt).as("时间语义过滤应在 !includeArchived 分支内").isGreaterThan(cond);
    }
}
