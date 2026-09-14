package com.family.finance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * v1.21 FR-577 · 资金来源关键字 → 家庭账户(配一次管一年)。
 *
 * <p>和 {@link ExpenseMerchantRuleMapper} 是同一个形状但<b>另一张表</b>:
 * 一个回答「这笔是什么消费」,一个回答「这笔从哪个账户出的」。
 * 塞进一张表要加 kind 列,而两者的键空间(商户名 / 资金来源)也不一样 ——
 * 分开两张小表比一张带类型的表好读。</p>
 */
@Mapper
public interface ExpenseAccountRuleMapper {

    /** 列清单 · 普通字符串前后留空格(文本块会拼出 SELECTid) */
    String COLS = " id, family_id AS familyId, keyword, account_id AS accountId, created_at AS createdAt ";

    record Rule(Long id, Long familyId, String keyword, Long accountId,
                java.time.LocalDateTime createdAt) {}

    /** 长关键字优先 —— 「招商银行储蓄卡」应该赢过「招商」,排序在这里定死,别让调用方自己排 */
    @Select("SELECT " + COLS + " FROM expense_account_rule WHERE family_id = #{familyId}"
          + " ORDER BY CHAR_LENGTH(keyword) DESC, id")
    List<Rule> findByFamily(@Param("familyId") long familyId);

    @Insert("""
            INSERT INTO expense_account_rule (family_id, keyword, account_id)
            VALUES (#{familyId}, #{keyword}, #{accountId})
            ON DUPLICATE KEY UPDATE account_id = VALUES(account_id)
            """)
    int upsert(@Param("familyId") long familyId,
               @Param("keyword") String keyword, @Param("accountId") long accountId);

    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM expense_account_rule WHERE id = #{id} AND family_id = #{familyId}")
    int delete(@Param("familyId") long familyId, @Param("id") long id);
}
