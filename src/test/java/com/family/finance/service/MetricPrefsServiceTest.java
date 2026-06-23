package com.family.finance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** v0.8 FR-149/150 · 可配置指标集逻辑:默认集 / 必选兜底 / 勾选解析 / 上限裁剪 / 序列化往返。 */
class MetricPrefsServiceTest {

    private final MetricPrefsService svc = new MetricPrefsService(new ObjectMapper());

    @Test
    void nullPrefs_usesDefaultSet_withMandatory() {
        Set<String> acct = svc.enabled(null, "account");
        // 必选 + 默认项在,进阶项不在
        assertThat(acct).contains("current_value", "xirr", "cum_pnl", "mom_delta", "share_pct", "sparkline", "plan_actual");
        assertThat(acct).doesNotContain("twr", "max_drawdown", "yoy", "months_held");

        Set<String> fam = svc.enabled(null, "family");
        assertThat(fam).contains("net_worth", "total_assets", "total_liab", "savings_rate", "nw_mom");
        assertThat(fam).doesNotContain("nw_yoy");   // 默认关(需满 1 年)
    }

    @Test
    void customPrefs_keepsChosen_plusMandatory_dropsUnchosen() {
        // 只选了 twr(进阶),没选默认的 cum_pnl;必选 current_value 仍应被强制纳入
        String json = "{\"account\":[\"twr\"],\"family\":[]}";
        Set<String> acct = svc.enabled(json, "account");
        assertThat(acct).contains("current_value");   // 必选兜底
        assertThat(acct).contains("twr");              // 用户勾选
        assertThat(acct).doesNotContain("cum_pnl", "xirr");   // 默认项但未勾 → 不在
        // family 全不选 → 仍保留必选 net_worth
        assertThat(svc.enabled(json, "family")).containsExactly("net_worth");
    }

    @Test
    void badJson_fallsBackToDefault() {
        assertThat(svc.enabled("not-json{", "account")).contains("current_value", "cum_pnl");
    }

    @Test
    void enabledCapped_takesFirstNByCatalogOrder() {
        List<String> capped = svc.enabledCapped(null, "account", 3);
        assertThat(capped).hasSize(3);
        // 目录顺序前三:current_value / xirr / cum_pnl
        assertThat(capped).containsExactly("current_value", "xirr", "cum_pnl");
    }

    @Test
    void serialize_roundTrips() {
        String json = svc.serialize(List.of("net_worth"), List.of("current_value", "twr"));
        Set<String> acct = svc.enabled(json, "account");
        assertThat(acct).contains("current_value", "twr");
        assertThat(acct).doesNotContain("cum_pnl");   // 未勾且非必选
        assertThat(svc.enabled(json, "family")).containsExactly("net_worth");
    }
}
