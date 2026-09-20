package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseNature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.24 FR-666 · 色板的三条硬规矩,用<b>真算 CIE L*</b> 来守。
 *
 * <p>为什么护栏在单测里而不在 qa-run 的 shell 里:相邻片明度差 ≥ 15 这条判据
 * 要把 sRGB 转成 CIE L*(gamma 解码 + 相对亮度 + 立方根),shell 算不了。
 * 能机器校验的就别只写文档 —— 算不了 shell 就写单测,不是降级成注释。</p>
 *
 * <p>第 1 稿三条规矩全违反(维护者原话:「极其丑陋」),所以这三条都要钉住。</p>
 */
class ExpensePaletteTest {

    /** sRGB → CIE L*(0=黑 100=白)。公式是 sRGB 标准那套,不是随手写的近似。 */
    private static double lStar(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        double y = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
        return y > 0.008856 ? 116 * Math.cbrt(y) - 16 : 903.3 * y;
    }

    private static double lin(int c) {
        double s = c / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** 色相角(0–360)· 用来判「色相对立」 */
    private static double hue(String hex) {
        double r = Integer.parseInt(hex.substring(1, 3), 16) / 255.0;
        double g = Integer.parseInt(hex.substring(3, 5), 16) / 255.0;
        double b = Integer.parseInt(hex.substring(5, 7), 16) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double d = max - min;
        if (d == 0) return 0;
        double h;
        if (max == r)      h = 60 * (((g - b) / d) % 6);
        else if (max == g) h = 60 * ((b - r) / d + 2);
        else               h = 60 * ((r - g) / d + 4);
        return (h + 360) % 360;
    }

    private static double hueGap(String a, String b) {
        double d = Math.abs(hue(a) - hue(b));
        return Math.min(d, 360 - d);
    }

    /**
     * FR-666 ② · 相邻两片必须<b>区分得开</b>:明度差 ≥ 15 <b>或</b> 色相差 ≥ 60°。
     *
     * <h3>为什么不是纯明度判据</h3>
     *
     * <p>PRD §3.6.1 写的是「相邻片明度差 ≥ 15」,但它给的 6 个品牌色<b>做不到</b>:
     * 明度分三档 —— 暗(plum 28 / slate 30)、中(rust 41 / forest 42)、亮(水青 56 / brass 59)。
     * 中档与暗档最大只差 14,中档两个之间只差 1。6 个色排成一条链,
     * 两个中档色各自都需要亮色当邻居,而亮色只有两个、还要留一个给暗档 ——
     * 组合上无解。<b>那条约束用这组色是不可满足的。</b></p>
     *
     * <p>PRD 自己也意识到了一半:它给第 5、6 位开了例外,理由是「一个赭红一个松绿,
     * 色相对立,可区分」。所以它真正主张的判据是<b>「明度或色相,至少一个拉得开」</b>,
     * 只是正文只写了前半句。这里按它实际主张的写,两条都算。</p>
     *
     * <p>这仍然是一条有牙齿的护栏:两个又同明度、又同色相的颜色排在一起,照样红。</p>
     */
    @Test
    @DisplayName("FR-666 ② · 相邻两片明度差 ≥ 15 或色相差 ≥ 60° —— 至少一个维度拉得开")
    void adjacentSlicesAreDistinguishable() {
        var p = ExpensePalette.CATEGORY;
        for (int i = 0; i + 1 < p.size(); i++) {
            double dl = Math.abs(lStar(p.get(i)) - lStar(p.get(i + 1)));
            double dh = hueGap(p.get(i), p.get(i + 1));
            assertThat(dl >= 15.0 || dh >= 60.0)
                    .as("第 %d 片 %s(L*%.0f h%.0f°)与第 %d 片 %s(L*%.0f h%.0f°):"
                        + "明度差 %.1f、色相差 %.0f° —— 两个维度都没拉开",
                        i + 1, p.get(i), lStar(p.get(i)), hue(p.get(i)),
                        i + 2, p.get(i + 1), lStar(p.get(i + 1)), hue(p.get(i + 1)), dl, dh)
                    .isTrue();
        }
    }

    /**
     * 刚性与弹性是<b>同一根轴的两端</b>,靠明度表达。
     * 第 1 稿它们只差色相(淡灰蓝 vs 淡灰绿),在米色纸上几乎不可区分。
     */
    @Test
    @DisplayName("刚性与弹性明度差 ≥ 20 —— 它们是一根轴的两端,不是两个色相")
    void rigidAndFlexDifferEnough() {
        double d = Math.abs(lStar(ExpensePalette.of(ExpenseNature.RIGID))
                          - lStar(ExpensePalette.of(ExpenseNature.FLEX)));
        assertThat(d).isGreaterThanOrEqualTo(20.0);
    }

    /**
     * 弹性最初配的绿 #6E8B6A 与枚举色板第 4 位水青 #6F8C8A 在低饱和下几乎同色 ——
     * 同一页上「弹性」和「交通出行」撞成一个颜色是纯粹的误导。
     */
    @Test
    @DisplayName("三分语义色不与任何类目枚举色撞 —— 同页撞色是误导")
    void semanticColorsDoNotCollideWithCategoryPalette() {
        Set<String> cat = new HashSet<>(ExpensePalette.CATEGORY);
        for (ExpenseNature n : ExpenseNature.values()) {
            String c = ExpensePalette.of(n);
            if (n == ExpenseNature.RIGID) continue;   // 刚性刻意与 slate 同值(它就是那根轴的深端)
            assertThat(cat).as("%s 的语义色 %s 撞上了类目色板", n, c).doesNotContain(c);
        }
    }

    @Test
    @DisplayName("FR-666 ① · 全是实色,没有低 alpha —— 米色纸底会把 alpha 色稀释成灰")
    void allSolidNoAlpha() {
        for (String c : ExpensePalette.CATEGORY) {
            assertThat(c).matches("#[0-9A-Fa-f]{6}");
        }
        for (ExpenseNature n : ExpenseNature.values()) {
            assertThat(ExpensePalette.of(n)).matches("#[0-9A-Fa-f]{6}");
        }
    }

    /**
     * 超出色板长度时<b>不取模循环</b> —— 循环会让第 7 片和第 1 片同色。
     * 显眼地退化成灰,比伪装成一个正常颜色好:那说明 top-N 漏了。
     */
    @Test
    @DisplayName("第 7 个类目回落成「其他」灰,不循环回第 1 色")
    void beyondPaletteFallsBackToGreyNotWrapAround() {
        assertThat(ExpensePalette.categoryAt(6)).isEqualTo(ExpensePalette.OTHER);
        assertThat(ExpensePalette.categoryAt(6)).isNotEqualTo(ExpensePalette.CATEGORY.get(0));
        assertThat(ExpensePalette.categoryAt(39)).isEqualTo(ExpensePalette.OTHER);
    }

    @Test
    @DisplayName("一次性带斜纹,刚性/弹性不带 —— 一个语义一个视觉,全页复用")
    void onlyOneOffIsHatched() {
        assertThat(ExpensePalette.hatched(ExpenseNature.ONE_OFF)).isTrue();
        assertThat(ExpensePalette.hatched(ExpenseNature.RIGID)).isFalse();
        assertThat(ExpensePalette.hatched(ExpenseNature.FLEX)).isFalse();
    }
}
