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
            SELECT ip.account_id, ip.insurance_kind, ip.insurer, ip.policy_no,
                   ip.policy_holder, ip.insured_person,
                   ip.coverage_amount, ip.premium_amount, ip.premium_frequency,
                   ip.premium_terms_total, ip.premium_terms_paid,
                   ip.policy_effective_date, ip.policy_maturity_date
              FROM account_insurance_policy ip
              JOIN account a ON a.id = ip.account_id
             WHERE a.family_id = #{familyId}
               AND ip.account_id = #{accountId}
            """)
    Optional<InsurancePolicy> findByAccount(@Param("familyId") long familyId,
                                            @Param("accountId") long accountId);

    /** 插入或整行覆盖(account_id 为 PK)· 建/编账户时调用 */
    @Update("""
            INSERT INTO account_insurance_policy (
                account_id, insurance_kind, insurer, policy_no, policy_holder, insured_person,
                coverage_amount, premium_amount, premium_frequency,
                premium_terms_total, premium_terms_paid,
                policy_effective_date, policy_maturity_date
            )
            SELECT #{p.accountId}, #{p.insuranceKind}, #{p.insurer}, #{p.policyNo},
                   #{p.policyHolder}, #{p.insuredPerson},
                   #{p.coverageAmount}, #{p.premiumAmount}, #{p.premiumFrequency},
                   #{p.premiumTermsTotal}, #{p.premiumTermsPaid},
                   #{p.policyEffectiveDate}, #{p.policyMaturityDate}
              FROM account a
             WHERE a.id = #{p.accountId} AND a.family_id = #{familyId}
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
    int upsert(@Param("familyId") long familyId, @Param("p") InsurancePolicy policy);

    /** 带归属断言的写入 —— 业务代码一律用这个(ON DUPLICATE 让影响行数有 0/1/2 三种取值) */
    default void upsertOwned(long familyId, InsurancePolicy policy) {
        if (upsert(familyId, policy) < 1) {
            throw new IllegalStateException("保单归属校验不通过:账户 " + policy.getAccountId()
                    + " 不属于家庭 " + familyId);
        }
    }

    @Delete("DELETE ip FROM account_insurance_policy ip"
          + " JOIN account a ON a.id = ip.account_id"
          + " WHERE a.family_id = #{familyId} AND ip.account_id = #{accountId}")
    void deleteByAccount(@Param("familyId") long familyId, @Param("accountId") long accountId);
}
