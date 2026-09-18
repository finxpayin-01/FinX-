package com.finx.payment.config;

import com.finx.payment.service.onramp.onramper.OnramperProperties;
import com.finx.payment.service.onramp.onramper.OnramperSignatureService;
import com.finx.payment.service.onramp.onramper.OnramperWebhookVerifier;
import com.finx.payment.service.onramp.BlockchainProperties;
import com.finx.payment.service.onramp.BlockchainSettlementVerifier;
import com.finx.payment.service.onramp.SettlementStatus;
import com.finx.payment.service.onramp.FinancialInfrastructureReadinessService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

class ConfigurationValidationTest {

    @Test
    void missingV2KeyFailsSignatureClosed() {
        OnramperProperties props = new OnramperProperties();
        OnramperSignatureService service = new OnramperSignatureService(props);
        
        Exception exception = assertThrows(RuntimeException.class, () -> {
            service.hashEndUserIp("192.168.1.1");
        });
        
        assertTrue(exception.getMessage().contains("ONRAMP_V2_PRIVATE_KEY missing. V2 Intent blocked."));
    }

    @Test
    void missingWebhookSecretFailsClosed() {
        OnramperWebhookVerifier verifier = new OnramperWebhookVerifier();
        boolean result = verifier.verifySignature("rawBody", "signature123", null);
        assertFalse(result, "Null secret should reject verification");
        
        boolean resultBlank = verifier.verifySignature("rawBody", "signature123", "");
        assertFalse(resultBlank, "Blank secret should reject verification");
    }

    @Test
    void missingRpcUrlRejectsBlockchainSettlement() {
        BlockchainProperties props = new BlockchainProperties();
        BlockchainSettlementVerifier verifier = new BlockchainSettlementVerifier(props);
        
        SettlementStatus result = verifier.verifySettlement("0xtxhash", "USDC", "100");
        assertEquals(SettlementStatus.RPC_UNAVAILABLE, result, "Missing RPC should gracefully decline settlement as RPC_UNAVAILABLE");
    }

    @Test
    void readinessServiceFailsOpenSilentlyWithoutConfig() {
        OnramperProperties rProps = new OnramperProperties();
        BlockchainProperties bProps = new BlockchainProperties();
        
        FinancialInfrastructureReadinessService read = new FinancialInfrastructureReadinessService(rProps, bProps);
        Map<String, Object> output = read.checkReadiness();
        
        assertFalse((Boolean) output.get("readyForSandbox"), "Should block sandbox readiness with empty config");
        Map<String, Object> bc = (Map<String, Object>) output.get("blockchain");
        assertFalse((Boolean) bc.get("rpcReachable"));
    }
}
