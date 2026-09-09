package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.domain.expense.ExpenseImportBatch;
import com.family.finance.domain.expense.ExpenseSource;
import com.family.finance.domain.expense.ExpenseSplit;
import com.family.finance.repository.ExpenseCategoryMapper;
import com.family.finance.repository.ExpenseImportBatchMapper;
import com.family.finance.repository.ExpenseSplitMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.21 · 两级恒等式与合并语义。
 *
 * <p>这个类里最重要的一条是 {@link #manualSurvivesReimport()} ——
 * 「我改过的数被一次重导抹了」是这个功能最伤信任的失败形式,
 * 而它在「只存合成额」的设计下<b>必然发生</b>。这条测试钉住的是那个结构决定。</p>
 *
 * <p>用内存假 mapper 而不是 @SpringBootTest:恒等式是纯逻辑,
 * 上下文装配会把一条 10ms 的断言变成 3s。</p>
 */
class ExpenseSplitServiceTest {

    private static final long FAM = 1L, PERIOD = 100L, ME = 7L;

    private ExpenseFakes.FakeSplitMapper splits;
    private ExpenseFakes.FakePmc pmc;
    private ExpenseSplitService svc;
    private long catFood, catTransit, catOther;

    // 假 mapper 在 ExpenseFakes(三个测试类共用)

    @BeforeEach
    void setUp() {
        splits = new ExpenseFakes.FakeSplitMapper();
        var cats = new ExpenseFakes.FakeCategoryMapper();
        var batches = new ExpenseFakes.FakeBatchMapper();
        pmc = new ExpenseFakes.FakePmc();
        var catSvc = new ExpenseCategoryService(cats, splits);
        svc = new ExpenseSplitService(splits, cats, batches, pmc.asMapper(), catSvc);

        catFood = catSvc.create(FAM, null, "餐饮美食").getId();
        catTransit = catSvc.create(FAM, null, "交通出行").getId();
        catOther = catSvc.ensureOther(FAM).getId();
    }

    private static BigDecimal y(String s) { return new BigDecimal(s); }

    // ─────────────────────────────────────────────────────────
    // 恒等式
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("手填:PMC 总额 = Σ 各类目")
    void manualSumsToPmc() {
        svc.saveManual(FAM, PERIOD, ME, Map.of(catFood, y("3180"), catTransit, y("860")));
        assertThat(svc.sumOf(PERIOD, ME)).isEqualByComparingTo("4040");
        assertThat(pmc.expense).isEqualByComparingTo("4040");
        assertThat(pmc.incomeTouched)
                .as("绝不能走那条整行 upsert —— 它会把收入抹成 null")
                .isFalse();
    }

    @Test
    @DisplayName("导入 + 手填相加,不是互相覆盖")
    void importAndManualAreAdditive() {
        svc.applyBatch(FAM, PERIOD, ME, ME, ExpenseSource.ALIPAY, Map.of(catFood, y("2180")), 41);
        svc.saveManual(FAM, PERIOD, ME, Map.of(catFood, y("2500")));   // 用户把它改大到 2500
        var cell = svc.cells(PERIOD, ME).get(catFood);
        assertThat(cell.total()).as("显示额 = 用户期望值").isEqualByComparingTo("2500");
        assertThat(cell.imported()).as("导入行原样保留可审计").isEqualByComparingTo("2180");
        assertThat(cell.manual()).as("手填行是差额").isEqualByComparingTo("320");
        assertThat(pmc.expense).isEqualByComparingTo("2500");
    }

    @Test
    @DisplayName("【最重要】重导只换该渠道的行 —— 手工修正永远不被冲掉")
    void manualSurvivesReimport() {
        svc.applyBatch(FAM, PERIOD, ME, ME, ExpenseSource.ALIPAY, Map.of(catFood, y("2180")), 41);
        svc.applyBatch(FAM, PERIOD, ME, ME, ExpenseSource.WECHAT, Map.of(catFood, y("1000")), 22);
        svc.saveManual(FAM, PERIOD, ME, Map.of(catFood, y("3500")));   // 手工 = 3500 − 3180 = 320

        // 重导支付宝:2180 → 2340
        svc.applyBatch(FAM, PERIOD, ME, ME, ExpenseSource.ALIPAY, Map.of(catFood, y("2340")), 43);

        var cell = svc.cells(PERIOD, ME).get(catFood);
        assertThat(cell.bySource().get(ExpenseSource.MANUAL))
                .as("手工行逐分不变 —— 这是这个功能最不能出错的地方")
                .isEqualByComparingTo("320");
        assertThat(cell.bySource().get(ExpenseSource.WECHAT))
                .as("其它渠道也不该被动")
                .isEqualByComparingTo("1000");
        assertThat(cell.bySource().get(ExpenseSource.ALIPAY)).isEqualByComparingTo("2340");
        assertThat(cell.total()).isEqualByComparingTo("3660");     // 2340+1000+320
    }

    @Test
    @DisplayName("撤销批次:该渠道的行整批移除,其余来源自动回落")
    void revokeBatchFallsBack() {
        var b = svc.applyBatch(FAM, PERIOD, ME, ME, ExpenseSource.ALIPAY, Map.of(catFood, y("2180")), 41);
        svc.saveManual(FAM, PERIOD, ME, Map.of(catFood, y("2500")));
        svc.revokeBatch(FAM, b.batchId());
        var cell = svc.cells(PERIOD, ME).get(catFood);
        assertThat(cell.total()).as("只剩手填的 320").isEqualByComparingTo("320");
        assertThat(pmc.expense).isEqualByComparingTo("320");
    }

    @Test
    @DisplayName("期望值为负 → 拒绝,并说清怎么冲正")
    void negativeWantedRejected() {
        assertThatThrownBy(() -> svc.saveManual(FAM, PERIOD, ME, Map.of(catFood, y("-100"))))
                .isInstanceOf(ExpenseSplitService.SplitException.class)
                .hasMessageContaining("不能是负数");
    }

    @Test
    @DisplayName("并回一个总数:分类明细清空,但 PMC 总额留着")
    void collapseKeepsTotal() {
        svc.saveManual(FAM, PERIOD, ME, Map.of(catFood, y("3180"), catTransit, y("860")));
        assertThat(pmc.expense).isEqualByComparingTo("4040");
        svc.collapse(FAM, PERIOD, ME);
        assertThat(svc.hasSplits(PERIOD, ME)).isFalse();
        assertThat(pmc.expense)
                .as("用户要的是「不再拆」,不是「这个月没花钱」")
                .isEqualByComparingTo("4040");
    }

    @Test
    @DisplayName("一行分类都没有时 PMC 写 null(= 没用分类填报),不是 0")
    void noSplitsWritesNull() {
        svc.saveManual(FAM, PERIOD, ME, Map.of(catFood, y("0")));
        assertThat(pmc.expense).isNull();
    }

    // ─────────────────────────────────────────────────────────
    // 报表 rollup:未细分
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("记在大类上的钱在 rollup 里显示为「未细分」,不是丢掉")
    void unsplitShowsAsLeaf() {
        var cats = new ExpenseFakes.FakeCategoryMapper();
        var catSvc = new ExpenseCategoryService(cats, splits);
        long top = catSvc.create(FAM, null, "餐饮美食").getId();
        long kid = catSvc.create(FAM, top, "外卖").getId();
        var svc2 = new ExpenseSplitService(splits, cats, new ExpenseFakes.FakeBatchMapper(), pmc.asMapper(), catSvc);

        svc2.saveManual(FAM, PERIOD, ME, Map.of(top, y("500"), kid, y("300")));
        var roll = svc2.rollup(FAM, PERIOD);
        assertThat(roll).hasSize(1);
        assertThat(roll.get(0).total()).isEqualByComparingTo("800");
        assertThat(roll.get(0).leaves()).extracting("name").contains("外卖", "未细分");
        assertThat(roll.get(0).leaves().stream().filter(l -> l.unsplit()).findFirst().orElseThrow().amount())
                .isEqualByComparingTo("500");
    }
}
