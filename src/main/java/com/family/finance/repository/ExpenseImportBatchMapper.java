package com.family.finance.repository;

import com.family.finance.domain.expense.ExpenseImportBatch;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** v1.21 · 导入批次 = 审计日志本体(渠道/行数/金额/替换链) */
@Mapper
public interface ExpenseImportBatchMapper {

    /** 列清单 · 普通字符串前后留空格(文本块会拼出 SELECTid) */
    String COLS = " id, family_id AS familyId, period_id AS periodId, member_id AS memberId,"
                + " channel, row_count AS rowCount, total_amount AS totalAmount,"
                + " replaced_id AS replacedId, imported_by AS importedBy,"
                + " imported_at AS importedAt, revoked_at AS revokedAt ";

    @Insert("""
            INSERT INTO expense_import_batch
                (family_id, period_id, member_id, channel, row_count, total_amount, replaced_id, imported_by)
            VALUES
                (#{familyId}, #{periodId}, #{memberId}, #{channel}, #{rowCount},
                 #{totalAmount}, #{replacedId}, #{importedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ExpenseImportBatch b);

    @Select("SELECT " + COLS
            + " FROM expense_import_batch"
            + " WHERE family_id = #{familyId} AND period_id = #{periodId} AND revoked_at IS NULL"
            + " ORDER BY id DESC")
    List<ExpenseImportBatch> findLiveByPeriod(@Param("familyId") long familyId,
                                              @Param("periodId") long periodId);

    @Select("SELECT " + COLS
            + " FROM expense_import_batch"
            + " WHERE family_id = #{familyId} AND period_id = #{periodId}"
            + "   AND member_id = #{memberId} AND channel = #{channel} AND revoked_at IS NULL"
            + " ORDER BY id DESC LIMIT 1")
    ExpenseImportBatch findLive(@Param("familyId") long familyId, @Param("periodId") long periodId,
                                @Param("memberId") long memberId, @Param("channel") String channel);

    @Select("SELECT " + COLS + " FROM expense_import_batch WHERE id = #{id} AND family_id = #{familyId}")
    ExpenseImportBatch find(@Param("familyId") long familyId, @Param("id") long id);

    @Update("""
            UPDATE expense_import_batch SET revoked_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id} AND family_id = #{familyId} AND revoked_at IS NULL
            """)
    int revoke(@Param("familyId") long familyId, @Param("id") long id);
}
