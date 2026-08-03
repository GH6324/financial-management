package com.family.finance.repository;

import com.family.finance.domain.flow.CashFlow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.Optional;

import java.util.List;

@Mapper
public interface CashFlowMapper {

    @Select("""
            SELECT id, period_id, account_id, kind, category_code, amount,
                   occurred_at, note, submitted_by, submitted_at
              FROM cash_flow
             WHERE period_id = #{periodId}
               AND account_id = #{accountId}
               AND deleted_at IS NULL
             ORDER BY submitted_at, id
            """)
    List<CashFlow> findByPeriodAndAccount(@Param("periodId") long periodId,
                                          @Param("accountId") long accountId);

    @Select("""
            SELECT cf.id, cf.period_id, cf.account_id, cf.kind, cf.category_code, cf.amount,
                   cf.occurred_at, cf.note, cf.submitted_by, cf.submitted_at
              FROM cash_flow cf
              JOIN period p ON p.id = cf.period_id
             WHERE p.family_id = #{familyId}
               AND cf.deleted_at IS NULL
             ORDER BY cf.period_id, cf.id
            """)
    List<CashFlow> findAllByFamily(@Param("familyId") long familyId);

    @Insert("""
            INSERT INTO cash_flow (
                period_id, account_id, kind, category_code, amount, occurred_at, note, submitted_by, is_adjustment,
                ref_holding_id, ref_shares
            ) VALUES (
                #{periodId}, #{accountId}, #{kind}, #{categoryCode}, #{amount}, #{occurredAt}, #{note}, #{submittedBy}, #{adjustment},
                #{refHoldingId}, #{refShares}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CashFlow cashFlow);

    /** v0.2 FR-32 · 按 id 取一行(含已删的);用于软删校验家庭归属与周期 · v0.12 带 ref_holding_id/ref_shares 供冲回 */
    @Select("""
            SELECT id, period_id, account_id, kind, category_code, amount,
                   occurred_at, note, submitted_by, submitted_at,
                   ref_holding_id AS refHoldingId, ref_shares AS refShares
              FROM cash_flow
             WHERE id = #{id}
            """)
    Optional<CashFlow> findById(@Param("id") long id);

    /** v0.2 FR-32 · 软删:UPDATE deleted_at = NOW(3) */
    @Update("""
            UPDATE cash_flow
               SET deleted_at = NOW(3)
             WHERE id = #{id}
               AND deleted_at IS NULL
            """)
    int softDelete(@Param("id") long id);

    /**
     * v1.8 · **口径 A · 家庭支出**:按账期汇总真实家庭支出。
     *
     * <p>三个过滤条件都不是可选的:</p>
     * <ul>
     *   <li>{@code kind='EXPENSE'} —— 只要支出</li>
     *   <li>{@code deleted_at IS NULL} —— 排除软删</li>
     *   <li>{@code is_adjustment = 0} —— <b>排除「现金调整」</b>。调整语义上是本金进出,
     *       必须计入账户外部流出(口径 B · 给 PnL 剔除用),但它<b>不是家庭消费</b>。
     *       beta 实测存在 39 条 is_adjustment=1 的行;若不排除,储蓄率 / 月均支出 /
     *       紧急储备分母会被污染。填报页支出类目下拉只取 kind='EXPENSE',
     *       天然排除 kind='BOTH' 的 cash_adjust,所以这条过滤是<b>语义边界</b>而非防御性写法。</li>
     * </ul>
     *
     * <p>与口径 B 的区别见 FactMapper.queryBase 里的 expense_orig(含调整,一行不动)。
     *
     * <p><b>两条必须与事实表对齐的规则</b>(开发中都踩过):</p>
     * <ul>
     *   <li>{@code a.archived_at IS NULL} —— 事实表默认镜头就排除归档账户。beta 上 43 条支出里
     *       24 条(¥395,340 · 74%)属于归档账户,漏掉这个过滤会让家庭 XIRR 从 −56.19% 漂到 −50.60%。</li>
     *   <li><b>换算到本位币</b> —— {@code cf.amount} 是账户原币。裸 {@code SUM(amount)} 会把
     *       USD 和 CNY 直接相加。这里按「该账期可得的最新 base→账户币 汇率」折回本位币,
     *       与事实表的 {@code fx_ba} 取法同构。残留差异:事实表用的是**窗口末**锚定的单一镜头汇率,
     *       本查询用**逐期**汇率 —— 只在「多币种 + 逐笔模式 + 看历史窗口」三者同时成立时才会显现,
     *       见 prd/v1.8.md 的「未做」。</li>
     * </ul>
     * 方法名刻意用 RealExpense 与之拉开距离 —— 不要「顺手统一」这两者。</p>
     */
    @Select("""
            <script>
            SELECT cf.period_id AS periodId,
                   SUM(CASE WHEN a.currency = fam.base_currency THEN cf.amount
                            ELSE cf.amount / COALESCE((
                                   SELECT fr.rate FROM fx_rate fr JOIN period pp ON pp.id = fr.period_id
                                    WHERE fr.family_id      = a.family_id
                                      AND fr.base_currency  = fam.base_currency
                                      AND fr.quote_currency = a.currency
                                      AND pp.period_start  &lt;= p.period_start
                                    ORDER BY pp.period_start DESC LIMIT 1), 1)
                       END) AS amount,
                   COUNT(*) AS itemCount,
                   MIN(p.period_start) AS periodStart
              FROM cash_flow cf
              JOIN account a   ON a.id   = cf.account_id
              JOIN family  fam ON fam.id = a.family_id
              JOIN period  p   ON p.id   = cf.period_id
             WHERE a.family_id = #{familyId}
               AND a.archived_at IS NULL
               AND cf.kind = 'EXPENSE'
               AND cf.deleted_at IS NULL
               AND cf.is_adjustment = 0
               <if test="periodIds != null and !periodIds.isEmpty()">
                 AND cf.period_id IN
                 <foreach collection="periodIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
               </if>
             GROUP BY cf.period_id
            </script>
            """)
    List<RealExpenseSum> sumRealExpenseByPeriod(@Param("familyId") long familyId,
                                                @Param("periodIds") java.util.Collection<Long> periodIds);

    /**
     * v1.8 · 口径 A 的按期汇总行。
     * periodStart 让 ExpenseLedgerService 能排序「只有逐笔、PMC 里没有」的账期,
     * 不必再全表扫一遍 PMC 去补日期。
     */
    record RealExpenseSum(Long periodId, java.math.BigDecimal amount, int itemCount,
                          java.time.LocalDate periodStart) {}

    /** v0.12 · 收入侧列表:某账期该家庭的「真实收入」流水(is_adjustment=0)· join 账户名/类型 + 类目名。 */
    @Select("""
            SELECT cf.id AS id, cf.account_id AS accountId, a.display_name AS accountName,
                   a.type AS accountType, a.currency AS currency,
                   cf.category_code AS categoryCode,
                   COALESCE(cat.display_name, cf.category_code) AS categoryName,
                   cf.amount AS amount, cf.note AS note,
                   COALESCE(m.display_name, '共同') AS ownerName,
                   cf.submitted_at AS submittedAt
              FROM cash_flow cf
              JOIN account a ON a.id = cf.account_id
              LEFT JOIN member m ON m.id = a.primary_owner_member_id
              LEFT JOIN cash_flow_category cat ON cat.code = cf.category_code
             WHERE cf.period_id = #{periodId}
               AND a.family_id = #{familyId}
               AND cf.kind = 'INCOME'
               AND cf.is_adjustment = 0
               AND cf.deleted_at IS NULL
             ORDER BY cf.id DESC
            """)
    List<IncomeEntryRow> findIncomeEntries(@Param("familyId") long familyId,
                                           @Param("periodId") long periodId);

    /**
     * v1.8 FR-270 · 支出侧列表:某账期该家庭的逐笔支出(口径 A · is_adjustment=0)。
     * 与 findIncomeEntries 同构,只换 kind。归档账户的历史行**仍要列出**
     * (用户得看得见、删得掉),所以这里不加 archived_at 过滤 —— 与按期汇总的口径不同,
     * 汇总要跟事实表对齐才加了归档过滤。
     */
    @Select("""
            SELECT cf.id AS id, cf.account_id AS accountId, a.display_name AS accountName,
                   a.type AS accountType, a.currency AS currency,
                   cf.category_code AS categoryCode,
                   COALESCE(cat.display_name, cf.category_code) AS categoryName,
                   cf.amount AS amount, cf.note AS note,
                   COALESCE(m.display_name, '共同') AS ownerName,
                   cf.submitted_at AS submittedAt
              FROM cash_flow cf
              JOIN account a ON a.id = cf.account_id
              LEFT JOIN member m ON m.id = a.primary_owner_member_id
              LEFT JOIN cash_flow_category cat ON cat.code = cf.category_code
             WHERE cf.period_id = #{periodId}
               AND a.family_id = #{familyId}
               AND cf.kind = 'EXPENSE'
               AND cf.is_adjustment = 0
               AND cf.deleted_at IS NULL
             ORDER BY cf.id DESC
            """)
    List<IncomeEntryRow> findExpenseEntries(@Param("familyId") long familyId,
                                            @Param("periodId") long periodId);

    /** v0.12 · 收入侧列表行(展示用投影)· v1.8 起支出侧复用同一投影。 */
    record IncomeEntryRow(Long id, Long accountId, String accountName, String accountType,
                          String currency, String categoryCode, String categoryName,
                          java.math.BigDecimal amount, String note,
                          String ownerName, java.time.LocalDateTime submittedAt) {}
}
