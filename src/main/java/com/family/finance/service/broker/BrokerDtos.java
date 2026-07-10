package com.family.finance.service.broker;

import java.math.BigDecimal;
import java.util.List;

/** 券商只读拉取的中性 DTO(与具体 SDK 解耦)· v0.15。 */
public final class BrokerDtos {
    private BrokerDtos() {}

    /** 一笔持仓(归一到我们的 Market + 纯 ticker;name=券商侧证券名(可空);equity=false 表示期权/期货等,本版跳过)。 */
    public record Position(String market, String ticker, String name, BigDecimal shares,
                           BigDecimal costPrice, String currency, boolean equity) {}

    /** 某币种现金。 */
    public record Cash(String currency, BigDecimal amount) {}

    /** 一次拉取快照:持仓 + 各币种现金 + 跳过的非股票笔数(期权/期货)。 */
    public record Snapshot(List<Position> positions, List<Cash> cash, int skippedNonEquity) {}

    /**
     * 测试连接报告(富卡片呈现)· v0.15.x:让用户一眼看到连的是哪个户、开了什么市场、里面有什么。
     * 字段可空/可空集合(老虎持仓未接线时 positionCount=-1 表示未知)。
     */
    public record TestReport(String summary, String accountMasked, String accountType,
                             List<String> markets, int positionCount,
                             java.util.Map<String, BigDecimal> cashByCcy) {}
}
