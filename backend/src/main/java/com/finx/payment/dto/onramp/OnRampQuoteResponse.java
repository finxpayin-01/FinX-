package com.finx.payment.dto.onramp;
import java.math.BigDecimal;
import java.time.Instant;
public class OnRampQuoteResponse {
    private String quoteReference;
    private String onrampProvider;
    private String fiatCurrency;
    private BigDecimal fiatAmount;
    private String cryptoAsset;
    private BigDecimal estimatedCryptoAmount;
    private String network;
    private String paymentMethod;
    private Instant expiresAt;
    // Getters Setters
    public String getQuoteReference() { return quoteReference; }
    public void setQuoteReference(String quoteReference) { this.quoteReference = quoteReference; }
    public String getOnrampProvider() { return onrampProvider; }
    public void setOnrampProvider(String onrampProvider) { this.onrampProvider = onrampProvider; }
    public String getFiatCurrency() { return fiatCurrency; }
    public void setFiatCurrency(String fiatCurrency) { this.fiatCurrency = fiatCurrency; }
    public BigDecimal getFiatAmount() { return fiatAmount; }
    public void setFiatAmount(BigDecimal fiatAmount) { this.fiatAmount = fiatAmount; }
    public String getCryptoAsset() { return cryptoAsset; }
    public void setCryptoAsset(String cryptoAsset) { this.cryptoAsset = cryptoAsset; }
    public BigDecimal getEstimatedCryptoAmount() { return estimatedCryptoAmount; }
    public void setEstimatedCryptoAmount(BigDecimal estimatedCryptoAmount) { this.estimatedCryptoAmount = estimatedCryptoAmount; }
    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
