package com.finx.payment.service.onramp.onramper;

import com.finx.payment.dto.onramp.OnRampQuoteRequest;
import com.finx.payment.dto.onramp.OnRampSessionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnramperProviderTest {

    private OnramperProperties properties;
    private OnramperWebhookVerifier webhookVerifier;
    private OnramperSignatureService signatureService;

    @BeforeEach
    void setUp() {
        properties = new OnramperProperties();
        properties.setApiKey("test-api-key");
        properties.setBaseUrl("https://onramper.test");
        webhookVerifier = mock(OnramperWebhookVerifier.class);
        signatureService = mock(OnramperSignatureService.class);
    }

    @Test
    void supportedAssetsMapsConfiguredFiatAndCryptoWhenProviderResponds() {
        OnramperProvider provider = providerReturning("{\"assets\":[]}", new AtomicReference<>());

        var result = provider.getSupportedAssets("INR", "IN");

        assertThat(result.getFiatCurrencies()).containsExactly("INR");
        assertThat(result.getCryptoAssets()).containsExactly("USDC");
    }

    @Test
    void quoteMapsPayoutAndDefaultsMissingCountryToIndia() {
        AtomicReference<ClientRequest> requestReference = new AtomicReference<>();
        OnramperProvider provider = providerReturning("{\"payout\":\"12.345\"}", requestReference);
        OnRampQuoteRequest request = new OnRampQuoteRequest();
        request.setFiatCurrency("INR");
        request.setFiatAmount(new BigDecimal("1000.00"));
        request.setCryptoAsset("USDC");
        request.setNetwork("polygon");

        var result = provider.createQuote(request);

        assertThat(result.getEstimatedCryptoAmount()).isEqualByComparingTo("12.345");
        assertThat(result.getFiatCurrency()).isEqualTo("INR");
        assertThat(result.getFiatAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.getCryptoAsset()).isEqualTo("USDC");
        assertThat(result.getOnrampProvider()).isEqualTo("onramper");
        assertThat(requestReference.get().url().getPath()).isEqualTo("/quotes");
        assertThat(requestReference.get().url().getQuery()).contains("country=IN", "fiatAmount=1000.00");
    }

    @Test
    void quoteUsesZeroPayoutWhenProviderOmitsPayout() {
        OnramperProvider provider = providerReturning("{}", new AtomicReference<>());
        OnRampQuoteRequest request = new OnRampQuoteRequest();
        request.setFiatCurrency("INR");
        request.setFiatAmount(BigDecimal.TEN);
        request.setCryptoAsset("USDC");
        request.setNetwork("polygon");
        request.setCountry("IN");

        assertThat(provider.createQuote(request).getEstimatedCryptoAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sessionMapsProviderCheckoutUrlAndIntentId() {
        AtomicReference<ClientRequest> requestReference = new AtomicReference<>();
        OnramperProvider provider = providerReturning(
                "{\"checkoutUrl\":\"https://checkout.onramper.test/abc\",\"intentId\":\"intent-123\"}",
                requestReference);
        OnRampSessionRequest request = sessionRequest();
        when(signatureService.hashEndUserIp("203.0.113.10")).thenReturn("ip-hash");

        var result = provider.createSession(request);

        assertThat(result.getCheckoutUrl()).isEqualTo("https://checkout.onramper.test/abc");
        assertThat(result.getProviderSessionId()).isEqualTo("intent-123");
        assertThat(requestReference.get().method()).isEqualTo(org.springframework.http.HttpMethod.POST);
        assertThat(requestReference.get().url().getPath()).isEqualTo("/checkout/intent");
        assertThat(requestReference.get().headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        verify(signatureService).hashEndUserIp("203.0.113.10");
    }

    @Test
    void sessionBuildsFallbackCheckoutUrlWhenProviderOmitsIntentFields() {
        OnramperProvider provider = providerReturning("{}", new AtomicReference<>());
        OnRampSessionRequest request = sessionRequest();
        when(signatureService.hashEndUserIp("203.0.113.10")).thenReturn("ip-hash");

        var result = provider.createSession(request);

        assertThat(result.getProviderSessionId()).isEqualTo("sess_finx-transaction-1");
        assertThat(result.getCheckoutUrl()).isEqualTo(
                "https://onramper.test/checkout/?sessionId=sess_finx-transaction-1");
    }

    @Test
    void paymentMethodsAndTransactionStatusReturnProviderDefaults() {
        OnramperProvider provider = providerReturning("{}", new AtomicReference<>());

        assertThat(provider.getPaymentMethods("INR", "USDC", "IN", "polygon").getMethods())
                .containsExactly("creditCard", "bankTransfer");
        assertThat(provider.getTransactionStatus("provider-id").getStatus()).isEqualTo("unknown");
    }

    @Test
    void webhookVerificationClassifiesCompletedTransactionOnlyWhenValid() {
        OnramperProvider provider = providerReturning("{}", new AtomicReference<>());
        String body = "{\"type\":\"transaction_completed\"}";
        when(webhookVerifier.verifySignature(body, "valid-signature", properties.getWebhookSecret()))
                .thenReturn(true);
        when(webhookVerifier.verifySignature("{\"type\":\"other\"}", "invalid-signature", properties.getWebhookSecret()))
                .thenReturn(false);

        var valid = provider.verifyWebhook(body, "valid-signature");
        var invalid = provider.verifyWebhook("{\"type\":\"other\"}", "invalid-signature");

        assertThat(valid.isValid()).isTrue();
        assertThat(valid.getEventType()).isEqualTo("transaction_completed");
        assertThat(invalid.isValid()).isFalse();
        assertThat(invalid.getEventType()).isNull();
    }

    private OnramperProvider providerReturning(String json, AtomicReference<ClientRequest> capturedRequest) {
        ExchangeFunction exchange = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(json)
                    .build());
        };
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchange);
        return new OnramperProvider(properties, webhookVerifier, signatureService, builder);
    }

    private static OnRampSessionRequest sessionRequest() {
        OnRampSessionRequest request = new OnRampSessionRequest();
        request.setTransactionId("finx-transaction-1");
        request.setDestinationWallet("0x1234");
        request.setEndUserIp("203.0.113.10");
        request.setFiatCurrency("INR");
        request.setFiatAmount(BigDecimal.valueOf(1000));
        request.setCryptoAsset("USDC");
        request.setNetwork("polygon");
        return request;
    }
}