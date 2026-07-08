package com.family.finance.web.dashboard;

import com.family.finance.factview.CashflowBreakdown;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * v0.10 · 仪表盘「人赚 vs 钱赚」本期拆解视图模型。
 *
 * <p>口径(同卡自洽):人赚 = 本期收入 − 支出({@link CashflowBreakdown#netInflow()});
 * 钱赚 = ΔNW − 人赚;故 {@code 收入−支出==人赚}、{@code 人赚+钱赚==ΔNW} 在卡内恒等,
 * 不会让用户发现对不上。首期(无上期 → ΔNW 不可算)只显人赚。</p>
 *
 * <p>双向条:零基线居中,正向右、负向左,半宽% = |值| ÷ 三者最大绝对值 × 50;四象限统一一套画法。</p>
 */
public record CashflowSplitView(
        BigDecimal deltaNetWorth,
        BigDecimal renZhuan,
        BigDecimal qianZhuan,
        BigDecimal openingBaseline,   // v0.13 · 本期新纳入本金(开账基线)· 既非人赚也非钱赚
        BigDecimal income,
        BigDecimal expense,
        int filledMembers,
        int totalMembers,
        boolean firstPeriod,
        String narrative
) {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HALF_WIDTH = BigDecimal.valueOf(50);

    /** backward-compat · 无开账基线(老调用/测试) */
    public static CashflowSplitView of(BigDecimal deltaNetWorth, CashflowBreakdown b,
                                       int filledMembers, int totalMembers) {
        return of(deltaNetWorth, b, filledMembers, totalMembers, ZERO);
    }

    /**
     * v0.13 · 从 ΔNW + 本期毛收支 + 开账基线装配。
     * 恒等式:{@code 人赚 + 钱赚 + 开账基线 == ΔNW};钱赚 = ΔNW − 人赚 − 开账基线(补录存量不算投资赚)。
     */
    public static CashflowSplitView of(BigDecimal deltaNetWorth, CashflowBreakdown b,
                                       int filledMembers, int totalMembers, BigDecimal openingBaseline) {
        BigDecimal ren = b == null ? ZERO : b.netInflow();
        BigDecimal ob = openingBaseline == null ? ZERO : openingBaseline;
        boolean first = deltaNetWorth == null;                 // 首期无上期 → 投资损益/ΔNW 不可算
        BigDecimal qian = first ? null : deltaNetWorth.subtract(ren).subtract(ob);
        BigDecimal inc = b == null ? ZERO : b.income();
        BigDecimal exp = b == null ? ZERO : b.expense();
        return new CashflowSplitView(deltaNetWorth, ren, qian, ob, inc, exp,
                filledMembers, totalMembers, first, narrative(first, ren, qian, deltaNetWorth));
    }

    /**
     * 空态 = 本期完全没有收支信号。
     * v0.12.2:收入侧口径已切 cash_flow(FR-142),PMC「2框」不再是唯一来源 —— 仅录了收入录入
     * (如股票 +股数,PMC 未填)时 filledMembers=0 但 income>0,不该再判空。故:PMC 未填 **且** 无
     * cash_flow 收/支 才算空。income/expense 取自 FR-142 口径的 CashflowBreakdown。
     */
    public boolean empty()   { return filledMembers <= 0 && sign(income) == 0 && sign(expense) == 0; }
    public boolean partial() { return filledMembers > 0 && totalMembers > 0 && filledMembers < totalMembers; }

    public boolean renPos()   { return sign(renZhuan) >= 0; }
    public boolean qianPos()  { return sign(qianZhuan) >= 0; }
    public boolean deltaPos() { return sign(deltaNetWorth) >= 0; }
    /** v0.13 · 本期有新纳入本金(开账基线 ≠ 0)才显第三行 */
    public boolean hasOpening() { return sign(openingBaseline) != 0; }
    public boolean openingPos() { return sign(openingBaseline) >= 0; }

    public int renWidth()   { return width(renZhuan); }
    public int qianWidth()  { return width(qianZhuan); }
    public int deltaWidth() { return width(deltaNetWorth); }
    public int openingWidth() { return width(openingBaseline); }

    /** 双向条内联样式(避免在模板里拼 Thymeleaf 表达式):正向右染绿、负向左染赭,宽度=半宽%。 */
    public String renBarStyle()   { return signedBar(renPos(), renWidth()); }
    public String qianBarStyle()  { return signedBar(qianPos(), qianWidth()); }
    public String deltaBarStyle() { return (deltaPos() ? "left:50%;" : "right:50%;") + "background:var(--ink);width:" + deltaWidth() + "%"; }
    /** v0.13 · 开账基线用铜色(区别于人赚绿/钱赚赭) */
    public String openingBarStyle() { return (openingPos() ? "left:50%;" : "right:50%;") + "background:var(--brass-deep);width:" + openingWidth() + "%"; }

    private static String signedBar(boolean pos, int w) {
        return (pos ? "left:50%;background:var(--forest);" : "right:50%;background:var(--rust);") + "width:" + w + "%";
    }

    private static int sign(BigDecimal v) { return v == null ? 0 : v.signum(); }

    /** 双向条半宽百分比(0–50):|v| ÷ 三者最大绝对值 × 50。 */
    private int width(BigDecimal v) {
        if (v == null) return 0;
        BigDecimal max = maxAbs();
        if (max.signum() == 0) return 0;
        return v.abs().multiply(HALF_WIDTH).divide(max, 0, RoundingMode.HALF_UP).intValue();
    }

    private BigDecimal maxAbs() {
        BigDecimal m = renZhuan == null ? ZERO : renZhuan.abs();
        if (qianZhuan != null) m = m.max(qianZhuan.abs());
        if (deltaNetWorth != null) m = m.max(deltaNetWorth.abs());
        if (openingBaseline != null) m = m.max(openingBaseline.abs());
        return m;
    }

    /** 一句话随符号自适应(给非技术家庭成员讲人话)。 */
    private static String narrative(boolean first, BigDecimal ren, BigDecimal qian, BigDecimal delta) {
        if (first) return "首期无对比,暂不拆投资损益。";
        boolean rp = ren.signum() >= 0, qp = qian.signum() >= 0;
        if (rp && qp)  return "存下的 + 投资一起把净资产推高了。";
        if (rp)        return delta.signum() >= 0
                ? "存下了钱,投资虽有回撤,净资产仍小幅增长。"
                : "存下了钱,但投资回撤吃掉得更多 → 净资产仍缩水。";
        if (qp)        return delta.signum() >= 0
                ? "这月超支,靠投资收益补上还有结余。"
                : "这月超支,投资收益没能补平 → 净资产仍缩水。";
        return "超支 + 投资双双下行 → 净资产明显缩水。";
    }
}
