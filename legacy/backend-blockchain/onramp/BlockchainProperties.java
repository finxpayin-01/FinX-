package com.finx.payment.service.onramp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "finx.blockchain")
public class BlockchainProperties {
    private String rpcUrl;
    private Long chainId;
    private String escrowContractAddress;
    private String usdcContractAddress;
    private Integer requiredConfirmations = 2; // minimum

    public String getRpcUrl() { return rpcUrl; }
    public void setRpcUrl(String rpcUrl) { this.rpcUrl = rpcUrl; }
    public Long getChainId() { return chainId; }
    public void setChainId(Long chainId) { this.chainId = chainId; }
    public String getEscrowContractAddress() { return escrowContractAddress; }
    public void setEscrowContractAddress(String escrowContractAddress) { this.escrowContractAddress = escrowContractAddress; }
    public String getUsdcContractAddress() { return usdcContractAddress; }
    public void setUsdcContractAddress(String usdcContractAddress) { this.usdcContractAddress = usdcContractAddress; }
    public Integer getRequiredConfirmations() { return requiredConfirmations; }
    public void setRequiredConfirmations(Integer requiredConfirmations) { this.requiredConfirmations = requiredConfirmations; }
}
