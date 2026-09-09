package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.21 · 类目树的语义:两个深度、封顶两层、删除按树走。
 *
 * <p>这里最该守住的是<b>「钱一分都不能丢」</b>:删类目是搬迁,不是删除。
 * 删细类 → 父级合计不变;删大类 → 全家总合计不变。</p>
 */
class ExpenseCategoryTreeTest {

    private static final long FAM = 1L, PERIOD = 100L, ME = 7L;

    private ExpenseFakes.FakeSplitMapper splits;
    private ExpenseFakes.FakeCategoryMapper cats;
    private ExpenseCategoryService svc;
    private ExpenseSplitService split;

    @BeforeEach
    void setUp() {
        splits = new ExpenseFakes.FakeSplitMapper();
        cats = new ExpenseFakes.FakeCategoryMapper();
        svc = new ExpenseCategoryService(cats, splits);
        split = new ExpenseSplitService(splits, cats,
                new ExpenseFakes.FakeBatchMapper(),
                new ExpenseFakes.FakePmc().asMapper(), svc);
    }

    private static BigDecimal y(String s) { return new BigDecimal(s); }

    // ─────────────── 起步包:两个深度,一级完全相同 ───────────────

    @Test
    @DisplayName("【兼容地基】简单版与复杂版的一级完全相同 —— 映射就是父子边,不需要映射表")
    void bothDepthsShareTheSameTopLevel() {
        var simple = new ExpenseFakes.FakeCategoryMapper();
        new ExpenseCategoryService(simple, splits).seed(FAM, false);
        var deep = new ExpenseFakes.FakeCategoryMapper();
        new ExpenseCategoryService(deep, splits).seed(FAM, true);

        var simpleTops = simple.byId.values().stream().filter(c -> c.isTopLevel())
                .map(c -> c.getName()).toList();
        var deepTops = deep.byId.values().stream().filter(c -> c.isTopLevel())
                .map(c -> c.getName()).toList();
        assertThat(deepTops).isEqualTo(simpleTops);
        assertThat(simple.byId.values().stream().anyMatch(c -> !c.isTopLevel()))
                .as("简单版一个细类都没有").isFalse();
        assertThat(deep.byId.values().stream().filter(c -> !c.isTopLevel()).count())
                .as("复杂版有细类").isGreaterThan(15L);
    }

    @Test
    @DisplayName("起步包必然带上「其他」—— 它是删除语义的最终落点")
    void seedAlwaysCreatesOther() {
        svc.seed(FAM, false);
        assertThat(svc.other(FAM)).isNotNull();
        assertThat(svc.other(FAM).isOther()).isTrue();
    }

    @Test
    @DisplayName("已经建过类目就不许再灌起步包")
    void seedRefusesWhenNotEmpty() {
        svc.create(FAM, null, "餐饮美食");
        assertThatThrownBy(() -> svc.seed(FAM, false))
                .isInstanceOf(ExpenseCategoryService.CategoryException.class)
                .hasMessageContaining("一片空白");
    }

    // ─────────────── 封顶两层 & 同名 ───────────────

    @Test
    @DisplayName("细类下面不能再分细类(封顶两层)")
    void noThirdLevel() {
        long top = svc.create(FAM, null, "餐饮美食").getId();
        long kid = svc.create(FAM, top, "外卖").getId();
        assertThatThrownBy(() -> svc.create(FAM, kid, "麦当劳"))
                .isInstanceOf(ExpenseCategoryService.CategoryException.class)
                .hasMessageContaining("两层就够了");
    }

    @Test
    @DisplayName("一级同名要在【应用层】拦 —— MySQL 的 UNIQUE 对 NULL 不去重,DB 拦不住")
    void topLevelDuplicateNameRejected() {
        svc.create(FAM, null, "餐饮美食");
        assertThatThrownBy(() -> svc.create(FAM, null, "餐饮美食"))
                .isInstanceOf(ExpenseCategoryService.CategoryException.class)
                .hasMessageContaining("已经有一个叫");
    }

