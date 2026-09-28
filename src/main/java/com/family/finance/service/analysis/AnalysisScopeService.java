package com.family.finance.service.analysis;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountType;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * v1.27 · 分析范围:有哪些选项、家里默认哪个、这一次用哪个(PRD FR-820 ~ FR-827)。
 *
 * <h3>三条规矩</h3>
 * <ul>
 *   <li><b>只给有区别的选项</b>(FR-820):没标记就没有「可调整」;没有房产类、其他类就没有「金融资产」;
 *       只剩「全部资产」时页面整个不出现切换 —— 与 v1.26 一致。</li>
 *   <li><b>家庭默认</b>(FR-822):存过且仍可选 → 用它;否则有标记 → 可调整,没有 → 全部。
 *       存过的值失效(比如标记全取消了)不报错,回落推导。</li>
 *   <li><b>名字与占比程序算好</b>:取自调用方给的切片的锚期,与页面上的 KPI 同一次加载。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AnalysisScopeService {

    private final AccountMapper accountMapper;
    private final FactViewService factViewService;
    private final FamilyConfigService configService;

    /** 选项固定顺序:与 preview 一致,最窄的在前 */
    private static final List<ScopeKind> ORDER = List.of(ScopeKind.ADJUSTABLE, ScopeKind.FINANCIAL, ScopeKind.ALL);

    /** 这一次用哪个范围:请求里带了且可选 → 它;否则家庭默认 */
    public AnalysisScope resolve(long familyId, String requested, FactSlice slice) {
        List<Account> accounts = accountMapper.findAllByFamily(familyId);
        ScopeKind want = ScopeKind.parse(requested);
        ScopeKind kind = want != null && available(want, accounts) ? want : familyDefault(familyId, accounts);
        return build(kind, accounts, sliceOrDefault(familyId, slice));
    }

    /**
     * 模板固定了范围时用:<b>就按这个种类</b>组装,不回落家庭默认。
     * 家里没有房产类、其他类时「金融资产」的排除集就是空的 —— 等同全部资产,这正是它该有的意思。
     */
    public AnalysisScope exactly(long familyId, ScopeKind kind, FactSlice slice) {
        return build(kind == null ? ScopeKind.ALL : kind, accountMapper.findAllByFamily(familyId),
                sliceOrDefault(familyId, slice));
    }

    /** 页面上可选的范围(已算好名字与占比);只有一个时页面不显示切换 */
    public List<AnalysisScope> options(long familyId, FactSlice slice) {
        List<Account> accounts = accountMapper.findAllByFamily(familyId);
        FactSlice s = sliceOrDefault(familyId, slice);
        List<AnalysisScope> out = new ArrayList<>();
        for (ScopeKind k : ORDER) {
            if (available(k, accounts)) out.add(build(k, accounts, s));
        }
        return out;
    }

    /** 家庭默认范围(FR-822) */
    public ScopeKind familyDefault(long familyId) {
        return familyDefault(familyId, accountMapper.findAllByFamily(familyId));
    }

    /** 分析设置 / 体检页「设为家里的默认」。存的是用户的明确选择 —— 选「全部资产」也存,之后不再被推导覆盖 */
    public void setFamilyDefault(long familyId, ScopeKind kind) {
        configService.set(familyId, FamilyConfigService.K_ANALYSIS_SCOPE_DEFAULT,
                (kind == null ? ScopeKind.ALL : kind).name());
    }

    /** 被标了「不参与配置分析」的在册账户(分析设置 ③ 与各页说明用) */
    public List<Account> markedAccounts(long familyId) {
        return accountMapper.findActiveByFamily(familyId).stream()
                .filter(Account::isAnalysisExcluded)
                .filter(a -> a.getType() != AccountType.LOAN)
                .toList();
    }

    // ───────────────────────── 内部 ─────────────────────────

    ScopeKind familyDefault(long familyId, List<Account> accounts) {
        ScopeKind saved = ScopeKind.parse(configService.getString(familyId,
                FamilyConfigService.K_ANALYSIS_SCOPE_DEFAULT, ""));
        if (saved != null && available(saved, accounts)) return saved;
        return available(ScopeKind.ADJUSTABLE, accounts) ? ScopeKind.ADJUSTABLE : ScopeKind.ALL;
    }

    /** 这个选项与「全部资产」有没有区别:看<b>在册</b>账户(归档的不算「家里有」) */
    static boolean available(ScopeKind kind, List<Account> accounts) {
        return switch (kind) {
            case ALL -> true;
            case ADJUSTABLE -> accounts.stream().anyMatch(a -> !a.isArchived() && marked(a));
            case FINANCIAL -> accounts.stream().anyMatch(a -> !a.isArchived() && nonFinancial(a.getType()));
        };
    }

    static boolean marked(Account a) {
        return a.isAnalysisExcluded() && a.getType() != null && a.getType() != AccountType.LOAN;
    }

    static boolean nonFinancial(AccountType t) {
        return t == AccountType.PROPERTY || t == AccountType.OTHER;
    }

    /**
     * 组装一个范围。排除集含<b>归档账户</b>(它们在历史期里有行,不排掉会让历史期的占比口径不一致);
     * 名字只列锚期有余额的(归档账户在锚期没有行,自然不出现)。
     */
    static AnalysisScope build(ScopeKind kind, List<Account> accounts, FactSlice slice) {
        if (kind == ScopeKind.ALL) {
            return AnalysisScope.all();
        }
        Set<Long> excluded = new HashSet<>();
        for (Account a : accounts) {
            boolean out = kind == ScopeKind.ADJUSTABLE ? marked(a) : nonFinancial(a.getType());
            if (out) excluded.add(a.getId());
        }
        List<String> types = new ArrayList<>();
        if (kind == ScopeKind.FINANCIAL) {
            if (accounts.stream().anyMatch(a -> !a.isArchived() && a.getType() == AccountType.PROPERTY)) types.add("房产类");
            if (accounts.stream().anyMatch(a -> !a.isArchived() && a.getType() == AccountType.OTHER)) types.add("其他类");
        }

        // 锚期资产行:算名字(按余额降序)、占比、是否为空
        Map<Long, BigDecimal> balance = new LinkedHashMap<>();
        Map<Long, String> names = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        BigDecimal inScope = BigDecimal.ZERO;
        if (slice != null && slice.lastPeriodId() != null) {
            for (AccountPeriodFact r : slice.rows()) {
                if (!Objects.equals(r.periodId(), slice.lastPeriodId())) continue;
                if (r.accountClass() != AccountClass.ASSET || r.endBalanceBase() == null) continue;
                BigDecimal v = r.endBalanceBase();
                if (v.signum() <= 0) continue;
                total = total.add(v);
                if (excluded.contains(r.accountId())) {
                    out = out.add(v);
                    balance.merge(r.accountId(), v, BigDecimal::add);
                    names.put(r.accountId(), r.accountName());
                } else {
                    inScope = inScope.add(v);
                }
            }
        }
        List<String> excludedNames = balance.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue(Comparator.reverseOrder()))
                .map(e -> names.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
        BigDecimal share = total.signum() == 0 ? null : out.divide(total, 6, RoundingMode.HALF_UP);
        boolean empty = !excluded.isEmpty() && total.signum() > 0 && inScope.signum() == 0;
        return new AnalysisScope(kind, excluded, excludedNames, types, share, empty);
    }

    private FactSlice sliceOrDefault(long familyId, FactSlice slice) {
        return slice != null ? slice : factViewService.loadDefault(familyId);
    }
}
