package com.family.finance.service.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.27.1 · 定制模板页的「范围」要说人话(维护者 2026-09-29:「你不想看不动产的分布就选 xxx」)。
 * 新加一个范围取值时,这里逼着它把「人话叫法」和「什么时候选它」一起写上。
 */
class ScopeKindPlainTest {

    @Test
    void 每个范围都有人话叫法和什么时候选它() {
        for (ScopeKind k : ScopeKind.values()) {
            assertThat(k.getPlainName()).as(k + " 的人话叫法").isNotBlank().isNotEqualTo(k.getLabel());
            assertThat(k.getWhenToUse()).as(k + " 什么时候选").isNotBlank();
        }
    }

    @Test
    void 不看房子和车_直接回答不想看不动产的人() {
        assertThat(ScopeKind.FINANCIAL.getPlainName()).contains("房子");
        assertThat(ScopeKind.FINANCIAL.getWhenToUse()).contains("不动产").contains("车");
    }

    @Test
    void 去掉标过的账户_告诉人去哪儿标() {
        assertThat(ScopeKind.ADJUSTABLE.getWhenToUse()).contains("不参与配置分析");
    }
}
