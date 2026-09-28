package com.family.finance.service.analysis;

import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * v1.27 FR-881 · 「分析角度不合适?换个模板,或基于它定制 →」只在第一次看到 AI 分析时出现,
 * 关掉就不再出现 —— <b>按人记</b>:一个人关掉,不打扰家里其他人(反过来也一样,别人关了你还能看到)。
 */
@Service
@RequiredArgsConstructor
public class AnalysisHintService {

    private final FamilyConfigService configService;

    public boolean dismissed(long familyId, long memberId) {
        return members(familyId).contains(String.valueOf(memberId));
    }

    public void dismiss(long familyId, long memberId) {
        Set<String> ids = members(familyId);
        if (ids.add(String.valueOf(memberId))) {
            configService.set(familyId, FamilyConfigService.K_ANALYSIS_HINT_DISMISSED, String.join(",", ids));
        }
    }

    private Set<String> members(long familyId) {
        String raw = configService.getString(familyId, FamilyConfigService.K_ANALYSIS_HINT_DISMISSED, "");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