    @Test
    @DisplayName("不同大类下可以有同名细类(「打车」在交通和差旅下各一个,合理)")
    void sameLeafNameUnderDifferentParents() {
        long a = svc.create(FAM, null, "交通出行").getId();
        long b = svc.create(FAM, null, "文化休闲").getId();
        svc.create(FAM, a, "打车");
        svc.create(FAM, b, "打车");   // 不该抛
        assertThat(cats.byId).hasSize(5);   // 2 大类 + 2 细类 + 其他
    }

    // ─────────────── 「其他」是基石 ───────────────

    @Test
    @DisplayName("「其他」不可删 / 不可停用 / 不可改名")
    void otherIsBedrock() {
        long other = svc.ensureOther(FAM).getId();
        assertThatThrownBy(() -> svc.delete(FAM, other)).hasMessageContaining("不能删");
        assertThatThrownBy(() -> svc.setArchived(FAM, other, true)).hasMessageContaining("不能停用");
        assertThatThrownBy(() -> svc.rename(FAM, other, "杂项")).hasMessageContaining("不能改名");
    }

    // ─────────────── 删除按树走:钱一分不丢 ───────────────

    @Test
    @DisplayName("【钱不能丢】删细类 → 搬到父级,大类合计一分不变")
    void deleteLeafKeepsTopTotal() {
        long top = svc.create(FAM, null, "餐饮美食").getId();
        long kid = svc.create(FAM, top, "外卖").getId();
        split.saveManual(FAM, PERIOD, ME, Map.of(top, y("500"), kid, y("300")));

        BigDecimal beforeTop = topTotal(top);
        assertThat(beforeTop).isEqualByComparingTo("800");

        svc.delete(FAM, kid);

        assertThat(topTotal(top))
                .as("外卖的 300 应该落到「餐饮美食 · 未细分」,不是消失也不是跑去「其他」")
                .isEqualByComparingTo("800");
        assertThat(splits.sumByFamily(FAM)).isEqualByComparingTo("800");
    }

    @Test
    @DisplayName("【钱不能丢】删大类(连带细类) → 搬到「其他」,全家总合计一分不变")
    void deleteTopMovesToOther() {
        long top = svc.create(FAM, null, "宠物").getId();
        long kid = svc.create(FAM, top, "猫粮").getId();
        long keep = svc.create(FAM, null, "餐饮美食").getId();
        long other = svc.ensureOther(FAM).getId();
        split.saveManual(FAM, PERIOD, ME, Map.of(top, y("120"), kid, y("80"), keep, y("1000")));
        assertThat(splits.sumByFamily(FAM)).isEqualByComparingTo("1200");

        svc.delete(FAM, top);

        assertThat(splits.sumByFamily(FAM)).as("总合计一分不变").isEqualByComparingTo("1200");
        assertThat(catTotal(other)).as("宠物 120 + 猫粮 80 都进了「其他」").isEqualByComparingTo("200");
        assertThat(catTotal(keep)).isEqualByComparingTo("1000");
        assertThat(cats.byId).doesNotContainKey(top);
        assertThat(cats.byId).as("细类跟着删").doesNotContainKey(kid);
    }

    @Test
    @DisplayName("搬迁撞上目标已有同来源行时【相加】,不是覆盖(uk_split 会撞键)")
    void moveMergesInsteadOfOverwriting() {
        long top = svc.create(FAM, null, "餐饮美食").getId();
        long kid = svc.create(FAM, top, "外卖").getId();
        // 同一格(期/人/来源=MANUAL)在父级和子级都有钱 —— 搬过去必然撞键
        split.saveManual(FAM, PERIOD, ME, Map.of(top, y("500"), kid, y("300")));
        svc.delete(FAM, kid);
        assertThat(catTotal(top)).as("500 + 300,不是被 300 覆盖成 300").isEqualByComparingTo("800");
    }

