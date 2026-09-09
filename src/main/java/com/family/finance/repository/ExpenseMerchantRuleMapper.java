package com.family.finance.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * v1.21 · 商户关键字 → 类目。
 *
 * <p>为什么需要它:<b>微信账单没有消费分类列</b>(只有「商户消费/转账/红包」这种交易类型),
 * 所以只能靠商户名/商品说明里的关键字映射。配一次管一年 —— 「美团」→ 餐饮美食 之后不用再管。</p>
 */
@Mapper
public interface ExpenseMerchantRuleMapper {

    record Rule(Long id, String keyword, Long categoryId, Integer hitCount) {}

    @Insert("""
            INSERT INTO expense_merchant_rule (family_id, keyword, category_id)
            VALUES (#{familyId}, #{keyword}, #{categoryId})
            ON DUPLICATE KEY UPDATE category_id = VALUES(category_id)
            """)
    int upsert(@Param("familyId") long familyId, @Param("keyword") String keyword,
               @Param("categoryId") long categoryId);

    @Select("""
            SELECT id, keyword, category_id AS categoryId, hit_count AS hitCount
              FROM expense_merchant_rule WHERE family_id = #{familyId}
             ORDER BY LENGTH(keyword) DESC, id
            """)
    List<Rule> findByFamily(@Param("familyId") long familyId);

    @Update("UPDATE expense_merchant_rule SET hit_count = hit_count + #{n} WHERE id = #{id}")
    int addHits(@Param("id") long id, @Param("n") int n);

    @Delete("DELETE FROM expense_merchant_rule WHERE id = #{id} AND family_id = #{familyId}")
    int delete(@Param("familyId") long familyId, @Param("id") long id);
}
