package com.family.finance.service.analysis;

import com.family.finance.domain.allocation.AnchorCode;
import com.family.finance.repository.AnalysisTemplateMapper;
import com.family.finance.service.analysis.AnalysisTemplate.Focus;
import com.family.finance.service.analysis.AnalysisTemplate.Stance;
import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * v1.27 · 分析模板:内置 5 个 + 我的模板(PRD §3.3 / §3.4)。
 *
 * <p>「基于它定制」= 把来源的设置<b>整份复制</b>成一行(tech-design v1.27 选型三 C):
 * 以后内置模板升级,用户的副本<b>逐字不变</b>(PRD §9 ⑥ · 单测守)。每家最多 {@value #MAX_CUSTOM} 个。</p>
 */
@Service
@RequiredArgsConstructor
public class AnalysisTemplateService {

    public static final int MAX_CUSTOM = 10;

    private final AnalysisTemplateMapper mapper;
    private final FamilyConfigService configService;

    /** 模板选择与分析设置用:内置 5 个在前,我的模板按创建顺序 */
    public List<AnalysisTemplate> list(long familyId) {
        List<AnalysisTemplate> out = new ArrayList<>(BuiltinTemplates.ALL);
        for (AnalysisTemplateMapper.Row r : mapper.findByFamily(familyId)) out.add(fromRow(r));
        return out;
    }

    public Optional<AnalysisTemplate> find(long familyId, String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        Optional<AnalysisTemplate> b = BuiltinTemplates.find(key);
        if (b.isPresent()) return b;
        Long id = customId(key);
        return id == null ? Optional.empty() : mapper.findById(familyId, id).map(AnalysisTemplateService::fromRow);
    }

    /** 这一次用哪个:请求里带了且存在 → 它;否则家庭默认 */
    public AnalysisTemplate resolve(long familyId, String requested) {
        return find(familyId, requested).orElseGet(() -> familyDefault(familyId));
    }

    /** 家里的默认模板;没设过 / 设的那个被删了 → 综合体检(FR-845) */
    public AnalysisTemplate familyDefault(long familyId) {
        String saved = configService.getString(familyId, FamilyConfigService.K_ANALYSIS_TEMPLATE_DEFAULT, "");
        return find(familyId, saved).orElse(BuiltinTemplates.general());
    }

    public void setFamilyDefault(long familyId, String key) {
        AnalysisTemplate t = find(familyId, key)
                .orElseThrow(() -> new IllegalArgumentException("没有这个模板"));
        configService.set(familyId, FamilyConfigService.K_ANALYSIS_TEMPLATE_DEFAULT, t.key());
    }

    /**
     * 基于某个模板建一份「我的模板」—— 完整副本,之后与来源再无关联。
     *
     * @return 新模板(已入库)
     */
    public AnalysisTemplate customize(long familyId, long memberId, AnalysisTemplate source, Draft draft) {
        if (mapper.countByFamily(familyId) >= MAX_CUSTOM) {
            throw new IllegalArgumentException("「我的模板」最多 " + MAX_CUSTOM + " 个,先删掉一个不用的");
        }
        AnalysisTemplateMapper.Row row = toRow(familyId, memberId, draft.validated());
        row.sourceKey = source == null ? null : source.key();
        row.sourceVersion = source == null ? null : source.version();
        mapper.insert(row);
        return find(familyId, BuiltinTemplates.CUSTOM_PREFIX + row.id).orElseThrow();
    }

    /** 编辑我的模板(内置模板不能改) */
    public AnalysisTemplate update(long familyId, long memberId, long id, Draft draft) {
        AnalysisTemplateMapper.Row row = toRow(familyId, memberId, draft.validated());
        row.id = id;
        if (mapper.update(row) == 0) throw new IllegalArgumentException("没有这个模板");
        return find(familyId, BuiltinTemplates.CUSTOM_PREFIX + id).orElseThrow();
    }

    /** 删我的模板;若它是家里的默认,默认回到综合体检 */
    public void delete(long familyId, long id) {
        String key = BuiltinTemplates.CUSTOM_PREFIX + id;
        mapper.delete(familyId, id);
        String saved = configService.getString(familyId, FamilyConfigService.K_ANALYSIS_TEMPLATE_DEFAULT, "");
        if (key.equals(saved)) {
            configService.set(familyId, FamilyConfigService.K_ANALYSIS_TEMPLATE_DEFAULT, BuiltinTemplates.GENERAL);
        }
    }

    /** 从某个模板预填一份草稿(定制页用)· 名字默认「我家的 X」 */
    public static Draft draftFrom(AnalysisTemplate t) {
        String name = t.builtin() ? "我家的" + t.name() : t.name();
        if (name.length() > AnalysisTemplate.MAX_NAME_CHARS) name = name.substring(0, AnalysisTemplate.MAX_NAME_CHARS);
        return new Draft(name, t.focus(), t.stance(), t.scope(), t.anchor(), t.extra());
    }

    // ───────────────────────── 转换 ─────────────────────────

    static AnalysisTemplate fromRow(AnalysisTemplateMapper.Row r) {
        return new AnalysisTemplate(
                BuiltinTemplates.CUSTOM_PREFIX + r.id, r.name, false, null,
                Focus.parseCsv(r.focus), Stance.parse(r.stance), ScopeKind.parse(r.scope),
                AnchorCode.isValid(r.anchor) ? r.anchor.toUpperCase() : null,
                r.extra == null || r.extra.isBlank() ? null : r.extra,
                r.version == null ? 1 : r.version, r.sourceKey, r.sourceVersion,
                r.updatedByMemberId, r.updatedAt);
    }

    private static AnalysisTemplateMapper.Row toRow(long familyId, long memberId, Draft d) {
        AnalysisTemplateMapper.Row row = new AnalysisTemplateMapper.Row();
        row.familyId = familyId;
        row.name = d.name();
        row.focus = Focus.toCsv(d.focus());
        row.stance = d.stance().name();
        row.scope = d.scope() == null ? null : d.scope().name();
        row.anchor = d.anchor();
        row.extra = d.extra();
        row.updatedByMemberId = memberId;
        return row;
    }

    private static Long customId(String key) {
        if (!key.startsWith(BuiltinTemplates.CUSTOM_PREFIX)) return null;
        try { return Long.valueOf(key.substring(BuiltinTemplates.CUSTOM_PREFIX.length())); }
        catch (NumberFormatException e) { return null; }
    }

    /** 定制页提交上来的设置 */
    public record Draft(String name, List<Focus> focus, Stance stance, ScopeKind scope, String anchor, String extra) {

        /** 校验 + 收口:名字必填、侧重 1–4、补充要求 ≤ 500 字、锚必须是已知的 */
        public Draft validated() {
            String n = name == null ? "" : name.trim();
            if (n.isEmpty()) throw new IllegalArgumentException("给模板起个名字");
            if (n.length() > AnalysisTemplate.MAX_NAME_CHARS) {
                throw new IllegalArgumentException("名字最多 " + AnalysisTemplate.MAX_NAME_CHARS + " 个字");
            }
            List<Focus> f = focus == null ? List.of() : focus.stream().distinct().toList();
            if (f.isEmpty() || f.size() > AnalysisTemplate.MAX_FOCUS) {
                throw new IllegalArgumentException("侧重要选 1 到 " + AnalysisTemplate.MAX_FOCUS + " 个");
            }
            String x = extra == null ? null : extra.strip();
            if (x != null && x.isEmpty()) x = null;
            if (x != null && x.codePointCount(0, x.length()) > AnalysisTemplate.MAX_EXTRA_CHARS) {
                throw new IllegalArgumentException("补充要求最多 " + AnalysisTemplate.MAX_EXTRA_CHARS + " 个字");
            }
            String a = anchor == null || anchor.isBlank() ? null : anchor.trim().toUpperCase();
            if (a != null && !AnchorCode.isValid(a)) throw new IllegalArgumentException("没有这个配置锚");
            return new Draft(n, f, stance == null ? Stance.FOLLOW : stance, scope, a, x);
        }
    }
}
