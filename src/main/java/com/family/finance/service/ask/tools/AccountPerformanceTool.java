package com.family.finance.service.ask.tools;

import com.family.finance.domain.ask.AskScope;
import com.family.finance.domain.family.Family;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.ask.AskTool;
import com.family.finance.service.ask.AskToolResult;
import com.family.finance.service.member.MemberDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.19 · 账户级收益表:XIRR / 累计损益 / 最大回撤 / 持有期数 / 预实。
 *
 * <p><b>这是唯一需要 {@link AskScope#DETAIL} 的工具</b> —— 它会返回<b>账户名</b>。
 * 「只给汇总」的凭据拿不到它:大多数问题(钱在哪些平台、房产占比、应急金够几个月)
 * 靠聚合就能答,不需要把账户名交出去。范围越小,万一泄露损失越小。</p>
 *
 * <p>成员真名走<b>现有脱敏</b>(代号),与既有六处 AI 同一份实现 ——
 * 即使数据被读走,也读不到「谁」。</p>
 */
@Component
@RequiredArgsConstructor
public class AccountPerformanceTool implements AskTool {

    /** 账户数再多也不至于几百个;给个上限防止一次吐太多 token */
    private static final int MAX_ROWS = 60;

    private final FactViewService factViewService;
    private final FamilyService familyService;
    private final PeriodMapper periodMapper;
    private final MemberDirectory memberDirectory;

    @Override public String name() { return "account_performance"; }

    @Override
    public String description() {
        return "每个账户的表现:现值、本期损益、累计损益、累计净投入、收益率(XIRR)、最大回撤、占比。"
             + "回答「哪个账户真在赚钱 / 谁拖后腿」用它。需要「含账户明细」范围的凭据。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of("type", "object",
                "properties", Map.of("period",
                        Map.of("type", "string", "description", "账期 yyyy-MM;不传用当前期")),
                "required", List.of());
    }

    /** 会返回账户名 → 需要 detail */
    @Override public AskScope requiredScope() { return AskScope.DETAIL; }

    @Override
    public AskToolResult execute(long familyId, Map<String, Object> args) {
        Family family = familyService.require(familyId);
        List<Period> all = periodMapper.findAllByFamily(familyId).stream()
                .filter(p -> p.getPeriodStart() != null)
                .sorted(Comparator.comparing(Period::getPeriodStart))
                .toList();
        if (all.isEmpty()) return AskToolResult.failed(name(), "这个家庭还没有任何账期");

        String want = args.get("period") == null ? null : String.valueOf(args.get("period")).trim();
        Period anchor = null;
        if (want != null && !want.isBlank()) {
            for (Period p : all) {
                if (p.getPeriodStart().toString().startsWith(want)) { anchor = p; break; }
            }
            if (anchor == null) {
                throw new AskParamException("没有 " + want + " 这个账期",
                        Map.of("periods", all.stream()
                                .map(p -> p.getPeriodStart().toString().substring(0, 7)).toList()));
            }
        } else {
            anchor = PeriodSummaryTool.resolveDefaultPeriod(all);
        }

        LocalDate end = anchor.getPeriodStart();
        FactSlice slice = factViewService.load(new FactFilter(
                familyId, family.getPeriodType(), end.minusMonths(12), end, false, null,
                family.getBaseCurrency()));
        List<AccountPerformance> rows = factViewService.accountPerformance(slice);

        boolean inProgress = !"CLOSED".equals(String.valueOf(anchor.getStatus()));
        String label = end.toString().substring(0, 7);

        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (AccountPerformance a : rows) {
            if (n++ >= MAX_ROWS) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("account", a.accountName());
            m.put("currency", a.accountCurrency());
            m.put("currentValue", plain(a.currentValue()));
            m.put("latestPnl", plain(a.latestPnl()));
            m.put("cumPnl", plain(a.cumPnl()));
            m.put("netPrincipal", plain(a.netPrincipal()));
            m.put("xirr", plain(a.xirr()));
            m.put("maxDrawdownPct", plain(a.maxDrawdownPct()));
            m.put("sharePct", plain(a.sharePct()));
            m.put("monthsHeld", a.monthsHeld());
            out.add(m);
        }

        AskToolResult.Builder b = AskToolResult.of(name())
                .put("period", label)
                .put("inProgress", inProgress)
                .put("accounts", out)
                .put("note", "xirr 满 12 期为年化,不足 12 期是累计收益率 —— 讲的时候要说清是哪一种,"
                           + "不要把累计说成年化");

        if (rows.size() > MAX_ROWS) {
            b.put("truncated", Map.of("shown", MAX_ROWS, "total", rows.size()));
        }
        if (inProgress) {
            b.metaExtra("warning", label + " 还没关账,本期损益会随后续录入变化。");
        }
        return b.meta(anchor.getId(), label, inProgress, "factview.accountPerformance",
                family.getBaseCurrency()).build();
    }

    private static String plain(Object v) {
        return v == null ? null : (v instanceof BigDecimal bd ? bd.toPlainString() : String.valueOf(v));
    }
}
