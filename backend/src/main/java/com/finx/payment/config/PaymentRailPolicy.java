package com.finx.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentRailPolicy {
    public enum Rail { BANK, RAZORPAY }
    private final Rail rail;

    public PaymentRailPolicy(@Value("${payment.rail:BANK}") String rail) {
        this.rail = "RAZORPAY".equalsIgnoreCase(rail) ? Rail.RAZORPAY : Rail.BANK;
    }

    public boolean isBank() { return rail == Rail.BANK; }
    public String name() { return rail.name(); }
}