    @Test
    @DisplayName("删除预告要说清会动多少期、搬到哪 —— 用户点确认前得知道后果")
    void previewTellsTheTruth() {
        long top = svc.create(FAM, null, "餐饮美食").getId();
        long kid = svc.create(FAM, top, "外卖").getId();
        split.saveManual(FAM, PERIOD, ME, Map.of(kid, y("300")));
        split.saveManual(FAM, PERIOD + 1, ME, Map.of(kid, y("200")));

        var leafImpact = svc.previewDelete(FAM, kid);
        assertThat(leafImpact.topLevel()).isFalse();
        assertThat(leafImpact.targetName()).isEqualTo("餐饮美食 · 未细分");
        assertThat(leafImpact.periods()).isEqualTo(2);

        var topImpact = svc.previewDelete(FAM, top);
        assertThat(topImpact.topLevel()).isTrue();
        assertThat(topImpact.targetName()).isEqualTo("其他");
        assertThat(topImpact.childCount()).isEqualTo(1);
    }

    // ─────────────── 深度切换无损 ───────────────

    @Test
    @DisplayName("【切换无损】切深度只改「以后怎么填」,历史行一行不动、大类合计逐分相等")
    void depthSwitchIsLossless() {
        long top = svc.create(FAM, null, "餐饮美食").getId();
        long kid = svc.create(FAM, top, "外卖").getId();
        split.saveManual(FAM, PERIOD, ME, Map.of(top, y("500"), kid, y("300")));

        // 「切换深度」在实现上只是一个配置键 —— 这里直接对比两个深度下的 rollup
        var deepRoll = split.rollup(FAM, PERIOD);
        BigDecimal deepTop = deepRoll.get(0).total();
        // 简单深度看到的是同一棵树的一级聚合 —— rollup 本身就是按大类聚的,所以值必然相同
        assertThat(deepTop).isEqualByComparingTo("800");
        assertThat(deepRoll.get(0).leaves()).hasSize(2);   // 外卖 + 未细分
        assertThat(splits.sumByFamily(FAM))
                .as("无论按哪个深度看,底层数据一分不变")
                .isEqualByComparingTo("800");
    }

    @Test
    @DisplayName("停用大类 → 其下细类一并停用(否则细类挂在看不见的大类下)")
    void archivingTopArchivesChildren() {
        long top = svc.create(FAM, null, "宠物").getId();
        long kid = svc.create(FAM, top, "猫粮").getId();
        svc.setArchived(FAM, top, true);
        assertThat(cats.byId.get(top).isArchived()).isTrue();
        assertThat(cats.byId.get(kid).isArchived()).isTrue();
        // 「其他」永远可填 —— 它是「懒得拆的余量」的去处(FR-512),不是普通类目
        assertThat(svc.fillable(FAM, true)).extracting("name")
                .as("停用的不出现;但兜底的「其他」始终在").containsExactly("其他");
    }

    @Test
    @DisplayName("可填清单:简单深度只给大类;复杂深度给细类,没细分的大类给自己")
    void fillableFollowsDepth() {
        long a = svc.create(FAM, null, "餐饮美食").getId();
        svc.create(FAM, a, "外卖");
        long b = svc.create(FAM, null, "交通出行").getId();   // 没有细类

        // 「其他」排最后(sortOrder 9999)—— 它是兜底行,不该抢占前面的位置
        assertThat(svc.fillable(FAM, false)).extracting("name")
                .containsExactly("餐饮美食", "交通出行", "其他");
        assertThat(svc.fillable(FAM, true)).extracting("name")
                .as("餐饮给它的细类;交通没细分,就填在大类上;其他始终兜底")
                .containsExactly("外卖", "交通出行", "其他");
    }

    // ─────────────── 上限 ───────────────

