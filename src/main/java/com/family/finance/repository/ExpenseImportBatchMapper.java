package com.family.finance.repository;

import com.family.finance.domain.expense.ExpenseImportBatch;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * v1.21 · 导入批次 = 审计日志本体(渠道 / 笔数 / 金额 / 剔除数 / 跳过数)。
 *
 * <p>第 1 稿这里有个 {@code replaced_id} 替换链,配套的是「同渠道重导 = 整条通道
 * DELETE+INSERT」。第 2 稿靠<b>交易号</b>去重,精确到笔,不需要替换链 ——
 * 批次只剩「这一批导了什么」这一个职责。</p>
 */
@Mapper
public interface ExpenseImportBatchMapper {

    /** 列清单 · 普通字符串前后留空格(文本块会拼出 SELECTid) */
    String COLS = " id, family_id AS familyId, period_id AS periodId, account_id AS accountId,"
                + " channel, row_count AS rowCount, total_amount AS totalAmount,"
                + " dropped_count AS droppedCount, skipped_count AS skippedCount,"
                + " imported_by AS importedBy,"
                + " imported_at AS importedAt, revoked_at AS revokedAt ";

    @Insert("""
            INSERT INTO expense_import_batch
                (family_id, period_id, account_id, channel, row_count, total_amount,
                 dropped_count, skipped_count, imported_by)
            VALUES
                (#{familyId}, #{periodId}, #{accountId}, #{channel}, #{rowCount}, #{totalAmount},
                 #{droppedCount}, #{skippedCount}, #{importedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ExpenseImportBatch b);

    @Select("SELECT " + COLS
            + " FROM expense_import_batch"
            + " WHERE family_id = #{familyId} AND period_id = #{periodId} AND revoked_at IS NULL"
            + " ORDER BY id DESC")
    List<ExpenseImportBatch> findLiveByPeriod(@Param("familyId") long familyId,
                                              @Param("periodId") long periodId);

    @Select("SELECT " + COLS + " FROM expense_import_batch WHERE id = #{id} AND family_id = #{familyId}")
    ExpenseImportBatch find(@Param("familyId") long familyId, @Param("id") long id);

    @Update("""
            UPDATE expense_import_batch SET revoked_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id} AND family_id = #{familyId} AND revoked_at IS NULL
            """)
    int markRevoked(@Param("familyId") long familyId, @Param("id") long id);
}
