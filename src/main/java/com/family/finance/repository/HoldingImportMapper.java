package com.family.finance.repository;

import com.family.finance.domain.holdingimport.HoldingImport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

/**
 * holding_import 表 Mapper · v1.4。
 */
@Mapper
public interface HoldingImportMapper {

    String COLS = """
            id, family_id AS familyId, account_id AS accountId, period_id AS periodId, status,
            vision_model AS visionModel, cost_est AS costEst, img_count AS imgCount, scan_error AS scanError,
            created_at AS createdAt, updated_at AS updatedAt, confirmed_at AS confirmedAt
            """;

    @Select("SELECT " + COLS + " FROM holding_import WHERE id = #{id}")
    Optional<HoldingImport> findById(@Param("id") long id);

    /** 该账户是否有未完成(UPLOADING/SCANNING/REVIEW)的导入 → 断点续看 · 取最新一条 */
    @Select("SELECT " + COLS + " FROM holding_import"
            + " WHERE account_id = #{accountId} AND status IN ('UPLOADING','SCANNING','REVIEW')"
            + " ORDER BY id DESC LIMIT 1")
    Optional<HoldingImport> findOpenByAccount(@Param("accountId") long accountId);

    @Insert("""
            INSERT INTO holding_import (family_id, account_id, period_id, status, vision_model, cost_est, img_count)
            VALUES (#{familyId}, #{accountId}, #{periodId}, #{status}, #{visionModel}, #{costEst}, #{imgCount})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(HoldingImport imp);

    @Update("UPDATE holding_import SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") long id, @Param("status") String status);

    @Update("UPDATE holding_import SET img_count = #{imgCount} WHERE id = #{id}")
    int updateImgCount(@Param("id") long id, @Param("imgCount") int imgCount);

    @Update("UPDATE holding_import SET status = 'REVIEW' WHERE id = #{id}")
    int markReview(@Param("id") long id);

    @Update("UPDATE holding_import SET status = 'SCANNING', scan_error = NULL WHERE id = #{id}")
    int markScanning(@Param("id") long id);

    @Update("UPDATE holding_import SET status = 'REVIEW', scan_error = #{err} WHERE id = #{id}")
    int markScanError(@Param("id") long id, @Param("err") String err);

    @Update("UPDATE holding_import SET status = 'CONFIRMED', confirmed_at = NOW(3) WHERE id = #{id}")
    int markConfirmed(@Param("id") long id);
}
