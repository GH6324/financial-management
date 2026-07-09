package com.family.finance.domain.broker;

/** 券商(v0.15 · 只读同步)。 */
public enum BrokerVendor {
    FUTU("富途"),
    TIGER("老虎");

    private final String label;
    BrokerVendor(String label) { this.label = label; }
    public String getLabel() { return label; }
}
