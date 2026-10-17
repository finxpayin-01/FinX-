package com.finx.banking.config;

import com.finx.banking.capability.BankingProviderCapabilities;
import com.finx.banking.contract.BankApiContract;
import com.finx.banking.contract.UnsupportedBankApiContract;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.partner.PartnerBankingProvider;
import com.finx.banking.signing.BankRequestSigner;
import com.finx.banking.signing.HmacSha256BankRequestSigner;
import com.finx.banking.transport.BankHttpTransport;
import com.finx.banking.transport.BankingWebClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Spring auto-configuration for regulated partner banking providers.
 * Activates ONLY when 'finx.banking.provider=partner'.
 * <p>
 * STRICT INVARIANT:
 * matchIfMissing is NEVER true for partner mode. Mock provider remains the default.
 * If provider=partner is selected but required mTLS/base properties are missing,
 * application startup fails closed immediately.
 */
@Configuration
public class BankingProviderAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BankingProviderAutoConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "finx.banking.provider", havingValue = "partner")
    public BankingProvider partnerBankingProvider(
            BankingPartnerProperties properties,
            ResourceLoader resourceLoader,
            @Autowired(required = false) BankApiContract customContract
    ) {
        log.info("Initializing PartnerBankingProvider in fail-closed partner mode");

        // Validate all required partner properties: fails startup if invalid
        properties.validateForPartnerMode();

        BankingWebClientFactory webClientFactory = new BankingWebClientFactory(resourceLoader);
        WebClient webClient = webClientFactory.createWebClient(properties);

        BankRequestSigner signer = null;
        if (properties.getSigning() != null && properties.getSigning().isEnabled()) {
            String algo = properties.getSigning().getAlgorithm();
            if ("HMAC-SHA256".equalsIgnoreCase(algo)) {
                signer = new HmacSha256BankRequestSigner(
                        properties.getSigning().getKeyId(),
                        properties.getSigning().getSecret()
                );
            } else {
                throw new IllegalStateException("UNSUPPORTED_SIGNING_ALGORITHM: Algorithm not supported: " + algo);
            }
        }

        BankHttpTransport transport = new BankHttpTransport(
                webClient,
                properties.getProviderName(),
                properties.getClientId(),
                signer
        );

        BankApiContract contract = customContract != null
                ? customContract
                : new UnsupportedBankApiContract(properties.getProviderName());

        BankingProviderCapabilities capabilities = (contract instanceof UnsupportedBankApiContract)
                ? BankingProviderCapabilities.empty()
                : BankingProviderCapabilities.of(
                        // If a real custom contract is supplied, it can declare capabilities; otherwise empty
                );

        return new PartnerBankingProvider(properties, transport, contract, capabilities);
    }
}
