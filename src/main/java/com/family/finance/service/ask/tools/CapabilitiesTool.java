package com.family.finance.service.ask.tools;

import com.family.finance.calc.lens.LensRegistry;
import com.family.finance.calc.lens.Position;
import com.family.finance.domain.ask.AskScope;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.ask.AskTool;
import com.family.finance.service.ask.AskToolResult;
import com.family.finance.service.lens.LensQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * v1.19 · 自省接口 —— <b>不让 agent 猜参数</b>。
 *
 * <p>agent 最大的浪费是猜:猜维度叫什么、猜账期格式、猜账户名。一次自省换来后面每一次
 * 精准查询,而且成本测算显示<b>「多几次盲查」比「一次自省」贵得多</b>
 * (单轮工具调用从 2 次涨到 8 次,成本是 6 倍)。</p>
 *
 * <p>所以系统提示词里明写:<b>拿不准维度或取值,先调这个,不要猜。</b></p>
 */
@Component
@RequiredArgsConstructor
public class CapabilitiesTool implements AskTool {

    private final LensQueryService lensQueryService;
    private final PeriodMapper periodMapper;

    @Override public String name() { return "capabilities"; }

    @Override
    public String description() {
        return "先调这个:列出你能用的全部维度、度量、每个维度的实际取值、可查账期。"
             + "拿不准参数时不要猜,调它。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override public AskScope requiredScope() { return AskScope.AGGREGATE; }

    @Override
    public AskToolResult execute(long familyId, Map<String, Object> args) {
        // 维度与度量:直接来自注册表 —— 加维度只在注册表登记一处,这里自动跟上
        List<Map<String, Object>> dims = new ArrayList<>();
        List<Position> positions = lensQueryService.positions(familyId);
        for (LensRegistry.Dimension d : LensRegistry.DIMENSIONS.values()) {
            // 每个维度的【实际取值】—— 光给维度名不够,agent 还是得猜取值怎么写
            Set<String> values = new LinkedHashSet<>();
            for (Position p : positions) {
                String v = d.extract().apply(p);
                if (v != null && !v.isBlank()) values.add(v);
            }
            dims.add(Map.of(
                    "key", d.key(),
                    "label", d.label(),
                    "holdingLevel", d.holdingLevel(),
                    "values", new ArrayList<>(values)));
        }

        List<Map<String, Object>> measures = LensRegistry.MEASURES.values().stream()
                .map(m -> Map.<String, Object>of("key", m.key(), "label", m.label()))
                .toList();

        // 账期表可能预建到很多年以后(beta 上排到 2041)。把【未来还没开始的期】排除掉 ——
        // 它们余额是结转来的、收支全零,数字上看不出异常,agent 会拿它当真话讲。
        java.time.LocalDate today = java.time.LocalDate.now();
        List<Period> periods = periodMapper.findAllByFamily(familyId);
        List<Map<String, Object>> periodList = periods.stream()
                .filter(p -> p.getPeriodStart() != null)
                .filter(p -> !p.getPeriodStart().isAfter(today))
                .sorted(Comparator.comparing(Period::getPeriodStart).reversed())
                .limit(24)
                .map(p -> Map.<String, Object>of(
                        "period", p.getPeriodStart().toString().substring(0, 7),
                        "status", String.valueOf(p.getStatus()),
                        "inProgress", !"CLOSED".equals(String.valueOf(p.getStatus()))))
                .toList();

        Period latest = periods.stream()
                .filter(p -> p.getPeriodStart() != null)
                .filter(p -> !p.getPeriodStart().isAfter(today))
                .max(Comparator.comparing(Period::getPeriodStart)).orElse(null);

        return AskToolResult.of(name())
                .put("dimensions", dims)
                .put("measures", measures)
                .put("periods", periodList)
                .put("notes", List.of(
                        "holdingLevel=true 的维度会把持仓账户拆开,该维度下收益类度量按持有口径、不可精确归因",
                        "period 格式 yyyy-MM,不传则用当前上下文账期",
                        "所有金额都已按视图币种换算,你不需要自己折算"))
                .meta(latest == null ? null : latest.getId(),
                      latest == null ? null : latest.getPeriodStart().toString().substring(0, 7),
                      latest != null && !"CLOSED".equals(String.valueOf(latest.getStatus())),
                      "ask.capabilities", null)
                .build();
    }
}
