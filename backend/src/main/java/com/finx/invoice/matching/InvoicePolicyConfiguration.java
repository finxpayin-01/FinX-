package com.finx.invoice.matching;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.math.BigDecimal;

@Configuration
public class InvoicePolicyConfiguration {
    @Bean public InvoiceMatchingPolicy invoiceMatchingPolicy(Environment env) {
        String p = "finx.invoice.";
        return new InvoiceMatchingPolicy(env.getProperty(p+"policy-version", "v2.0.0"),
                new BigDecimal(env.getProperty(p+"allowed-tolerance", "0")),
                env.getProperty(p+"comparison-basis", "TOTAL"),
                new BigDecimal(env.getProperty(p+"minimum-confidence", "0.75")),
                env.getProperty(p+"weights.identity", Integer.class, 20),
                env.getProperty(p+"weights.context", Integer.class, 20),
                env.getProperty(p+"weights.money", Integer.class, 25),
                env.getProperty(p+"weights.identifier-date", Integer.class, 10),
                env.getProperty(p+"weights.duplicates", Integer.class, 15),
                env.getProperty(p+"weights.evidence", Integer.class, 10));
    }
}
