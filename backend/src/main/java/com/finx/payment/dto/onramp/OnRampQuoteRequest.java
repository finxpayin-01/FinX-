package com.finx.payment.dto.onramp;
import java.math.BigDecimal;
public class OnRampQuoteRequest {
    private String fiatCurrency;
    private BigDecimal fiatAmount;
    private String cryptoAsset;
    private String network;
    private String country;
    public String getFiatCurrency() { return fiatCurrency; }
    public void setFiatCurrency(String fiatCurrency) { this.fiatCurrency = fiatCurrency; }
    public BigDecimal getFiatAmount() { return fiatAmount; }
    public void setFiatAmount(BigDecimal fiatAmount) { this.fiatAmount = fiatAmount; }
    public String getCryptoAsset() { return cryptoAsset; }
    public void setCryptoAsset(String cryptoAsset) { this.cryptoAsset = cryptoAsset; }
    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
}
