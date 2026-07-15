package com.family.finance.repository;

import com.family.finance.domain.insurance.InsurancePolicy;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

/**
 * v0.17 · 保险保单登记旁表 · 1:1 account · 纯展示(net worth 只认 snapshot,不读本表)。
 * 注解式(与 {@link AccountMapper} 同风格)· 依赖 mapUnderscoreToCamelCase。
 */
@Mapper
public interface InsurancePolicyMapper {

    @Select("""
            SELECT account_id, insurance_kind, insurer, policy_no, policy_holder, insured_person,
                   coverage_amount, premium_amount, premium_frequency,
                   premium_terms_total, premium_terms_paid,
                   policy_effective_date, policy_maturity_date
              FROM account_insurance_policy
             WHERE account_id = #{accountId}
            """)
    Optional<InsurancePolicy> findByAccount(@Param("accountId") long accountId);

    /** 插入或整行覆盖(account_id 为 PK)· 建/编账户时调用 */
    @Update("""
            INSERT INTO account_insurance_policy (
                account_id, insurance_kind, insurer, policy_no, policy_holder, insured_person,
                coverage_amount, premium_amount, premium_frequency,
                premium_terms_total, premium_terms_paid,
                policy_effective_date, policy_maturity_date
            ) VALUES (
                #{accountId}, #{insuranceKind}, #{insurer}, #{policyNo}, #{policyHolder}, #{insuredPerson},
                #{coverageAmount}, #{premiumAmount}, #{premiumFrequency},
                #{premiumTermsTotal}, #{premiumTermsPaid},
                #{policyEffectiveDate}, #{policyMaturityDate}
            )
            ON DUPLICATE KEY UPDATE
                insurance_kind = VALUES(insurance_kind),
                insurer = VALUES(insurer),
                policy_no = VALUES(policy_no),
                policy_holder = VALUES(policy_holder),
                insured_person = VALUES(insured_person),
                coverage_amount = VALUES(coverage_amount),
                premium_amount = VALUES(premium_amount),
                premium_frequency = VALUES(premium_frequency),
                premium_terms_total = VALUES(premium_terms_total),
                premium_terms_paid = VALUES(premium_terms_paid),
                policy_effective_date = VALUES(policy_effective_date),
                policy_maturity_date = VALUES(policy_maturity_date)
            """)
    void upsert(InsurancePolicy policy);

    @Delete("DELETE FROM account_insurance_policy WHERE account_id = #{accountId}")
    void deleteByAccount(@Param("accountId") long accountId);
}
