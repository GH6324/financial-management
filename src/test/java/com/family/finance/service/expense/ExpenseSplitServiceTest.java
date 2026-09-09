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

    private FakeSplitMapper splits;
    private FakePmc pmc;
    private ExpenseSplitService svc;
    private long catFood, catTransit, catOther;

    // ──────────────── 内存假实现 ────────────────

    /** 按 uk_split(period,member,category,source) 唯一 —— 与真表约束一致,否则测不出双计 */
    static class FakeSplitMapper implements ExpenseSplitMapper {
        final Map<String, ExpenseSplit> rows = new LinkedHashMap<>();
        static String k(long p, long m, long c, String s) { return p + "/" + m + "/" + c + "/" + s; }

        @Override public int upsert(ExpenseSplit r) {
            rows.put(k(r.getPeriodId(), r.getMemberId(), r.getCategoryId(), r.getSource().name()), r);
            return 1;
        }
        @Override public List<ExpenseSplit> findByPeriodMember(long periodId, long memberId) {
            return rows.values().stream()
                    .filter(r -> r.getPeriodId() == periodId && r.getMemberId() == memberId).toList();
        }
        @Override public List<ExpenseSplit> findByPeriod(long familyId, long periodId) {
            return rows.values().stream().filter(r -> r.getPeriodId() == periodId).toList();
        }
        @Override public List<ExpenseSplit> findByFamily(long familyId) { return new ArrayList<>(rows.values()); }
        @Override public BigDecimal sumByPeriodMember(long periodId, long memberId) {
            var list = findByPeriodMember(periodId, memberId);
            if (list.isEmpty()) return null;
            BigDecimal s = BigDecimal.ZERO;
            for (ExpenseSplit r : list) s = s.add(r.getAmount());
            return s;
        }
        @Override public int deleteBySource(long periodId, long memberId, String source) {
            return (int) removeIf(r -> r.getPeriodId() == periodId && r.getMemberId() == memberId
                    && r.getSource().name().equals(source));
        }
        @Override public int deleteByPeriodMember(long periodId, long memberId) {
            return (int) removeIf(r -> r.getPeriodId() == periodId && r.getMemberId() == memberId);
        }
        @Override public int deleteOne(long periodId, long memberId, long categoryId, String source) {
            return rows.remove(k(periodId, memberId, categoryId, source)) == null ? 0 : 1;
        }
        @Override public int deleteByCategory(long familyId, long categoryId) {
            return (int) removeIf(r -> r.getCategoryId() == categoryId);
        }
        @Override public int countPeriodsUsing(long familyId, long categoryId) {
            return (int) rows.values().stream().filter(r -> r.getCategoryId() == categoryId)
                    .map(ExpenseSplit::getPeriodId).distinct().count();
        }
        @Override public int countRowsUsing(long familyId, long categoryId) {
            return (int) rows.values().stream().filter(r -> r.getCategoryId() == categoryId).count();
        }
        @Override public BigDecimal sumByFamily(long familyId) {
            BigDecimal s = BigDecimal.ZERO;
            for (ExpenseSplit r : rows.values()) s = s.add(r.getAmount());
            return s;
        }
        private long removeIf(java.util.function.Predicate<ExpenseSplit> p) {
            var keys = rows.entrySet().stream().filter(e -> p.test(e.getValue()))
                    .map(Map.Entry::getKey).toList();
            keys.forEach(rows::remove);
            return keys.size();
        }
    }

    static class FakeCategoryMapper implements ExpenseCategoryMapper {
        final Map<Long, ExpenseCategory> byId = new LinkedHashMap<>();
        final AtomicLong seq = new AtomicLong(1);
        @Override public int insert(ExpenseCategory c) {
            c.setId(seq.getAndIncrement()); byId.put(c.getId(), c); return 1;
        }
        /** 与真 SQL 的 {@code ORDER BY sort_order, id} 保持一致 —— 假实现的排序和真表不同会掩盖排序 bug */
        @Override public List<ExpenseCategory> findByFamily(long familyId) {
            return byId.values().stream()
                    .sorted(java.util.Comparator
                            .comparingInt((ExpenseCategory c) -> c.getSortOrder() == null ? 0 : c.getSortOrder())
                            .thenComparingLong(ExpenseCategory::getId))
                    .toList();
        }
        @Override public ExpenseCategory find(long familyId, long id) { return byId.get(id); }
        @Override public int rename(ExpenseCategory c) {
            ExpenseCategory e = byId.get(c.getId());
            if (e == null || e.getSystemCode() != null) return 0;
            e.setName(c.getName()); e.setSortOrder(c.getSortOrder()); return 1;
        }
        @Override public int setArchived(long familyId, long id, boolean a) {
            ExpenseCategory e = byId.get(id);
            if (e == null || e.getSystemCode() != null) return 0;
            e.setArchivedAt(a ? java.time.LocalDateTime.now() : null); return 1;
        }
        @Override public int setArchivedByParent(long familyId, long parentId, boolean a) {
            byId.values().stream().filter(c -> parentId == (c.getParentId() == null ? -1L : c.getParentId()))
                    .filter(c -> c.getSystemCode() == null)
                    .forEach(c -> c.setArchivedAt(a ? java.time.LocalDateTime.now() : null));
            return 1;
        }
        @Override public int delete(long familyId, long id) {
            ExpenseCategory e = byId.get(id);
            if (e == null || e.getSystemCode() != null) return 0;
            byId.remove(id); return 1;
        }
        @Override public int deleteChildren(long familyId, long parentId) {
            var ids = byId.values().stream()
                    .filter(c -> parentId == (c.getParentId() == null ? -1L : c.getParentId()))
                    .map(ExpenseCategory::getId).toList();
            ids.forEach(byId::remove); return ids.size();
        }
        @Override public int countByFamily(long familyId) { return byId.size(); }
        @Override public ExpenseCategory findBySystemCode(long familyId, String code) {
            return byId.values().stream().filter(c -> code.equals(c.getSystemCode())).findFirst().orElse(null);
        }
    }

    static class FakeBatchMapper implements ExpenseImportBatchMapper {
        final Map<Long, ExpenseImportBatch> byId = new LinkedHashMap<>();
        final AtomicLong seq = new AtomicLong(1);
        @Override public int insert(ExpenseImportBatch b) {
            b.setId(seq.getAndIncrement()); byId.put(b.getId(), b); return 1;
        }
        @Override public List<ExpenseImportBatch> findLiveByPeriod(long familyId, long periodId) {
            return byId.values().stream()
                    .filter(b -> b.getPeriodId() == periodId && b.getRevokedAt() == null).toList();
        }
        @Override public ExpenseImportBatch findLive(long familyId, long periodId, long memberId, String channel) {
            return byId.values().stream()
                    .filter(b -> b.getPeriodId() == periodId && b.getMemberId() == memberId
                            && b.getChannel().name().equals(channel) && b.getRevokedAt() == null)
                    .reduce((a, b) -> b).orElse(null);
        }
        @Override public ExpenseImportBatch find(long familyId, long id) { return byId.get(id); }
        @Override public int revoke(long familyId, long id) {
            ExpenseImportBatch b = byId.get(id);
            if (b == null || b.getRevokedAt() != null) return 0;
            b.setRevokedAt(java.time.LocalDateTime.now()); return 1;
        }
    }

    /**
     * PMC 用 mock 而不是手写假实现 —— 这个接口有十几个聚合方法,
     * 而这里只关心两件事:写进去的支出值是多少、有没有误用那条会抹掉收入的整行 upsert。
     */
    static class FakePmc {
        BigDecimal expense;
        boolean incomeTouched = false;

        PeriodMemberCashflowMapper asMapper() {
            PeriodMemberCashflowMapper m = org.mockito.Mockito.mock(PeriodMemberCashflowMapper.class);
            org.mockito.Mockito.doAnswer(inv -> {
                expense = inv.getArgument(3);
                return 1;
            }).when(m).upsertExpenseOnly(org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(inv -> {
                incomeTouched = true;   // 走到这里就是错的:那条 upsert 是整行覆盖,会把收入抹成 null
                return 1;
            }).when(m).upsert(org.mockito.ArgumentMatchers.any());
            return m;
        }
    }

    @BeforeEach
    void setUp() {
        splits = new FakeSplitMapper();
        var cats = new FakeCategoryMapper();
        var batches = new FakeBatchMapper();
        pmc = new FakePmc();
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
        var cats = new FakeCategoryMapper();
        var catSvc = new ExpenseCategoryService(cats, splits);
        long top = catSvc.create(FAM, null, "餐饮美食").getId();
        long kid = catSvc.create(FAM, top, "外卖").getId();
        var svc2 = new ExpenseSplitService(splits, cats, new FakeBatchMapper(), pmc.asMapper(), catSvc);

        svc2.saveManual(FAM, PERIOD, ME, Map.of(top, y("500"), kid, y("300")));
        var roll = svc2.rollup(FAM, PERIOD);
        assertThat(roll).hasSize(1);
        assertThat(roll.get(0).total()).isEqualByComparingTo("800");
        assertThat(roll.get(0).leaves()).extracting("name").contains("外卖", "未细分");
        assertThat(roll.get(0).leaves().stream().filter(l -> l.unsplit()).findFirst().orElseThrow().amount())
                .isEqualByComparingTo("500");
    }
}
