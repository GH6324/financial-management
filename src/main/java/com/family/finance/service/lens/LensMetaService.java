package com.family.finance.service.lens;

import com.family.finance.calc.lens.LensRegistry;
import com.family.finance.repository.LensBoardMapper;
import com.family.finance.service.config.FamilyConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import java.util.Map;
import java.util.Set;

/**
 * v1.1 · lens 前端元数据装配(dims/measures/boards JSON + 旭日配色方案)。
 * 透视主体内嵌仪表盘 + /lens 直链壳两处共用(评审修订),避免 controller 重复。
 */
@Service
@RequiredArgsConstructor
public class LensMetaService {

    /** 旭日环级配色方案合法值(lens.js PALETTE_PLANS 同源)· 默认 D 莫兰迪 */
    public static final Set<String> PALETTE_PLANS = Set.of("A", "B", "C", "D", "E");
    public static final String PALETTE_DEFAULT = "D";

    private final LensBoardMapper lensBoardMapper;
    private final FamilyConfigService configService;
    private final LensInsightService lensInsightService;   // v1.1.x #7 · AI 解读按钮可用性
    private final ObjectMapper objectMapper;

    /** 当前生效的配色方案 key(脏值回落默认) */
    public String palette(long familyId) {
        String p = configService.getString(familyId, FamilyConfigService.K_LENS_PALETTE, PALETTE_DEFAULT);
        return PALETTE_PLANS.contains(p) ? p : PALETTE_DEFAULT;
    }

    public void addMeta(long familyId, Model model) {
        model.addAttribute("lensPalette", palette(familyId));
        model.addAttribute("lensAiAvailable", lensInsightService.available(familyId));
        try {
            model.addAttribute("dimsJson", objectMapper.writeValueAsString(
                    LensRegistry.DIMENSIONS.values().stream()
                            .map(d -> Map.of("key", d.key(), "label", d.label(), "holdingLevel", d.holdingLevel()))
                            .toList()));
            model.addAttribute("measuresJson", objectMapper.writeValueAsString(
                    LensRegistry.MEASURES.values().stream()
                            .map(m -> Map.of("key", m.key(), "label", m.label()))
                            .toList()));
            model.addAttribute("boardsJson", objectMapper.writeValueAsString(
                    lensBoardMapper.findByFamily(familyId).stream()
                            .map(b -> Map.of("id", b.getId(), "name", b.getName(), "spec", b.getSpecJson()))
                            .toList()));
        } catch (Exception e) {
            // 元数据装配失败不拖垮页面 · 前端空数组降级
            model.addAttribute("dimsJson", "[]");
            model.addAttribute("measuresJson", "[]");
            model.addAttribute("boardsJson", "[]");
        }
    }
}
