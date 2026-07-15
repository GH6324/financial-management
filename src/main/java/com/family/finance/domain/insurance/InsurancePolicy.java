package com.family.finance.domain.insurance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * v0.17 · 保险保单登记(旁表 account_insurance_policy · 1:1 account)· issue #6。
 *
 * <p><b>纯展示</b>:任何估值 / 净资产 / 配置引擎都<b>不读</b>本对象(净资产只认手填现金价值 snapshot)。
 * 所有字段可空,留空即在详情页隐藏。为什么用旁表而非 account 加列:11 个冷、保险专属、几乎全 NULL
 * 的字段不该污染 account 热表(列表/填报/仪表盘每次查),旁表仅在详情/编辑页 lazy 加载。见 tech-design/v0.17 决策 2。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsurancePolicy {
    /** 主键 = account.id(1:1) */
    private Long accountId;
    /** 子类型 · InsuranceSubType.name() · 展示 + 分组 */
    private String insuranceKind;
    /** 承保公司 */
    private String insurer;
    /** 保单号 */
    private String policyNo;
    /** 投保人(自填) */
    private String policyHolder;
    /** 被保人(自填) */
    private String insuredPerson;
    /** 保额(身故 / 满期) */
    private BigDecimal coverageAmount;
    /** 每期保费 */
    private BigDecimal premiumAmount;
    /** 缴费周期 · SINGLE 趸交 / ANNUAL 年缴 / MONTHLY 月缴 */
    private String premiumFrequency;
    /** 总缴费期数 */
    private Integer premiumTermsTotal;
    /** 已缴期数 */
    private Integer premiumTermsPaid;
    /** 生效日 */
    private LocalDate policyEffectiveDate;
    /** 满期 / 领取日 */
    private LocalDate policyMaturityDate;

    /** 是否有任何已登记内容(决定详情页是否显示保单段) */
    public boolean hasAnyField() {
        return insuranceKind != null || insurer != null || policyNo != null
            || policyHolder != null || insuredPerson != null || coverageAmount != null
            || premiumAmount != null || premiumFrequency != null
            || premiumTermsTotal != null || premiumTermsPaid != null
            || policyEffectiveDate != null || policyMaturityDate != null;
    }

    /** 累计已缴保费 = 每期保费 × 已缴期数(纯登记数字,不算收益/IRR)· 任一缺失返 null */
    public BigDecimal paidPremiumTotal() {
        if (premiumAmount == null || premiumTermsPaid == null) return null;
        return premiumAmount.multiply(BigDecimal.valueOf(premiumTermsPaid));
    }

    /** 子类型中文 label · 模板用 */
    public String kindLabel() {
        return com.family.finance.domain.account.InsuranceSubType.labelOf(insuranceKind);
    }

    /** 缴费周期中文 · 模板用 */
    public String frequencyLabel() {
        if (premiumFrequency == null) return "";
        return switch (premiumFrequency) {
            case "SINGLE" -> "趸交";
            case "ANNUAL" -> "年缴";
            case "MONTHLY" -> "月缴";
            default -> premiumFrequency;
        };
    }
}
