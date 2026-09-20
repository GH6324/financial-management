package com.family.finance.domain.expense;

/**
 * v1.24 FR-610 · 支出性质 —— 和「钱花在哪」正交的<b>第三个维度</b>。
 *
 * <h3>它回答的不是「花在哪」,是「能不能不花」</h3>
 *
 * <p>项目里已经有两个维度,别把它们混成一个:</p>
 * <ul>
 *   <li><b>资金性质</b> {@code cash_flow.category_code}(消费 / 还贷 / 利息 / 给亲属)
 *       —— 这笔钱<b>是什么行为</b>,决定它进不进「钱花在哪」</li>
 *   <li><b>消费分类</b> {@code cash_flow.expense_category_id}(餐饮 / 住房 / 交通)
 *       —— 这笔消费<b>买了什么</b></li>
 *   <li><b>支出性质</b>(本枚举)—— 这笔消费<b>能不能不花</b></li>
 * </ul>
 *
 * <p>三者互不推导:住房物业是刚性的、餐饮是弹性的,但两者都是消费;
 * 同一个「数码电器」类目下,换手机是一次性的、买数据线是弹性的 ——
 * 所以性质<b>既挂在类目上、也能被单笔覆盖</b>(FR-613)。</p>
 *
 * <h3>只有三个值,不多不少</h3>
 *
 * <p>第四个值(比如「半刚性」)的诱惑很大,但用户要回答的问题是
 * 「这个月多花的钱,是躲不掉的还是可以少花的」—— 三档已经够判断,
 * 再细只会让每次录入多一次犹豫,而这个项目的硬约束是 10 分钟/月。</p>
 *
 * <p><b>逐笔只能勾成 {@link #ONE_OFF}</b>(FR-614):单笔覆盖只开放「这笔是一次性的」
 * 这一个动作。让用户逐笔在刚性/弹性之间选,等于把类目的意义又稀释一遍。</p>
 */
public enum ExpenseNature {

    /** 刚性:躲不掉的。房贷物业、水电燃气、学费、看病。 */
    RIGID("刚性", "躲不掉的"),

    /** 弹性:可多可少的。吃饭、买衣服、娱乐。<b>也是所有说不清的东西的归宿</b>(FR-616)。 */
    FLEX("弹性", "可多可少的"),

    /** 一次性:这个月有、下个月没有的。换手机、装修、一次旅行。 */
    ONE_OFF("一次性", "这次才有的");

    private final String label;
    private final String hint;

    ExpenseNature(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    /** 面向用户的两个字 —— 模板只用这个,不许自己写中文(否则三处文案迟早分叉) */
    public String getLabel() { return label; }

    /** 一句白话解释,给编辑页和图例的 title 用 */
    public String getHint() { return hint; }

    /**
     * 宽松解析。
     *
     * <p><b>永不抛异常、永不返回 null</b> —— 认不出来一律当弹性(FR-616)。
     * 库里存的是 VARCHAR,可空;一个 NPE 或 IllegalArgumentException 在报表渲染中途抛出来
     * 的后果是整页 500(chunked streaming 下连 /error 都不出,只有白屏),
     * 而代价仅仅是一类支出被归错档。两害相权很清楚。</p>
     */
    public static ExpenseNature parse(String raw) {
        if (raw == null || raw.isBlank()) return FLEX;
        for (ExpenseNature n : values()) {
            if (n.name().equalsIgnoreCase(raw.trim())) return n;
        }
        return FLEX;
    }
}
