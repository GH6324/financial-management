package com.family.finance.repository;

import com.family.finance.factview.FactBaseRow;
import com.family.finance.factview.FactFilter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FactMapper {
    // v0.8 BUG-FIX(v08-CCY-INV-2):baseCurrency 入参,fx_to_base 经本位币三角换算(支持视图币种为第三币种)
    List<FactBaseRow> queryBase(@Param("f") FactFilter filter, @Param("baseCurrency") String baseCurrency);

    /**
     * v1.6.30 · 窗口内**已定稿**期 id(升序)· v1.23 判据扩大并改名。
     *
     * <p>queryBase 不过滤 period.status(存量指标需要看到进行中的期),所以收益类指标要另外知道
     * 哪些期的数据可信 —— 否则会把半填的 OPEN 期当终值。见 FactSlice#settledPeriodIds。</p>
     *
     * <p><b>v1.23 起「已定稿」= CLOSED 或「已自然结束且填报完成」</b>。
     * 原来只认 CLOSED,双活跃窗口下补录期没关就被判成不可信,收益类指标集体锚回上上期。
     * 改名是刻意的:{@code closed} 只覆盖前一种,{@code settled} 两种都覆盖 ——
     * 名字对不上语义是下一个人踩坑的起点。</p>
     */
    List<Long> findSettledPeriodIds(@Param("f") FactFilter filter);
}
