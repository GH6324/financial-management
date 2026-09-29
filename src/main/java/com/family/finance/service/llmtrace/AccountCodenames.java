package com.family.finance.service.llmtrace;

import com.family.finance.domain.account.Account;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.28 · 账户名换代号(PRD FR-920 · tech-design v1.28 选型五)。
 *
 * <p>「账户A / 账户B …」按账户 id 升序编,家里新建账户不会让老账户的代号变。
 * <b>只在系统写入账户名的位置用</b>(账户清单、单账户的「账户名」一行、范围块里列出的账户、调仓的账户列表、
 * 透视按账户切的行)—— 不对整段提示词做全文替换:用户常把账户叫「现金」「房贷」「公积金」,
 * 全文替换会把材料里的普通词一起换掉。用户自己写的偏好 / 补充要求也不动。</p>
 *
 * <p>回答展示前用 {@link #reverse(String)} 把代号换回真名(与成员名反映射同一处)。</p>
 */
public final class AccountCodenames {

    private static final Pattern CODE = Pattern.compile("账户([A-Z]{1,2})(?![A-Za-z])");

    private final Map<Long, String> codeById = new LinkedHashMap<>();
    private final Map<String, String> codeToReal = new LinkedHashMap<>();

    private AccountCodenames() {}

    public static AccountCodenames of(List<Account> accounts) {
        AccountCodenames c = new AccountCodenames();
        if (accounts == null) return c;
        List<Account> sorted = accounts.stream()
                .filter(a -> a != null && a.getId() != null)
                .sorted(Comparator.comparing(Account::getId))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            Account a = sorted.get(i);
            String code = "账户" + letters(i);
            c.codeById.put(a.getId(), code);
            c.codeToReal.put(code, a.getDisplayName() == null ? code : a.getDisplayName());
        }
        return c;
    }

    /** 0 → A · 25 → Z · 26 → AA …(超过 702 个账户的家庭不存在) */
    static String letters(int i) {
        return i < 26 ? String.valueOf((char) ('A' + i))
                : String.valueOf((char) ('A' + i / 26 - 1)) + (char) ('A' + i % 26);
    }

    /** 这个账户的代号;不认识的账户(不该发生)退回原名 —— 宁可不换,也不编一个不存在的代号 */
    public String code(Long accountId, String fallbackName) {
        String c = accountId == null ? null : codeById.get(accountId);
        return c != null ? c : fallbackName;
    }

    /** 按名字找代号(范围块只有名字)· 同名账户取 id 最小的那个 */
    public String codeForName(String name) {
        if (name == null) return null;
        for (Map.Entry<String, String> e : codeToReal.entrySet()) {
            if (name.equals(e.getValue())) return e.getKey();
        }
        return name;
    }

    /** 代号 → 真名(面板的代号对照)· 只列真的换了名字的 */
    public Map<String, String> codeToReal() {
        return codeToReal;
    }

    /** 回答里的代号换回真名 · 「账户AB」不会被当成「账户A」+「B」 */
    public String reverse(String text) {
        if (text == null || text.isEmpty() || codeToReal.isEmpty()) return text;
        Matcher m = CODE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String real = codeToReal.get(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(real != null ? real : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
