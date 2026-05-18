package com.family.finance.domain.notify;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v0.4.14 FR-63c · 家庭级短信通知配置(短信平台 aksk + 签名/模板)。
 *
 * <p>私密红线:本类所有 sms* 字段(尤其 accessKeySecret)绝不进 PromptBuilder /
 * 任何 LLM prompt / audit_log 明文 / 前端明文回显。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FamilyNotifyConfig {
    private Long familyId;
    private boolean smsEnabled;
    /** 短信渠道供应商 code · 目前仅 aliyun */
    private String smsProvider;
    /** 私密 · 阿里云 AccessKeyId */
    private String smsAccessKeyId;
    /** 私密 · 阿里云 AccessKeySecret · 绝不出现在日志/prompt/前端明文 */
    private String smsAccessKeySecret;
    /** 运营商报备的实名短信签名(如「家庭账房」) */
    private String smsSignName;
    /** 运营商报备的短信模板 code */
    private String smsTemplateCode;
    private LocalDateTime updatedAt;

    /** 短信可用 = 开关开 + aksk + 签名 + 模板都已配 */
    public boolean smsUsable() {
        return smsEnabled
            && notBlank(smsAccessKeyId) && notBlank(smsAccessKeySecret)
            && notBlank(smsSignName) && notBlank(smsTemplateCode);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
