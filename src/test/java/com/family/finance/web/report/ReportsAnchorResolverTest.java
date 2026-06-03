package com.family.finance.web.report;

import com.family.finance.domain.period.Period;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v0.5.5 FR-94 · 报表锚定选择守护。
 *
 * <p>报表 = 已关账快照:优先「最近已关账(≤今天)」作锚 + closedSnapshot=true;
 * 无已关账期才退 open/latest 仅渲染外壳(closedSnapshot=false)。</p>
 */
class ReportsAnchorResolverTest {

    private static Period p(long id) { return Period.builder().id(id).build(); }

    @Test
    void picks_latest_closed_as_snapshot() {
        Period closed = p(10);
        var r = ReportsAnchorResolver.resolve(Optional.of(closed), Optional.of(p(20)), List.of(p(30)));
        assertThat(r.closedSnapshot()).isTrue();
        assertThat(r.anchor()).isSameAs(closed);
    }

    @Test
    void falls_back_to_open_when_no_closed() {
        Period open = p(20);
        var r = ReportsAnchorResolver.resolve(Optional.empty(), Optional.of(open), List.of(p(30)));
        assertThat(r.closedSnapshot()).isFalse();
        assertThat(r.anchor()).isSameAs(open);
    }

    @Test
    void falls_back_to_latest_when_no_closed_no_open() {
        Period latest = p(30);
        var r = ReportsAnchorResolver.resolve(Optional.empty(), Optional.empty(), List.of(latest));
        assertThat(r.closedSnapshot()).isFalse();
        assertThat(r.anchor()).isSameAs(latest);
    }

    @Test
    void throws_when_no_period_at_all() {
        assertThatThrownBy(() ->
                ReportsAnchorResolver.resolve(Optional.empty(), Optional.empty(), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
