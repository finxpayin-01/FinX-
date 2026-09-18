package com.finx.payment.service.onramp;

import com.finx.payment.service.onramp.onramper.OnramperProperties;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import java.util.Map;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class FinancialInfrastructureReadinessService {
    private static final Logger log = LoggerFactory.getLogger(FinancialInfrastructureReadinessService.class);

    private final OnramperProperties onramperProperties;
    private final BlockchainProperties blockchainProperties;

    public FinancialInfrastructureReadinessService(OnramperProperties onramperProperties, BlockchainProperties blockchainProperties) {
        this.onramperProperties = onramperProperties;
        this.blockchainProperties = blockchainProperties;
    }

    public Map<String, Object> checkReadiness() {
        Map<String, Object> response = new HashMap<>();

        // Onramper checks
        Map<String, Object> onramperStats = new HashMap<>();
        boolean apiKeyReady = isValidString(onramperProperties.getApiKey());
        boolean v2KeyReady = isValidString(onramperProperties.getV2PrivateKey());
        boolean webhookReady = isValidString(onramperProperties.getWebhookSecret());
        
        onramperStats.put("apiKeyConfigured", apiKeyReady);
        onramperStats.put("v2SigningConfigured", v2KeyReady);
        onramperStats.put("webhookConfigured", webhookReady);
        response.put("onramper", onramperStats);

        // Blockchain checks
        Map<String, Object> blockchainStats = new HashMap<>();
        boolean rpcConfigured = isValidString(blockchainProperties.getRpcUrl());
        boolean escrowConfigured = isValidString(blockchainProperties.getEscrowContractAddress());
        boolean usdcConfigured = isValidString(blockchainProperties.getUsdcContractAddress());
        
        blockchainStats.put("rpcConfigured", rpcConfigured);
        blockchainStats.put("escrowContractConfigured", escrowConfigured);
        blockchainStats.put("usdcContractConfigured", usdcConfigured);
        blockchainStats.put("expectedChainId", blockchainProperties.getChainId());
        
        boolean rpcReachable = false;
        long actualChainId = 0L;
        
        if (rpcConfigured) {
            try {
                Web3j web3j = Web3j.build(new HttpService(blockchainProperties.getRpcUrl()));
                actualChainId = web3j.ethChainId().send().getChainId().longValue();
                rpcReachable = true;
            } catch (Exception e) {
                log.warn("Readiness Service: RPC Unreachable", e);
            }
        }
        
        blockchainStats.put("rpcReachable", rpcReachable);
        blockchainStats.put("actualChainId", actualChainId);
        response.put("blockchain", blockchainStats);

        boolean readyForSandbox = apiKeyReady && v2KeyReady && webhookReady && 
                                  rpcConfigured && rpcReachable && escrowConfigured && usdcConfigured &&
                                  (blockchainProperties.getChainId() != null && blockchainProperties.getChainId() == actualChainId);

        response.put("readyForSandbox", readyForSandbox);
        
        if (readyForSandbox) {
            log.info("Financial infrastructure is fully READY for Sandox operations.");
        } else {
            log.warn("Financial infrastructure is MISSING CRITICAL SETTINGS. Blocking financial hooks.");
        }
        
        return response;
    }

    private boolean isValidString(String str) {
        return str != null && !str.isBlank();
    }
}
