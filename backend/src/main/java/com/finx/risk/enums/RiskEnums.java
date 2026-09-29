package com.finx.risk.enums;

public class RiskEnums {

    public enum RiskLevel {
        LOW,
        MODERATE,
        ELEVATED,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum RiskEntityType {
        ORGANIZATION,
        DEAL,
        SETTLEMENT,
        USER
    }
}
