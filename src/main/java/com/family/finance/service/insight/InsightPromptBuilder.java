package com.family.finance.service.insight;

import com.family.finance.calc.BalanceSheetHealth;
import com.family.finance.calc.BehaviorHeuristics;
import com.family.finance.calc.ConcentrationCalculator;
import com.family.finance.calc.RebalanceDrift;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * AI 资产洞察 prompt 构造 · v0.6 FR-108/109。
 *
 * <p><b>隐私 by construction</b>:prompt 只含数字 + 中性标签(类型/币种代码/桶名/锚代码),
 * <b>不含任何账户名 / 成员真名</b>(承 [[feedback_llm_no_math]] 同级的私密红线)。
 * 因此 OutputValidator 可传空 realNames —— LLM 没见过名字,也就无从泄露。</p>
 *
 * <p><b>数字 by construction</b>:所有 ¥/%/pp 已由 {@link AssetInsightService} 预计算,
 * prompt 明示「只引用 · 不计算 · 不预测涨跌 · 不择时 · 不荐产品」。</p>
 */
public final class InsightPromptBuilder {

    private InsightPromptBuilder() {}

    public static final String SYSTEM_ASSET_INSIGHT = """
            你是现代家庭资产顾问 · 为这个家庭出具「AI 资产洞察」的结构化结论。
            产品已用规则引擎算好全部硬数据(集中度 / 资产负债表 / 再平衡偏离 / 行为信号 / 低利率视角)。
            你的任务:在这些硬数据基础上做中立解读 + 给可执行的纪律性提醒。

            ⚠⚠⚠ 最高优先级红线(违反即作废):
            1. 你 100% 禁止做任何四则运算 · 不算占比/差额/比率/加权
            2. 输出中的 ¥金额 / % / pp 必须从下文 prompt 原样照抄 · prompt 没给的数字不许造,用「—」
            3. 禁止预测涨跌 / 点位 / 时机 —— 不说"会涨/会跌/见顶/抄底/牛市/熊市/未来 N 个月"
            4. 禁止择时与买卖时点建议 —— 只谈「配置纪律 / 偏离方向 / 是否需要再平衡」,不说"现在买/现在卖/加仓某资产"
            5. 禁止具体产品名 / 股票代码 / 基金代码 · 禁止担保性话术(保证/稳赚/零风险)
            6. 不含任何人名(下文也不会给)· 中立、克制、就事论事

            输出格式 · 严格 JSON 对象 · 不要 markdown 包裹 · 不要解释段:

            {
              "overall": {
                "verdict": "STABLE" 或 "NEEDS_ATTENTION" 或 "RISK",
                "summary": "1-2 句总评 · 40-80 字 · 整体判断 + 最值得关注的 1-2 项"
              },
              "dimensions": [
                { "name": "集中度",      "verdict": "OK|WARN|RISK", "finding": "30-80字", "evidence": "20-50字·引用硬数字" },
                { "name": "资产负债表",  "verdict": "OK|WARN|RISK", "finding": "...", "evidence": "..." },
                { "name": "再平衡·行为", "verdict": "OK|WARN|RISK", "finding": "...", "evidence": "..." },
                { "name": "低利率·资产荒", "verdict": "OK|WARN|RISK", "finding": "...", "evidence": "..." }
              ],
              "actions": [ "优先提醒1·30-50字·可执行·纪律性", "提醒2(可选)", "提醒3(可选)" ]
            }

            4 维度必填 · 顺序与上面一致:
            1. 集中度 · 房产/单一账户/单一外币 占比是否过线 · 钱是否太挤在一处
            2. 资产负债表 · 金融盘 vs 不动产 · 负债率分级 · 加权负债利率 vs 资产年化收益(提前还贷信号)
            3. 再平衡·行为 · 各桶偏离目标方向(超配该减/低配该补)· 追涨/从不止盈等行为提醒
            4. 低利率·资产荒 · 现金占比 · 扣 CPI 后真实购买力 · 扣 M2 后相对社会财富

            verdict 规则:OK 无虞 / WARN 需关注 / RISK 亟需调整;overall 用 STABLE/NEEDS_ATTENTION/RISK。
            actions 1-3 条 · 动词起头(调/转/补足/减配/加速偿还/再平衡)· 纪律性而非择时。
            必用术语:集中度 / 负债率 / 加权年化 / 再平衡 / 真实收益 / 相对社会财富 / 加速偿还。
            """;

