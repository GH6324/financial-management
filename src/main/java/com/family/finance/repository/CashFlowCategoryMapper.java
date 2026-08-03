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

    /**
     * v0.12 · 收入类目(含 account_type 绑定)· 供收入侧下拉 + 联动校验。
     * 排除 stock_sell(卖出回款不算收入 · V35 停用)· 保留历史行只为 cash_flow 外键完整。
     */
    @Select("""
            SELECT code, display_name, kind, sort_order, account_type
              FROM cash_flow_category
             WHERE kind = 'INCOME'
               AND code <> 'stock_sell'
             ORDER BY sort_order, code
            """)
    List<CashFlowCategory> listIncomeOrdered();

    /**
     * v1.8 FR-270a · 支出类目下拉。停在「性质」这一层(日常开支 / 还贷 / 利息支出 / 转账给亲属),
     * 不预置消费品类 —— 想细分的家庭自己在管理页加。
     * 排除 {@code cash_adjust}(kind=BOTH):那是余额对账用的现金调整,不是家庭支出。
     */
    @Select("""
            SELECT code, display_name, kind, sort_order, account_type
              FROM cash_flow_category
             WHERE kind = 'EXPENSE'
             ORDER BY sort_order, code
            """)
    List<CashFlowCategory> listExpenseOrdered();

    @Select("""
            SELECT code, display_name, kind, sort_order, account_type
              FROM cash_flow_category
             WHERE code = #{code}
            """)
    Optional<CashFlowCategory> findByCode(@Param("code") String code);
}
