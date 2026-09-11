package com.family.finance.service.expense.imports;

import com.family.finance.domain.account.Account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21 FR-575 · 把账单里的<b>资金来源</b>映射到家庭账户。
 *
 * <h3>为什么不能整批一个账户</h3>
 *
 * <p>一份支付宝月账单里,190 笔支出可能分别走<b>余额宝 / 花呗 / 招商银行储蓄卡 / 余额</b>——
 * 「收/付款方式」那一列本来就是变化的。整批落到同一个账户,等于把几个账户的钱算到一个头上:
 * 余额轧差全错、账户级收益率全错,而且<b>不报错</b>。</p>
 *
 * <h3>三层,和分类归类同一套思路</h3>
 *
 * <ol>
 *   <li><b>家庭记住的规则</b> —— 用户上次把「招商银行储蓄卡(1234)」改到某个账户并勾了记住</li>
 *   <li><b>名字互相包含</b> —— 账户名「支付宝-余额宝」包含资金来源「余额宝」,或反过来。
 *       长的账户名优先(「招行信用卡」比「招行」更具体)</li>
 *   <li><b>内置别名</b> —— 「花呗」「白条」这类不会出现在账户名里的说法</li>
 * </ol>
 *
 * <p>都没命中就返回 {@code null},由确认页落到用户选的<b>默认账户</b>并标出来
 * —— <b>不猜一个像样的</b>:账户猜错比分类猜错严重得多,分类错了只是构成图不准,
 * 账户错了会让那个账户的余额和收益率一起错。</p>
 */
public final class BillAccountResolver {

    private BillAccountResolver() {}

    /** 资金来源里的说法 → 账户名里可能出现的词。只放「账户名里一定不会有」的那些。 */
    static final Map<String, String[]> ALIAS = new LinkedHashMap<>();
    static {
        ALIAS.put("花呗", new String[]{"花呗", "支付宝", "信用"});
        ALIAS.put("白条", new String[]{"白条", "京东"});
        ALIAS.put("零钱通", new String[]{"零钱通", "微信"});
        ALIAS.put("零钱", new String[]{"零钱", "微信"});
        ALIAS.put("余额宝", new String[]{"余额宝", "支付宝"});
        ALIAS.put("余额", new String[]{"余额", "支付宝"});
        ALIAS.put("账户余额", new String[]{"余额", "支付宝"});
    }

    /** 命中依据 —— 确认页要标出来,用户才知道哪几行值得核对 */
    public enum How {
        RULE("你的规则"), NAME("名字匹配"), ALIAS_HIT("常见叫法"), DEFAULT("默认账户");
        public final String label;
        How(String l) { this.label = l; }
        public boolean needsReview() { return this == DEFAULT; }
    }

    public record Hit(Long accountId, How how) {}

    /**
     * @param payMethod 账单里的资金来源原文;为空直接落默认
     * @param accounts  家庭在用的账户
     * @param rules     家庭记住的「资金来源关键字 → 账户」
     */
    public static Hit resolve(String payMethod, List<Account> accounts,
                              Map<String, Long> rules, Long defaultAccountId) {
        String pm = payMethod == null ? "" : payMethod.trim();
        if (pm.isEmpty() || accounts == null || accounts.isEmpty()) {
            return new Hit(defaultAccountId, How.DEFAULT);
        }

        // ① 家庭规则(长关键字优先 —— 「招商银行储蓄卡」应该赢过「招商」)
        if (rules != null) {
            String best = null;
            for (String k : rules.keySet()) {
                if (k != null && !k.isBlank() && pm.contains(k)
                        && (best == null || k.length() > best.length())) best = k;
            }
            if (best != null) return new Hit(rules.get(best), How.RULE);
        }

        /* ② 名字互相包含。【取最长匹配】而不是第一个命中 ——
         *    「招行信用卡」和「招行理财-稳健」都含「招行」,先命中谁取决于 id 顺序,
         *    那是隐形的随机。按匹配长度排序至少是确定的。 */
        Account bestAcct = null;
        int bestLen = 0;
        for (Account a : accounts) {
            String name = a.getDisplayName() == null ? "" : a.getDisplayName().trim();
            if (name.isEmpty()) continue;
            int len = overlap(pm, name);
            if (len > bestLen) { bestLen = len; bestAcct = a; }
        }
        // 两个字以上才算命中 —— 一个字的重合(「行」「中」)几乎全是巧合
        if (bestAcct != null && bestLen >= 2) return new Hit(bestAcct.getId(), How.NAME);

        // ③ 内置别名
        for (var e : ALIAS.entrySet()) {
            if (!pm.contains(e.getKey())) continue;
            for (String want : e.getValue()) {
                for (Account a : accounts) {
                    String name = a.getDisplayName() == null ? "" : a.getDisplayName();
                    if (name.contains(want)) return new Hit(a.getId(), How.ALIAS_HIT);
                }
            }
        }
        return new Hit(defaultAccountId, How.DEFAULT);
    }

    /**
     * 两个串里「一个包含另一个的那一段」有多长。
     *
     * <p>账单写「招商银行储蓄卡(1234)」而账户叫「招商银行」,或者反过来账户叫
     * 「招商银行储蓄卡 1234」而账单只写「招商银行」—— 两个方向都要认。</p>
     */
    static int overlap(String a, String b) {
        if (a.contains(b)) return b.length();
        if (b.contains(a)) return a.length();
        return 0;
    }
}