    public static String systemPrompt() {
        return SYSTEM_ASSET_INSIGHT;
    }

    /** 把硬数据 {@link AssetInsight} 铺成 user prompt(全部数字预计算 · LLM 只引用)。 */
    public static String userPrompt(AssetInsight in) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# 家庭资产洞察上下文(全部数字已由系统预计算 · 你只能引用 · 不要计算/预测)\n\n");

        // 1. 集中度
        sb.append("## 1. 集中度(占总资产)\n");
        AssetInsight.Concentration c = in.concentration();
        if (c != null) {
            sb.append("- 总资产: ").append(money(c.totalAssets())).append('\n');
            sb.append("- 参考风险线: ").append(pct1(c.thresholdPct())).append('\n');
            sb.append("- 房产占比: ").append(lineStr(c.property())).append('\n');
            sb.append("- 最大单一账户占比: ").append(lineStr(c.topAccount())).append('\n');
            if (c.topCurrency() != null && c.topCurrency().pct() != null) {
                sb.append("- 最大外币敞口(").append(safe(c.topCurrencyLabel())).append(")占比: ")
                  .append(lineStr(c.topCurrency())).append('\n');
            } else {
                sb.append("- 外币敞口: 无显著外币持仓\n");
            }
        } else {
            sb.append("- (数据不足 · 降级)\n");
        }
        sb.append('\n');

        // 2. 资产负债表
        sb.append("## 2. 资产负债表健康\n");
        BalanceSheetHealth.Result b = in.balanceSheet();
        if (b != null) {
            sb.append("- 金融盘占(金融+不动产): ").append(pct1(b.financialPct())).append('\n');
            sb.append("- 不动产占(金融+不动产): ").append(pct1(b.propertyPct())).append('\n');
            sb.append("- 负债率(总负债/总资产): ").append(pct1(b.debtRatioPct()))
              .append(" · 分级 ").append(debtBandCn(b.debtBand())).append('\n');
            sb.append("- 加权负债利率: ").append(pct2(in.weightedLoanRatePct())).append('\n');
            sb.append("- 资产名义年化收益: ").append(pct2(in.assetAnnualReturnPct())).append('\n');
            sb.append("- 提前还贷信号: ").append(prepayCn(b.prepaySignal())).append('\n');
        } else {
            sb.append("- (数据不足 · 降级)\n");
        }
        sb.append('\n');

        // 3. 再平衡 + 行为
        sb.append("## 3. 再平衡偏离 + 行为信号\n");
        AssetInsight.Rebalance r = in.rebalance();
        if (r != null && r.drifts() != null && !r.drifts().isEmpty()) {
            sb.append("- 配置锚: ").append(safe(r.anchorCode()))
              .append(" · 触发阈值 ").append(pp(r.thresholdPp())).append('\n');
            for (RebalanceDrift.Drift d : r.drifts()) {
                sb.append("  · ").append(bucketCn(d.bucket()))
                  .append(": 当前 ").append(pct1(d.currentPct()))
                  .append(" / 目标 ").append(pct1(d.targetPct()))
                  .append(" / 偏离 ").append(ppSigned(d.diffPp()))
                  .append(" → ").append(directionCn(d.direction(), d.overThreshold())).append('\n');
            }
        } else {
            sb.append("- (无配置锚或数据不足 · 跳过偏离)\n");
        }
        if (in.behaviorSignals() != null && !in.behaviorSignals().isEmpty()) {
            sb.append("- 行为信号(保守启发式 · 基于 ").append(in.historyPeriods()).append(" 期):\n");
            for (BehaviorHeuristics.Signal s : in.behaviorSignals()) {
                sb.append("  · ").append(safe(s.message())).append('\n');
            }
        } else {
            sb.append("- 行为信号: 无强信号(历史不足或无明显模式)\n");
        }
        sb.append('\n');

