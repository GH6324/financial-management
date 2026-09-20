package com.family.finance.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * v1.21 · 从 {@code cash_flow} 这一侧看「消费分类」。
 *
 * <p>为什么单开一个 mapper 而不是往 {@link CashFlowMapper} 里塞:那个类已经 300 行、
 * 承担的是「流水本身的增删查」;这里问的是<b>另一个问题</b> —— 按分类聚合、按分类搬家、
 * 按交易号去重。混在一起之后没人分得清哪些方法动的是流水、哪些动的是分类。</p>
 *
 * <h3>所有查询都必须 {@code deleted_at IS NULL}</h3>
 *
 * <p>软删是本项目的既定做法。漏掉这个条件的后果不是报错,而是<b>报表里多出用户已经删掉的钱</b> ——
 * 它看起来完全正常,只是数字大了一点。护栏 {@code v1210-EXPFLOW-SOFT-DELETE} 扫这条。</p>
 *
 * <h3>口径:只看「消费」性质</h3>
 *
 * <p>{@code category_code = 'consumption'} 是硬条件。还贷 / 利息支出 / 转账给亲属
 * 不进「钱花在哪」(它们不是消费),但仍然进储蓄率 —— 那条口径归
 * {@code ExpenseLedgerService} 管,这里一个字都不碰。</p>
 */
@Mapper
public interface ExpenseFlowMapper {

    // ══════════════════════════════════════════════════════════════════════════
    // v1.24 · 这里原来有两条按分类聚合的查询(sumByCategory / sumByPeriodAndCategory)
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 它们被【删掉】了,不是改好了 —— 第二层构成现在走
    // CashFlowMapper.sumExpenseByPeriodCategoryOneOff,与第一层拼同一套过滤常量。
    //
    // 为什么非删不可:那两条 SQL 与第一层(expenseBreakdown)口径分叉了【四处】——
    // 没有家庭隔离、不排归档账户、不排现金调整、而且不换汇(SUM(cf.amount) 把 USD
    // 直接加进 CNY)。两条 SQL 各自都跑得出数、都不报错,只是合不上;
    // PRD FR-663 要求两层合计相等,而按当时的写法它们本来就不可能相等。
    //
    // 「补三个 WHERE、不补换汇」是最诱人的改法,也是最坏的一种:其余三项对上之后,
    // 多币种家庭仍然错,而且错得更像「就差那一点」。所以整条删掉,只留一个取数口。

    /**
     * 下钻:某一期某个分类的那些笔(FR-570)。分类为 null 时看未分类的那一堆。
     *
     * <p>v1.24 · <b>过滤条件与第二层聚合逐条对齐</b>(归档账户 / 现金调整都排除)——
     * 否则点进去看到的笔数与饼片上标的笔数对不上,而那是最招人怀疑「程序算错了」的一种不一致。</p>
     */
    record FlowRow(Long id, LocalDate occurredAt, String note, BigDecimal amount,
                   String accountName, Long categoryId, String categoryName, boolean oneOff) {}

    @Select("""
            <script>
            SELECT cf.id, cf.occurred_at AS occurredAt, cf.note, cf.amount,
                   a.display_name AS accountName, cf.expense_category_id AS categoryId,
                   CASE WHEN ec.id IS NULL THEN NULL
                        WHEN ecp.name IS NULL THEN ec.name
                        ELSE CONCAT(ecp.name, ' › ', ec.name) END AS categoryName,
                   cf.one_off AS oneOff
              FROM cash_flow cf
              JOIN account a ON a.id = cf.account_id
              LEFT JOIN expense_category ec  ON ec.id = cf.expense_category_id
              LEFT JOIN expense_category ecp ON ecp.id = ec.parent_id
             WHERE a.family_id = #{familyId}
               AND a.archived_at IS NULL
               AND cf.period_id = #{periodId}
               AND cf.kind = 'EXPENSE'
               AND cf.category_code = 'consumption'
               AND cf.deleted_at IS NULL
               AND cf.is_adjustment = 0
               <choose>
                 <when test="categoryId == null">AND cf.expense_category_id IS NULL</when>
                 <otherwise>AND cf.expense_category_id = #{categoryId}</otherwise>
               </choose>
             ORDER BY cf.occurred_at DESC, cf.id DESC
             LIMIT 500
            </script>
            """)
    List<FlowRow> drillDown(@Param("familyId") long familyId,
                            @Param("periodId") long periodId, @Param("categoryId") Long categoryId);

