package com.family.finance.domain.account;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountTemplate {
    private Long id;
    private String code;
    private String displayName;
    private AccountType type;
    private String defaultCurrency;
    private String icon;
    /** v1.5.2 · 平台默认值(持有机构/App · 建户未填时带出为账户「平台」打标 · 可空) */
    private String platform;
    private Integer sortOrder;
    private boolean customSlot;
}
