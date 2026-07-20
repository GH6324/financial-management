package com.family.finance.service.review;

import com.family.finance.calc.review.AttributionEngine;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.lens.AssetClass;
import com.family.finance.domain.member.Member;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactSlice;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * v1.2 · 归因装配(FactSlice → AttributionEngine 输入 + 账户级标签 + 12 期趋势)· tech-design v1.2 §1。
 * 维度集 = 账户 / 资产类型 / 成员 / 平台 / 币种 / 账户类型(行业不做:持仓账户账户级行业恒空,见 PRD 修订)。
 */
@Service
@RequiredArgsConstructor
public class AttributionService {

    public static final Map<String, String> DIMS = new LinkedHashMap<>() {{
        put("acct", "账户"); put("assetClass", "资产类型"); put("owner", "成员");
        put("platform", "平台"); put("currency", "币种"); put("type", "账户类型");
    }};

    private final AccountMapper accountMapper;
    private final MemberMapper memberMapper;

    /** anchor 期归因(rows = slice 中 anchor 期各账户事实;delta/human/opening 由调用方按现有 KPI 口径给) */
    public AttributionEngine.Result attribute(long familyId, List<AccountPeriodFact> anchorRows,
                                              BigDecimal delta, BigDecimal humanEarned, BigDecimal opening) {
        Map<Long, Map<String, String>> labels = accountLabels(familyId);
        List<AttributionEngine.AcctInput> inputs = new ArrayList<>();
        for (AccountPeriodFact f : anchorRows) {
            if (f.accountType() == AccountType.LOAN) continue;   // 负债不进钱赚(与 lens 口径一致)
            inputs.add(new AttributionEngine.AcctInput(
                    f.accountId(), f.accountName(), f.accountCurrency(),
                    f.periodPnlBase(), f.periodPnlOrig(),
                    f.endBalanceBase(), f.endBalanceOrig(),
                    f.previousEndBalanceBase(), f.previousEndBalanceOrig(),
                    labels.getOrDefault(f.accountId(), Map.of())));
        }
        return AttributionEngine.attribute(inputs, delta, humanEarned, opening);
    }

    /** 近 N 期「钱赚」按维度分组(时间升序;组值 = Σ periodPnlBase;LOAN 剔除) */
    public List<AttributionEngine.TrendRow> trend(long familyId, FactSlice slice, String dimKey, int lastN) {
        Map<Long, Map<String, String>> labels = accountLabels(familyId);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yy-MM");
        Map<Long, List<AccountPeriodFact>> byPeriod = slice.byPeriod();
        List<Long> pids = slice.periodIds();
        List<AttributionEngine.TrendRow> out = new ArrayList<>();
        for (Long pid : pids.subList(Math.max(0, pids.size() - lastN), pids.size())) {
            Map<String, BigDecimal> group = new LinkedHashMap<>();
            String label = "";
            for (AccountPeriodFact f : byPeriod.getOrDefault(pid, List.of())) {
                if (f.accountType() == AccountType.LOAN) continue;
                if (f.periodStart() != null) label = f.periodStart().format(fmt);
                BigDecimal pnl = f.periodPnlBase() == null ? BigDecimal.ZERO : f.periodPnlBase();
                if (pnl.signum() == 0) continue;
                String key = "acct".equals(dimKey) ? f.accountName()
                        : labels.getOrDefault(f.accountId(), Map.of()).get(dimKey);
                group.merge(key == null ? "未分类" : key, pnl, BigDecimal::add);
            }
            out.add(new AttributionEngine.TrendRow(label, group));
        }
        return out;
    }

    /** 账户级维度标签(与 lens 同源口径) */
    private Map<Long, Map<String, String>> accountLabels(long familyId) {
        Map<Long, String> memberName = memberMapper.findActiveByFamily(familyId).stream()
                .collect(Collectors.toMap(Member::getId, Member::getDisplayName));
        Map<Long, Map<String, String>> out = new HashMap<>();
        for (Account a : accountMapper.findActiveByFamily(familyId)) {
            Map<String, String> m = new HashMap<>();
            AssetClass cls = AssetClass.fromName(a.getAssetClass());
            if (cls == null) cls = AssetClass.defaultFor(a.getType(), a.getProductCategoryCode());
            m.put("assetClass", cls == null ? null : cls.getLabel());
            m.put("owner", a.getPrimaryOwnerMemberId() == null ? "共同"
                    : memberName.getOrDefault(a.getPrimaryOwnerMemberId(), "成员#" + a.getPrimaryOwnerMemberId()));
            m.put("platform", a.getPlatformTag() == null || a.getPlatformTag().isBlank() ? null : a.getPlatformTag());
            m.put("currency", a.getCurrency());
            m.put("type", a.getType().getLabel());
            out.put(a.getId(), m);
        }
        return out;
    }
}
