package com.finx.payment.dto.onramp;
import java.math.BigDecimal;
public class OnRampSessionRequest {
    private String transactionId; // FinX Correlation ID
    private String quoteReference;
    private String onrampProvider;
    private String destinationWallet;
    private String redirectUrl;
    private String endUserIp;
    private String network;
    private String cryptoAsset;
    private String fiatCurrency;
    private BigDecimal fiatAmount;
    // getters setters
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getQuoteReference() { return quoteReference; }
    public void setQuoteReference(String quoteReference) { this.quoteReference = quoteReference; }
    public String getOnrampProvider() { return onrampProvider; }
    public void setOnrampProvider(String onrampProvider) { this.onrampProvider = onrampProvider; }
    public String getDestinationWallet() { return destinationWallet; }
    public void setDestinationWallet(String destinationWallet) { this.destinationWallet = destinationWallet; }
    public String getRedirectUrl() { return redirectUrl; }
    public void setRedirectUrl(String redirectUrl) { this.redirectUrl = redirectUrl; }
    public String getEndUserIp() { return endUserIp; }
    public void setEndUserIp(String endUserIp) { this.endUserIp = endUserIp; }
    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }
    public String getCryptoAsset() { return cryptoAsset; }
    public void setCryptoAsset(String cryptoAsset) { this.cryptoAsset = cryptoAsset; }
    public String getFiatCurrency() { return fiatCurrency; }
    public void setFiatCurrency(String fiatCurrency) { this.fiatCurrency = fiatCurrency; }
    public BigDecimal getFiatAmount() { return fiatAmount; }
    public void setFiatAmount(BigDecimal fiatAmount) { this.fiatAmount = fiatAmount; }
}