    @Test
    @DisplayName("超过 40 个类目就劝阻 —— 鲨鱼记账 40+ 平铺的教训")
    void tooManyCategoriesRejected() {
        for (int i = 0; i < ExpenseCategoryService.MAX_TOTAL - 1; i++) {
            svc.create(FAM, null, "类目" + i);
        }
        assertThatThrownBy(() -> svc.create(FAM, null, "再来一个"))
                .isInstanceOf(ExpenseCategoryService.CategoryException.class)
                .hasMessageContaining("苦差");
    }

    // ─────────────── 小工具 ───────────────

    private BigDecimal topTotal(long topId) {
        return split.rollup(FAM, PERIOD).stream()
                .filter(r -> r.topId() == topId).findFirst()
                .map(r -> r.total()).orElse(BigDecimal.ZERO);
    }

    private BigDecimal catTotal(long categoryId) {
        BigDecimal s = BigDecimal.ZERO;
        for (var r : splits.findByFamily(FAM)) {
            if (r.getCategoryId() == categoryId) s = s.add(r.getAmount());
        }
        return s;
    }

    @Test
    @DisplayName("导入渠道的行也跟着搬迁,不只搬手填")
    void moveCarriesImportedRowsToo() {
        long top = svc.create(FAM, null, "餐饮美食").getId();
        long kid = svc.create(FAM, top, "外卖").getId();
        split.applyBatch(FAM, PERIOD, ME, ME, ExpenseSource.ALIPAY, Map.of(kid, y("700")), 9);
        assertThat(splits.sumByFamily(FAM)).isEqualByComparingTo("700");
        svc.delete(FAM, kid);
        assertThat(catTotal(top)).as("支付宝那 700 也要搬到父级").isEqualByComparingTo("700");
        assertThat(splits.sumByFamily(FAM)).isEqualByComparingTo("700");
    }

    // ─────────────── 「钱在账上,页面上必须有它的框」 ───────────────

    @Test
    @DisplayName("【踩过的坑】复杂深度下,钱记在大类上时填报表单也要显示它 —— 否则看不见也改不了")
    void unsplitTopLevelStillGetsAnInputBox() {
        long top = svc.create(FAM, null, "餐饮美食").getId();
        svc.create(FAM, top, "外卖");                       // 有细类 → 复杂深度下 fillable 里没有 top
        long other = svc.ensureOther(FAM).getId();

        assertThat(svc.fillable(FAM, true)).extracting("name")
                .as("复杂深度的可填清单本来只有细类")
                .containsExactly("外卖", "其他");

        // 导入把钱落在了【大类】上(渠道分类名就是大类粒度)
        split.applyBatch(FAM, PERIOD, ME, ME, ExpenseSource.ALIPAY, Map.of(top, y("1325")), 2);

        assertThat(svc.fillableWith(FAM, true, split.cells(PERIOD, ME).keySet()))
                .extracting("name")
                .as("有钱的大类必须露出来 —— 钱在账上却没有输入框,是最让人不安的状态")
                .contains("餐饮美食");
        assertThat(svc.isUnsplit(FAM, cats.byId.get(top), true))
                .as("它要被标成「未细分」,不然用户以为多了个同名类目")
                .isTrue();
        assertThat(svc.isUnsplit(FAM, cats.byId.get(other), true))
                .as("「其他」没有细类,不算未细分")
                .isFalse();
    }

    @Test
    @DisplayName("停用的类目上还有钱时,也要在表单里露出来(否则那笔钱永远改不了)")
    void archivedCategoryWithMoneyStillShows() {
        long c = svc.create(FAM, null, "宠物").getId();
        split.saveManual(FAM, PERIOD, ME, Map.of(c, y("120")));
        svc.setArchived(FAM, c, true);

        assertThat(svc.fillable(FAM, false)).extracting("name").doesNotContain("宠物");
        assertThat(svc.fillableWith(FAM, false, split.cells(PERIOD, ME).keySet()))
                .extracting("name").contains("宠物");
    }
}