    /**
     * v1.24 FR-633 · 一期里<b>全部</b>消费笔(不按分类筛)。
     *
     * <p>给「一次性明细」用:哪些笔算一次性由 {@code ExpenseNatureService} 在 Java 侧判
     * (FR-615 的判据只能有一处),所以 SQL 这里不能替它筛,只负责把原始列交出去。</p>
     *
     * <p>过滤条件与 {@link #drillDown} 逐字相同 —— 两者是同一批行的不同切法。</p>
     */
    @Select("""
            SELECT cf.id, cf.occurred_at AS occurredAt, cf.note, cf.amount,
                   a.display_name AS accountName, cf.expense_category_id AS categoryId,
                   CASE WHEN ec.id IS NULL THEN NULL
                        WHEN ecp.name IS NULL THEN ec.name
                        ELSE CONCAT(ecp.name, ' › ', ec.name) END AS categoryName,
                   cf.one_off AS oneOff
              FROM cash_flow cf
              JOIN account a ON a.id = cf.account_id
              LEFT JOIN expense_category ec  ON ec.id = cf.expense_category_id
              LEFT JOIN expense_category ecp ON ecp.id = ec.parent_id
             WHERE a.family_id = #{familyId}
               AND a.archived_at IS NULL
               AND cf.period_id = #{periodId}
               AND cf.kind = 'EXPENSE'
               AND cf.category_code = 'consumption'
               AND cf.deleted_at IS NULL
               AND cf.is_adjustment = 0
             ORDER BY cf.occurred_at DESC, cf.id DESC
             LIMIT 500
            """)
    List<FlowRow> drillDownAll(@Param("familyId") long familyId, @Param("periodId") long periodId);

    /**
     * 删类目时搬家。
     *
     * <p>第 1 稿这一步很难写:{@code expense_split} 上有
     * {@code UNIQUE(period,member,category,source)},裸 UPDATE 会撞键,得先合并同槽行再删源行。
     * 改成逐笔载体之后<b>一条 UPDATE 就够了</b> —— 每一笔本来就是独立一行,没有任何唯一约束会撞。
     * 这是「分类挂在一笔上」附带的简化,不是刻意设计的。</p>
     */
    @Update("""
            UPDATE cash_flow cf
              JOIN period p    ON p.id = cf.period_id
               SET cf.expense_category_id = #{toId}
             WHERE p.family_id = #{familyId}
               AND cf.expense_category_id = #{fromId}
            """)
    int moveCategory(@Param("familyId") long familyId,
                     @Param("fromId") long fromId, @Param("toId") long toId);

    /** 删类目前的影响预告:会动多少笔 / 横跨多少期(FR-505) */
    record Impact(int rowCount, int periods, BigDecimal amount) {}

    @Select("""
            SELECT COUNT(*)                        AS row_count,
                   COUNT(DISTINCT cf.period_id)    AS periods,
                   COALESCE(SUM(cf.amount), 0)     AS amount
              FROM cash_flow cf
              JOIN period p ON p.id = cf.period_id
             WHERE p.family_id = #{familyId}
               AND cf.expense_category_id = #{categoryId}
               AND cf.deleted_at IS NULL
            """)
    Impact impactOf(@Param("familyId") long familyId, @Param("categoryId") long categoryId);

