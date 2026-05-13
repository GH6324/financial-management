package com.family.finance.repository;

import com.family.finance.domain.allocation.AllocationAnchor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * v0.4 FR-62a · allocation_anchor 静态预置表 mapper · 只读。
 */
@Mapper
public interface AllocationAnchorMapper {

    @Select("""
            SELECT code, display_name, cash_pct, invest_pct, property_pct, insurance_pct,
                   description, display_order
              FROM allocation_anchor
             ORDER BY display_order, code
            """)
    List<AllocationAnchor> findAll();

    @Select("""
            SELECT code, display_name, cash_pct, invest_pct, property_pct, insurance_pct,
                   description, display_order
              FROM allocation_anchor
             WHERE code = #{code}
            """)
    Optional<AllocationAnchor> findByCode(@Param("code") String code);
}
