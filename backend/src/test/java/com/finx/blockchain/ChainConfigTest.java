package com.finx.blockchain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChainConfigTest {

    private ChainConfig config;

    @BeforeEach
    void setUp() {
        config = new ChainConfig();
        config.enabled = true;
        config.chainId = 80002;
        config.rpcUrl = "https://polygon-amoy-bor-rpc.publicnode.com";
        config.token = "0x41e94eb019c0762f9bfcf9fb1e58725bfb0e7582";
        config.factory = "0x1111111111111111111111111111111111111111";
        config.creator = "0x2222222222222222222222222222222222222222";
        config.decimals = 6;
        config.confirmations = 12;
        config.lastRpcConnected = true;
        config.lastRemoteChainId = 80002L;
        config.lastRpcCheck = Long.MAX_VALUE;
    }

    @Test
    void testDisabledStatus() {
        config.enabled = false;
        assertEquals("BLOCKCHAIN_DISABLED", config.computeStatus());
        assertFalse(config.isReady());
    }

    @Test
    void testRpcUnavailableWhenBlank() {
        config.rpcUrl = "";
        assertEquals("RPC_UNAVAILABLE", config.computeStatus());
    }

    @Test
    void testTokenContractMissing() {
        config.token = "";
        assertEquals("TOKEN_CONTRACT_MISSING", config.computeStatus());
    }

    @Test
    void testEscrowContractMissing() {
        config.factory = "";
        assertEquals("ESCROW_CONTRACT_MISSING", config.computeStatus());
    }

    @Test
    void testSignerNotConfigured() {
        config.creator = "";
        assertEquals("SIGNER_NOT_CONFIGURED", config.computeStatus());
    }

    @Test
    void testWalletVerificationDecoupledFromFactory() {
        config.factory = "";
        // Factory is missing, but wallet verification should still be allowed
        assertDoesNotThrow(() -> config.requireWalletVerificationAvailable());
        var pub = config.publicConfig();
        assertTrue((Boolean) pub.get("walletVerificationAvailable"));
        assertFalse((Boolean) pub.get("escrowCreationAvailable"));
    }

    @Test
    void testPublicConfigNeverLeaksSecrets() {
        var pub = config.publicConfig();
        assertFalse(pub.containsKey("privateKey"));
        assertFalse(pub.containsKey("secret"));
        assertTrue(pub.containsKey("status"));
        assertTrue(pub.containsKey("ready"));
        assertTrue(pub.containsKey("rpcConnected"));
        assertTrue(pub.containsKey("walletVerificationAvailable"));
        assertTrue(pub.containsKey("escrowCreationAvailable"));
        assertEquals("80002", pub.get("chainId"));
        assertEquals("0x41e94eb019c0762f9bfcf9fb1e58725bfb0e7582", pub.get("token"));
    }
}
