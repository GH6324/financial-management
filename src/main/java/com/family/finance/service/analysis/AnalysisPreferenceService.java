package com.family.finance.service.analysis;

import com.family.finance.repository.AnalysisPreferenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * v1.27 · 分析偏好:家里人写给 AI 的几句话(PRD §3.5)。
 *
 * <p><b>改不了数字、也越不过规矩</b>(FR-852):只进提示词,且放在材料与规矩之后、带固定的包裹说明
 * (见 {@link AnalysisPromptBlocks#preferences})。它不是规则引擎 —— 不改任何计算、阈值、卡片亮不亮。</p>
 *
 * <p>只有两条写入路径,都是用户点的:分析设置页「加一条」、超级 Agent 确认卡上点「记住」。
 * AI 自己存不了;外部 AI 客户端(只读口令)连提议都做不到(tech-design v1.27 选型六)。</p>
 */
@Service
@RequiredArgsConstructor
public class AnalysisPreferenceService {

    public static final int MAX_CHARS = 200;
    public static final int MAX_COUNT = 10;
    public static final String SOURCE_SETTINGS = "SETTINGS";
    public static final String SOURCE_AGENT = "AGENT";

    private final AnalysisPreferenceMapper mapper;

    public List<AnalysisPreferenceMapper.Row> list(long familyId) {
        return mapper.findByFamily(familyId);
    }

    /** 启用中的原文(按添加顺序)—— 进提示词前还要过真名映射,见 {@link AnalysisPromptBlocks} */
    public List<String> enabledTexts(long familyId) {
        return mapper.findByFamily(familyId).stream()
                .filter(r -> Boolean.TRUE.equals(r.enabled))
                .map(r -> r.content)
                .toList();
    }

    public AnalysisPreferenceMapper.Row add(long familyId, long memberId, String text, String source) {
        String t = normalize(text);
        if (t.isEmpty()) throw new IllegalArgumentException("写点什么再保存");
        if (t.codePointCount(0, t.length()) > MAX_CHARS) {
            throw new IllegalArgumentException("每条最多 " + MAX_CHARS + " 个字");
        }
        if (mapper.countByFamily(familyId) >= MAX_COUNT) {
            throw new IllegalArgumentException("分析偏好最多 " + MAX_COUNT + " 条,先删掉一条不用的");
        }
        // 同一句话不存两遍(对话里说两次「记住」很常见)
        for (AnalysisPreferenceMapper.Row r : mapper.findByFamily(familyId)) {
            if (t.equals(r.content)) {
                if (!Boolean.TRUE.equals(r.enabled)) mapper.setEnabled(familyId, r.id, true);
                return r;
            }
        }
        AnalysisPreferenceMapper.Row row = new AnalysisPreferenceMapper.Row();
        row.familyId = familyId;
        row.content = t;
        row.source = SOURCE_AGENT.equals(source) ? SOURCE_AGENT : SOURCE_SETTINGS;
        row.createdByMemberId = memberId;
        mapper.insert(row);
        return row;
    }

    public void setEnabled(long familyId, long id, boolean enabled) {
        mapper.setEnabled(familyId, id, enabled);
    }

    public void delete(long familyId, long id) {
        mapper.delete(familyId, id);
    }

    /** 缓存指纹:启用中的原文变了,就是另一份结论(FR-848 / FR-853) */
    public static String fingerprint(List<String> enabledTexts) {
        if (enabledTexts == null || enabledTexts.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String s : enabledTexts) {
                md.update(s.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(String.join("\u0000", enabledTexts).hashCode());
        }
    }

    /** 换行、多余空白收成一个空格:提示词里一条就是一行 */
    static String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").strip();
    }
}
