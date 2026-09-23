package com.finx.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentRailPolicy {
    public enum Rail { BANK }
    private final Rail rail;

    public PaymentRailPolicy(@Value("${payment.rail:BANK}") String rail) {
        this.rail = Rail.BANK;
    }

    public boolean isBank() { return rail == Rail.BANK; }
    public String name() { return rail.name(); }
}
