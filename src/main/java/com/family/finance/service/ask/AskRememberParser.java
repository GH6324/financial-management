package com.family.finance.service.ask;

import com.family.finance.service.analysis.AnalysisPreferenceService;
import com.family.finance.service.analysis.BuiltinTemplates;

import java.util.Optional;
import java.util.regex.Matcher;

/**
 * v1.27 · 超级 Agent 的「提议记住」标记(PRD FR-854 / FR-849 · tech-design v1.27 选型六)。
 *
 * <pre>
 * {{remember:原文}}                          → 记成一条分析偏好?
 * {{remember:原文|STEADY}}                   → 再给「切到「稳健守护」」「基于当前模板定制」
 * {{remember:原文||exclude=自住房}}           → 再给「把自住房标成不参与配置分析 →」
 * </pre>
 *
 * <p>为什么是正文标记而不是一个工具:工具表会原样暴露给外部 MCP 客户端(只读口令),
 * 放一个写工具进去,只读口令就不再只读;AI 自己也能调它把偏好存了 —— 违反「只有用户点了才算」。
 * 标记只是一段文字,真正的保存是用户在确认卡上点的一次站内表单提交。</p>
 *
 * <p>模型的输出是不可信输入:模板 key 只认内置的五个;账户名交给调用方去本家账户里对,对不上就丢。</p>
 */
public final class AskRememberParser {

    private AskRememberParser() {}

    /**
     * @param text        建议记住的原文(已去掉换行,截到偏好的长度上限)
     * @param templateKey 说的是分析角度 / 立场时,模型建议的内置模板(null = 没有)
     * @param excludeName 说的是「别算某个资产」时,那个资产的名字(null = 没有;"" = 说了排除但没点名)
     */
    public record Proposal(String text, String templateKey, String excludeName) {
        public boolean suggestsTemplate() { return templateKey != null; }
        public boolean suggestsExclude() { return excludeName != null; }
        public String templateName() {
            return BuiltinTemplates.find(templateKey).map(t -> t.name()).orElse(null);
        }
    }

    /** 正文里第一个「提议记住」标记;一轮只出一张卡 —— 一次问三件事的确认卡没人看得完 */
    public static Optional<Proposal> first(String body) {
        if (body == null || body.isEmpty()) return Optional.empty();
        Matcher m = AskCitationRenderer.REMEMBER.matcher(body);
        return m.find() ? parse(m.group(1)) : Optional.empty();
    }

    /** 标记里面那一段(不含花括号)→ 提议;原文为空 → 没有 */
    public static Optional<Proposal> parse(String inner) {
        if (inner == null) return Optional.empty();
        String[] parts = inner.split("\\|", -1);
        String text = parts[0].replaceAll("[\\r\\n\\t]+", " ").strip();
        if (text.isEmpty()) return Optional.empty();
        if (text.codePointCount(0, text.length()) > AnalysisPreferenceService.MAX_CHARS) {
            text = text.substring(0, text.offsetByCodePoints(0, AnalysisPreferenceService.MAX_CHARS));
        }
        String tpl = null;
        if (parts.length > 1 && !parts[1].isBlank()) {
            tpl = BuiltinTemplates.find(parts[1].strip()).map(t -> t.key()).orElse(null);
        }
        String exclude = null;
        if (parts.length > 2) {
            String p = parts[2].strip();
            if (p.equals("exclude")) exclude = "";
            else if (p.startsWith("exclude=")) exclude = p.substring("exclude=".length()).strip();
        }
        return Optional.of(new Proposal(text, tpl, exclude));
    }
}
