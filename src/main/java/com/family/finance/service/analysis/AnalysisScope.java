package com.family.finance.service.analysis;

import com.family.finance.factview.FactSlice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * v1.27 · 一次分析用哪一组资产(PRD §3.2 · tech-design v1.27 三.2)。
 *
 * <p><b>只作用在「占比类」上</b>:配置 / 风险分布、配置类规则、配置锚、AI 的配置结论。
 * 净资产、收益、流动性不读它 —— 它们回答的是「有多少钱」,房子是真实拥有的,一分不能少。</p>
 *
 * @param kind          三个取值之一
 * @param excludedIds   不在范围里的账户(以排除为主语义;空 = 与全部资产相同)
 * @param excludedNames 被拿掉、且在锚期有余额的账户名(按余额从大到小)—— 页面小字与 AI 提示词用
 * @param excludedTypes 「金融资产」拿掉的是哪几类(「房产类」「其他类」),按家里实际有的写
 * @param excludedShare 被拿掉的合计 ÷ 总资产(0–1,程序算好);算不出来为 null
 * @param empty         范围里一个有余额的资产账户都不剩(FR-826)—— 占比类显示空态、AI 不调用,
 *                      <b>不许</b>退回全部资产
 */
public record AnalysisScope(
        ScopeKind kind,
        Set<Long> excludedIds,
        List<String> excludedNames,
        List<String> excludedTypes,
        BigDecimal excludedShare,
        boolean empty
) {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public AnalysisScope {
        excludedIds = excludedIds == null ? Set.of() : Set.copyOf(excludedIds);
        excludedNames = excludedNames == null ? List.of() : List.copyOf(excludedNames);
        excludedTypes = excludedTypes == null ? List.of() : List.copyOf(excludedTypes);
        kind = kind == null ? ScopeKind.ALL : kind;
    }

    /** 全部资产(现状)· 没有任何排除 */
    public static AnalysisScope all() {
        return new AnalysisScope(ScopeKind.ALL, Set.of(), List.of(), List.of(), BigDecimal.ZERO, false);
    }

    /** 与「全部资产」没有区别(没排除任何账户) */
    public boolean isAll() { return excludedIds.isEmpty(); }

    public boolean includes(Long accountId) {
        return accountId == null || !excludedIds.contains(accountId);
    }

    /** 把全量切片收成范围内的切片 —— 唯一入口是 {@link FactSlice#excludingAccounts} */
    public FactSlice apply(FactSlice full) {
        return full == null ? null : full.excludingAccounts(excludedIds);
    }

    /** 卡片标题旁小字:「自住房 · 车」;太多时「自住房 · 车 等 5 个账户」 */
    public String excludedLabel() {
        if (excludedNames.isEmpty()) {
            return excludedTypes.isEmpty() ? "" : String.join(" · ", excludedTypes);
        }
        if (excludedNames.size() <= 3) return String.join(" · ", excludedNames);
        return String.join(" · ", excludedNames.subList(0, 2)) + " 等 " + excludedNames.size() + " 个账户";
    }

    /** 被拿掉的占总资产百分比(1 位小数);没有数时 null */
    public BigDecimal excludedSharePct() {
        return excludedShare == null ? null : excludedShare.multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP);
    }

    /** 范围选项下面那行说明(FR-821):「不含 自住房、车 · 占总资产 91%」 */
    public String optionSubtitle() {
        if (isAll()) return "含所有账户";
        String what = kind == ScopeKind.FINANCIAL && !excludedTypes.isEmpty()
                ? String.join("、", excludedTypes)
                : (excludedNames.isEmpty() ? String.join("、", excludedTypes) : namesJoined("、"));
        String share = excludedShare == null ? ""
                : " · 占总资产 " + excludedShare.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
        return "不含 " + what + share;
    }

    /** 名字用给定分隔符连起来(提示词里用「、」) */
    public String namesJoined(String sep) {
        return String.join(sep, excludedNames);
    }

    /** 缓存指纹:种类 + 排除集(排序)—— 标记集合变了就是另一个范围 */
    public String fingerprint() {
        return kind.name() + ":" + new TreeSet<>(excludedIds);
    }
}
