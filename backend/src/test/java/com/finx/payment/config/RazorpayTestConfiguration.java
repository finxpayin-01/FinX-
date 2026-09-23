package com.finx.payment.config;

import com.finx.payment.service.RazorpayService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Replaces only the external order-creation call in tests. Signature validation
 * remains the production HMAC implementation and uses test-only fixture keys.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RazorpayTestConfiguration {

    @Bean
    @Primary
    RazorpayService testRazorpayService(RazorpayProperties properties) {
        return new RazorpayService(properties) {
            @Override
            public String createOrder(BigDecimal amount, String currency, String receipt) {
                return "order_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            }
        };
    }
}
