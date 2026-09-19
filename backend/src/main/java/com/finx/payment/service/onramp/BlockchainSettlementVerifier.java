package com.finx.payment.service.onramp;

import org.springframework.stereotype.Service;

@Service
public class BlockchainSettlementVerifier {
    private final BlockchainProperties properties;
    public BlockchainSettlementVerifier(BlockchainProperties properties) { this.properties = properties; }

    /** Legacy API lacks recipient and escrow identity and cannot prove settlement. */
    @Deprecated
    public SettlementStatus verifySettlement(String txHash, String expectedAsset, String expectedAmount) {
        if (properties.getRpcUrl() == null || properties.getRpcUrl().isBlank()) return SettlementStatus.RPC_UNAVAILABLE;
        return SettlementStatus.REJECTED;
    }
}
