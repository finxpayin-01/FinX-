package com.finx.blockchain;

import com.finx.common.response.ApiResponse;
import com.finx.security.service.UserPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.*;

@RestController
@RequestMapping("/api/blockchain")
public class BlockchainController {
    private final BlockchainEscrowRail rail;
    private final WalletService wallets;
    private final ChainConfig config;
    private final ChainIndexer indexer;
    public BlockchainController(BlockchainEscrowRail rail,WalletService wallets,ChainConfig config,ChainIndexer indexer) {
        this.rail=rail;this.wallets=wallets;this.config=config;this.indexer=indexer;
    }
    public record WalletRequest(String address,String signature) {}
    public record HashRequest(String txHash) {}
    @GetMapping("/config") public Object config() { return ApiResponse.success(config.publicConfig()); }
    @PostMapping("/wallet/challenge") public Object challenge(@RequestBody WalletRequest body,@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success(wallets.challenge(body.address(),actor));
    }
    @PostMapping("/wallet/verify") public Object verify(@RequestBody WalletRequest body,@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success(wallets.verify(body.signature(),actor));
    }
    @GetMapping("/deals/{dealId}") public Object get(@PathVariable UUID dealId,@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success(rail.get(dealId,actor));
    }
    @PostMapping("/deals/{dealId}") public Object create(@PathVariable UUID dealId,@RequestBody BlockchainEscrowRail.CreateRequest body,
            @RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success("Escrow terms recorded; deployment pending",rail.create(dealId,body,key,actor));
    }
    @PostMapping("/deals/{dealId}/intents") public Object intent(@PathVariable UUID dealId,@RequestBody BlockchainEscrowRail.IntentRequest body,
            @RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success("Wallet authorization required",rail.intent(dealId,body,key,actor));
    }
    @PostMapping("/deals/{dealId}/funding-intent") public Object funding(@PathVariable UUID dealId,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success(rail.funding(dealId,key,actor));
    }
    @PostMapping("/transactions/{id}") public Object submitted(@PathVariable UUID id,@RequestBody HashRequest body,@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success("Submitted; awaiting chain verification",rail.submitted(id,body.txHash(),actor));
    }
    @GetMapping("/transactions/{id}") public Object transaction(@PathVariable UUID id,@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success(rail.transaction(id,actor));
    }
    @PostMapping("/transactions/{id}/replacement") public Object replace(@PathVariable UUID id,@RequestBody HashRequest body,@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success("Replacement submitted; verification pending",rail.replace(id,body.txHash(),actor));
    }
    @GetMapping("/admin") @PreAuthorize("hasRole('ADMIN')") public Object admin() {
        return ApiResponse.success(Map.of("escrows",rail.admin(),"transactions",rail.adminTransactions(),"rpcHealth",indexer.health(),"providerStatus",rail.providerStatus()));
    }
}
