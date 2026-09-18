package com.finx.payment.dto.onramp;
public class ProviderTransactionResponse {
    private String status;
    private String transactionHash;
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTransactionHash() { return transactionHash; }
    public void setTransactionHash(String transactionHash) { this.transactionHash = transactionHash; }
}
