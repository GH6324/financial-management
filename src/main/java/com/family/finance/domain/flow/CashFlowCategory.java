package com.family.finance.domain.flow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashFlowCategory {
    private String code;
    private String displayName;
    private String kind;
    private Integer sortOrder;
    /** v0.12 · 收入类目绑定的账户类型(CASH/STOCK/...);null=不限。用于收入侧「类目↔账户」联动与校验。 */
    private String accountType;
}
