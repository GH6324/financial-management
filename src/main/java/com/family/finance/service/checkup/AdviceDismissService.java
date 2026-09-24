package com.family.finance.service.checkup;

import com.family.finance.service.checkup.rule.Advice;
import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 体检「值得做的事」里被用户标成「不适用」的提醒。
 *
 * <h3>issue #22</h3>
 *
 * <p>每张提醒卡上都有一个「✕ 不适用」按钮,点了没反应 —— 它从 v0.2(2026-05)加上的那天起
 * 就调用一个<b>不存在的</b>前端函数 {@code advice.dismiss(…)},后端也从来没有对应的接口。
 * 浏览器报 {@code advice is not defined},页面上什么都不发生。四个多月没人发现,
 * 因为点了没反应的按钮不会报错给任何人看。</p>
 *
 * <h3>为什么要存下来,而不是在页面上藏一下</h3>
 *
 * <p>只在前端藏,刷新一下就回来了,家里另一个人在别的设备上也照样看得到 ——
 * 那等于没点。标成「不适用」是这一家人的判断,存在家庭级配置里。</p>
 *
 * <p>粒度是「规则 + 账户」:同一条规则在 A 账户不适用,不代表在 B 账户也不适用。
 * 家庭级规则没有账户,记作 {@code *}。</p>
 *
 * <p>没有加表:条目数量很少(规则总共二十来条),放在已有的家庭配置里就够,
 * 不需要为它做数据库迁移。{@code value_text} 是 512 字符,超出时丢最早标记的那些。</p>
 */
@Service
@RequiredArgsConstructor
public class AdviceDismissService {

    private static final int MAX_CHARS = 500;

    private final FamilyConfigService configService;

    static String keyOf(String ruleId, Long accountId) {
        return ruleId + "|" + (accountId == null ? "*" : accountId);
    }

    public Set<String> dismissed(long familyId) {
        String raw = configService.getString(familyId, FamilyConfigService.K_CHECKUP_ADVICE_DISMISSED, "");
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public void dismiss(long familyId, String ruleId, Long accountId) {
        if (ruleId == null || ruleId.isBlank() || ruleId.contains("|") || ruleId.contains("\n")) return;
        Set<String> s = dismissed(familyId);
        s.add(keyOf(ruleId.trim(), accountId));
        save(familyId, s);
    }

    public void restoreAll(long familyId, Long accountId) {
        Set<String> s = dismissed(familyId);
        if (accountId == null) {
            s.removeIf(k -> k.endsWith("|*"));                 // 家庭页的「恢复」只恢复家庭级的
        } else {
            s.removeIf(k -> k.endsWith("|" + accountId));      // 账户页只恢复这个账户的
        }
        save(familyId, s);
    }

    /** 过滤掉被标成不适用的 */
    public List<Advice> visible(long familyId, List<Advice> all) {
        if (all == null || all.isEmpty()) return all;
        Set<String> d = dismissed(familyId);
        if (d.isEmpty()) return all;
        return all.stream().filter(a -> !d.contains(keyOf(a.ruleId(), a.accountId()))).toList();
    }

    /** 这一批里被藏起来了几条(页面上说「已隐藏 N 条 · 恢复」) */
    public int hiddenCount(long familyId, List<Advice> all) {
        if (all == null || all.isEmpty()) return 0;
        return all.size() - visible(familyId, all).size();
    }

    private void save(long familyId, Set<String> keys) {
        // 超长时丢最早标记的(LinkedHashSet 保序,最早的在最前面)
        List<String> list = new java.util.ArrayList<>(keys);
        while (!list.isEmpty() && String.join("\n", list).length() > MAX_CHARS) list.remove(0);
        configService.set(familyId, FamilyConfigService.K_CHECKUP_ADVICE_DISMISSED, String.join("\n", list));
    }
}
