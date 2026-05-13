package com.family.finance.repository;

import com.family.finance.domain.category.ProductCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * 产品类目 mapper · v0.2 引入。
 *
 * v0.2 范围:**只读**(类目静态 V11 预置 + admin 微调走另一端点)。
 * 详见 TDD § 决策 4
 */
@Mapper
public interface ProductCategoryMapper {

    @Select("""
            SELECT code, display_name, risk_level, liquidity_class, benchmark_label, benchmark_pct,
                   applicable_types, description, display_order
              FROM product_category
             ORDER BY display_order, code
            """)
    List<ProductCategory> findAll();

    @Select("""
            SELECT code, display_name, risk_level, liquidity_class, benchmark_label, benchmark_pct,
                   applicable_types, description, display_order
              FROM product_category
             WHERE code = #{code}
            """)
    Optional<ProductCategory> findByCode(@Param("code") String code);
}
