package com.family.finance.service.broker.ibkr;

import com.family.finance.domain.broker.BrokerLink;
import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IBKR 客户端 · v1.26。
 *
 * <p>守:同一口令关联了多个账房账户,一轮只取一次报表(FR-758 · 不撞限流)· 选错 / 没选账户时说清楚 ·
 * 没配口令时说清楚去哪配。</p>
 */
class IbkrFlexClientTest {

    FamilyConfigService config;
    AtomicInteger fetches;
    IbkrFlexClient client;

    @BeforeEach
    void setUp() throws Exception {
        config = mock(FamilyConfigService.class);
        when(config.getString(anyLong(), anyString(), anyString())).thenReturn("");
        when(config.getString(anyLong(), eq(FamilyConfigService.K_BROKER_IBKR_TOKEN), anyString())).thenReturn("123456789012345678901234");
        when(config.getString(anyLong(), eq(FamilyConfigService.K_BROKER_IBKR_QUERY), anyString())).thenReturn("1045872");
        fetches = new AtomicInteger();
        String xml = IbkrFlexParserTest.sample();
        IbkrFlexHttp fake = new IbkrFlexHttp(null, "", "UA", ms -> { }, 0, 0) {
            @Override public String fetchStatementXml(String token, String queryId) {
                fetches.incrementAndGet();
                return xml;
            }
        };
        client = new IbkrFlexClient(config, "test", base -> fake);
    }

    static BrokerLink link(String acct) {
        BrokerLink l = new BrokerLink();
        l.setBrokerAccountId(acct);
        return l;
    }

    @Test
    void 两个账房账户共用一个口令_一轮只取一次报表() {
        client.fetch(1L, link("U1234521"));
        client.fetch(1L, link("U7658830"));
        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    void 测试连接强制取新的_并列出找到的账户() {
        client.fetch(1L, link("U1234521"));
        var r = client.testConnection(1L, null);
        assertThat(fetches.get()).isEqualTo(2);
        assertThat(r.summary()).contains("找到 2 个账户").contains("U•••4521").contains("U•••8830")
                .doesNotContain("U1234521");   // 页面上一律打码
    }

    @Test
    void 报表里有两个账户却没选_说清楚要选() {
        assertThatThrownBy(() -> client.fetch(1L, link(null)))
                .isInstanceOf(IbkrFlexException.class).hasMessageContaining("要选定是哪一个");
    }

    @Test
    void 选的账户不在报表里_说清楚() {
        assertThatThrownBy(() -> client.fetch(1L, link("U0000001")))
                .isInstanceOf(IbkrFlexException.class).hasMessageContaining("报表里没有账户");
    }

    @Test
    void 没配口令_说清楚去哪配() {
        when(config.getString(anyLong(), eq(FamilyConfigService.K_BROKER_IBKR_TOKEN), anyString())).thenReturn("");
        assertThatThrownBy(() -> client.fetch(1L, link("U1234521")))
                .isInstanceOf(IbkrFlexException.class).hasMessageContaining("管理 → 券商同步");
    }

    @Test
    void 到期天数_没填是null_填了算天数() {
        assertThat(client.daysToExpiry(1L, LocalDate.of(2026, 9, 24))).isNull();
        when(config.getString(anyLong(), eq(FamilyConfigService.K_BROKER_IBKR_EXPIRES), anyString())).thenReturn("2026-10-03");
        assertThat(client.daysToExpiry(1L, LocalDate.of(2026, 9, 24))).isEqualTo(9L);
    }

    @Test
    void 账号打码() {
        assertThat(IbkrFlexClient.mask("U1234521")).isEqualTo("U•••4521");
        assertThat(IbkrFlexClient.mask("U12")).isEqualTo("•••");
    }
}
