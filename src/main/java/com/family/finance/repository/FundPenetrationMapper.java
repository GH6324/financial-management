package com.family.finance.repository;

import com.family.finance.domain.penetration.FundPenetrationCache;
import org.apache.ibatis.annotations.*;

import java.util.Optional;

@Mapper
public interface FundPenetrationMapper {

    String COLS = "fund_code AS fundCode, report_period AS reportPeriod, stock_pct AS stockPct, bond_pct AS bondPct, "
            + "cash_pct AS cashPct, covered_pct AS coveredPct, alloc_json AS allocJson, fund_name AS fundName, "
            + "fund_type AS fundType, status, fetched_at AS fetchedAt";

    @Select("SELECT " + COLS + " FROM fund_penetration_cache WHERE fund_code = #{fundCode}")
    Optional<FundPenetrationCache> findByCode(@Param("fundCode") String fundCode);

    @Insert("INSERT INTO fund_penetration_cache (fund_code, report_period, stock_pct, bond_pct, cash_pct, covered_pct, "
            + "alloc_json, fund_name, fund_type, status, fetched_at) VALUES (#{fundCode}, #{reportPeriod}, #{stockPct}, "
            + "#{bondPct}, #{cashPct}, #{coveredPct}, #{allocJson}, #{fundName}, #{fundType}, #{status}, NOW(3)) "
            + "ON DUPLICATE KEY UPDATE report_period=VALUES(report_period), stock_pct=VALUES(stock_pct), "
            + "bond_pct=VALUES(bond_pct), cash_pct=VALUES(cash_pct), covered_pct=VALUES(covered_pct), "
            + "alloc_json=VALUES(alloc_json), fund_name=VALUES(fund_name), fund_type=VALUES(fund_type), "
            + "status=VALUES(status), fetched_at=NOW(3)")
    int upsert(FundPenetrationCache c);
}
