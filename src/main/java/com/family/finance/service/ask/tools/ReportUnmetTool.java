package com.family.finance.service.ask.tools;

import com.family.finance.domain.ask.AskScope;
import com.family.finance.repository.AskUnmetNeedMapper;
import com.family.finance.service.ask.AskTool;
import com.family.finance.service.ask.AskToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * v1.19 · 「我够不着」。
 *
 * <p>这是<b>唯一一个会写库的工具</b>,但它写的不是账目 —— 是一条产品反馈:
 * 「用户问了 X,我需要 Y 才能答,而 Y 现在没有」。</p>
 *
 * <p>为什么值得单独做一个工具:模型遇到查不到的东西,默认行为是<b>拿相近的数字凑</b>。
 * 给它一个正当的出口(记一笔、如实说没有),比在提示词里反复叮嘱「不要编」有效得多 ——
 * 它需要的是一个可以执行的动作,不是一句禁令。</p>
 *
 * <p>顺带,这些记录是下一版加什么接口的依据,比坐着猜用户要什么准。</p>
 */
@Component
@RequiredArgsConstructor
public class ReportUnmetTool implements AskTool {

    private static final int MAX_LEN = 512;

    private final AskUnmetNeedMapper mapper;

    @Override public String name() { return "report_unmet"; }

    @Override
    public String description() {
        return "当你发现用户的问题需要的数据现在拿不到时,调这个记一笔,然后如实告诉用户这一项看不到。"
             + "不要用别的数字代替。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "question", Map.of("type", "string", "description", "用户原本问的是什么"),
                        "needed", Map.of("type", "string", "description", "你需要什么数据才能回答")),
                "required", List.of("question"));
    }

    @Override public AskScope requiredScope() { return AskScope.AGGREGATE; }

    @Override
    public AskToolResult execute(long familyId, Map<String, Object> args) {
        String q = trim(args.get("question"));
        if (q == null || q.isBlank()) {
            throw new AskParamException("question 必填", Map.of("hint", "把用户原话填进 question"));
        }
        mapper.insert(familyId, q, trim(args.get("needed")));
        return AskToolResult.of(name())
                .put("recorded", true)
                .put("note", "已记下。现在请如实告诉用户这一项你看不到,并说明你能看到的是什么。")
                .summary("记下一条够不着:" + (q.length() > 18 ? q.substring(0, 18) + "…" : q))
                .meta(null, null, false, "ask.unmet", null)
                .build();
    }

    private static String trim(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.length() <= MAX_LEN ? s : s.substring(0, MAX_LEN);
    }
}
