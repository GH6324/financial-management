package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.domain.expense.ExpenseImportBatch;
import com.family.finance.repository.ExpenseCategoryMapper;
import com.family.finance.repository.ExpenseImportBatchMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v1.21 · 测试用的内存假 mapper。
 *
 * <p>提成公共类是因为三个测试类都要用(恒等式 / 树语义 / 账单映射)。
 * 用内存假实现而不是 {@code @SpringBootTest}:这些都是纯逻辑,
 * 上下文装配会把一条 10ms 的断言变成 3s。</p>
 *
 * <p><b>假实现必须与真表/真 SQL 行为一致</b> —— 开发时踩过一次:
 * 假的 {@code findByFamily} 少了真 SQL 的 {@code ORDER BY sort_order},
 * 于是排序 bug 在单测里看不出来。凡是偷懒的地方都会变成盲区。</p>
 */
public final class ExpenseFakes {

    private ExpenseFakes() {}

    /**
     * {@code cash_flow} 那一侧的假实现。
     *
     * <p>第 2 稿的分类挂在<b>每一笔</b>上,所以这里存的是「一笔」而不是「一格汇总」。
     * 顺带把第 1 稿那个 {@code uk_split(期,人,类目,来源)} 唯一约束的模拟一起去掉了 ——
     * 逐笔载体没有唯一约束,搬家一条 UPDATE 就够。</p>
     */
    public static class FakeFlowMapper implements com.family.finance.repository.ExpenseFlowMapper {
        /** 一笔:期 / 分类 / 金额 */
        public record Row(long periodId, Long categoryId, BigDecimal amount) {}
        public final List<Row> rows = new ArrayList<>();

        public void add(long periodId, Long categoryId, String amount) {
            rows.add(new Row(periodId, categoryId, new BigDecimal(amount)));
        }

        @Override public List<CatSum> sumByCategory(long familyId, long periodId) {
            Map<Long, BigDecimal> amt = new LinkedHashMap<>();
            Map<Long, Integer> cnt = new LinkedHashMap<>();
            for (Row r : rows) {
                if (r.periodId() != periodId) continue;
                amt.merge(r.categoryId(), r.amount(), BigDecimal::add);
                cnt.merge(r.categoryId(), 1, Integer::sum);
            }
            List<CatSum> out = new ArrayList<>();
            amt.forEach((c, a) -> out.add(new CatSum(c, a, cnt.get(c))));
            return out;
        }

        @Override public List<PeriodCatSum> sumByPeriodAndCategory(long familyId, List<Long> periodIds) {
            Map<String, BigDecimal> acc = new LinkedHashMap<>();
            for (Row r : rows) {
                if (!periodIds.contains(r.periodId())) continue;
                acc.merge(r.periodId() + "/" + r.categoryId(), r.amount(), BigDecimal::add);
            }
            List<PeriodCatSum> out = new ArrayList<>();
            acc.forEach((k, v) -> {
                String[] p = k.split("/");
                Long cid = "null".equals(p[1]) ? null : Long.parseLong(p[1]);
                out.add(new PeriodCatSum(Long.parseLong(p[0]), cid, v));
            });
            return out;
        }

        @Override public List<FlowRow> drillDown(long familyId, long periodId, Long categoryId) { return List.of(); }

        /** 搬家 —— 真实现是一条 UPDATE,这里照做:逐笔载体不会撞任何唯一键 */
        /* v1.22 · 「已存在」的笔可以就地改分类/账户(FR-598)。
           这个 fake 只需要满足接口 —— 真正的行为由 ExistingFlowUpdateService 的
           e2e 主线验(那里能查 DB 的余额,单测的假 mapper 验不出余额挪动)。 */
        @Override public java.util.List<ExistingRow> findExistingByTxNos(long familyId, java.util.List<String> txNos) {
            return java.util.List.of();
        }
        @Override public int updateCategory(long familyId, long id, Long categoryId) { return 0; }
        @Override public int updateAccount(long familyId, long id, long accountId) { return 0; }

        @Override public int moveCategory(long familyId, long fromId, long toId) {
            int n = 0;
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (r.categoryId() != null && r.categoryId() == fromId) {
                    rows.set(i, new Row(r.periodId(), toId, r.amount()));
                    n++;
                }
            }
            return n;
        }

        @Override public Impact impactOf(long familyId, long categoryId) {
            int n = 0;
            BigDecimal amt = BigDecimal.ZERO;
            java.util.Set<Long> ps = new java.util.HashSet<>();
            for (Row r : rows) {
                if (r.categoryId() == null || r.categoryId() != categoryId) continue;
                n++; amt = amt.add(r.amount()); ps.add(r.periodId());
            }
            return new Impact(n, ps.size(), amt);
        }

        @Override public List<String> existingTxNos(long familyId, List<String> txNos) { return List.of(); }

        @Override public List<Long> recentCategoryIds(long familyId, java.time.LocalDate since, int limit) {
            Map<Long, Integer> cnt = new LinkedHashMap<>();
            for (Row r : rows) if (r.categoryId() != null) cnt.merge(r.categoryId(), 1, Integer::sum);
            return cnt.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(limit).map(Map.Entry::getKey).toList();
        }

        @Override public boolean batchAffectsBalance(long familyId, long batchId) { return false; }

        @Override public List<AcctSum> batchAmountByAccount(long familyId, long batchId) { return List.of(); }

        @Override public int softDeleteBatch(long familyId, long batchId) { return 0; }
    }

    public static class FakeCategoryMapper implements ExpenseCategoryMapper {
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

    public static class FakeBatchMapper implements ExpenseImportBatchMapper {
        final Map<Long, ExpenseImportBatch> byId = new LinkedHashMap<>();
        final AtomicLong seq = new AtomicLong(1);
        @Override public int insert(ExpenseImportBatch b) {
            b.setId(seq.getAndIncrement()); byId.put(b.getId(), b); return 1;
        }
        @Override public List<ExpenseImportBatch> findLiveByPeriod(long familyId, long periodId) {
            return byId.values().stream()
                    .filter(b -> b.getPeriodId() == periodId && b.getRevokedAt() == null).toList();
        }
        @Override public ExpenseImportBatch find(long familyId, long id) { return byId.get(id); }
        @Override public int markRevoked(long familyId, long id) {
            ExpenseImportBatch b = byId.get(id);
            if (b == null || b.getRevokedAt() != null) return 0;
            b.setRevokedAt(java.time.LocalDateTime.now()); return 1;
        }
    }

    /**
     * PMC 用 mock 而不是手写假实现 —— 这个接口有十几个聚合方法,
     * 而这里只关心两件事:写进去的支出值是多少、有没有误用那条会抹掉收入的整行 upsert。
     */
    public static class FakePmc {
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

}
