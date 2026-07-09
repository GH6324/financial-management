package com.family.finance.domain.broker;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 账房账户 ↔ 券商交易账户绑定(1:1)+ 同步元数据 · v0.15。
 * 不存任何交易凭据(凭据走 family_runtime_config 私密)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerLink {
    private Long id;
    private Long accountId;
    private BrokerVendor vendor;
    private String brokerAccountId;
    private boolean enabled;
    private LocalDateTime lastSyncedAt;
    private String lastStatus;
    private LocalDateTime createdAt;
}
