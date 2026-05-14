package com.family.finance.service.checkup.llm;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.member.Member;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.service.checkup.AccountDiagnose;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.checkup.rule.Advice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 构造器 · v0.2 FR-40c · 2026-05-10 修订 (决策 20)
 *
 * <p><b>新方向(综合智能诊断)</b>:
 * 不再做 per-advice 润色,而是一次性把全家完整画像 + 命中规则集合送给 LLM,
 * 让它做跨规则、跨账户的综合判断,输出 200-500 字诊断长文。
 *
 * <p><b>隐私边界(2026-05-10 用户授权)</b>:
 * <ul>
 *   <li>✅ 上传:账户名 / 流水备注 / 家庭名 / 资产数字 / 类目 / 风险 / 命中规则 ID</li>
 *   <li>❌ 唯一脱敏:成员真名 → 「成员A / 成员B / 成员C」(按 member.id ASC 稳定映射)</li>
 *   <li>❌ 系统层面没存:身份证号 / 手机号 / 邮箱 / 地址</li>
 * </ul>
 */
public final class PromptBuilder {

    /** 综合诊断 system prompt(适配两层 UX:规则便签卡 + AI 综合诊断) */
    public static final String SYSTEM_DIAGNOSE = """
            你是现代家庭资产顾问 · 为这个家庭出具体检报告中的「AI 综合诊断」结构化结果。
            产品 UX 之前已展示系统规则引擎的"硬数据便签卡"(精确数字 + 触发规则 ID)。
            你的任务是在这些硬数据基础上 · 做跨维度综合判断 + 给可执行优先行动。

            输出格式 · 严格 JSON 对象 · 不要 markdown 包裹 · 不要解释段:

            {
              "overall": {
                "verdict": "STABLE" 或 "NEEDS_ATTENTION" 或 "RISK",
                "summary": "1-2 句总评 · 40-80 字 · 整体判断 + 最值得关注的 1-2 项"
              },
              "dimensions": [
                {
                  "name": "资产配置",
                  "verdict": "OK" 或 "WARN" 或 "RISK",
                  "finding": "诊断结论 1-2 句 · 30-80 字",
                  "evidence": "数据支撑 1 句 · 20-50 字 · 必须引用上下文硬事实"
                },
                { "name": "风险敞口", "verdict": ..., "finding": ..., "evidence": ... },
                { "name": "流动性", "verdict": ..., "finding": ..., "evidence": ... },
                { "name": "收益质量", "verdict": ..., "finding": ..., "evidence": ... }
              ],
              "actions": [
                "优先行动 1 · 30-50 字 · 可执行 · 跨规则综合",
                "优先行动 2(可选)",
                "优先行动 3(可选)"
              ]
            }

            诊断 4 方向必填 · 顺序与上面一致:
            1. 资产配置 · 现金/投资/房产/保险 4 桶比例是否合理 · 是否需要再平衡
            2. 风险敞口 · 风险等级分布 vs 用户偏好 · 是否过激/过保守
            3. 流动性 · 应急储备月数 · 是否足够 · 是否过度
            4. 收益质量 · 家庭加权 XIRR / TWR vs 基准 · 跑赢/输

            verdict 取值规则:
            - OK · 无问题 / 显著优于基准
            - WARN · 需关注 · 偏离 0.5-2pp 或配置偏离 5-10%
            - RISK · 亟需调整 · 偏离 2pp+ 或配置偏离 10%+
            overall.verdict 同理(STABLE/NEEDS_ATTENTION/RISK)。

            actions 数组 1-3 条 · 优先级:最大偏离的桶 / 最严重风险 / 跨规则综合 ·
            每条要可执行(动词起头 · "调"、"转"、"补足"、"减仓"等)· 不空洞。

            语调与禁词:
            - 现代家庭资产顾问 · 专业 · 克制 · 不空洞
            - 必用术语:流动性配置 / 应急储备 / 风险敞口 / 再平衡 / 加权年化 /
              跑赢 / 跑输 / 加速偿还
            - 禁词:保证 / 稳赚 / 一定能 / 必然 / 零风险 / 包赚
            - 不要具体产品名(余额宝/茅台/510300)· 不要担保性话术 · 不要真名
            - 涉及家庭成员用「成员A / 成员B / 成员C」代号

            数据约束:
            - 出现的 ¥ 数字必须在硬事实中出现过 · 或合理推导
            - 不推荐违反风险等级的操作(R2 用户不建议买 R5)
            """;

    private PromptBuilder() {}

    public static String systemPromptForDiagnose() {
        return SYSTEM_DIAGNOSE;
    }

