package com.family.finance.repository;

import com.family.finance.domain.notify.FamilyNotifyConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

/**
 * v0.4.14 FR-63c · family_notify_config CRUD。
 *
 * <p>私密红线:任何 sms* 字段绝不进 PromptBuilder / LLM prompt / audit_log 明文。
 */
@Mapper
public interface FamilyNotifyConfigMapper {

    @Select("""
            SELECT family_id, sms_enabled, sms_provider,
                   sms_access_key_id, sms_access_key_secret,
                   sms_sign_name, sms_template_code, updated_at
              FROM family_notify_config
             WHERE family_id = #{familyId}
            """)
    Optional<FamilyNotifyConfig> findByFamily(@Param("familyId") long familyId);

    /**
     * upsert · 首次配置 INSERT,后续 ON DUPLICATE KEY UPDATE。
     * family_id 为 PK,保证一家一行。
     */
    @Update("""
            INSERT INTO family_notify_config
                (family_id, sms_enabled, sms_provider,
                 sms_access_key_id, sms_access_key_secret,
                 sms_sign_name, sms_template_code)
            VALUES
                (#{familyId}, #{smsEnabled}, #{smsProvider},
                 #{smsAccessKeyId}, #{smsAccessKeySecret},
                 #{smsSignName}, #{smsTemplateCode})
            ON DUPLICATE KEY UPDATE
                sms_enabled = VALUES(sms_enabled),
                sms_provider = VALUES(sms_provider),
                sms_access_key_id = VALUES(sms_access_key_id),
                sms_access_key_secret = VALUES(sms_access_key_secret),
                sms_sign_name = VALUES(sms_sign_name),
                sms_template_code = VALUES(sms_template_code)
            """)
    int upsert(FamilyNotifyConfig config);
}
