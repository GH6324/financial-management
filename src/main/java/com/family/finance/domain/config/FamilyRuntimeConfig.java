package com.family.finance.domain.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v0.4.18 · family_runtime_config K-V 表行(详 prd/v0.4.md §22)。
 *
 * <p>泛通用 K-V · 类型转换在 {@link com.family.finance.service.config.FamilyConfigService}
 * 内做(boolean / int / double / String)。
 *
 * <p>私密红线:含 LLM API key 等敏感字段时,value_text 是明文 ·
 * 同 family_notify_config 的 sms_access_key_secret 处理(MySQL 权限严控)·
 * 绝不入 LLM prompt / audit_log 明文 / 前端明文回显。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FamilyRuntimeConfig {
    private Long familyId;
    private String keyName;
    private String valueText;
    private LocalDateTime updatedAt;
}
