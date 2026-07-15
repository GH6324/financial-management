package com.family.finance.service.lens;

import com.family.finance.calc.lens.LensRegistry;
import com.family.finance.repository.LensBoardMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import java.util.Map;

/**
 * v1.1 · lens 前端元数据装配(dims/measures/boards JSON)。
 * 透视主体内嵌仪表盘 + /lens 直链壳两处共用(评审修订),避免 controller 重复。
 */
@Service
@RequiredArgsConstructor
public class LensMetaService {

    private final LensBoardMapper lensBoardMapper;
    private final ObjectMapper objectMapper;

    public void addMeta(long familyId, Model model) {
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