    /**
     * 导入去重:这个家在这个渠道下,哪些交易号已经落过了(FR-560 ⑤)。
     *
     * <p>范围是<b>整个家庭</b>而不是当期 —— 用户可能把 9 月的账单误导进 8 月那一期,
     * 只查当期的话那笔会被当成新的再落一次。</p>
     */
    @Select("""
            <script>
            SELECT DISTINCT cf.ext_tx_no
              FROM cash_flow cf
              JOIN period p ON p.id = cf.period_id
             WHERE p.family_id = #{familyId}
               AND cf.deleted_at IS NULL
               AND cf.ext_tx_no IS NOT NULL
               AND cf.ext_tx_no IN
                   <foreach item="t" collection="txNos" open="(" separator="," close=")">#{t}</foreach>
            </script>
            """)
    List<String> existingTxNos(@Param("familyId") long familyId, @Param("txNos") List<String> txNos);

    /** 最近用过的分类,常用置顶用(FR-553)。按「最近出现过的笔数」排,不按时间点 —— 偶然用一次不该顶上去。 */
    @Select("""
            SELECT cf.expense_category_id
              FROM cash_flow cf
              JOIN period p ON p.id = cf.period_id
             WHERE p.family_id = #{familyId}
               AND cf.kind = 'EXPENSE'
               AND cf.category_code = 'consumption'
               AND cf.expense_category_id IS NOT NULL
               AND cf.deleted_at IS NULL
               AND cf.occurred_at >= #{since}
             GROUP BY cf.expense_category_id
             ORDER BY COUNT(*) DESC
             LIMIT #{limit}
            """)
    List<Long> recentCategoryIds(@Param("familyId") long familyId,
                                 @Param("since") LocalDate since, @Param("limit") int limit);

    /**
     * 这个批次当初扣过余额吗。
     *
     * <p>撤销时要用:<b>只有扣过的才加回</b>,否则「只记构成」的批次一撤销,
     * 余额会凭空多出一笔钱。同一批次的行 {@code affects_balance} 必然一致
     * (整批一个开关),所以取 MAX 即可。</p>
     */
    @Select("""
            SELECT COALESCE(MAX(cf.affects_balance), 0)
              FROM cash_flow cf
              JOIN expense_import_batch b ON b.id = cf.import_batch_id
             WHERE b.family_id = #{familyId}
               AND cf.import_batch_id = #{batchId}
            """)
    boolean batchAffectsBalance(@Param("familyId") long familyId, @Param("batchId") long batchId);

    /**
     * 这个批次每个账户各落了多少钱 —— 撤销时要<b>按账户分别加回</b>。
     *
     * <p>一批可能跨几个账户(一份账单里「收/付款方式」是变化的),
     * 全加回批次的「主账户」会把别的账户的钱塞给它。
     * <b>必须在软删之前调</b>,软删之后就查不到了。</p>
     */
    record AcctSum(Long accountId, BigDecimal amount) {}

    @Select("""
            SELECT cf.account_id AS accountId, SUM(cf.amount) AS amount
              FROM cash_flow cf
              JOIN expense_import_batch b ON b.id = cf.import_batch_id
             WHERE b.family_id = #{familyId}
               AND cf.import_batch_id = #{batchId}
               AND cf.deleted_at IS NULL
             GROUP BY cf.account_id
            """)
    List<AcctSum> batchAmountByAccount(@Param("familyId") long familyId, @Param("batchId") long batchId);

    /** 整批撤销:软删该批次落的所有流水(FR-539) */
    @Update("""
            UPDATE cash_flow cf
              JOIN expense_import_batch b ON b.id = cf.import_batch_id
               SET cf.deleted_at = NOW(3)
             WHERE b.family_id = #{familyId}
               AND cf.import_batch_id = #{batchId}
               AND cf.deleted_at IS NULL
            """)
    int softDeleteBatch(@Param("familyId") long familyId, @Param("batchId") long batchId);

    // ══════════════ v1.22 · 「已存在」的笔可以就地修正(FR-598 ~ FR-600)══════════════

