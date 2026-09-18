package com.finx.payment.dto.onramp;
public class WebhookVerificationResult {
    private boolean isValid;
    private String eventType;
    private String providerTransactionId;
    private String finxTransactionId;
    private String status;
    private String txHash;
    public WebhookVerificationResult(boolean isValid) { this.isValid = isValid; }
    public boolean isValid() { return isValid; }
    public void setValid(boolean valid) { isValid = valid; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public void setProviderTransactionId(String providerTransactionId) { this.providerTransactionId = providerTransactionId; }
    public String getFinxTransactionId() { return finxTransactionId; }
    public void setFinxTransactionId(String finxTransactionId) { this.finxTransactionId = finxTransactionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }
}
