package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseNature;

import java.util.List;

/**
 * v1.24 FR-666 · 支出章节的配色 —— <b>全页唯一来源</b>。
 *
 * <h3>为什么要一个常量类而不是各处写十六进制</h3>
 *
 * <p>v1.21 的 9 色泥色板散在 {@code ReportsController} 的一个方法里,
 * 饼图用它、趋势柱也用它,但归因瀑布和三分条各写各的 ——
 * 结果同一页上「弹性」和「交通出行」可以撞成同一个颜色,而那是纯粹的误导。
 * 一个语义一个色、全页复用,只有把值收在一处才做得到。</p>
 *
 * <h3>三条硬规矩(第 1 稿三条全违反)</h3>
 *
 * <ol>
 *   <li><b>用实色,不用低 alpha。</b>纸底是米色 {@code #F4EFE6},
 *       20–55% alpha 叠上去之后饱和度被纸底稀释、明度全被拉到 85–92,整张图灰蒙蒙。
 *       要低饱和就直接选低饱和的实色,别让纸底替你调色。</li>
 *   <li><b>靠明度拉开,不靠饱和度。</b>晚清账册风不允许高饱和,
 *       那唯一能用的维度就是 L*。相邻片明度差 ≥ 15 —— 由单测
 *       {@code ExpensePaletteTest} 真算 CIE L* 来守(shell 算不了 L*,所以护栏在单测里)。</li>
 *   <li><b>一个语义一个色。</b>三分条 / 12 期分层柱 / 归因瀑布的一次性段用同一个值。</li>
 * </ol>
 */
public final class ExpensePalette {

    private ExpensePalette() {}

    /**
     * FR-666 · 类目枚举色板,<b>按明度交替排</b>,让相邻扇片对比最大。
     *
     * <p>全部直接取项目既有品牌色(slate / brass / plum / rust / forest),
     * 只补了一个水青占第 6 色位 —— 同一个 app 的图不该各用一套颜色。</p>
     *
     * <p>第 5 与第 6 明度接近(40/41),但一个赭红一个松绿,<b>色相对立</b>,可区分。</p>
     */
    public static final List<String> CATEGORY = List.of(
            "#3C4A5A",   // slate  · L* 29
            "#B08642",   // brass  · L* 57
            "#5C3A4B",   // plum   · L* 27
            "#6F8C8A",   // 水青    · L* 53
            "#9C4A2A",   // rust   · L* 40
            "#4F6B47"    // forest · L* 41
    );

    /** 「其他 N 项」的中性灰(ink-subtle · L* 62)—— 它是背景信息,不该抢视线 */
    public static final String OTHER = "#A09486";

    /** 「未分类」(rule · L* 76)+ 斜纹。最浅的一档,同样是背景信息 */
    public static final String UNCLASSIFIED = "#C9BDA8";

    /**
     * FR-630 · 三分的<b>语义色</b>,刻意不共用上面的枚举色板。
     *
     * <p>刚性与弹性是<b>同一根轴的两端</b>(可压缩性),所以用同色相的深浅两档,
     * 用明度表达而不是两个色相 —— 淡灰蓝 vs 淡灰绿在米色纸上几乎不可区分,第 1 稿就栽在这。</p>
     *
     * <p>一次性<b>离轴</b>(它不是「压不压得动」的问题),所以换色相 + 加纹理。</p>
     *
     * <p>弹性最初配的是绿 {@code #6E8B6A},和枚举色板第 4 位水青 {@code #6F8C8A}
     * 在低饱和下几乎同色 —— 同一页上「弹性」和「交通出行」撞成一个颜色是误导,改成浅 slate。</p>
     */
    public static String of(ExpenseNature nature) {
        return switch (nature) {
            case RIGID  -> "#3C4A5A";   // L* 29 · 可压缩轴的深端
            case FLEX   -> "#7B8FA0";   // L* 58 · 同色相的浅端
            case ONE_OFF -> "#C2A56B";  // L* 69 · 离轴 → 换色相,另加 3px 斜纹
        };
    }

    /** 三分色上的文字色 —— 跟着背景明度走,不让模板自己判断 */
    public static String inkOn(ExpenseNature nature) {
        return nature == ExpenseNature.RIGID ? "#FFFFFF" : "#2B2A28";
    }

    /**
     * 一次性段是否要斜纹。
     *
     * <p>斜纹宽度定在 <b>3px</b>:5px 的粗斜纹在小面积上像施工警示带(第 1 稿实测)。
     * 具体 CSS 在模板里,这里只回答「要不要」,免得模板去判断语义。</p>
     */
    public static boolean hatched(ExpenseNature nature) {
        return nature == ExpenseNature.ONE_OFF;
    }

    /**
     * 按序取第 i 个类目色;超出色板长度回落到「其他」灰。
     *
     * <p>不取模循环 —— 循环会让第 7 片和第 1 片同色,而 FR-665 已经把同屏片数压到
     * 最多 6 + 其他,真出现第 7 个就说明 top-N 逻辑漏了,那时候该显眼地退化成灰,
     * 而不是伪装成一个正常颜色。</p>
     */
    public static String categoryAt(int i) {
        return (i >= 0 && i < CATEGORY.size()) ? CATEGORY.get(i) : OTHER;
    }
}
