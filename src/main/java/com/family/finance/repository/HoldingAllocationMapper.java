package com.family.finance.repository;

import com.family.finance.domain.penetration.HoldingAllocation;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * v1.5 · 持仓的「方向」构成(穿透结果)。
 *
 * <p>v1.24 · 家庭隔离:本表没有 {@code family_id} 列,归属沿
 * {@code holding_allocation → stock_holding → account.family_id} 这条两跳链取,
 * 每条语句自己走完。删除类语句尤其不能省 —— 一个跨家庭的 {@code holdingId}
 * 会静默删掉别人家的穿透结果,而穿透结果是可重算的,所以连报错都不会有。</p>
 */
@Mapper
public interface HoldingAllocationMapper {

    String COLS = " al.id, al.holding_id AS holdingId, al.weight_bp AS weightBp,"
            + " al.asset_class AS assetClass, al.industry, al.kind, al.source,"
            + " al.report_period AS reportPeriod, al.created_at AS createdAt, al.updated_at AS updatedAt ";

    /** 归属链一次写完 —— 两跳都要,少一跳就只是「某个持仓」而不是「我家的持仓」 */
    String OWNED = " JOIN stock_holding h ON h.id = al.holding_id"
            + " JOIN account a ON a.id = h.account_id";

    @Select("SELECT" + COLS + "FROM holding_allocation al" + OWNED
          + " WHERE a.family_id = #{familyId} AND al.holding_id = #{holdingId}"
          + " ORDER BY al.weight_bp DESC, al.id")
    List<HoldingAllocation> findByHolding(@Param("familyId") long familyId,
                                          @Param("holdingId") long holdingId);

    /** 批量:一账户下所有持仓的方向(lens 组装用,避免 N+1) */
    @Select("<script>SELECT" + COLS + "FROM holding_allocation al" + OWNED
          + " WHERE a.family_id = #{familyId} AND al.holding_id IN "
          + "<foreach item='hid' collection='holdingIds' open='(' separator=',' close=')'>#{hid}</foreach> "
          + "ORDER BY al.holding_id, al.weight_bp DESC, al.id</script>")
    List<HoldingAllocation> findByHoldings(@Param("familyId") long familyId,
                                           @Param("holdingIds") List<Long> holdingIds);

    @Insert("INSERT INTO holding_allocation (holding_id, weight_bp, asset_class, industry, kind, source, report_period) "
          + "SELECT #{al.holdingId}, #{al.weightBp}, #{al.assetClass}, #{al.industry}, #{al.kind},"
          + " #{al.source}, #{al.reportPeriod}"
          + " FROM stock_holding h JOIN account a ON a.id = h.account_id"
          + " WHERE h.id = #{al.holdingId} AND a.family_id = #{familyId}")
    @Options(useGeneratedKeys = true, keyProperty = "al.id")
    int insert(@Param("familyId") long familyId, @Param("al") HoldingAllocation a);

    /** 带归属断言的插入 —— 业务代码一律用这个 */
    default void insertOwned(long familyId, HoldingAllocation a) {
        if (insert(familyId, a) != 1) {
            throw new IllegalStateException("持仓方向归属校验不通过:持仓 " + a.getHoldingId()
                    + " 不属于家庭 " + familyId);
        }
    }

    @Update("UPDATE holding_allocation al" + OWNED
          + " SET al.weight_bp=#{al.weightBp}, al.asset_class=#{al.assetClass}, al.industry=#{al.industry},"
          + " al.kind=#{al.kind}, al.source=#{al.source}, al.report_period=#{al.reportPeriod}"
          + " WHERE a.family_id = #{familyId} AND al.id=#{al.id}")
    int update(@Param("familyId") long familyId, @Param("al") HoldingAllocation a);

    /** 重拉:只删 PENETRATED / DEFAULT,保留用户手改的 MANUAL */
    @Delete("DELETE al FROM holding_allocation al" + OWNED
          + " WHERE a.family_id = #{familyId} AND al.holding_id = #{holdingId} AND al.source <> 'MANUAL'")
    int deleteNonManual(@Param("familyId") long familyId, @Param("holdingId") long holdingId);

    @Delete("DELETE al FROM holding_allocation al" + OWNED
          + " WHERE a.family_id = #{familyId} AND al.holding_id = #{holdingId}")
    int deleteByHolding(@Param("familyId") long familyId, @Param("holdingId") long holdingId);

    @Select("SELECT COALESCE(SUM(al.weight_bp),0) FROM holding_allocation al" + OWNED
          + " WHERE a.family_id = #{familyId} AND al.holding_id = #{holdingId} AND al.source='MANUAL'")
    int manualWeightBp(@Param("familyId") long familyId, @Param("holdingId") long holdingId);
}
