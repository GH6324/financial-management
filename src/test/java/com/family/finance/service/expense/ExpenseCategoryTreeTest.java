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

    private ExpenseFakes.FakeFlowMapper flows;
    private ExpenseFakes.FakeCategoryMapper cats;
    private ExpenseCategoryService svc;

    @BeforeEach
    void setUp() {
        flows = new ExpenseFakes.FakeFlowMapper();
        cats = new ExpenseFakes.FakeCategoryMapper();
        svc = new ExpenseCategoryService(cats, flows);
    }

    /** 记几笔到某个分类上 —— 第 2 稿的钱挂在【笔】上,不是「格」上 */
    private void spend(long periodId, long categoryId, String amount) {
        flows.add(periodId, categoryId, amount);
    }

    /** 全家挂在分类上的钱合计 —— 删类目「一分不丢」的断言靠它 */
    private BigDecimal totalOnCategories() {
        return flows.rows.stream().map(ExpenseFakes.FakeFlowMapper.Row::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 某个分类上现在挂着多少钱 */
    private BigDecimal onCategory(long categoryId) {
        return flows.rows.stream().filter(r -> r.categoryId() != null && r.categoryId() == categoryId)
                .map(ExpenseFakes.FakeFlowMapper.Row::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal y(String s) { return new BigDecimal(s); }

    // ─────────────── 起步包:两个深度,一级完全相同 ───────────────

    @Test
    @DisplayName("【兼容地基】简单版与复杂版的一级完全相同 —— 映射就是父子边,不需要映射表")
    void bothDepthsShareTheSameTopLevel() {
        var simple = new ExpenseFakes.FakeCategoryMapper();
        new ExpenseCategoryService(simple, flows).seed(FAM, false);
        var deep = new ExpenseFakes.FakeCategoryMapper();
        new ExpenseCategoryService(deep, flows).seed(FAM, true);

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
        spend(PERIOD, top, "500");
        spend(PERIOD, kid, "300");

        svc.delete(FAM, kid);

        assertThat(onCategory(top))
                .as("外卖那 300 应该落到父级「餐饮美食」上,不是消失也不是跑去「其他」")
                .isEqualByComparingTo("800");
        assertThat(totalOnCategories()).isEqualByComparingTo("800");
    }

    @Test
    @DisplayName("【钱不能丢】删大类(连带细类) → 搬到「其他」,全家总合计一分不变")
    void deleteTopMovesToOther() {
        long top = svc.create(FAM, null, "宠物").getId();
        long kid = svc.create(FAM, top, "猫粮").getId();
        long keep = svc.create(FAM, null, "餐饮美食").getId();
        long other = svc.ensureOther(FAM).getId();
        spend(PERIOD, top, "120");
        spend(PERIOD, kid, "80");
        spend(PERIOD, keep, "1000");
        assertThat(totalOnCategories()).isEqualByComparingTo("1200");

        svc.delete(FAM, top);

        assertThat(totalOnCategories()).as("总合计一分不变").isEqualByComparingTo("1200");
        assertThat(onCategory(other)).as("宠物 120 + 猫粮 80 都进了「其他」").isEqualByComparingTo("200");
        assertThat(onCategory(keep)).isEqualByComparingTo("1000");
        assertThat(cats.byId).doesNotContainKey(top);
        assertThat(cats.byId).as("细类跟着删").doesNotContainKey(kid);
    }

    @Test
    @DisplayName("父子都有钱时搬家是【相加】,不是覆盖")
    void moveAddsInsteadOfOverwriting() {
        long top = svc.create(FAM, null, "餐饮美食").getId();
        long kid = svc.create(FAM, top, "外卖").getId();
        // 第 1 稿这里会撞 uk_split(期,人,类目,来源) 唯一键,得先合并同槽行再删源行;
        // 逐笔载体没有这个问题 —— 每一笔本来就是独立一行,一条 UPDATE 就够。
        spend(PERIOD, top, "500");
        spend(PERIOD, kid, "300");
        svc.delete(FAM, kid);
        assertThat(onCategory(top)).as("500 + 300,不是被 300 覆盖成 300").isEqualByComparingTo("800");
    }

    @Test
    @DisplayName("删除预告要说清会动多少期、搬到哪 —— 用户点确认前得知道后果")
    void previewTellsTheTruth() {
        long top = svc.create(FAM, null, "餐饮美食").getId();
        long kid = svc.create(FAM, top, "外卖").getId();
        spend(PERIOD, kid, "300");
        spend(PERIOD + 1, kid, "200");

        var leafImpact = svc.previewDelete(FAM, kid);
        assertThat(leafImpact.topLevel()).isFalse();
        assertThat(leafImpact.targetName()).isEqualTo("餐饮美食");
        assertThat(leafImpact.periods()).isEqualTo(2);
        assertThat(leafImpact.rows()).as("会动 2 笔").isEqualTo(2);
        assertThat(leafImpact.amount()).isEqualByComparingTo("500");

        var topImpact = svc.previewDelete(FAM, top);
        assertThat(topImpact.topLevel()).isTrue();
        assertThat(topImpact.targetName()).isEqualTo("其他");
        assertThat(topImpact.childCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("停用大类 → 其下细类一并停用(否则细类挂在看不见的大类下)")
    void archivingTopArchivesChildren() {
        long top = svc.create(FAM, null, "宠物").getId();
        long kid = svc.create(FAM, top, "猫粮").getId();
        svc.setArchived(FAM, top, true);
        assertThat(cats.byId.get(top).isArchived()).isTrue();
        assertThat(cats.byId.get(kid).isArchived()).isTrue();
        // 「其他」永远可选 —— 它是兜底项,不是普通类目
        assertThat(svc.pickable(FAM).keySet()).extracting("name")
                .as("停用的大类不出现在宫格里;但兜底的「其他」始终在").containsExactly("其他");
    }

    @Test
    @DisplayName("宫格给整棵树:大类永远可选,细类挂在它下面(没有「当前深度」这回事)")
    void pickableGivesWholeTree() {
        long a = svc.create(FAM, null, "餐饮美食").getId();
        svc.create(FAM, a, "外卖");
        svc.create(FAM, null, "交通出行");   // 没有细类

        var grid = svc.pickable(FAM);
        // 「其他」排最后(sortOrder 9999)—— 它是兜底项,不该抢占前面的位置
        assertThat(grid.keySet()).extracting("name")
                .containsExactly("餐饮美食", "交通出行", "其他");
        assertThat(grid.values().stream().flatMap(java.util.List::stream)).extracting("name")
                .as("细类只有外卖一个;交通出行没细类,点它本身就能提交")
                .containsExactly("外卖");
    }

    @Test
    @DisplayName("最近常用按【用过的笔数】排,不按最后一次使用时间")
    void recentUsedRanksByCount() {
        long food = svc.create(FAM, null, "餐饮美食").getId();
        long med = svc.create(FAM, null, "医疗健康").getId();
        for (int i = 0; i < 5; i++) spend(PERIOD, food, "30");
        spend(PERIOD, med, "800");        // 金额大但只有一笔

        assertThat(svc.recentUsed(FAM, 3)).extracting("name")
                .as("偶然记过一笔「医疗健康」不该顶到第一位")
                .containsExactly("餐饮美食", "医疗健康");
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

    // ─────────────── 导入落的笔也跟着搬 ───────────────

    @Test
    @DisplayName("导入落的笔也跟着搬迁,不只搬手工记的")
    void moveCarriesImportedRowsToo() {
        long top = svc.create(FAM, null, "餐饮美食").getId();
        long kid = svc.create(FAM, top, "外卖").getId();
        spend(PERIOD, kid, "700");            // 来自导入还是手记,在逐笔载体上没有区别
        svc.delete(FAM, kid);
        assertThat(onCategory(top)).as("那 700 也要搬到父级").isEqualByComparingTo("700");
        assertThat(totalOnCategories()).isEqualByComparingTo("700");
    }

    // ─────────────── 停用与可选 ───────────────

    @Test
    @DisplayName("停用的类目不再出现在宫格里,但它上面的历史钱一分不动")
    void archivedCategoryKeepsItsMoney() {
        long c = svc.create(FAM, null, "宠物").getId();
        spend(PERIOD, c, "120");
        svc.setArchived(FAM, c, true);

        assertThat(svc.pickable(FAM).keySet()).extracting("name").doesNotContain("宠物");
        assertThat(onCategory(c))
                .as("停用只影响「以后还能不能选」,不动历史")
                .isEqualByComparingTo("120");
        assertThat(svc.isUsable(FAM, c)).as("停用的不能再被新的笔选中").isFalse();
    }

    @Test
    @DisplayName("isUsable 挡住别家的 id 与不存在的 id —— 录入/导入落库前的最后一道")
    void isUsableGuardsOwnership() {
        long mine = svc.create(FAM, null, "餐饮美食").getId();
        assertThat(svc.isUsable(FAM, mine)).isTrue();
        assertThat(svc.isUsable(FAM, 99999L)).isFalse();
        assertThat(svc.isUsable(FAM, null)).isFalse();
    }

    @Test
    @DisplayName("显示名:细类带父名(「餐饮美食 › 外卖」),大类就是它自己")
    void displayNameShowsParent() {
        long top = svc.create(FAM, null, "餐饮美食").getId();
        long kid = svc.create(FAM, top, "外卖").getId();
        assertThat(svc.displayName(FAM, top)).isEqualTo("餐饮美食");
        assertThat(svc.displayName(FAM, kid)).isEqualTo("餐饮美食 › 外卖");
        assertThat(svc.displayName(FAM, null)).isEqualTo("未分类");
    }
}
