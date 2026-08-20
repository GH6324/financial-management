package com.family.finance.repository;

import com.family.finance.domain.stock.StockValuationEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * v0.4.1 · stock_valuation_event mapper · ledger 查询 + 估值 hook 写入。
 */
@Mapper
public interface StockValuationEventMapper {

    @Insert("""
            INSERT INTO stock_valuation_event
                (family_id, account_id, period_id, prev_balance, new_balance, delta,
                 trigger_kind, triggered_by_member_id, note, ref_import_id, source_tag)
            VALUES
                (#{familyId}, #{accountId}, #{periodId}, #{prevBalance}, #{newBalance}, #{delta},
                 #{triggerKind}, #{triggeredByMemberId}, #{note}, #{refImportId}, #{sourceTag})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(StockValuationEvent e);

    /** 按账户 + 周期查事件 · 给 ledger view 用 · 按 triggered_at 升序 */
    @Select("""
            SELECT id, family_id, account_id, period_id, prev_balance, new_balance, delta,
                   trigger_kind, triggered_by_member_id, note, ref_import_id AS refImportId, source_tag AS sourceTag, triggered_at
              FROM stock_valuation_event
             WHERE account_id = #{accountId} AND period_id = #{periodId}
             ORDER BY triggered_at
            """)
    List<StockValuationEvent> findByAccountAndPeriod(@Param("accountId") long accountId,
                                                    @Param("periodId") long periodId);

    /** 按账户查所有期事件 · 给 accounts/{id} 详情页用 · 倒序 */
    @Select("""
            SELECT id, family_id, account_id, period_id, prev_balance, new_balance, delta,
                   trigger_kind, triggered_by_member_id, note, ref_import_id AS refImportId, source_tag AS sourceTag, triggered_at
              FROM stock_valuation_event
             WHERE account_id = #{accountId}
             ORDER BY triggered_at DESC
             LIMIT #{limit}
            """)
    List<StockValuationEvent> findRecentByAccount(@Param("accountId") long accountId,
                                                  @Param("limit") int limit);

    /** v1.18.2 · 对账用:一条估值事件的最小投影(账户币种 · 与 period_snapshot / 流水同币种)。 */
    record ReconEvent(long accountId, long periodId, java.time.LocalDate periodStart,
                      java.math.BigDecimal delta, java.time.LocalDateTime at) {}

    /**
     * v1.18.2 · 全家庭估值事件(带时间)· <b>只读</b>,供账目对账用。
     *
     * <p><b>为什么要带时间、而不是按期合计</b>:第一版按期合计、拿
     * {@code periodPnl − Σ事件Δ} 当不变量,<b>抓不到要抓的那笔</b> —— 估值覆盖余额时会
     * <b>忠实地写一条 delta = −(被抹掉的钱) 的事件</b>,两边正好相消、恒等式闭合。
     * (这和归因瀑布「未归因」是同一个毛病:把结果记下来再拿结果去对,永远对得上。)</p>
     *
     * <p>真正的签名要在<b>时间线</b>上才看得见 —— 生产数据长这样:</p>
     * <pre>  08-17 17:42  转入 +40,000
     *  08-18 00:20  估值 Δ −40,000.00     ← 精确抹掉
     *  08-18 10:35  转入 +35,000
     *  08-18 16:10  估值 Δ −35,000.00     ← 又精确抹掉一次</pre>
     * <p>即:<b>某次估值的 Δ,恰好等于它之前那段时间里流入的钱的相反数</b>。
     * 市场波动不可能精确到分,所以这个形状几乎不会误报。</p>
     */
    @Select("""
            SELECT e.account_id AS accountId, e.period_id AS periodId,
                   p.period_start AS periodStart, e.delta AS delta, e.triggered_at AS at
              FROM stock_valuation_event e
              JOIN period p ON p.id = e.period_id
             WHERE e.family_id = #{familyId}
             ORDER BY e.account_id, e.period_id, e.triggered_at
            """)
    List<ReconEvent> findEventsForReconcile(@Param("familyId") long familyId);

    /** v1.18.2 · 对账用:一笔进出账户的钱(有符号 · 账户币种)。 */
    record ReconFlow(long accountId, long periodId, java.math.BigDecimal signedAmount,
                     java.time.LocalDateTime at) {}

    /**
     * v1.18.2 · 全家庭「进出账户的钱」时间线(收支 + 划转双边)· <b>只读</b>。
     *
     * <p>过滤条件与事实表口径一致({@code is_draft = 0} / {@code deleted_at IS NULL} /
     * 划转进账按 {@code COALESCE(to_amount, amount)} 取<b>到账币种</b>的金额)。
     * 这里是<b>期内时间线</b>粒度,不是在重新定义「净流水」—— 单测钉住:
     * 按期把它汇总,必须逐分等于事实表的净流水,防止这份 SQL 将来自己漂走。</p>
     */
    @Select("""
            SELECT account_id AS accountId, period_id AS periodId,
                   signed_amount AS signedAmount, at AS at FROM (
              SELECT cf.account_id, cf.period_id, cf.submitted_at AS at,
                     CASE WHEN cf.kind = 'INCOME' THEN cf.amount ELSE -cf.amount END AS signed_amount
                FROM cash_flow cf JOIN period p ON p.id = cf.period_id
               WHERE p.family_id = #{familyId} AND cf.deleted_at IS NULL
              UNION ALL
              SELECT t.to_account_id, t.period_id, t.submitted_at,
                     COALESCE(t.to_amount, t.amount)
                FROM transfer t JOIN period p ON p.id = t.period_id
               WHERE p.family_id = #{familyId} AND t.is_draft = 0 AND t.deleted_at IS NULL
              UNION ALL
              SELECT t.from_account_id, t.period_id, t.submitted_at, -t.amount
                FROM transfer t JOIN period p ON p.id = t.period_id
               WHERE p.family_id = #{familyId} AND t.is_draft = 0 AND t.deleted_at IS NULL
            ) x
             ORDER BY accountId, periodId, at
            """)
    List<ReconFlow> findFlowsForReconcile(@Param("familyId") long familyId);
}