    /**
     * 构造全家维度的 user prompt。
     *
     * @param familyName    家庭名(直接上传)
     * @param diagnose      全家 ViewModel(KPI / 配置 / 风险 / 流动性 / 收益)
     * @param accountInfos  各账户的硬事实(包含账户名、类目、风险、余额、年化等)
     * @param adviceList    规则引擎本次命中的所有 Advice(rawTitle/rawBody)
     * @param mapping       member 真名 → 成员代号
     */
    public static String userPromptForFamily(String familyName,
                                             FamilyDiagnose diagnose,
                                             List<AccountSummary> accountInfos,
                                             List<Advice> adviceList,
                                             Map<String, String> mapping) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# 家庭综合体检上下文\n\n");
        sb.append("家庭名: ").append(safe(familyName)).append('\n');
        sb.append("视角: 全家(全部账户聚合)\n\n");

        sb.append("## 1. KPI 速览(本位币 CNY)\n");
        if (diagnose.kpi() != null) {
            sb.append("- 总资产: ").append(money(diagnose.kpi().totalAssets())).append('\n');
            sb.append("- 总负债: ").append(money(diagnose.kpi().totalLiabilities())).append('\n');
            sb.append("- 净资产: ").append(money(diagnose.kpi().netWorth())).append('\n');
            sb.append("- 净资产较上期变化: ").append(money(diagnose.kpi().netWorthDelta())).append('\n');
        }
        sb.append("- 家庭加权 XIRR: ").append(diagnose.familyXirrPctLabel()).append('\n');
        sb.append("- 家庭加权 TWR: ").append(diagnose.familyTwrPctLabel()).append('\n');
        sb.append("- 流动资产: ").append(money(diagnose.liquidAssets())).append('\n');
        sb.append("- 紧急储备: ").append(diagnose.emergencyMonthsLabel()).append('\n');
        if (diagnose.cumulativeYtdPnl() != null) {
            sb.append("- 本年累计损益: ").append(money(diagnose.cumulativeYtdPnl())).append('\n');
        }
        sb.append('\n');

        if (diagnose.allocation() != null && !diagnose.allocation().isEmpty()) {
            sb.append("## 2. 资产配置(按类型)\n");
            for (AllocationSlice s : diagnose.allocation()) {
                sb.append("- ").append(s.label().replace('\n', ' '))
                        .append(": ").append(money(s.value()))
                        .append(" · 占比 ").append(pct1(s.ratio())).append('\n');
            }
            sb.append('\n');
        }

