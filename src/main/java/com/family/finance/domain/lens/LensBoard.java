package com.family.finance.domain.lens;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** v1.1 · 用户自存透视看板(lens_board)· 预设 5 块为代码常量不落库 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LensBoard {
    private Long id;
    private Long familyId;
    private String name;
    /** LensQuery spec(rows/cols/measures/filters)JSON */
    private String specJson;
    private Integer displayOrder;
    private LocalDateTime createdAt;
}