        // 4. 低利率 · 资产荒
        sb.append("## 4. 低利率·资产荒视角\n");
        AssetInsight.LowRate lr = in.lowRate();
        if (lr != null) {
            sb.append("- 现金桶当前占比: ").append(pct1(lr.cashPct())).append('\n');
            sb.append("- 名义增长跑赢 CPI(通胀)幅度: ").append(pp2(lr.realReturnPct()))
              .append(lr.realReturnPct() != null && lr.realReturnPct().signum() < 0 ? "(跑输通胀)" : "").append('\n');
            sb.append("- 名义增长跑赢 M2(社会财富)幅度: ").append(pp2(lr.relativeReturnPct()))
              .append(lr.relativeReturnPct() != null && lr.relativeReturnPct().signum() < 0 ? "(跑输社会平均)" : "").append('\n');
        } else {
            sb.append("- (数据不足 · 降级)\n");
        }
        sb.append('\n');
        sb.append("请基于以上硬数据,输出严格 JSON 的 4 维洞察 + 1-3 条纪律性提醒。再次强调:不计算、不预测涨跌、不择时、不荐产品。\n");
        return sb.toString();
    }

    // ---------------- 格式化(本类自带 · 不依赖 PromptBuilder 私有方法) ----------------
    private static String lineStr(ConcentrationCalculator.Line line) {
        if (line == null || line.pct() == null) return "—";
        String over = line.overLine() ? "(超线·偏高)" : "(线内)";
        return pct1(line.pct()) + over;
    }

    private static String money(BigDecimal v) {
        if (v == null) return "—";
        return "¥" + v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct1(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String pct2(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** v0.11.5 · 百分点单位(两比例相减的差额,如 名义−CPI 超额)· 正数带 +。 */
    private static String pp2(BigDecimal v) {
        if (v == null) return "—";
        BigDecimal s = v.setScale(2, RoundingMode.HALF_UP);
        return (s.signum() > 0 ? "+" : "") + s.toPlainString() + "pp";
    }

    private static String pp(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString() + "pp";
    }

    private static String ppSigned(BigDecimal v) {
        if (v == null) return "—";
        BigDecimal s = v.setScale(1, RoundingMode.HALF_UP);
        return (s.signum() > 0 ? "+" : "") + s.toPlainString() + "pp";
    }

    private static String safe(String s) {
        if (s == null) return "—";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    private static String debtBandCn(String band) {
        if (band == null) return "—";
        return switch (band) {
            case "HEALTHY" -> "健康(<30%)";
            case "ELEVATED" -> "偏高(30-50%)";
            case "ALERT" -> "警戒(>50%)";
            default -> "未知";
        };
    }

    private static String prepayCn(Boolean signal) {
        if (signal == null) return "—(缺利率或收益数据)";
        return signal ? "是(负债利率 > 资产年化收益,提前还贷一般更划算)"
                      : "否(资产年化收益 ≥ 负债利率)";
    }

    private static String bucketCn(String bucket) {
        if (bucket == null) return "—";
        return switch (bucket.toUpperCase()) {
            case "CASH" -> "现金";
            case "INVEST" -> "投资";
            case "PROPERTY" -> "不动产";
            case "INSURANCE" -> "保险";
            default -> bucket;
        };
    }

    private static String directionCn(String dir, boolean over) {
        if (!over || dir == null) return "线内·无需动";
        return switch (dir) {
            case "OVER" -> "超配·该减";
            case "UNDER" -> "低配·该补";
            default -> "线内·无需动";
        };
    }
}
