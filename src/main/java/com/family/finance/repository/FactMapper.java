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
     * v1.6.30 · 窗口内已关账期 id(升序)。
     *
     * <p>queryBase 不过滤 period.status(存量指标需要看到进行中的期),所以收益类指标要另外知道
     * 哪些期真的关账了 —— 否则会把半填的 OPEN 期当终值。见 FactSlice#closedPeriodIds。</p>
     */
    List<Long> findClosedPeriodIds(@Param("f") FactFilter filter);
}
