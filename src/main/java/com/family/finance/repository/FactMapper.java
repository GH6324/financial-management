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
}
