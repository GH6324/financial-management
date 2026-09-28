package com.family.finance.service.analysis;

import com.family.finance.factview.FactSlice;
import com.family.finance.service.FamilyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * v1.27 · 把「这一次 AI 分析用什么范围、什么模板、哪些偏好」解析成一个 {@link AnalysisContext}。
 *
 * <p>范围的优先级:<b>模板固定了范围 → 用模板的</b>(选「投资组合体检」本身就是一个明确的选择);
 * 否则用页面上的范围(`?scope=` 或家庭默认)。页脚会写出这次用的是哪个,用户看得见。</p>
 */
@Service
@RequiredArgsConstructor
public class AnalysisContextService {

    private final AnalysisScopeService scopeService;
    private final AnalysisTemplateService templateService;
    private final AnalysisPreferenceService preferenceService;
    private final FamilyService familyService;

    /**
     * @param pageScope   页面上正在用的范围(已解析);null = 按 `requestedScope` / 家庭默认解析
     * @param requestedScope `?scope=`(pageScope 为 null 时才看)
     * @param requestedTemplate `?tpl=`;空 = 家里的默认模板
     * @param slice       页面已加载的切片(算名字与占比);null = 自己加载
     */
    public AnalysisContext resolve(long familyId, AnalysisScope pageScope, String requestedScope,
                                   String requestedTemplate, FactSlice slice) {
        AnalysisTemplate t = templateService.resolve(familyId, requestedTemplate);
        AnalysisScope scope = t.scope() != null
                ? scopeService.exactly(familyId, t.scope(), slice)
                : (pageScope != null ? pageScope : scopeService.resolve(familyId, requestedScope, slice));
        String stance = AnalysisPromptBlocks.riskAppetiteLabel(familyService.require(familyId).getRiskAppetite());
        String sourceName = t.builtin() ? null
                : BuiltinTemplates.find(t.sourceKey()).map(AnalysisTemplate::name).orElse(null);
        return new AnalysisContext(scope, t, preferenceService.enabledTexts(familyId), stance, sourceName);
    }

    /** 超级 Agent 用:家里的默认范围 + 默认模板 + 偏好 */
    public AnalysisContext familyDefaults(long familyId) {
        return resolve(familyId, null, null, null, null);
    }

    /** 只要偏好(复盘 / 透视 / 目标 / 单账户诊断) */
    public java.util.List<String> preferences(long familyId) {
        return preferenceService.enabledTexts(familyId);
    }
}
