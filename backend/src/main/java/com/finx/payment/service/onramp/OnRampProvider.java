package com.finx.payment.service.onramp;

import com.finx.payment.dto.onramp.*;

public interface OnRampProvider {

    SupportedAssetsResponse getSupportedAssets(String sourceFiat, String country);

    PaymentMethodsResponse getPaymentMethods(String sourceFiat, String destinationCrypto, String country, String network);

    OnRampQuoteResponse createQuote(OnRampQuoteRequest request);

    OnRampSessionResponse createSession(OnRampSessionRequest request);

    ProviderTransactionResponse getTransactionStatus(String providerTransactionId);

    WebhookVerificationResult verifyWebhook(String rawBody, String signature);
}
