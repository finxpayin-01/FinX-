package com.finx.payment.config;

import com.finx.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentRailPolicy {
    public enum Rail { BLOCKCHAIN, RAZORPAY }
    private final Rail rail;

    public PaymentRailPolicy(@Value("${payment.rail:BLOCKCHAIN}") String rail) {
        this.rail = Rail.valueOf(rail.trim().toUpperCase(java.util.Locale.ROOT));
    }

    public boolean blockchain() { return rail == Rail.BLOCKCHAIN; }
    public String name() { return rail.name(); }
    public void requireRazorpay() {
        if (blockchain()) throw new BadRequestException("Razorpay is paused. Use blockchain escrow.");
    }
    public void requireBlockchain() {
        if (!blockchain()) throw new BadRequestException("Blockchain mutations are paused.");
    }
}
