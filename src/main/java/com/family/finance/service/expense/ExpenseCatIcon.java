package com.family.finance.service.expense;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1.21 · 支出类目 → 图标。
 *
 * <h3>为什么按名字猜,而不是让用户选</h3>
 *
 * <p>让用户为每个类目挑一个图标,是在建类目这件事上凭空加一步 —— 而建类目本来就是
 * 用户嫌麻烦的地方(调研里「填半天分类还不对」是最集中的抱怨)。按名字猜能覆盖绝大多数:
 * 起步包的 10 个大类<b>刻意与支付宝的交易分类同名</b>,自建的类目名也基本都带常见词。
 * 猜不中就给一个中性的标签图标 —— 不好看,但不会错。</p>
 *
 * <h3>为什么不用 emoji</h3>
 *
 * <p>项目铁律(memory {@code feedback_no_emoji}):UI 文案不许 emoji,一律
 * Feather 风格的 inline SVG(24×24 viewBox · {@code stroke=currentColor})。
 * emoji 在不同系统上渲染差异极大,而且和整套衬线排版格格不入。</p>
 *
 * <h3>关键词表的顺序有意义</h3>
 *
 * <p>{@link #KEYWORDS} 是 {@link LinkedHashMap},<b>先匹配到的赢</b>。
 * 所以「宠物」要排在「用品」前面 —— 否则「宠物用品」会落到购物那个图标上。
 * 加新词时想一想它会不会被前面某个更泛的词截胡。</p>
 */
public final class ExpenseCatIcon {

    private ExpenseCatIcon() {}

    /** 猜不中时的中性图标 —— 一个标签,不带任何语义 */
    public static final String DEFAULT = "tag";

    /**
     * 关键词 → 图标 key。<b>顺序即优先级</b>(见类注释)。
     *
     * <p>具体的词排在泛词前面:先「宠物」再「用品」,先「外卖」再「餐」。</p>
     */
    private static final Map<String, String> KEYWORDS = new LinkedHashMap<>();
    static {
        // ── 具体先行 ──
        KEYWORDS.put("外卖", "bowl");
        KEYWORDS.put("三餐", "bowl");
        KEYWORDS.put("聚餐", "bowl");
        KEYWORDS.put("饮品", "cup");
        KEYWORDS.put("咖啡", "cup");
        KEYWORDS.put("零食", "cup");
        KEYWORDS.put("宠物", "paw");
        KEYWORDS.put("打车", "car");
        KEYWORDS.put("加油", "car");
        KEYWORDS.put("停车", "car");
        KEYWORDS.put("火车", "plane");
        KEYWORDS.put("机票", "plane");
        KEYWORDS.put("旅游", "plane");
        KEYWORDS.put("酒店", "plane");
        KEYWORDS.put("公共交通", "bus");
        KEYWORDS.put("地铁", "bus");
        KEYWORDS.put("房租", "home");
        KEYWORDS.put("房贷", "home");
        KEYWORDS.put("物业", "home");
        KEYWORDS.put("水电", "zap");
        KEYWORDS.put("燃气", "zap");
        KEYWORDS.put("话费", "wifi");
        KEYWORDS.put("网费", "wifi");
        KEYWORDS.put("会员", "wifi");
        KEYWORDS.put("订阅", "wifi");
        KEYWORDS.put("看病", "heart");
        KEYWORDS.put("买药", "heart");
        KEYWORDS.put("保健", "heart");
        KEYWORDS.put("学费", "book");
        KEYWORDS.put("书籍", "book");
        KEYWORDS.put("课程", "book");
        KEYWORDS.put("影音", "play");
        KEYWORDS.put("演出", "play");
        KEYWORDS.put("运动", "activity");
        KEYWORDS.put("健身", "activity");
        KEYWORDS.put("衣物", "shirt");
        KEYWORDS.put("鞋包", "shirt");
        KEYWORDS.put("美容", "scissors");
        KEYWORDS.put("美发", "scissors");
        KEYWORDS.put("数码", "monitor");
        KEYWORDS.put("家电", "monitor");
        KEYWORDS.put("家居", "sofa");
        KEYWORDS.put("家装", "sofa");
        KEYWORDS.put("日用", "basket");
        KEYWORDS.put("红包", "gift");
        KEYWORDS.put("礼物", "gift");
        KEYWORDS.put("人情", "gift");
        KEYWORDS.put("孩子", "baby");
        KEYWORDS.put("母婴", "baby");
        KEYWORDS.put("育儿", "baby");
        // ── 泛词兜底(起步包的 10 个大类都落在这一段) ──
        KEYWORDS.put("餐饮", "bowl");
        KEYWORDS.put("美食", "bowl");
        KEYWORDS.put("百货", "basket");
        KEYWORDS.put("购物", "basket");
        KEYWORDS.put("服饰", "shirt");
        KEYWORDS.put("装扮", "shirt");
        KEYWORDS.put("电器", "monitor");
        KEYWORDS.put("交通", "car");
        KEYWORDS.put("出行", "car");
        KEYWORDS.put("住房", "home");
        KEYWORDS.put("居住", "home");
        KEYWORDS.put("医疗", "heart");
        KEYWORDS.put("健康", "heart");
        KEYWORDS.put("教育", "book");
        KEYWORDS.put("培训", "book");
        KEYWORDS.put("学习", "book");
        KEYWORDS.put("文化", "play");
        KEYWORDS.put("休闲", "play");
        KEYWORDS.put("娱乐", "play");
        KEYWORDS.put("充值", "wifi");
        KEYWORDS.put("缴费", "wifi");
        KEYWORDS.put("通讯", "wifi");
        KEYWORDS.put("保险", "shield");
        KEYWORDS.put("其他", "dots");
    }

    /** 性质项的图标 —— 它们不是消费,图标也该看起来不一样 */
    public static String forNature(String code) {
        if (code == null) return DEFAULT;
        return switch (code) {
            case "loan_payment" -> "bank";
            case "interest_paid" -> "percent";
            case "to_relatives" -> "users";
            default -> DEFAULT;
        };
    }

    /**
     * 按类目名猜一个图标 key。
     *
     * @return 永远返回一个可用的 key({@link #DEFAULT} 兜底),<b>不会是 null</b> ——
     *         模板里没有兜底分支,返回 null 会渲染出一个空方块
     */
    public static String of(String name) {
        if (name == null || name.isBlank()) return DEFAULT;
        String n = name.trim();
        for (var e : KEYWORDS.entrySet()) {
            if (n.contains(e.getKey())) return e.getValue();
        }
        return DEFAULT;
    }
}
