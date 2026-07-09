package com.family.finance.service.broker;

import java.math.BigDecimal;
import java.util.List;

/** 券商只读拉取的中性 DTO(与具体 SDK 解耦)· v0.15。 */
public final class BrokerDtos {
    private BrokerDtos() {}

    /** 一笔持仓(归一到我们的 Market + 纯 ticker;equity=false 表示期权/期货等,本版跳过)。 */
    public record Position(String market, String ticker, BigDecimal shares,
                           BigDecimal costPrice, String currency, boolean equity) {}

    /** 某币种现金。 */
    public record Cash(String currency, BigDecimal amount) {}

    /** 一次拉取快照:持仓 + 各币种现金 + 跳过的非股票笔数(期权/期货)。 */
    public record Snapshot(List<Position> positions, List<Cash> cash, int skippedNonEquity) {}
}
