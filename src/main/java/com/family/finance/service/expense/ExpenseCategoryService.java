package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.domain.expense.ExpenseSplit;
import com.family.finance.repository.ExpenseCategoryMapper;
import com.family.finance.repository.ExpenseSplitMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21 · 支出类目树的 CRUD、起步包与删除语义。
 *
 * <h3>一棵树,两个深度</h3>
 *
 * <p>「简单版」与「复杂版」<b>不是两棵树</b>,是同一棵树的两个深度:复杂版的一级与简单版
 * <b>完全相同</b>,二级只是在它下面细化。于是「两版互相映射」<b>不需要映射表</b> ——
 * 映射就是 {@code parentId} 这条父子边,天生存在、永不漂移。</p>
 *
 * <p>切换深度只改「以后按哪一层填」,<b>历史行一行不动</b>:
 * 复杂→简单时二级明细原样留着、报表按父级聚合(合计无损);
 * 简单→复杂时一级上的钱在复杂视图显示为「该大类 · 未细分」。</p>
 *
 * <h3>删除语义按树走</h3>
 *
 * <pre>
 *   删【二级】 → 数据搬到父级   → 变成该大类的「未细分」· 大类合计一分不变
 *   删【一级】 → 数据搬到「其他」· 全家总合计一分不变(连带其下二级一起搬)
 * </pre>
 *
 * <p>比「一律转其他」精确:删掉「外卖」这个细类,钱不该跑到「其他」去,它明明还是餐饮。</p>
 *
 * <h3>一个必须绕开的坑</h3>
 *
 * <p>搬迁不能裸调 {@code UPDATE ... SET category_id = target} —— {@code expense_split} 上有
 * {@code UNIQUE(period, member, category, source)},目标类目已有同来源行时会撞键。
 * 所以 {@link #moveSplits} 先<b>合并同键行</b>(相加)再删源行。</p>
 */
@Service
@RequiredArgsConstructor
public class ExpenseCategoryService {

    /** 层级封顶两层。理由见类注释与 PRD §1.1(随手记「默认树过细」的反面教材)。 */
    public static final int MAX_DEPTH = 2;

    /** 含二级、含停用。鲨鱼记账 40+ 项平铺的教训:再多就没法在月底扫一眼填完。 */
    public static final int MAX_TOTAL = 40;

    private final ExpenseCategoryMapper categoryMapper;
    private final ExpenseSplitMapper splitMapper;

    /** 业务异常 —— 让页面能显示人话,而不是让 DB 约束冒成 500(v1.20 踩过) */
    public static class CategoryException extends RuntimeException {
        public CategoryException(String m) { super(m); }
    }

    // ──────────────────────── 读 ────────────────────────

    /** 一级 → 其下二级(按 sortOrder)。整棵读回内存组装 —— 最多 40 行,不值得递归 SQL。 */
    public Map<ExpenseCategory, List<ExpenseCategory>> tree(long familyId) {
        List<ExpenseCategory> all = categoryMapper.findByFamily(familyId);
        Map<ExpenseCategory, List<ExpenseCategory>> out = new LinkedHashMap<>();
        for (ExpenseCategory c : all) if (c.isTopLevel()) out.put(c, new ArrayList<>());
        for (ExpenseCategory c : all) {
            if (c.isTopLevel()) continue;
            for (var e : out.entrySet()) {
                if (e.getKey().getId().equals(c.getParentId())) { e.getValue().add(c); break; }
            }
        }
        return out;
    }

    public List<ExpenseCategory> all(long familyId) { return categoryMapper.findByFamily(familyId); }

    /** 这个家有没有用过分类 —— 页面据此决定要不要渲染任何新东西(零组态逐字一致) */
    public boolean hasAny(long familyId) { return categoryMapper.countByFamily(familyId) > 0; }

    /** 当前深度下可填的类目:L1 只给一级;L2 给「有子的大类的子」+「没子的大类自己」 */
    public List<ExpenseCategory> fillable(long familyId, boolean deepMode) {
        var t = tree(familyId);
        List<ExpenseCategory> out = new ArrayList<>();
        for (var e : t.entrySet()) {
            ExpenseCategory top = e.getKey();
            if (top.isArchived()) continue;
            List<ExpenseCategory> kids = e.getValue().stream().filter(k -> !k.isArchived()).toList();
            if (!deepMode || kids.isEmpty()) {
                out.add(top);           // 简单深度,或这个大类还没细分 → 直接填在大类上
            } else {
                out.addAll(kids);
            }
        }
        return out;
    }

    public ExpenseCategory other(long familyId) {
        return categoryMapper.findBySystemCode(familyId, ExpenseCategory.SYSTEM_OTHER);
    }

    // ──────────────────────── 起步包 ────────────────────────

    /**
     * 起步包的一级<b>刻意与支付宝账单「交易分类」同名</b>。
     *
     * <p>数的源头就是这套名字(支付宝 CSV 的「交易分类」列、月账单统计页),
     * 同名意味着<b>导入零映射、手抄零翻译</b>。用户随时可以改名 —— 它只是起点,不是约束。</p>
     */
    static final String[][] STARTER = {
            {"餐饮美食", "三餐,外卖,饮品零食,聚餐请客"},
            {"日用百货", "日用品,家居家装"},
            {"服饰装扮", "衣物鞋包,美容美发"},
            {"数码电器", "数码,家电"},
            {"交通出行", "公共交通,打车,加油停车,火车机票"},
            {"住房物业", "房租房贷,水电燃气,物业"},
            {"医疗健康", "看病买药,保健"},
            {"教育培训", "学费,书籍课程"},
            {"文化休闲", "旅游酒店,影音演出,运动健身"},
            {"充值缴费", "话费网费,会员订阅"},
    };

    /**
     * 一键起步。{@code deep=false} 只建 10 个大类;{@code deep=true} 连细类一起建(~30 项)。
     *
     * <p>无论哪个深度,<b>一级完全相同</b> —— 这是「随时可换、历史不丢」的前提。</p>
     */
    @Transactional
    public void seed(long familyId, boolean deep) {
        if (hasAny(familyId)) throw new CategoryException("已经建过类目了,起步包只在一片空白时用。");
        int order = 10;
        for (String[] row : STARTER) {
            ExpenseCategory top = ExpenseCategory.builder()
                    .familyId(familyId).parentId(null).name(row[0]).sortOrder(order).build();
            categoryMapper.insert(top);
            if (deep) {
                int sub = 10;
                for (String kid : row[1].split(",")) {
                    categoryMapper.insert(ExpenseCategory.builder()
                            .familyId(familyId).parentId(top.getId()).name(kid.trim()).sortOrder(sub).build());
                    sub += 10;
                }
            }
            order += 10;
        }
        ensureOther(familyId);
    }

    /** 「其他」必须存在 —— 删除语义的最终落点。任何建类目的路径都要过它。 */
    @Transactional
    public ExpenseCategory ensureOther(long familyId) {
        ExpenseCategory o = other(familyId);
        if (o != null) return o;
        ExpenseCategory n = ExpenseCategory.builder()
                .familyId(familyId).parentId(null).name("其他")
                .systemCode(ExpenseCategory.SYSTEM_OTHER).sortOrder(9999).build();
        categoryMapper.insert(n);
        return n;
    }

    // ──────────────────────── 写 ────────────────────────

    @Transactional
    public ExpenseCategory create(long familyId, Long parentId, String rawName) {
        String name = requireName(rawName);
        if (categoryMapper.countByFamily(familyId) >= MAX_TOTAL) {
            throw new CategoryException("类目已经有 " + MAX_TOTAL + " 个了 —— 再多会让月底抄账单变成一件苦差。"
                    + "先停用几个不用的,或者把细类合并一下。");
        }
        if (parentId != null) {
            ExpenseCategory p = categoryMapper.find(familyId, parentId);
            if (p == null) throw new CategoryException("找不到这个大类,刷新一下再试。");
            // 封顶两层:父节点自己必须是一级
            if (!p.isTopLevel()) throw new CategoryException("细类下面不能再分细类 —— 两层就够了。"
                    + "月底填的是汇总数,分太细反而没人愿意填。");
            if (p.isOther()) throw new CategoryException("「其他」是兜底项,不给它加细类。");
        }
        requireNameFree(familyId, parentId, name, null);
        ExpenseCategory c = ExpenseCategory.builder()
                .familyId(familyId).parentId(parentId).name(name)
                .sortOrder(nextOrder(familyId, parentId)).build();
        categoryMapper.insert(c);
        ensureOther(familyId);
        return c;
    }

    @Transactional
    public void rename(long familyId, long id, String rawName) {
        ExpenseCategory c = mustFind(familyId, id);
        if (c.isOther()) throw new CategoryException("「其他」不能改名 —— 它是兜底项,换了名字用户会以为它是个普通类目。");
        String name = requireName(rawName);
        requireNameFree(familyId, c.getParentId(), name, id);
        c.setName(name);
        if (c.getSortOrder() == null) c.setSortOrder(0);
        categoryMapper.rename(c);
    }

    @Transactional
    public void setArchived(long familyId, long id, boolean archived) {
        ExpenseCategory c = mustFind(familyId, id);
        if (c.isOther()) throw new CategoryException("「其他」不能停用 —— 删别的类目时,钱要有地方去。");
        categoryMapper.setArchived(familyId, id, archived);
        // 一级停用 → 其下二级一并停用,否则细类会挂在一个看不见的大类下
        if (c.isTopLevel()) categoryMapper.setArchivedByParent(familyId, id, archived);
    }

    /** 删除前的影响预告(FR-505:要说清会动多少期数据) */
    public record DeleteImpact(String name, boolean topLevel, String targetName,
                               int periods, int rows, int childCount) {}

    public DeleteImpact previewDelete(long familyId, long id) {
        ExpenseCategory c = mustFind(familyId, id);
        if (c.isOther()) throw new CategoryException("「其他」不能删 —— 删别的类目时,钱要有地方去。");
        var t = tree(familyId);
        int kids = 0;
        for (var e : t.entrySet()) if (e.getKey().getId().equals(id)) kids = e.getValue().size();

        List<Long> ids = new ArrayList<>();
        ids.add(id);
        if (c.isTopLevel()) {
            for (var e : t.entrySet()) {
                if (!e.getKey().getId().equals(id)) continue;
                for (ExpenseCategory k : e.getValue()) ids.add(k.getId());
            }
        }
        int periods = 0, rows = 0;
        for (Long cid : ids) {
            periods = Math.max(periods, splitMapper.countPeriodsUsing(familyId, cid));
            rows += splitMapper.countRowsUsing(familyId, cid);
        }
        String target = c.isTopLevel() ? "其他" : parentName(familyId, c.getParentId()) + " · 未细分";
        return new DeleteImpact(c.getName(), c.isTopLevel(), target, periods, rows, kids);
    }

    /**
     * 删除。<b>钱一分都不能丢</b>:
     * 删二级搬到父级(大类合计不变),删一级搬到「其他」(全家总合计不变)。
     */
    @Transactional
    public void delete(long familyId, long id) {
        ExpenseCategory c = mustFind(familyId, id);
        if (c.isOther()) throw new CategoryException("「其他」不能删 —— 删别的类目时,钱要有地方去。");

        if (c.isTopLevel()) {
            long otherId = ensureOther(familyId).getId();
            // 先把子类的数据搬走,再搬自己的,最后删节点 —— 顺序反了会留下孤儿行
            for (ExpenseCategory k : childrenOf(familyId, id)) moveSplits(familyId, k.getId(), otherId);
            moveSplits(familyId, id, otherId);
            categoryMapper.deleteChildren(familyId, id);
            categoryMapper.delete(familyId, id);
        } else {
            moveSplits(familyId, id, c.getParentId());
            categoryMapper.delete(familyId, id);
        }
    }

    /**
     * 把 {@code fromId} 的来源行搬到 {@code toId}。
     *
     * <p><b>不能裸 UPDATE</b>:{@code uk_split(period,member,category,source)} 会在目标已有同来源行时撞键。
     * 所以先把撞键的那些<b>相加合并</b>,再把剩下的搬过去。</p>
     */
    private void moveSplits(long familyId, long fromId, long toId) {
        if (fromId == toId) return;
        List<ExpenseSplit> all = splitMapper.findByFamily(familyId);
        List<ExpenseSplit> moving = all.stream().filter(r -> fromId == r.getCategoryId()).toList();
        if (moving.isEmpty()) return;

        // 目标类目已有的行,按 (期/人/来源) 建索引 —— 撞上的要相加,不是覆盖
        Map<String, BigDecimal> existing = new LinkedHashMap<>();
        for (ExpenseSplit r : all) {
            if (toId != r.getCategoryId()) continue;
            existing.put(slot(r), nz(r.getAmount()));
        }
        for (ExpenseSplit r : moving) {
            BigDecimal merged = nz(r.getAmount()).add(existing.getOrDefault(slot(r), BigDecimal.ZERO));
            splitMapper.upsert(ExpenseSplit.builder()
                    .familyId(familyId).periodId(r.getPeriodId()).memberId(r.getMemberId())
                    .categoryId(toId).source(r.getSource()).amount(merged).batchId(r.getBatchId())
                    .build());
            existing.put(slot(r), merged);   // 同一格若有多行源(理论上不会),继续累加而不是各自覆盖
        }
        // 钱已经加到目标行上了,源行必须删干净 —— 留着就是双计
        splitMapper.deleteByCategory(familyId, fromId);
    }

    // ──────────────────────── 小工具 ────────────────────────

    /** 一「格」= 期 × 人 × 来源(不含类目 —— 搬迁时正是要跨类目对齐同一格) */
    private static String slot(ExpenseSplit r) {
        return r.getPeriodId() + "/" + r.getMemberId() + "/" + r.getSource();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private List<ExpenseCategory> childrenOf(long familyId, long parentId) {
        return categoryMapper.findByFamily(familyId).stream()
                .filter(c -> parentId == (c.getParentId() == null ? -1L : c.getParentId()))
                .toList();
    }

    private String parentName(long familyId, Long parentId) {
        if (parentId == null) return "";
        ExpenseCategory p = categoryMapper.find(familyId, parentId);
        return p == null ? "" : p.getName();
    }

    private ExpenseCategory mustFind(long familyId, long id) {
        ExpenseCategory c = categoryMapper.find(familyId, id);
        if (c == null) throw new CategoryException("找不到这个类目,刷新一下再试。");
        return c;
    }

    private static String requireName(String raw) {
        String n = raw == null ? "" : raw.trim();
        if (n.isEmpty()) throw new CategoryException("给它起个名字。");
        if (n.length() > 24) throw new CategoryException("名字太长了(最多 24 个字)—— 短名字在月底那张表上更好认。");
        return n;
    }

    /**
     * 同层同名拒绝。
     *
     * <p>DB 上有 {@code uk_expcat_name(family_id, parent_id, name)},但<b>一级的 parent_id 是 NULL,
     * 而 MySQL 的 UNIQUE 对 NULL 不去重</b> —— 所以一级重名 DB 拦不住,必须在这里拦。
     * 这不是多余的防御,是那条唯一键的真实覆盖范围。</p>
     */
    private void requireNameFree(long familyId, Long parentId, String name, Long selfId) {
        for (ExpenseCategory c : categoryMapper.findByFamily(familyId)) {
            if (selfId != null && selfId.equals(c.getId())) continue;
            boolean sameLevel = parentId == null
                    ? c.getParentId() == null
                    : parentId.equals(c.getParentId());
            if (sameLevel && name.equals(c.getName())) {
                throw new CategoryException("已经有一个叫「" + name + "」的"
                        + (parentId == null ? "大类" : "细类") + "了,换个名字。");
            }
        }
    }

    private int nextOrder(long familyId, Long parentId) {
        int max = 0;
        for (ExpenseCategory c : categoryMapper.findByFamily(familyId)) {
            boolean sameLevel = parentId == null
                    ? c.getParentId() == null
                    : parentId.equals(c.getParentId());
            if (sameLevel && c.getSortOrder() != null && c.getSortOrder() < 9000) {
                max = Math.max(max, c.getSortOrder());
            }
        }
        return max + 10;
    }
}
