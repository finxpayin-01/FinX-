package com.finx.payment.dto.onramp;
public class OnRampSessionResponse {
    private String checkoutUrl;
    private String providerSessionId;
    public String getCheckoutUrl() { return checkoutUrl; }
    public void setCheckoutUrl(String checkoutUrl) { this.checkoutUrl = checkoutUrl; }
    public String getProviderSessionId() { return providerSessionId; }
    public void setProviderSessionId(String providerSessionId) { this.providerSessionId = providerSessionId; }
}