        if (diagnose.riskDistribution() != null && !diagnose.riskDistribution().isEmpty()) {
            sb.append("## 3. 风险敞口(按风险等级)\n");
            for (FamilyDiagnose.RiskBucket b : diagnose.riskDistribution()) {
                sb.append("- ").append(b.stars()).append(" ").append(b.label())
                        .append(": ").append(money(b.amount()))
                        .append(" · 占比 ").append(pct1(b.ratio())).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## 4. 各账户硬事实(已脱敏成员真名,真名→代号)\n");
        for (AccountSummary a : accountInfos) {
            sb.append("- 【").append(safe(a.accountName())).append("】")
                    .append(" 类型=").append(a.accountType())
                    .append(" 类目=").append(a.categoryCode() == null ? "—" : a.categoryCode())
                    .append(" 风险=").append(a.riskLabel())
                    .append(" 主理人=").append(a.ownerCodename() == null ? "—" : a.ownerCodename())
                    .append(" 当前余额=").append(money(a.currentBalance()));
            if (a.annualizedReturn() != null) {
                sb.append(" 年化=").append(pct2(a.annualizedReturn()));
            }
            if (a.benchmarkLabel() != null) {
                sb.append(" 基准=").append(a.benchmarkLabel())
                        .append("(").append(a.benchmarkPct() == null ? "—" : pct1(a.benchmarkPct())).append(")");
            }
            sb.append('\n');
        }
        sb.append('\n');

        sb.append("## 5. 系统规则引擎本次命中(").append(adviceList.size()).append(" 条)\n");
        if (adviceList.isEmpty()) {
            sb.append("(无规则命中,家庭整体配置较健康。请输出表扬性综合诊断,但仍须给前瞻性建议。)\n");
        } else {
            for (Advice a : adviceList) {
                sb.append("- [").append(a.severity().name()).append("] ")
                        .append(a.ruleId()).append(" / ").append(a.dimension().label)
                        .append(" · 标题:").append(safe(a.rawTitle()))
                        .append(" · 正文:").append(safe(a.rawBody()))
                        .append('\n');
            }
        }
        sb.append('\n');

        sb.append("## 6. 成员代号映射(仅供你理解上下文,不要在输出中使用真名)\n");
        if (mapping.isEmpty()) {
            sb.append("(无成员维度映射)\n");
        } else {
            mapping.forEach((real, code) -> sb.append("- ").append(code).append(" ↔ (家庭成员)\n"));
        }
        sb.append('\n');

        sb.append("---\n");
        sb.append("请输出 200-500 字综合诊断段落,严格遵守 system prompt 中的语调与禁词约束。");
        return sb.toString();
    }

    /**
     * 构造账户维度的 user prompt(单账户深度诊断)。
     */
    public static String userPromptForAccount(String familyName,
                                              FamilyDiagnose familyDiagnose,
                                              AccountDiagnose accountDiagnose,
                                              List<Advice> adviceList,
                                              Map<String, String> mapping,
                                              String ownerCodename) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# 账户深度体检上下文\n\n");
        sb.append("家庭名: ").append(safe(familyName)).append('\n');
        sb.append("视角: 单账户(此账户在全家配置中的位置)\n\n");

        Account acc = accountDiagnose.account();
        sb.append("## 1. 此账户硬事实\n");
        sb.append("- 账户名: ").append(safe(acc.getDisplayName())).append('\n');
        sb.append("- 类型: ").append(acc.getType()).append(" / ").append(acc.getType().getLabel()).append('\n');
        sb.append("- 类目: ");
        if (accountDiagnose.category() != null) {
            sb.append(accountDiagnose.category().getCode())
                    .append(" / ").append(accountDiagnose.category().getDisplayName());
            if (accountDiagnose.category().getBenchmarkLabel() != null) {
                sb.append(" · 基准 ").append(accountDiagnose.category().getBenchmarkLabel());
                if (accountDiagnose.category().getBenchmarkPct() != null) {
                    sb.append("(").append(pct1(accountDiagnose.category().getBenchmarkPct().multiply(new BigDecimal("100")))).append(")");
                }
            }
        } else {
            sb.append("(未设置)");
        }
        sb.append('\n');
        sb.append("- 风险等级: ").append(accountDiagnose.riskStars())
                .append(accountDiagnose.riskOverridden() ? "(用户覆盖)" : "")
                .append('\n');
        sb.append("- 主理人: ").append(ownerCodename == null ? "(未指定)" : ownerCodename).append('\n');
        sb.append("- 当前余额: ").append(money(accountDiagnose.currentBalance())).append('\n');
        if (accountDiagnose.previousBalance() != null) {
            sb.append("- 上期余额: ").append(money(accountDiagnose.previousBalance())).append('\n');
        }
        if (accountDiagnose.monthDelta() != null) {
            sb.append("- 本期变化: ").append(money(accountDiagnose.monthDelta()))
                    .append(" / ").append(accountDiagnose.monthDeltaPctLabel()).append('\n');
        }
        if (accountDiagnose.annualizedReturn() != null) {
            sb.append("- 年化 XIRR: ").append(accountDiagnose.annualizedReturnPctLabel()).append('\n');
        }
        if (accountDiagnose.cumulativePnl() != null) {
            sb.append("- 累计投资损益: ").append(money(accountDiagnose.cumulativePnl())).append('\n');
        }
        if (accountDiagnose.netPrincipalInjected() != null) {
            sb.append("- 累计净外部本金注入: ").append(money(accountDiagnose.netPrincipalInjected())).append('\n');
        }
        if (accountDiagnose.drawdown() != null && accountDiagnose.drawdown().drawdown() != null) {
            sb.append("- 最大回撤: ").append(pct2(accountDiagnose.drawdown().drawdown().multiply(new BigDecimal("100"))));
            if (accountDiagnose.drawdown().troughMonth() != null) {
                sb.append("(").append(accountDiagnose.drawdown().troughMonth()).append(" 触底)");
            }
            sb.append('\n');
        }
        if (accountDiagnose.benchmark() != null
                && accountDiagnose.benchmark().status() == com.family.finance.calc.BenchmarkComparator.Status.COMPARED) {
            sb.append("- 基准对照: 本账户年化=").append(pct2(accountDiagnose.benchmark().accountPct()))
                    .append(" 基准=").append(pct2(accountDiagnose.benchmark().benchmarkPct()))
                    .append(" 差额=").append(pct2(accountDiagnose.benchmark().diffPct())).append("pp\n");
        }
        sb.append('\n');

        sb.append("## 2. 全家上下文摘要\n");
        if (familyDiagnose != null) {
            if (familyDiagnose.kpi() != null) {
                sb.append("- 全家总资产: ").append(money(familyDiagnose.kpi().totalAssets())).append('\n');
                sb.append("- 全家净资产: ").append(money(familyDiagnose.kpi().netWorth())).append('\n');
            }
            sb.append("- 全家加权 XIRR: ").append(familyDiagnose.familyXirrPctLabel()).append('\n');
            sb.append("- 全家紧急储备: ").append(familyDiagnose.emergencyMonthsLabel()).append('\n');
            if (accountDiagnose.currentBalance() != null && familyDiagnose.kpi() != null
                    && familyDiagnose.kpi().totalAssets() != null
                    && familyDiagnose.kpi().totalAssets().signum() > 0) {
                BigDecimal share = accountDiagnose.currentBalance()
                        .divide(familyDiagnose.kpi().totalAssets(), 4, RoundingMode.HALF_EVEN);
                sb.append("- 此账户占全家总资产: ").append(pct1(share.multiply(new BigDecimal("100")))).append('\n');
            }
        }
        sb.append('\n');

        sb.append("## 3. 系统规则引擎本次命中(").append(adviceList.size()).append(" 条,本账户视角)\n");
        if (adviceList.isEmpty()) {
            sb.append("(本账户未触发任何规则,整体表现良好。请输出表扬性 + 前瞻性综合诊断。)\n");
        } else {
            for (Advice a : adviceList) {
                sb.append("- [").append(a.severity().name()).append("] ")
                        .append(a.ruleId()).append(" / ").append(a.dimension().label)
                        .append(" · 标题:").append(safe(a.rawTitle()))
                        .append(" · 正文:").append(safe(a.rawBody()))
                        .append('\n');
            }
        }
        sb.append('\n');

        sb.append("---\n");
        sb.append("请输出 200-500 字综合诊断段落,严格遵守 system prompt 中的语调与禁词约束。");
        return sb.toString();
    }

