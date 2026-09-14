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
               AND affects_balance = 1
             ORDER BY submitted_at, id
            """)
    /**
     * 该账户该期<b>参与余额解释</b>的流水。
     *
     * <p>{@code affects_balance = 1} 不是可选的:唯一的调用方是
     * {@code EntryService.reconciliationTotals},它算的是「余额变动能不能被流水解释」。
     * 把「只记构成、不动余额」的笔算进来,会让 unexplained 变成负数 ——
     * 账户上凭空出现一个「解释过头」的差额,而用户什么都没做错。</p>
     */
    List<CashFlow> findByPeriodAndAccount(@Param("periodId") long periodId,
                                          @Param("accountId") long accountId);

    @Select("""
            SELECT cf.id, cf.period_id, cf.account_id, cf.kind, cf.category_code, cf.amount,
                   cf.occurred_at, cf.note, cf.submitted_by, cf.submitted_at,
                   cf.source_tag AS sourceTag
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
                ref_holding_id, ref_shares, source_tag,
                expense_category_id, import_batch_id, ext_tx_no, affects_balance
            ) VALUES (
                #{periodId}, #{accountId}, #{kind}, #{categoryCode}, #{amount}, #{occurredAt}, #{note}, #{submittedBy}, #{adjustment},
                #{refHoldingId}, #{refShares}, COALESCE(#{sourceTag}, 'UNKNOWN'),
                #{expenseCategoryId}, #{importBatchId}, #{extTxNo}, #{affectsBalance}
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
                   cf.submitted_at AS submittedAt,
                   cf.occurred_at AS occurredAt,
                   /* v1.21 · 收入不接自定义消费分类(PRD FR-556),但这一列【必须给】——
                    * IncomeEntryRow 这个 record 被收入与支出【两条查询共用】,
                    * 只给支出加一列的话,收入这条返回 11 列而 record 要 12 个,
                    * MyBatis 构造时 IndexOutOfBounds,而且是【运行期】才炸:
                    * 编译过、单测过,填报页直接变成错误页。真踩了。 */
                   NULL AS expenseCategoryName
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
                   cf.submitted_at AS submittedAt,
                   /* v1.21 · 交易日期。列表原来只显示【填报时间】—— 手工记一笔时两者差不多,
                    * 但导入进来的几百笔全是同一个入库时刻,按日期搜就全中了。
                    * 账单里有真实交易日,拿来用。 */
                   cf.occurred_at AS occurredAt,
                   /* v1.21 · 消费分类。列表原来只显示【性质】(日常开支),
                    * 而 v1.21 之后一行最有意义的标签是「餐饮美食 › 外卖」——
                    * 不带出来的话,用户在填报页看到的是一片一模一样的「日常开支」。
                    * 细类带上父名,两个同名细类才分得清。 */
                   CASE WHEN ec.id IS NULL THEN NULL
                        WHEN ecp.name IS NULL THEN ec.name
                        ELSE CONCAT(ecp.name, ' › ', ec.name) END AS expenseCategoryName
              FROM cash_flow cf
              JOIN account a ON a.id = cf.account_id
              LEFT JOIN member m ON m.id = a.primary_owner_member_id
              LEFT JOIN cash_flow_category cat ON cat.code = cf.category_code
              LEFT JOIN expense_category ec  ON ec.id = cf.expense_category_id
              LEFT JOIN expense_category ecp ON ecp.id = ec.parent_id
             WHERE cf.period_id = #{periodId}
               AND a.family_id = #{familyId}
               AND cf.kind = 'EXPENSE'
               AND cf.is_adjustment = 0
               AND cf.deleted_at IS NULL
             ORDER BY cf.id DESC
            """)
    List<IncomeEntryRow> findExpenseEntries(@Param("familyId") long familyId,
                                            @Param("periodId") long periodId);

    /**
     * v1.8 FR-272 · 支出构成:按维度分组的逐笔支出(口径 A · 已折本位币)。
     *
     * <p>{@code dim} 三选一:{@code category} / {@code account} / {@code member}。
     * 用 {@code <choose>} 而不是拼字符串 —— 分组键必须来自白名单,不能让参数进 SQL 结构。
     * 归档过滤 / 换汇规则与 {@link #sumRealExpenseByPeriod} 完全一致(同一口径,别分叉)。</p>
     */
    @Select("""
            <script>
            SELECT
              <choose>
                <when test="dim == 'account'">a.id AS groupKey, a.display_name AS groupLabel</when>
                <when test="dim == 'member'">
                  COALESCE(m.id, 0) AS groupKey, COALESCE(m.display_name, '共同') AS groupLabel
                </when>
                <otherwise>
                  cf.category_code AS groupKey,
                  COALESCE(cat.display_name, cf.category_code) AS groupLabel
                </otherwise>
              </choose>,
                   SUM(CASE WHEN a.currency = fam.base_currency THEN cf.amount
                            ELSE cf.amount / COALESCE((
                                   SELECT fr.rate FROM fx_rate fr JOIN period pp ON pp.id = fr.period_id
                                    WHERE fr.family_id      = a.family_id
                                      AND fr.base_currency  = fam.base_currency
                                      AND fr.quote_currency = a.currency
                                      AND pp.period_start  &lt;= p.period_start
                                    ORDER BY pp.period_start DESC LIMIT 1), 1)
                       END) AS amountBase,
                   COUNT(*) AS itemCount
              FROM cash_flow cf
              JOIN account a   ON a.id   = cf.account_id
              JOIN family  fam ON fam.id = a.family_id
              JOIN period  p   ON p.id   = cf.period_id
              LEFT JOIN member m ON m.id = a.primary_owner_member_id
              LEFT JOIN cash_flow_category cat ON cat.code = cf.category_code
             WHERE a.family_id = #{familyId}
               AND a.archived_at IS NULL
               AND cf.kind = 'EXPENSE'
               AND cf.deleted_at IS NULL
               AND cf.is_adjustment = 0
               AND cf.period_id IN
               <foreach collection="periodIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
             GROUP BY groupKey, groupLabel
             ORDER BY amountBase DESC
            </script>
            """)
    List<ExpenseGroup> expenseBreakdown(@Param("familyId") long familyId,
                                        @Param("periodIds") java.util.Collection<Long> periodIds,
                                        @Param("dim") String dim);

    /**
     * v1.8 · 落在**已归档账户**上的逐笔支出(与构成口径互补)。
     *
     * <p>全站统计都排除归档账户,所以这些行不进构成。但**不能不说** —— 用户看到「录了 6 笔,
     * 构成里只有 3 笔」会以为程序丢数据。v1.8 起服务端已拦住往归档账户记支出,
     * 这个查询是为**历史数据**准备的:先记账、后来把账户归档了的那些行。</p>
     */
    @Select("""
            <script>
            SELECT COUNT(*) AS itemCount, COALESCE(SUM(cf.amount), 0) AS amount
              FROM cash_flow cf
              JOIN account a ON a.id = cf.account_id
             WHERE a.family_id = #{familyId}
               AND a.archived_at IS NOT NULL
               AND cf.kind = 'EXPENSE'
               AND cf.deleted_at IS NULL
               AND cf.is_adjustment = 0
               AND cf.period_id IN
               <foreach collection="periodIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
            </script>
            """)
    ArchivedExpense sumArchivedExpense(@Param("familyId") long familyId,
                                       @Param("periodIds") java.util.Collection<Long> periodIds);

    /** v1.8 · 归档账户上的支出汇总(原币直加,只用于「有多少笔没进构成」的提示,不进任何计算)。 */
    record ArchivedExpense(int itemCount, java.math.BigDecimal amount) {}

    /** v1.8 · 支出构成的一组(维度值 → 本位币金额 + 笔数)。 */
    record ExpenseGroup(String groupKey, String groupLabel,
                        java.math.BigDecimal amountBase, int itemCount) {}

    /** v1.8 FR-272 · 某一组的逐笔明细(抽屉用)· dim 白名单同上。 */
    @Select("""
            <script>
            SELECT cf.id AS id, cf.account_id AS accountId, a.display_name AS accountName,
                   a.type AS accountType, a.currency AS currency,
                   cf.category_code AS categoryCode,
                   COALESCE(cat.display_name, cf.category_code) AS categoryName,
                   cf.amount AS amount, cf.note AS note,
                   COALESCE(m.display_name, '共同') AS ownerName,
                   cf.submitted_at AS submittedAt,
                   -- v1.21 · IncomeEntryRow 是【三条】查询共用的投影,加列时三条都要跟上。
                   -- 这条当时漏了 → MyBatis 构造 record 时 Index 11 out of bounds → 抽屉永远是空的,
                   -- 而且【不报错到页面上】(HTMX 片段失败只是没内容),所以肉眼看不出来。
                   cf.occurred_at AS occurredAt,
                   -- 细类带上父名,与填报页列表同一口径(两个同名细类才分得清)
                   CASE WHEN ec.id IS NULL THEN NULL
                        WHEN ecp.name IS NULL THEN ec.name
                        ELSE CONCAT(ecp.name, ' › ', ec.name) END AS expenseCategoryName
              FROM cash_flow cf
              JOIN account a ON a.id = cf.account_id
              LEFT JOIN member m ON m.id = a.primary_owner_member_id
              LEFT JOIN cash_flow_category cat ON cat.code = cf.category_code
              LEFT JOIN expense_category ec  ON ec.id = cf.expense_category_id
              LEFT JOIN expense_category ecp ON ecp.id = ec.parent_id
             WHERE a.family_id = #{familyId}
               AND a.archived_at IS NULL
               AND cf.kind = 'EXPENSE'
               AND cf.deleted_at IS NULL
               AND cf.is_adjustment = 0
               AND cf.period_id IN
               <foreach collection="periodIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
              <choose>
                <when test="dim == 'account'">AND a.id = #{groupKey}</when>
                <when test="dim == 'member'">AND COALESCE(m.id, 0) = #{groupKey}</when>
                <otherwise>AND cf.category_code = #{groupKey}</otherwise>
              </choose>
             ORDER BY cf.period_id DESC, cf.id DESC
             LIMIT 200
            </script>
            """)
    List<IncomeEntryRow> expenseBreakdownDetail(@Param("familyId") long familyId,
                                                @Param("periodIds") java.util.Collection<Long> periodIds,
                                                @Param("dim") String dim,
                                                @Param("groupKey") String groupKey);

    /** v0.12 · 收入侧列表行(展示用投影)· v1.8 起支出侧复用同一投影。 */
    /** {@code expenseCategoryName} 只有支出侧会有值(收入不接自定义分类,见 PRD FR-556) */
    record IncomeEntryRow(Long id, Long accountId, String accountName, String accountType,
                          String currency, String categoryCode, String categoryName,
                          java.math.BigDecimal amount, String note,
                          String ownerName, java.time.LocalDateTime submittedAt,
                          java.time.LocalDate occurredAt,
                          String expenseCategoryName) {}
}
