package com.family.finance.service.ask;

import com.family.finance.calc.lens.Position;
import com.family.finance.domain.ask.AskScope;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.analysis.AnalysisScopeService;
import com.family.finance.service.ask.tools.CapabilitiesTool;
import com.family.finance.service.ask.tools.PivotTool;
import com.family.finance.service.lens.LensQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.27 FR-856 · 「只给汇总」的口令拿不到任何账户名(验收 #11)。
 * 口子在「账户组」维:没分组的账户,这一维的取值就是账户名。
 */
class AggregateNoNamesTest {

    static Position pos(long acc, String name) {
        return new Position(acc, null, name, name, new BigDecimal("100"), "现金", "低", "流动", "CNY", "成员A",
                "现金类", "招商银行", null, "中国", null, "自己盯", null, null, null, null, null, name);
    }

    @Test
    void capabilitiesHidesGroupValuesForAggregateTokens() {
        LensQueryService lens = mock(LensQueryService.class);
        when(lens.positions(anyLong())).thenReturn(List.of(pos(1, "张三的工资卡"), pos(2, "家里的金库")));
        PeriodMapper periods = mock(PeriodMapper.class);
        AnalysisScopeService scopes = mock(AnalysisScopeService.class);
        var tool = new CapabilitiesTool(lens, periods, scopes);
        String agg = tool.execute(1L, Map.of(), AskScope.AGGREGATE).data().toString();
        assertThat(agg).doesNotContain("张三的工资卡").doesNotContain("家里的金库");
        String detail = tool.execute(1L, Map.of(), AskScope.DETAIL).data().toString();
        assertThat(detail).contains("家里的金库");
    }

    @Test
    void pivotRejectsGroupDimensionForAggregateTokens() {
        LensQueryService lens = mock(LensQueryService.class);
        when(lens.positions(anyLong())).thenReturn(List.of(pos(1, "张三的工资卡")));
        var tool = new PivotTool(lens, mock(AnalysisScopeService.class), null, null, null);
        assertThatThrownBy(() -> tool.execute(1L, Map.of("rows", List.of("group")), AskScope.AGGREGATE))
                .hasMessageContaining("只给汇总");
        assertThatThrownBy(() -> tool.execute(1L, Map.of("rows", List.of("platform"),
                "filters", Map.of("group", List.of("张三的工资卡"))), AskScope.AGGREGATE))
                .hasMessageContaining("只给汇总");
    }
}
