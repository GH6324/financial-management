package com.family.finance.service.lens;

import com.family.finance.calc.lens.LensQuery;
import com.family.finance.calc.lens.LensRegistry;
import com.family.finance.calc.lens.PivotEngine;
import com.family.finance.calc.lens.Position;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.checkup.llm.LlmClient;
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
 * v1.1.x · 透视 AI 洞察解读(2026-07-17 评审 #7)。
 *
 * <p><b>LLM 严禁做数学</b>(feedback_llm_no_math):本类先用 {@link PivotEngine} 把当前 drill 视图
 * 的结构化事实全部算好(合计 / top 分布及占比 / 未分类率 / 集中度),LLM 只负责把数字解读成
 * 2-4 条家庭听得懂的观察 + 建议方向,不荐产品、不预测涨跌。
 * <b>真名脱敏</b>(隐私红线):成员真名(主理人维值 / 账户名里出现)统一替换为「成员A/B/…」再喂 LLM。
 * 全部 client 不可用 → available()=false,前端按钮降级隐藏。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LensInsightService {

    private final List<LlmClient> clients;
    private final LensQueryService lensQueryService;
    private final MemberMapper memberMapper;

    public boolean available() {
        return clients.stream().anyMatch(LlmClient::available);
    }

    /** 当前透视视图 → AI 解读文本(2-4 条要点);LLM 全失败返回 null(前端提示稍后再试) */
    public String interpret(long familyId, LensQuery q) {
        List<Position> ps = lensQueryService.positions(familyId);
        PivotEngine.Result r = PivotEngine.pivot(ps, q);
        String facts = buildFacts(q, r);
        if (facts == null) return "当前范围没有头寸,无可解读。";
        facts = anonymize(familyId, facts);

        String system = """
                你是家庭资产报表的解读助手。下面给你一份**已经计算好**的资产透视事实(JSON 风格文本)。
                规则(必须遵守):
                1. 严禁做任何计算(不要加减乘除、不要重新算占比),只引用给出的数字;
                2. 输出 2-4 条要点,每条一行,以「· 」开头;每条 ≤50 字,家庭成员听得懂的大白话;
                3. 先说结构上最显著的事实(谁最大 / 是否集中 / 未分类多不多),再给"值得看一眼"的方向;
                4. 不推荐任何具体产品、不预测涨跌、不使用专业黑话;
                5. 「未分类」占比高时提醒去打标,不要臆测未分类里是什么。
                只输出要点行,不要标题、不要开场白、不要 markdown 加粗。""";
        for (LlmClient client : clients) {
            if (!client.available()) continue;
            try {
                String out = client.chat(system, facts);
                if (out != null && !out.isBlank()) return out.trim();
            } catch (Exception e) {
                log.warn("透视 AI 解读 vendor={} 失败: {}", client.vendor(), e.toString());
            }
        }
        return null;
    }

    /** 工程侧算好全部数字(金额 · 占比%);返回 null = 无头寸 */
    private String buildFacts(LensQuery q, PivotEngine.Result r) {
        BigDecimal grand = r.grand().isEmpty() ? BigDecimal.ZERO : nz(r.grand().get(0));
        if (grand.signum() == 0 || r.rowKeys().isEmpty()) return null;
        String rowDim = q.rowsSafe().stream().map(k -> LensRegistry.DIMENSIONS.get(k).label())
                .reduce((a, b) -> a + "×" + b).orElse("维度");
        StringBuilder sb = new StringBuilder();
        sb.append("视角: 按「").append(rowDim).append("」切分");
        if (q.filters() != null && !q.filters().isEmpty()) {
            sb.append(",筛选=");
            q.filters().forEach((k, v) -> sb.append(LensRegistry.DIMENSIONS.containsKey(k) ? LensRegistry.DIMENSIONS.get(k).label() : k)
                    .append(String.join("/", v)).append(" "));
        }
        sb.append("\n合计: ").append(money(grand)).append("\n分布(全量 · 占比为合计的百分比):\n");
        record Slice(String name, BigDecimal v) {}
        List<Slice> slices = new ArrayList<>();
        for (int i = 0; i < r.rowKeys().size(); i++) {
            slices.add(new Slice(String.join("·", r.rowKeys().get(i)), nz(r.rowTotals().get(i).get(0))));
        }
        slices.sort((a, b) -> b.v().compareTo(a.v()));
        BigDecimal unclassified = BigDecimal.ZERO;
        for (Slice sl : slices) {
            BigDecimal pct = sl.v().multiply(BigDecimal.valueOf(100)).divide(grand, 1, RoundingMode.HALF_UP);
            sb.append("  ").append(sl.name()).append(": ").append(money(sl.v())).append(" (").append(pct).append("%)\n");
            if (sl.name().contains("未分类")) unclassified = unclassified.add(sl.v());
        }
        BigDecimal top1 = slices.get(0).v().multiply(BigDecimal.valueOf(100)).divide(grand, 1, RoundingMode.HALF_UP);
        sb.append("最大一块: ").append(slices.get(0).name()).append(" 占 ").append(top1).append("%\n");
        sb.append("未分类占比: ").append(unclassified.multiply(BigDecimal.valueOf(100)).divide(grand, 1, RoundingMode.HALF_UP)).append("%\n");
        return sb.toString();
    }

    /** 成员真名 → 成员A/B/…(按家庭成员表全量替换,含主理人维值与账户名中出现) */
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

    private static String money(BigDecimal v) {
        return "¥" + v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
