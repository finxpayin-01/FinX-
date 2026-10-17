package com.finx.banking.readiness;

import com.finx.banking.capability.BankingProviderCapabilities;
import com.finx.banking.config.BankingPartnerProperties;
import com.finx.banking.contract.UnsupportedBankApiContract;
import com.finx.banking.provider.mock.MockBankingProvider;
import com.finx.banking.provider.partner.PartnerBankingProvider;
import com.finx.banking.transport.BankHttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class BankingProviderReadinessServiceTest {

    @Test
    @DisplayName("Mock provider readiness: readyForSandbox=true, readyForProduction=false")
    void testMockProviderReadiness() {
        MockBankingProvider mock = new MockBankingProvider("secret");
        BankingProviderReadinessService service = new BankingProviderReadinessService(mock, null, false);

        BankingProviderReadiness readiness = service.evaluateReadiness();

        assertThat(readiness.providerName()).isEqualTo("MOCK");
        assertThat(readiness.readyForSandbox()).isTrue();
        assertThat(readiness.readyForProduction()).isFalse();
        assertThat(readiness.blockers()).isNotEmpty();
        assertThat(readiness.blockers()).anyMatch(b -> b.contains("MOCK simulation"));
    }

    @Test
    @DisplayName("Partner provider with unsupported contract: readyForSandbox=false, readyForProduction=false")
    void testPartnerUnsupportedContractReadiness() {
        BankingPartnerProperties props = new BankingPartnerProperties();
        props.setEnabled(true);
        props.setProviderName("PARTNER_BANK");
        props.setSandbox(true);
        props.getMtls().setEnabled(true);

        PartnerBankingProvider partner = new PartnerBankingProvider(
                props,
                new BankHttpTransport(Mockito.mock(WebClient.class), "PARTNER_BANK", "c1"),
                new UnsupportedBankApiContract("PARTNER_BANK"),
                BankingProviderCapabilities.of(BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES)
        );

        BankingProviderReadinessService service = new BankingProviderReadinessService(partner, props, false);
        BankingProviderReadiness readiness = service.evaluateReadiness();

        assertThat(readiness.providerName()).isEqualTo("PARTNER_BANK");
        assertThat(readiness.readyForSandbox()).isFalse();
        assertThat(readiness.readyForProduction()).isFalse();
        assertThat(readiness.blockers()).anyMatch(b -> b.contains("Official partner bank API contract is not present"));
    }

    @Test
    @DisplayName("Null provider bean evaluated safely without NPE")
    void testNullProviderEvaluatedSafely() {
        BankingProviderReadinessService service = new BankingProviderReadinessService(null, null, false);
        BankingProviderReadiness readiness = service.evaluateReadiness();

        assertThat(readiness.providerName()).isEqualTo("NONE");
        assertThat(readiness.configured()).isFalse();
        assertThat(readiness.readyForSandbox()).isFalse();
        assertThat(readiness.readyForProduction()).isFalse();
        assertThat(readiness.blockers()).isNotEmpty();
    }
}