    /**
     * 把 family.members 按 id ASC 映射成 「成员A / 成员B / 成员C」 代号。
     * 同时给出反向 map(代号 → 真名),供前端反映射使用。
     */
    public static NameMapping buildNameMapping(List<Member> members) {
        Map<String, String> realToCodename = new LinkedHashMap<>();
        Map<String, String> codenameToReal = new LinkedHashMap<>();
        if (members == null || members.isEmpty()) {
            return new NameMapping(realToCodename, codenameToReal);
        }
        // 按 id ASC 排序(MemberMapper 已经返回 ORDER BY id,但保险再排一次)
        List<Member> sorted = members.stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            String code = "成员" + (char) ('A' + i);
            String real = sorted.get(i).getDisplayName();
            if (real == null || real.isBlank()) {
                continue;
            }
            realToCodename.put(real, code);
            codenameToReal.put(code, real);
        }
        return new NameMapping(realToCodename, codenameToReal);
    }

    /** 应用 mapping 把文本里的真名替换成代号(用于 owner_label / 流水备注) */
    public static String applyMapping(String text, Map<String, String> realToCodename) {
        if (text == null || text.isEmpty() || realToCodename.isEmpty()) {
            return text;
        }
        String out = text;
        // 按真名长度倒序替换(防止"张"先于"Alice"被替换)
        List<String> names = realToCodename.keySet().stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
        for (String real : names) {
            out = out.replace(real, realToCodename.get(real));
        }
        return out;
    }

    /** 反向应用:LLM 输出里的代号还原回真名(供前端展示) */
    public static String reverseMapping(String text, Map<String, String> codenameToReal) {
        if (text == null || text.isEmpty() || codenameToReal.isEmpty()) {
            return text;
        }
        String out = text;
        // 按代号长度倒序替换(防止"成员A"先于"成员AB"被替换)
        List<String> codes = codenameToReal.keySet().stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
        for (String code : codes) {
            out = out.replace(code, codenameToReal.get(code));
        }
        return out;
    }

    private static String money(BigDecimal v) {
        if (v == null) return "—";
        return "¥" + v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct1(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(1, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }

    private static String pct2(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }

    private static String safe(String s) {
        if (s == null) return "—";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    /**
     * 单个账户在 prompt 里的紧凑表示(已应用真名映射)。
     * 由 LlmDiagnoseService 在调用 PromptBuilder 前组装。
     */
    public record AccountSummary(
            String accountName,
            String accountType,
            String categoryCode,
            String riskLabel,
            String ownerCodename,
            BigDecimal currentBalance,
            BigDecimal annualizedReturn,
            String benchmarkLabel,
            BigDecimal benchmarkPct
    ) {}

    /** 真名 ↔ 代号 双向映射 */
    public record NameMapping(
            Map<String, String> realToCodename,
            Map<String, String> codenameToReal
    ) {}
}