    /**
     * 按交易号查出<b>已经入账</b>的那一笔,连同它所属账期的状态。
     *
     * <p>为什么要带 {@code periodStatus}:去重范围是<b>整个家庭</b>而不是当期,
     * 所以「已存在」的笔可能落在别的账期、甚至<b>已关账</b>的期。
     * 改那种笔的账户会去动一个用户已经核对过并封存的 {@code period_snapshot} ——
     * 必须在服务端拦(FR-600),前端 disabled 只防手滑,防不住构造请求。</p>
     *
     * <p>{@code affectsBalance} 决定改账户时要不要挪余额:当初没落到账户的笔,
     * 改账户只是改归属,一分钱都不该动。</p>
     */
    @Select("""
            <script>
            SELECT cf.id                  AS id,
                   cf.period_id           AS periodId,
                   p.status               AS periodStatus,
                   cf.account_id          AS accountId,
                   cf.amount              AS amount,
                   cf.affects_balance     AS affectsBalance,
                   cf.expense_category_id AS expenseCategoryId,
                   cf.ext_tx_no           AS txNo,
                   cf.note                AS note
              FROM cash_flow cf
              JOIN period p ON p.id = cf.period_id
             WHERE p.family_id = #{familyId}
               AND cf.deleted_at IS NULL
               AND cf.kind = 'EXPENSE'
               AND cf.ext_tx_no IN
                   <foreach item="t" collection="txNos" open="(" separator="," close=")">#{t}</foreach>
            </script>
            """)
    List<ExistingRow> findExistingByTxNos(@Param("familyId") long familyId,
                                          @Param("txNos") List<String> txNos);

    /** 已入账的一笔(修正入口用)。{@code periodStatus} 是 OPEN / CLOSED 的字面值 */
    record ExistingRow(long id, long periodId, String periodStatus, long accountId,
                       java.math.BigDecimal amount, boolean affectsBalance,
                       Long expenseCategoryId, String txNo, String note) {
        /** 已关账的期只能改分类,不能改账户 —— 改账户要动已封存的期末余额 */
        public boolean closed() { return "CLOSED".equalsIgnoreCase(periodStatus); }
    }

    /**
     * 就地改这一笔的消费分类。
     *
     * <p>分类<b>不动钱</b>,所以已关账的期也能改 —— 它只影响「钱花在哪」的构成图。</p>
     */
    @Update("""
            UPDATE cash_flow cf
              JOIN period p ON p.id = cf.period_id
               SET cf.expense_category_id = #{categoryId}
             WHERE p.family_id = #{familyId}
               AND cf.id = #{id}
               AND cf.deleted_at IS NULL
            """)
    int updateCategory(@Param("familyId") long familyId,
                       @Param("id") long id, @Param("categoryId") Long categoryId);

    /**
     * 就地改这一笔的账户。
     *
     * <p><b>只改流水的归属列</b> —— 余额的挪动由调用方在同一个事务里做,
     * 因为那要走 {@code EntryService} 的既有路径(审计日志 + 镜头失效都挂在那)。
     * 这里多做一步就会出现两套改余额的代码。</p>
     */
    /**
     * v1.24 FR-613 · 就地改这一笔的「一次性」勾。
     *
     * <p>与改分类一样<b>不动钱</b>,所以已关账的期也能改 —— 它只影响这笔进不进常态月均。</p>
     */
    @Update("""
            UPDATE cash_flow cf
              JOIN period p ON p.id = cf.period_id
               SET cf.one_off = #{oneOff}
             WHERE p.family_id = #{familyId}
               AND cf.id = #{id}
               AND cf.deleted_at IS NULL
            """)
    int updateOneOff(@Param("familyId") long familyId,
                     @Param("id") long id, @Param("oneOff") boolean oneOff);

    @Update("""
            UPDATE cash_flow cf
              JOIN period p ON p.id = cf.period_id
               SET cf.account_id = #{accountId}
             WHERE p.family_id = #{familyId}
               AND cf.id = #{id}
               AND cf.deleted_at IS NULL
            """)
    int updateAccount(@Param("familyId") long familyId,
                      @Param("id") long id, @Param("accountId") long accountId);
}
