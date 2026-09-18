package com.finx.payment.dto.onramp;
import java.util.List;
public class SupportedAssetsResponse {
    private List<String> fiatCurrencies;
    private List<String> cryptoAssets;
    public List<String> getFiatCurrencies() { return fiatCurrencies; }
    public void setFiatCurrencies(List<String> fiatCurrencies) { this.fiatCurrencies = fiatCurrencies; }
    public List<String> getCryptoAssets() { return cryptoAssets; }
    public void setCryptoAssets(List<String> cryptoAssets) { this.cryptoAssets = cryptoAssets; }
}
