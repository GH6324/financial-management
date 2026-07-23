package com.family.finance.domain.penetration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.5 · 全局共享穿透缓存(无 family_id)· 只按公开基金代码,金额不入表(隐私)。
 * 穿透结果按基金代码全体家庭复用;`拉取` 从缓存(缺则实时抓东财)物化成持仓方向。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundPenetrationCache {
    private String fundCode;
    private String reportPeriod;
    private BigDecimal stockPct;      // 股票占净比
    private BigDecimal bondPct;       // 债券占净比
    private BigDecimal cashPct;       // 现金占净比
    private BigDecimal coveredPct;    // 前十大覆盖股票仓位比例
    /** 行业权重明细 JSON:[{"industry":"HOME_APPLIANCE","weightBp":900}, ...](占净值 · 万分比) */
    private String allocJson;
    private String fundName;
    private String fundType;
    /** OK/UNPENETRABLE/FAILED */
    private String status;
    private LocalDateTime fetchedAt;

    public static final String OK = "OK";
    public static final String UNPENETRABLE = "UNPENETRABLE";
    public static final String FAILED = "FAILED";
}
