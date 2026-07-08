package com.family.finance.domain.account;

public enum AccountType {
    STOCK("股票"),
    CASH("现金"),
    WEALTH("理财"),
    CRYPTO("加密"),
    PROPERTY("房产"),
    LOAN("贷款"),
    OTHER("其他");

    private final String label;

    AccountType(String label) { this.label = label; }

    public String getLabel() { return label; }
}
