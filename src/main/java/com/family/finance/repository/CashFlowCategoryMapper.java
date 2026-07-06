package com.family.finance.repository;

import com.family.finance.domain.flow.CashFlowCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface CashFlowCategoryMapper {

    @Select("""
            SELECT code, display_name, kind, sort_order, account_type
              FROM cash_flow_category
             ORDER BY sort_order, code
            """)
    List<CashFlowCategory> listOrdered();

    /** v0.12 · 收入类目(含 account_type 绑定)· 供收入侧下拉 + 联动校验。 */
    @Select("""
            SELECT code, display_name, kind, sort_order, account_type
              FROM cash_flow_category
             WHERE kind = 'INCOME'
             ORDER BY sort_order, code
            """)
    List<CashFlowCategory> listIncomeOrdered();

    @Select("""
            SELECT code, display_name, kind, sort_order, account_type
              FROM cash_flow_category
             WHERE code = #{code}
            """)
    Optional<CashFlowCategory> findByCode(@Param("code") String code);
}
