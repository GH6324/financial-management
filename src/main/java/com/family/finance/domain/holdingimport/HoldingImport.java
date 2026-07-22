package com.family.finance.domain.holdingimport;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.4 · 持仓截图导入任务(状态机)。
 *
 * <p>一次导入 = 用户在某账户某开账期上传若干截图 → 视觉识别 → 三态比对 → 确认落库。
 * 持久化以支持:断点续看(退出再进回到 REVIEW)、原图回看、流水明细。</p>
 *
 * <p>status 流转:{@code UPLOADING → SCANNING → REVIEW → CONFIRMED / ABANDONED}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldingImport {
    public static final String UPLOADING = "UPLOADING";
    public static final String SCANNING  = "SCANNING";
    public static final String REVIEW    = "REVIEW";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String ABANDONED = "ABANDONED";

    private Long id;
    private Long familyId;
    private Long accountId;
    private Long periodId;
    private String status;
    private String visionModel;
    private BigDecimal costEst;
    private Integer imgCount;
    private String scanError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime confirmedAt;
}
