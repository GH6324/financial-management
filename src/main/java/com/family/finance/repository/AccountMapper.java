package com.family.finance.repository;

import com.family.finance.domain.account.Account;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface AccountMapper {

    @Select("""
            SELECT id, family_id, template_id, display_name, type, currency,
                   primary_owner_member_id, default_payment_source_account_id,
                   display_order, archived_at, created_at, updated_at,
                   product_category_code, risk_level_override, loan_kind, annual_rate_pct, expected_return_pct
              FROM account
             WHERE id = #{id}
            """)
    Optional<Account> findById(@Param("id") long id);

    @Select("""
            SELECT id, family_id, template_id, display_name, type, currency,
                   primary_owner_member_id, default_payment_source_account_id,
                   display_order, archived_at, created_at, updated_at,
                   product_category_code, risk_level_override, loan_kind, annual_rate_pct, expected_return_pct
              FROM account
             WHERE family_id = #{familyId}
             ORDER BY archived_at IS NOT NULL, display_order, id
            """)
    List<Account> findAllByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT id, family_id, template_id, display_name, type, currency,
                   primary_owner_member_id, default_payment_source_account_id,
                   display_order, archived_at, created_at, updated_at,
                   product_category_code, risk_level_override, loan_kind, annual_rate_pct, expected_return_pct
              FROM account
             WHERE family_id = #{familyId}
               AND archived_at IS NULL
             ORDER BY display_order, id
            """)
    List<Account> findActiveByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT COUNT(*)
              FROM account
             WHERE family_id = #{familyId}
               AND archived_at IS NULL
            """)
    int countActiveByFamily(@Param("familyId") long familyId);

    @Insert("""
            INSERT INTO account (
                family_id, template_id, display_name, type, currency,
                primary_owner_member_id, default_payment_source_account_id, display_order,
                product_category_code, risk_level_override, loan_kind, annual_rate_pct, expected_return_pct
            ) VALUES (
                #{familyId}, #{templateId}, #{displayName}, #{type}, #{currency},
                #{primaryOwnerMemberId}, #{defaultPaymentSourceAccountId}, #{displayOrder},
                #{productCategoryCode}, #{riskLevelOverride}, #{loanKind}, #{annualRatePct}, #{expectedReturnPct}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Account account);

    @Update("""
            UPDATE account
               SET template_id = #{templateId},
                   display_name = #{displayName},
                   type = #{type},
                   currency = #{currency},
                   primary_owner_member_id = #{primaryOwnerMemberId},
                   default_payment_source_account_id = #{defaultPaymentSourceAccountId},
                   display_order = #{displayOrder},
                   product_category_code = #{productCategoryCode},
                   risk_level_override = #{riskLevelOverride},
                   loan_kind = #{loanKind},
                   annual_rate_pct = #{annualRatePct},
                   expected_return_pct = #{expectedReturnPct}
             WHERE id = #{id}
               AND family_id = #{familyId}
            """)
    int update(Account account);

    @Update("""
            UPDATE account
               SET archived_at = NOW(3)
             WHERE id = #{id}
               AND family_id = #{familyId}
               AND archived_at IS NULL
            """)
    int archive(@Param("familyId") long familyId, @Param("id") long id);

    @Update("""
            UPDATE account
               SET archived_at = NULL
             WHERE id = #{id}
               AND family_id = #{familyId}
            """)
    int restore(@Param("familyId") long familyId, @Param("id") long id);
}
