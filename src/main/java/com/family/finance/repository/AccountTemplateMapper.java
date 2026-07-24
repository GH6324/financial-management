package com.family.finance.repository;

import com.family.finance.domain.account.AccountTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface AccountTemplateMapper {

    @Select("""
            SELECT id, code, display_name, type, default_currency, icon, platform, sort_order, is_custom_slot AS custom_slot
              FROM account_template
             ORDER BY sort_order, id
            """)
    List<AccountTemplate> listOrdered();

    @Select("""
            SELECT id, code, display_name, type, default_currency, icon, platform, sort_order, is_custom_slot AS custom_slot
              FROM account_template
             WHERE id = #{id}
            """)
    Optional<AccountTemplate> findById(@Param("id") long id);
}
