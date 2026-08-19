package com.family.finance.domain.ledger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.18 · 流水来源枚举护栏。
 *
 * <p>这一列是<b>展示用的元信息</b>,不参与任何金额计算 —— 所以解析必须"永不抛异常":
 * 加了新来源、用户又回滚到老版本时,老代码读到不认识的值应该显示"来源未记录",
 * 而不是让整个流水页 500。</p>
 */
class LedgerSourceTest {

    @Test
    void unknown_means_not_recorded_not_manual() {
        // 语义边界:历史数据是 UNKNOWN,它【不等于】手动填报
        assertThat(LedgerSource.UNKNOWN.getLabel()).contains("未记录");
        assertThat(LedgerSource.UNKNOWN).isNotEqualTo(LedgerSource.MANUAL);
        assertThat(LedgerSource.UNKNOWN.isAutomatic()).isFalse();
        assertThat(LedgerSource.MANUAL.isAutomatic()).isFalse();
    }

    @Test
    void parse_never_throws_and_falls_back_to_unknown() {
        assertThat(LedgerSource.parse("SYNC_BROKER_FUTU")).isEqualTo(LedgerSource.SYNC_BROKER_FUTU);
        assertThat(LedgerSource.parse("sync_stock_api")).isEqualTo(LedgerSource.SYNC_STOCK_API);   // 大小写不敏感
        assertThat(LedgerSource.parse("  MANUAL  ")).isEqualTo(LedgerSource.MANUAL);              // 容忍空白
        // 认不出的一律 UNKNOWN,不抛异常(回滚到老版本时读到新值就是这种情况)
        assertThat(LedgerSource.parse("SOME_FUTURE_SOURCE")).isEqualTo(LedgerSource.UNKNOWN);
        assertThat(LedgerSource.parse("")).isEqualTo(LedgerSource.UNKNOWN);
        assertThat(LedgerSource.parse(null)).isEqualTo(LedgerSource.UNKNOWN);
    }

    @Test
    void automatic_covers_price_feeds_and_brokers_only() {
        assertThat(LedgerSource.SYNC_STOCK_API.isAutomatic()).isTrue();
        assertThat(LedgerSource.SYNC_METAL_API.isAutomatic()).isTrue();
        assertThat(LedgerSource.SYNC_CRYPTO_API.isAutomatic()).isTrue();
        assertThat(LedgerSource.SYNC_BROKER_FUTU.isAutomatic()).isTrue();
        assertThat(LedgerSource.SYNC_BROKER_TIGER.isAutomatic()).isTrue();
        // 这几个不算"自动拉数据":截图是人发起的,开账延续与系统联动是内部动作
        assertThat(LedgerSource.IMPORT_SCREENSHOT.isAutomatic()).isFalse();
        assertThat(LedgerSource.CARRIED_FORWARD.isAutomatic()).isFalse();
        assertThat(LedgerSource.SYSTEM_ADJUST.isAutomatic()).isFalse();
    }

    @Test
    void broker_vendor_maps_to_its_own_source() {
        assertThat(LedgerSource.ofBroker("FUTU")).isEqualTo(LedgerSource.SYNC_BROKER_FUTU);
        assertThat(LedgerSource.ofBroker("tiger")).isEqualTo(LedgerSource.SYNC_BROKER_TIGER);
        assertThat(LedgerSource.ofBroker("UNKNOWN_BROKER")).isEqualTo(LedgerSource.UNKNOWN);
        assertThat(LedgerSource.ofBroker(null)).isEqualTo(LedgerSource.UNKNOWN);
    }

    /** 每个来源都要有面向用户的中文名 + 分组(页面按分组上色),不许漏。 */
    @Test
    void every_source_has_label_and_group() {
        for (LedgerSource s : LedgerSource.values()) {
            assertThat(s.getLabel()).as(s.name() + " 的中文名").isNotBlank();
            assertThat(s.getGroup()).as(s.name() + " 的分组").isNotBlank();
        }
    }

    /** 面向用户的名字里不许出现技术词(项目既有原则:入口/标签文案不用 API / 接口这类词)。 */
    @Test
    void labels_avoid_technical_jargon() {
        for (LedgerSource s : LedgerSource.values()) {
            assertThat(s.getLabel().toLowerCase())
                    .as(s.name())
                    .doesNotContain("api").doesNotContain("sync").doesNotContain("cron");
        }
    }
}
