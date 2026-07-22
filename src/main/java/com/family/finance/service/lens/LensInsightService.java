package com.family.finance.service.lens;

import com.family.finance.calc.lens.LensQuery;
import com.family.finance.calc.lens.LensRegistry;
import com.family.finance.calc.lens.PivotEngine;
import com.family.finance.calc.lens.Position;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.checkup.llm.LlmClient;
import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1.x · 透视 AI 洞察(2026-07-17 #7 · 同日评审重做 #4:总结≠洞察)。
 *
 * <p><b>洞察 = 工程先判信号,LLM 只把信号讲成人话</b>:本类先用 {@link PivotEngine} 算出当前视图
 * 的分布事实,再由工程规则对照<b>体检阈值</b>(集中度 / 高风险占比,管理页可配)判定异常信号
 * (过度集中 / 打标缺口 / 过度分散 / 碎片化 / 高风险超标);LLM 基于信号输出洞察与一条可执行动作,
 * <b>严禁计算</b>(feedback_llm_no_math)、不荐产品、不预测涨跌。无信号时如实说结构无显著异常。
 * <b>真名脱敏</b>:成员真名统一替换「成员A/B/…」再喂 LLM(隐私红线)。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LensInsightService {

    private final List<LlmClient> clients;
    private final LensQueryService lensQueryService;
    private final MemberMapper memberMapper;
    private final FamilyConfigService configService;

    public boolean available() {
        return clients.stream().anyMatch(LlmClient::available);
    }

    /** 解读结果:文本 + 出洞察的模型(前端展示) */
    public record Insight(String text, String vendor) {}

    /** 当前透视视图 → 洞察;LLM 全失败返回 null(前端提示稍后再试) */
    public Insight interpret(long familyId, LensQuery q) {
        List<Position> ps = lensQueryService.positions(familyId);
        // v1.3 · 洞察不止金额:固定按「金额 + 本期收益额 + 累计收益额 + 累计收益率」聚合当前维度,
        //   既判金额分布信号,也判收益信号(最大拖累 / 最赚 / 收益率最低)· 持仓级维度收益自动降级为 null。
        LensQuery iq = new LensQuery(q.rowsSafe(), java.util.List.of(),
                java.util.List.of("value", "latestPnl", "cumPnl", "cumReturn"), q.filtersSafe());
        PivotEngine.Result r = PivotEngine.pivot(ps, iq);
        String facts = buildFactsAndSignals(familyId, q, r);
        if (facts == null) return new Insight("当前范围没有头寸,无可解读。", "-");
        facts = anonymize(familyId, facts);

        String system = """
                你是家庭资产报表的洞察助手。下面是一份**已经计算好**的透视事实,末尾的「信号」
                是系统判定好的结论(结构异常 + 收益亮点/拖累,含数字)。你的任务不是复述分布,而是把信号讲成洞察。
                规则(必须遵守):
                1. 严禁做任何计算,只引用给出的数字;
                2. 输出 2-4 条,每条一行以「· 」开头,每条 ≤55 字,大白话;
                3. 每条洞察必须落在某个信号上(结构为什么不合理/什么风险,或哪块本期拖累/收益率差),按重要度排序;
                4. 最后一条固定是「值得做的一件事」:从信号推出的最优先动作(如 去打标 / 分散某平台 / 降高风险仓 / 复盘最大拖累那块);
                5. 「信号」为空时,如实说结构与收益均无显著异常,再给一句最值得留意的观察,不要硬编;
                6. 不推荐任何具体产品、不预测涨跌、不用黑话。
                只输出要点行,不要标题、开场白、markdown。""";
        String primary = configService.getString(familyId, FamilyConfigService.K_LLM_PRIMARY_VENDOR, "qwen");
        for (LlmClient client : com.family.finance.service.checkup.llm.LlmDiagnoseService.orderByPrimaryVendor(clients, primary)) {
            if (!client.available()) continue;
            try {
                String out = client.chat(system, facts);
                if (out != null && !out.isBlank()) return new Insight(out.trim(), client.vendor());
            } catch (Exception e) {
                log.warn("透视 AI 洞察 vendor={} 失败: {}", client.vendor(), e.toString());
            }
        }
        return null;
    }

    /** 分布事实 + 工程判定的异常信号(全部数字算好);返回 null = 无头寸 */
    private String buildFactsAndSignals(long familyId, LensQuery q, PivotEngine.Result r) {
        BigDecimal grand = r.grand().isEmpty() ? BigDecimal.ZERO : nz(r.grand().get(0));
        if (grand.signum() == 0 || r.rowKeys().isEmpty()) return null;
        String dimKey = q.rowsSafe().isEmpty() ? "" : q.rowsSafe().get(0);
        String rowDim = q.rowsSafe().stream().map(k -> LensRegistry.DIMENSIONS.get(k).label())
                .reduce((a, b) -> a + "×" + b).orElse("维度");

        record Slice(String name, BigDecimal v, BigDecimal pct) {}
        List<Slice> slices = new ArrayList<>();
        for (int i = 0; i < r.rowKeys().size(); i++) {
            BigDecimal v = nz(r.rowTotals().get(i).get(0));
            slices.add(new Slice(String.join("·", r.rowKeys().get(i)), v, pct(v, grand)));
        }
        slices.sort((a, b) -> b.v().compareTo(a.v()));

        StringBuilder sb = new StringBuilder();
        sb.append("视角: 按「").append(rowDim).append("」切分");
        if (q.filters() != null && !q.filters().isEmpty()) {
            sb.append(",筛选=");
            q.filters().forEach((k, v) -> sb.append(LensRegistry.DIMENSIONS.containsKey(k) ? LensRegistry.DIMENSIONS.get(k).label() : k)
                    .append(String.join("/", v)).append(" "));
        }
        sb.append("\n合计: ").append(money(grand)).append("\n分布:\n");
        BigDecimal unclassified = BigDecimal.ZERO;
        int tiny = 0;
        for (Slice sl : slices) {
            sb.append("  ").append(sl.name()).append(": ").append(money(sl.v())).append(" (").append(sl.pct()).append("%)\n");
            if (sl.name().contains("未分类")) unclassified = unclassified.add(sl.v());
            if (sl.pct().compareTo(BigDecimal.ONE) < 0) tiny++;
        }

        // ── 工程判定异常信号(对照管理页体检阈值) ──
        double concTh = switch (dimKey) {
            case "industry" -> configService.getDouble(familyId, FamilyConfigService.K_LENS_INDUSTRY_CONC, 0.40);
            case "platform" -> configService.getDouble(familyId, FamilyConfigService.K_LENS_PLATFORM_CONC, 0.40);
            default -> 0.50;
        };
        double highRiskTh = configService.getDouble(familyId, FamilyConfigService.K_CHECKUP_HIGH_RISK, 0.40);
        List<String> signals = new ArrayList<>();
        Slice top1 = slices.get(0);
        if (!top1.name().contains("未分类") && top1.pct().doubleValue() >= concTh * 100) {
            signals.add("过度集中: 最大一块「" + top1.name() + "」占 " + top1.pct() + "%,超过集中度阈值 " + Math.round(concTh * 100) + "%");
        }
        BigDecimal unclPct = pct(unclassified, grand);
        if (unclPct.doubleValue() >= 30) {
            signals.add("打标缺口: 「未分类」合计占 " + unclPct + "%(金额 " + money(unclassified) + "),这部分结构不可见");
        }
        if ("risk".equals(dimKey)) {
            for (Slice sl : slices) {
                if (sl.name().contains("高风险") && sl.pct().doubleValue() >= highRiskTh * 100) {
                    signals.add("高风险超标: 高风险占 " + sl.pct() + "%,超过体检阈值 " + Math.round(highRiskTh * 100) + "%");
                }
            }
        }
        BigDecimal top3 = slices.stream().limit(3).map(Slice::pct).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (slices.size() >= 8 && top3.doubleValue() < 50) {
            signals.add("过度分散: 切成 " + slices.size() + " 块且前 3 块合计仅 " + top3 + "%,管理成本高、单块难起作用");
        }
        if (tiny >= 3) {
            signals.add("碎片化: 有 " + tiny + " 块占比不足 1% 的零碎头寸,可考虑归并");
        }

        // ── v1.3 · 收益信号(持仓级维度收益不可精确归因则跳过 · latestPnl=1 / cumReturn=3) ──
        if (!r.holdingLevelSplit()) {
            record RSlice(String name, BigDecimal latest, BigDecimal cumR) {}
            List<RSlice> rs = new ArrayList<>();
            for (int i = 0; i < r.rowKeys().size(); i++) {
                rs.add(new RSlice(String.join("·", r.rowKeys().get(i)),
                        r.rowTotals().get(i).get(1), r.rowTotals().get(i).get(3)));
            }
            RSlice worst = rs.stream().filter(x -> x.latest() != null && x.latest().signum() < 0)
                    .min((a, b) -> a.latest().compareTo(b.latest())).orElse(null);
            if (worst != null) signals.add("本期最大拖累: 「" + worst.name() + "」本期亏 " + money(worst.latest()));
            RSlice best = rs.stream().filter(x -> x.latest() != null && x.latest().signum() > 0)
                    .max((a, b) -> a.latest().compareTo(b.latest())).orElse(null);
            if (best != null) signals.add("本期最赚: 「" + best.name() + "」本期赚 " + money(best.latest()));
            RSlice lowR = rs.stream().filter(x -> x.cumR() != null)
                    .min((a, b) -> a.cumR().compareTo(b.cumR())).orElse(null);
            if (lowR != null && lowR.cumR().signum() < 0)
                signals.add("累计收益率最低: 「" + lowR.name() + "」累计 " + lowR.cumR() + "%");
        }

        sb.append("信号(系统判定,含结构异常与收益亮点/拖累,共 ").append(signals.size()).append(" 条):\n");
        if (signals.isEmpty()) sb.append("  (无 · 本视角结构无显著异常)\n");
        else signals.forEach(sig -> sb.append("  - ").append(sig).append('\n'));
        return sb.toString();
    }

    /** 成员真名 → 成员A/B/…(主理人维值与账户名中出现的都替换) */
    private String anonymize(long familyId, String text) {
        Map<String, String> repl = new LinkedHashMap<>();
        char c = 'A';
        for (var m : memberMapper.findActiveByFamily(familyId)) {
            if (m.getDisplayName() != null && !m.getDisplayName().isBlank()) repl.put(m.getDisplayName(), "成员" + c++);
        }
        for (var e : repl.entrySet()) text = text.replace(e.getKey(), e.getValue());
        return text;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static BigDecimal pct(BigDecimal v, BigDecimal grand) {
        return v.multiply(BigDecimal.valueOf(100)).divide(grand, 1, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal v) {
        return "¥" + v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
