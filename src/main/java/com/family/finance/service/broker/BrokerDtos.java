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

    /**
     * 拉不到价的股票(v1.26 · IBKR 的伦敦 / 东京 / 新加坡等市场):按券商给的收盘价做「手动估值」持仓。
     * {@code unitPrice} / {@code costPrice} 是持仓币种的单价;折成账户币种在对账时做(那里才知道账户币种)。
     */
    public record ManualPosition(String symbol, String name, String exchange, BigDecimal shares,
                                 BigDecimal unitPrice, BigDecimal costPrice, String currency) {}

    /** 一次拉取快照:持仓 + 各币种现金 + 跳过的非股票笔数(期权/期货)+ 按券商价估值的持仓(v1.26)。 */
    public record Snapshot(List<Position> positions, List<Cash> cash, int skippedNonEquity,
                           List<ManualPosition> manualPositions) {
        /** 富途 / 老虎沿用的旧签名:没有「按券商价估值」的持仓 */
        public Snapshot(List<Position> positions, List<Cash> cash, int skippedNonEquity) {
            this(positions, cash, skippedNonEquity, List.of());
        }
    }

    /**
     * 测试连接报告(富卡片呈现)· v0.15.x:让用户一眼看到连的是哪个户、开了什么市场、里面有什么。
     * 字段可空/可空集合(老虎持仓未接线时 positionCount=-1 表示未知)。
     */
    public record TestReport(String summary, String accountMasked, String accountType,
                             List<String> markets, int positionCount,
                             java.util.Map<String, BigDecimal> cashByCcy) {}
}
