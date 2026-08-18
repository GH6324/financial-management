package com.family.finance.service.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.17.2 · 密钥掩码护栏。
 *
 * <p>页面上要露几个字符是<b>用户要的</b>:手上常有多把 key(不同账号 / 不同额度),
 * 只说"已配置"没法确认当前跑的是哪一把,于是每次都只能整条重贴。
 * 但露多少是安全边界 —— 这条测试钉住"够辨认、不足以拼出密钥"。</p>
 */
class SecretMaskTest {

    @Test
    void typical_key_shows_head_and_tail_only() {
        String masked = FamilyConfigService.maskSecret("sk-5dd1234567890abcdeff5e6");
        assertThat(masked).startsWith("sk-5dd").endsWith("f5e6").contains("••••••");
        // 中段一个字符都不许漏
        assertThat(masked).doesNotContain("1234567890");
    }

    /** 露出来的字符数必须是固定的 10 位(头 6 + 尾 4),不随密钥长度增长。 */
    @Test
    void revealed_length_does_not_grow_with_key_length() {
        String shortKey = FamilyConfigService.maskSecret("sk-" + "a".repeat(20));
        String longKey  = FamilyConfigService.maskSecret("sk-" + "a".repeat(200));
        assertThat(shortKey.replace("•", "")).hasSize(10);
        assertThat(longKey.replace("•", "")).hasSize(10);
    }

    /** 太短的密钥无法安全打码 → 全打码,不给"看着像露了一半"的错觉。 */
    @Test
    void short_secrets_are_fully_masked() {
        assertThat(FamilyConfigService.maskSecret("sk-123")).doesNotContain("sk").matches("•+");
        assertThat(FamilyConfigService.maskSecret("123456789012")).doesNotContain("1");   // 恰好 12 位:全打码
        assertThat(FamilyConfigService.maskSecret("1234567890123")).startsWith("123456"); // 13 位才开始露
    }

    @Test
    void blank_stays_blank() {
        assertThat(FamilyConfigService.maskSecret(null)).isEmpty();
        assertThat(FamilyConfigService.maskSecret("")).isEmpty();
        assertThat(FamilyConfigService.maskSecret("   ")).isEmpty();
    }
}
