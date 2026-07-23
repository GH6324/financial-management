package com.family.finance.repository;

import com.family.finance.domain.penetration.HoldingAllocation;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface HoldingAllocationMapper {

    String COLS = "id, holding_id AS holdingId, weight_bp AS weightBp, asset_class AS assetClass, "
            + "industry, kind, source, report_period AS reportPeriod, created_at AS createdAt, updated_at AS updatedAt";

    @Select("SELECT " + COLS + " FROM holding_allocation WHERE holding_id = #{holdingId} ORDER BY weight_bp DESC, id")
    List<HoldingAllocation> findByHolding(@Param("holdingId") long holdingId);

    /** 批量:一账户下所有持仓的方向(lens 组装用,避免 N+1) */
    @Select("<script>SELECT " + COLS + " FROM holding_allocation WHERE holding_id IN "
            + "<foreach item='h' collection='holdingIds' open='(' separator=',' close=')'>#{h}</foreach> "
            + "ORDER BY holding_id, weight_bp DESC, id</script>")
    List<HoldingAllocation> findByHoldings(@Param("holdingIds") List<Long> holdingIds);

    @Insert("INSERT INTO holding_allocation (holding_id, weight_bp, asset_class, industry, kind, source, report_period) "
            + "VALUES (#{holdingId}, #{weightBp}, #{assetClass}, #{industry}, #{kind}, #{source}, #{reportPeriod})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(HoldingAllocation a);

    @Update("UPDATE holding_allocation SET weight_bp=#{weightBp}, asset_class=#{assetClass}, industry=#{industry}, "
            + "kind=#{kind}, source=#{source}, report_period=#{reportPeriod} WHERE id=#{id}")
    int update(HoldingAllocation a);

    /** 重拉:只删 PENETRATED / DEFAULT,保留用户手改的 MANUAL */
    @Delete("DELETE FROM holding_allocation WHERE holding_id = #{holdingId} AND source <> 'MANUAL'")
    int deleteNonManual(@Param("holdingId") long holdingId);

    @Delete("DELETE FROM holding_allocation WHERE holding_id = #{holdingId}")
    int deleteByHolding(@Param("holdingId") long holdingId);

    @Select("SELECT COALESCE(SUM(weight_bp),0) FROM holding_allocation WHERE holding_id = #{holdingId} AND source='MANUAL'")
    int manualWeightBp(@Param("holdingId") long holdingId);
}
