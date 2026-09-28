package com.family.finance.service.checkup.rule;

import com.family.finance.service.checkup.AccountDiagnose;
import com.family.finance.service.checkup.FamilyDiagnose;

import java.math.BigDecimal;
import java.util.List;

/**
 * 规则上下文 · 装载所有事实数据(由程序计算),规则只读不改
 *
 * 账户级规则:account 非 null,family 非 null(允许跨账户对比)
 * 家庭级规则:account = null,family + accounts 全集
 *
 * @param avgMonthlyExpense 家庭近 12 月月均支出(本位币),用于流动性月数计算
 */
public record RuleContext(
        AccountDiagnose account,
        FamilyDiagnose family,
        List<AccountDiagnose> accounts,
        BigDecimal avgMonthlyExpense
) {
    public boolean isAccountScope() {
        return account != null;
    }

    public boolean isFamilyScope() {
        return account == null && family != null;
    }

    /** v1.27 · 占比类规则用的分析范围(随家庭诊断一起算好;没有就是全部资产) */
    public com.family.finance.service.analysis.AnalysisScope analysisScope() {
        return family == null || family.scope() == null
                ? com.family.finance.service.analysis.AnalysisScope.all() : family.scope();
    }

    /** v1.27 · 规则文案里的分母:「总资产」/「可调整的资产」/「金融资产」—— 全部资产时与 v1.26 逐字相同 */
    public String ratioDenominator() {
        var s = analysisScope();
        return s.isAll() ? "总资产" : s.kind().getLabel();
    }

    /**
     * v1.27 · 规则文案末尾的「(不含 自住房、车)」(PRD §9 ⑧):拿掉房子之后可调部分的占比上升,
     * 可能新亮一条「单一类型过半」—— 这是对的,但不说「不含 X」,用户只会看到「标了一下,警告更多了」。
     */
    public String scopeNote() {
        var s = analysisScope();
        if (s.isAll() || s.excludedLabel().isBlank()) return "";
        return "(不含 " + s.excludedLabel().replace(" · ", "、") + ")";
    }

    public static RuleContext forAccount(AccountDiagnose account, FamilyDiagnose family,
                                         List<AccountDiagnose> accounts, BigDecimal avgMonthlyExpense) {
        return new RuleContext(account, family, accounts, avgMonthlyExpense);
    }

    public static RuleContext forFamily(FamilyDiagnose family, List<AccountDiagnose> accounts,
                                        BigDecimal avgMonthlyExpense) {
        return new RuleContext(null, family, accounts, avgMonthlyExpense);
    }
}
