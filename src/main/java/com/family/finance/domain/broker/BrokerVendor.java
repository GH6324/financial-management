package com.family.finance.domain.broker;

/** 券商(v0.15 · 只读同步)。 */
public enum BrokerVendor {
    FUTU("富途"),
    TIGER("老虎"),
    /** v1.26 · 盈透 · 走 Flex Web Service(报表口令只能取报表,物理上不能交易) */
    IBKR("盈透");

    private final String label;
    BrokerVendor(String label) { this.label = label; }
    public String getLabel() { return label; }
}
